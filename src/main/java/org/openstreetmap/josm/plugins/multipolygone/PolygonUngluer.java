package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Analyzes an area feature (closed way or multipolygon) and detects where its
 * boundary shares nodes with centerline features (roads, waterways, railways, etc.).
 * Produces an {@link UngluePlan} that offsets the shared segments away from the
 * centerline by half the feature's width.
 */
class PolygonUngluer {

    /** Tag keys whose ways are considered "centerline" linear features. */
    private static final Set<String> CENTERLINE_KEYS = Set.of(
        "highway", "waterway", "railway"
    );

    /**
     * Analyzes whether the given primitive has boundary nodes glued to centerline features.
     *
     * @return an UngluePlan, or null if no glued segments are found
     */
    static UngluePlan analyze(OsmPrimitive selected, DataSet ds) {
        if (selected instanceof Way way) {
            if (way.isIncomplete() || way.isDeleted()) return null;
            if (!way.isClosed() || way.getNodesCount() < 4) return null;
            return analyzeClosedWay(way, way, ds);
        } else if (selected instanceof Relation rel) {
            return analyzeMultipolygon(rel, ds);
        }
        return null;
    }

    private static UngluePlan analyzeMultipolygon(Relation rel, DataSet ds) {
        String type = rel.get("type");
        if (!"multipolygon".equals(type) && !"boundary".equals(type)) return null;

        // For now: only handle single closed outer way
        Way outerWay = null;
        for (RelationMember m : rel.getMembers()) {
            if (!m.isWay()) continue;
            String role = m.getRole();
            if ("outer".equals(role) || role.isEmpty()) {
                if (outerWay != null) return null; // multiple outers — too complex for now
                outerWay = m.getWay();
            }
        }
        if (outerWay == null || !outerWay.isClosed() || outerWay.getNodesCount() < 4) return null;
        return analyzeClosedWay(outerWay, rel, ds);
    }

    /**
     * Core analysis: finds glued runs and computes offset geometry.
     *
     * @param areaWay the closed way defining the area boundary
     * @param source  the original primitive (Way or Relation)
     * @param ds      the dataset
     */
    private static UngluePlan analyzeClosedWay(Way areaWay, OsmPrimitive source, DataSet ds) {
        List<MultipolyGonePreferences.BreakTagWidth> tagWidths =
            MultipolyGonePreferences.getBreakTagWidths();

        // Get boundary nodes (exclude closure duplicate: last == first)
        List<Node> boundaryNodes = areaWay.getNodes();
        int ringSize = boundaryNodes.size() - 1; // closed way repeats first node
        if (ringSize < 3) return null;

        // Collect the set of ways that are part of this area (to exclude from centerline search)
        Set<Way> areaWays = new HashSet<>();
        areaWays.add(areaWay);
        if (source instanceof Relation rel) {
            for (RelationMember m : rel.getMembers()) {
                if (m.isWay()) areaWays.add(m.getWay());
            }
        }

        // For each boundary node, find centerline ways it belongs to
        // A node is "glued" if it is shared with a non-area way that has a centerline tag
        List<Set<Way>> nodeCenterlines = new ArrayList<>(ringSize);
        Set<Way> allCenterlines = new LinkedHashSet<>();

        for (int i = 0; i < ringSize; i++) {
            Node node = boundaryNodes.get(i);
            Set<Way> centerlines = new LinkedHashSet<>();
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (!(referrer instanceof Way w)) continue;
                if (areaWays.contains(w)) continue;
                if (w.isDeleted() || w.isIncomplete()) continue;
                if (isCenterlineWay(w, tagWidths)) {
                    centerlines.add(w);
                    allCenterlines.add(w);
                }
            }
            nodeCenterlines.add(centerlines);
        }

        if (allCenterlines.isEmpty()) return null;

        // Build glued runs: consecutive boundary nodes sharing the same centerline way
        List<GluedRunCandidate> candidates = buildGluedRuns(boundaryNodes, ringSize,
            nodeCenterlines, allCenterlines);

        if (candidates.isEmpty()) return null;

        // Compute offset geometry for each run
        List<UngluePlan.GluedRun> gluedRuns = new ArrayList<>();

        for (GluedRunCandidate candidate : candidates) {
            double width = getWayWidth(candidate.centerlineWay, tagWidths);
            double halfWidthMeters = width / 2.0;

            List<EastNorth> offsetPoints = computeOffsetPoints(
                candidate.startIdx, candidate.endIdx, boundaryNodes, ringSize,
                candidate.centerlineWay, halfWidthMeters);

            if (offsetPoints.isEmpty()) continue;

            gluedRuns.add(new UngluePlan.GluedRun(
                candidate.centerlineWay,
                candidate.sharedNodes(boundaryNodes, ringSize),
                width,
                offsetPoints));
        }

        if (gluedRuns.isEmpty()) return null;

        // Assemble result geometry: replace glued segments with offset points
        List<EastNorth> resultGeometry = new ArrayList<>();
        List<Node> resultReusedNodes = new ArrayList<>();
        assembleResultGeometry(boundaryNodes, ringSize, gluedRuns, candidates,
            resultGeometry, resultReusedNodes);

        // Close the ring
        if (!resultGeometry.isEmpty()) {
            resultGeometry.add(resultGeometry.get(0));
            resultReusedNodes.add(resultReusedNodes.get(0));
        }

        int totalShared = gluedRuns.stream()
            .mapToInt(r -> r.getSharedNodes().size())
            .sum();
        String desc = tr("{0} shared node(s) across {1} centerline(s) — offset by half-width",
            totalShared, gluedRuns.size());

        return new UngluePlan(source, gluedRuns, resultGeometry, resultReusedNodes, desc);
    }

    // -----------------------------------------------------------------------
    // Centerline detection
    // -----------------------------------------------------------------------

    /**
     * Determines whether a way is a centerline linear feature (road, waterway, etc.).
     * Uses the hardcoded CENTERLINE_KEYS set plus configured tag filters from preferences.
     */
    private static boolean isCenterlineWay(Way w, List<MultipolyGonePreferences.BreakTagWidth> tagWidths) {
        for (String key : CENTERLINE_KEYS) {
            if (w.hasKey(key)) return true;
        }
        // Also match configured break-tag filters (captures things like aeroway=taxiway, etc.)
        for (MultipolyGonePreferences.BreakTagWidth tw : tagWidths) {
            if (tw.matches(w)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Glued run detection
    // -----------------------------------------------------------------------

    /** Intermediate representation of a contiguous run before offset computation. */
    private static class GluedRunCandidate {
        final Way centerlineWay;
        final int startIdx; // inclusive index in boundary ring
        final int endIdx;   // inclusive index in boundary ring

        GluedRunCandidate(Way centerlineWay, int startIdx, int endIdx) {
            this.centerlineWay = centerlineWay;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
        }

        /** Extracts the shared nodes from the boundary ring. */
        List<Node> sharedNodes(List<Node> boundaryNodes, int ringSize) {
            List<Node> result = new ArrayList<>();
            int idx = startIdx;
            while (true) {
                result.add(boundaryNodes.get(idx));
                if (idx == endIdx) break;
                idx = (idx + 1) % ringSize;
            }
            return result;
        }
    }

    /**
     * Finds contiguous runs of boundary nodes that share a centerline way.
     * A run requires at least 2 consecutive boundary nodes on the same centerline.
     */
    private static List<GluedRunCandidate> buildGluedRuns(
            List<Node> boundaryNodes, int ringSize,
            List<Set<Way>> nodeCenterlines, Set<Way> allCenterlines) {

        List<GluedRunCandidate> result = new ArrayList<>();

        for (Way centerline : allCenterlines) {
            // Find all boundary indices that share this centerline
            boolean[] onCenterline = new boolean[ringSize];
            for (int i = 0; i < ringSize; i++) {
                onCenterline[i] = nodeCenterlines.get(i).contains(centerline);
            }

            // Extract contiguous runs (handling wrap-around)
            // First, find a starting point that is NOT on the centerline
            // to avoid splitting a run that wraps around
            int startSearch = -1;
            for (int i = 0; i < ringSize; i++) {
                if (!onCenterline[i]) {
                    startSearch = i;
                    break;
                }
            }

            if (startSearch == -1) {
                // All nodes are on this centerline — entire boundary is glued
                // This is an edge case; create one run covering the whole ring
                result.add(new GluedRunCandidate(centerline, 0, ringSize - 1));
                continue;
            }

            // Walk from startSearch, collecting runs
            int i = startSearch;
            int visited = 0;
            while (visited < ringSize) {
                int idx = i % ringSize;
                if (onCenterline[idx]) {
                    // Start of a run
                    int runStart = idx;
                    int runEnd = idx;
                    i++;
                    visited++;
                    while (visited < ringSize) {
                        int nextIdx = i % ringSize;
                        if (!onCenterline[nextIdx]) break;
                        runEnd = nextIdx;
                        i++;
                        visited++;
                    }
                    // Only include runs of 2+ nodes (a single shared node isn't a "glued segment")
                    if (runStart != runEnd) {
                        result.add(new GluedRunCandidate(centerline, runStart, runEnd));
                    }
                } else {
                    i++;
                    visited++;
                }
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Offset computation
    // -----------------------------------------------------------------------

    /**
     * Computes offset points for a glued run. The offset direction is determined by
     * which side of the centerline the area's interior lies on.
     *
     * @param startIdx   start index in boundary ring (inclusive)
     * @param endIdx     end index in boundary ring (inclusive)
     * @param boundaryNodes the full boundary (with closure duplicate)
     * @param ringSize   number of unique nodes in ring
     * @param centerline the centerline way
     * @param halfWidthMeters offset distance in meters
     * @return offset EastNorth points replacing the shared segment
     */
    private static List<EastNorth> computeOffsetPoints(
            int startIdx, int endIdx, List<Node> boundaryNodes, int ringSize,
            Way centerline, double halfWidthMeters) {

        List<EastNorth> result = new ArrayList<>();

        // Determine offset side: is the area interior to the left or right
        // of the centerline direction?
        boolean offsetLeft = determineOffsetSide(startIdx, endIdx, boundaryNodes, ringSize, centerline);

        // Walk the run indices and compute perpendicular offsets
        int idx = startIdx;
        while (true) {
            Node node = boundaryNodes.get(idx);
            EastNorth en = node.getEastNorth();

            // Find the local centerline direction at this node
            EastNorth[] lineDir = getCenterlineDirection(node, centerline);
            if (lineDir == null) {
                // Fallback: use boundary direction
                int prevIdx = (idx - 1 + ringSize) % ringSize;
                int nextIdx = (idx + 1) % ringSize;
                lineDir = new EastNorth[]{
                    boundaryNodes.get(prevIdx).getEastNorth(),
                    boundaryNodes.get(nextIdx).getEastNorth()
                };
            }

            EastNorth[] offsets = GeometryUtils.perpendicularOffsets(
                lineDir[0], lineDir[1], en, halfWidthMeters);

            // offsets[0] = left, offsets[1] = right
            result.add(offsetLeft ? offsets[0] : offsets[1]);

            if (idx == endIdx) break;
            idx = (idx + 1) % ringSize;
        }

        return result;
    }

    /**
     * Determines whether the area interior is to the left or right of the centerline
     * direction at the glued run.
     *
     * Strategy: take a non-shared boundary node adjacent to the run and check which
     * side of the centerline it lies on.
     */
    private static boolean determineOffsetSide(int startIdx, int endIdx,
            List<Node> boundaryNodes, int ringSize, Way centerline) {

        // Find a boundary node just before or after the run that is NOT on the centerline
        int probeIdx = (startIdx - 1 + ringSize) % ringSize;
        Node probeNode = boundaryNodes.get(probeIdx);
        EastNorth probeEN = probeNode.getEastNorth();

        // Get centerline direction at the start of the run
        Node runStartNode = boundaryNodes.get(startIdx);
        EastNorth[] lineDir = getCenterlineDirection(runStartNode, centerline);
        if (lineDir == null) return true; // fallback

        // Cross product: positive = probe is to the left of centerline direction
        double dx = lineDir[1].east() - lineDir[0].east();
        double dy = lineDir[1].north() - lineDir[0].north();
        double px = probeEN.east() - lineDir[0].east();
        double py = probeEN.north() - lineDir[0].north();
        double cross = dx * py - dy * px;

        return cross > 0; // positive cross product means probe is to the left
    }

    /**
     * Gets the centerline direction (as two EastNorth points) at a given node.
     * Returns the segment endpoints from the centerline way that contain this node.
     */
    private static EastNorth[] getCenterlineDirection(Node node, Way centerline) {
        List<Node> wayNodes = centerline.getNodes();
        for (int i = 0; i < wayNodes.size(); i++) {
            if (wayNodes.get(i).equals(node)) {
                // Use surrounding nodes for direction
                EastNorth prev, next;
                if (i > 0 && i < wayNodes.size() - 1) {
                    prev = wayNodes.get(i - 1).getEastNorth();
                    next = wayNodes.get(i + 1).getEastNorth();
                } else if (i == 0 && wayNodes.size() > 1) {
                    prev = wayNodes.get(0).getEastNorth();
                    next = wayNodes.get(1).getEastNorth();
                } else if (i == wayNodes.size() - 1 && wayNodes.size() > 1) {
                    prev = wayNodes.get(i - 1).getEastNorth();
                    next = wayNodes.get(i).getEastNorth();
                } else {
                    return null;
                }
                return new EastNorth[]{prev, next};
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Width determination
    // -----------------------------------------------------------------------

    /**
     * Determines the width of a centerline way, reusing PolygonBreaker's logic.
     */
    private static double getWayWidth(Way w, List<MultipolyGonePreferences.BreakTagWidth> tagWidths) {
        // 1. Explicit "width" tag
        double width = PolygonBreaker.parseWidthTag(w.get("width"));
        if (width > 0) return width;

        // 2. Estimate from lanes
        width = PolygonBreaker.estimateWidthFromLanes(w);
        if (width > 0) return width;

        // 3. Best matching tag filter
        double filterWidth = 0;
        for (MultipolyGonePreferences.BreakTagWidth tw : tagWidths) {
            if (tw.matches(w)) {
                filterWidth = Math.max(filterWidth, tw.widthMeters);
            }
        }
        if (filterWidth > 0) return filterWidth;

        // 4. Default
        return 3.5;
    }

    // -----------------------------------------------------------------------
    // Result geometry assembly
    // -----------------------------------------------------------------------

    /**
     * Assembles the result geometry by walking the boundary ring and replacing
     * glued runs with their offset points.
     */
    private static void assembleResultGeometry(
            List<Node> boundaryNodes, int ringSize,
            List<UngluePlan.GluedRun> gluedRuns,
            List<GluedRunCandidate> candidates,
            List<EastNorth> resultGeometry,
            List<Node> resultReusedNodes) {

        // Build a set of indices that are part of any glued run
        // and a mapping from startIdx -> gluedRun for substitution
        boolean[] isGlued = new boolean[ringSize];
        int[] runStartAt = new int[ringSize]; // -1 if not a run start
        java.util.Arrays.fill(runStartAt, -1);

        for (int r = 0; r < candidates.size(); r++) {
            GluedRunCandidate c = candidates.get(r);
            int idx = c.startIdx;
            while (true) {
                isGlued[idx] = true;
                if (idx == c.endIdx) break;
                idx = (idx + 1) % ringSize;
            }
            runStartAt[c.startIdx] = r;
        }

        // Walk the ring, emitting non-glued nodes as-is and glued runs as offset points
        int i = 0;
        while (i < ringSize) {
            if (!isGlued[i]) {
                // Non-glued node — keep as-is
                Node node = boundaryNodes.get(i);
                resultGeometry.add(node.getEastNorth());
                resultReusedNodes.add(node);
                i++;
            } else if (runStartAt[i] >= 0) {
                // Start of a glued run — emit offset points
                int runIdx = runStartAt[i];
                UngluePlan.GluedRun run = gluedRuns.get(runIdx);
                GluedRunCandidate c = candidates.get(runIdx);
                for (EastNorth en : run.getOffsetPoints()) {
                    resultGeometry.add(en);
                    resultReusedNodes.add(null); // new node needed
                }
                // Skip past the run
                int idx = c.startIdx;
                while (true) {
                    if (idx == c.endIdx) break;
                    idx = (idx + 1) % ringSize;
                }
                i = (c.endIdx + 1) % ringSize;
                // Guard against infinite loop if endIdx wraps
                if (c.endIdx >= c.startIdx) {
                    i = c.endIdx + 1;
                } else {
                    // Wrap-around run: we started at startIdx and will end past ringSize
                    break;
                }
            } else {
                // Middle of a glued run (already handled by the start) — skip
                i++;
            }
        }
    }
}
