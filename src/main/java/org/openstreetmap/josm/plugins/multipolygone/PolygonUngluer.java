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

    /** Tag keys that identify a closed way as an area feature eligible for node snapping. */
    private static final Set<String> AREA_FEATURE_KEYS = Set.of(
        "landuse", "leisure", "building", "amenity", "natural"
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

        // Try to reuse nodes from adjacent area ways at run boundaries.
        // When a glued run borders a non-glued node shared with another area way,
        // the adjacent way may already have a node at the correct offset position.
        resolveAdjacentAreaNodes(resultGeometry, resultReusedNodes, areaWays);

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
    // Adjacent area node reuse
    // -----------------------------------------------------------------------

    /**
     * After assembling the result geometry, checks transitions between offset
     * nodes (null in reusedNodes) and kept nodes (non-null) for opportunities
     * to reuse nodes from adjacent area ways.
     *
     * <p>When a kept boundary node is shared with another closed area way, and
     * the adjacent offset point is close to one of that way's neighbors of
     * the shared node, the offset point is replaced with the existing neighbor
     * node. This maintains connectivity between adjacent areas after ungluing.
     */
    private static void resolveAdjacentAreaNodes(
            List<EastNorth> resultGeometry,
            List<Node> resultReusedNodes,
            Set<Way> areaWays) {

        int size = resultGeometry.size();
        if (size < 2) return;

        // Walk the result looking for transitions: kept → offset or offset → kept
        for (int i = 0; i < size; i++) {
            if (resultReusedNodes.get(i) != null) continue; // not an offset node
            // This is an offset node — check neighbors for a kept node
            // that's shared with another area way
            resolveOneDirection(i, -1, size, resultGeometry, resultReusedNodes, areaWays);
            resolveOneDirection(i, +1, size, resultGeometry, resultReusedNodes, areaWays);
        }
    }

    /**
     * For an offset node at index {@code idx}, look in direction {@code dir}
     * for the nearest kept (non-null) node. If found and shared with another
     * area way, try to match this offset node to one of that way's neighbors
     * of the shared node.
     */
    private static void resolveOneDirection(int idx, int dir, int size,
            List<EastNorth> resultGeometry,
            List<Node> resultReusedNodes,
            Set<Way> areaWays) {

        List<MultipolyGonePreferences.BreakTagWidth> tagWidths =
            MultipolyGonePreferences.getBreakTagWidths();

        // Find the nearest kept node in the given direction
        int neighborIdx = idx + dir;
        if (neighborIdx < 0 || neighborIdx >= size) return;
        Node anchor = resultReusedNodes.get(neighborIdx);
        if (anchor == null) return; // adjacent is also offset — skip

        EastNorth offsetEN = resultGeometry.get(idx);

        // Look for other area feature ways that share the anchor node.
        // Only snap to closed ways tagged with an allowlisted area key
        // (landuse, leisure, building, amenity, natural).
        for (OsmPrimitive ref : anchor.getReferrers()) {
            if (!(ref instanceof Way otherWay)) continue;
            if (areaWays.contains(otherWay)) continue;
            if (otherWay.isDeleted() || otherWay.isIncomplete()) continue;
            if (!otherWay.isClosed() || otherWay.getNodesCount() < 4) continue;
            if (!isAreaFeature(otherWay)) continue;

            // Find anchor in the other way and get its neighbors
            List<Node> otherNodes = otherWay.getNodes();
            int otherRingSize = otherNodes.size() - 1;
            for (int j = 0; j < otherRingSize; j++) {
                if (!otherNodes.get(j).equals(anchor)) continue;
                // Check both neighbors of anchor in the other way
                Node prev = otherNodes.get((j - 1 + otherRingSize) % otherRingSize);
                Node next = otherNodes.get((j + 1) % otherRingSize);

                // Filter out candidates that are on a centerline way — we must
                // not snap back to a road node we're trying to offset away from
                if (isOnCenterline(prev, areaWays, tagWidths)) prev = null;
                if (isOnCenterline(next, areaWays, tagWidths)) next = null;

                Node best = pickCloser(offsetEN, prev, next);
                if (best != null) {
                    EastNorth bestEN = best.getEastNorth();
                    double metersPerUnit = org.openstreetmap.josm.data.projection.ProjectionRegistry
                        .getProjection().getMetersPerUnit();
                    double distMeters = bestEN.distance(offsetEN) * metersPerUnit;
                    // Reuse if within a generous but safe threshold (half a road width ≈ 5m max)
                    if (distMeters < 5.0) {
                        resultGeometry.set(idx, bestEN);
                        resultReusedNodes.set(idx, best);
                        return;
                    }
                }
            }
        }
    }

    /** Checks whether a closed way is an area feature eligible for node snapping. */
    private static boolean isAreaFeature(Way w) {
        for (String key : AREA_FEATURE_KEYS) {
            if (w.hasKey(key)) return true;
        }
        return false;
    }

    /**
     * Checks whether a node is on any centerline way (road, waterway, etc.)
     * other than the area ways being unglued.
     */
    private static boolean isOnCenterline(Node node,
            Set<Way> areaWays, List<MultipolyGonePreferences.BreakTagWidth> tagWidths) {
        if (node == null) return false;
        for (OsmPrimitive ref : node.getReferrers()) {
            if (!(ref instanceof Way w)) continue;
            if (areaWays.contains(w)) continue;
            if (w.isDeleted() || w.isIncomplete()) continue;
            if (isCenterlineWay(w, tagWidths)) return true;
        }
        return false;
    }

    /** Returns whichever of a or b is closer to target, or null if both are null. */
    private static Node pickCloser(EastNorth target, Node a, Node b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        EastNorth ea = a.getEastNorth();
        EastNorth eb = b.getEastNorth();
        if (ea == null) return b;
        if (eb == null) return a;
        return ea.distance(target) <= eb.distance(target) ? a : b;
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

        // For each boundary index, record which runs start there and which runs
        // cover it (so we know whether it's glued at all).
        boolean[] isGlued = new boolean[ringSize];
        // Multiple runs can start at the same index (rare but possible);
        // more commonly, a run's startIdx equals a previous run's endIdx.
        List<List<Integer>> runsStartingAt = new ArrayList<>(ringSize);
        for (int idx = 0; idx < ringSize; idx++) {
            runsStartingAt.add(null);
        }

        for (int r = 0; r < candidates.size(); r++) {
            GluedRunCandidate c = candidates.get(r);
            int idx = c.startIdx;
            while (true) {
                isGlued[idx] = true;
                if (idx == c.endIdx) break;
                idx = (idx + 1) % ringSize;
            }
            List<Integer> list = runsStartingAt.get(c.startIdx);
            if (list == null) {
                list = new ArrayList<>(2);
                runsStartingAt.set(c.startIdx, list);
            }
            list.add(r);
        }

        // Walk the ring, emitting non-glued nodes as-is and glued runs as offset points.
        // Track which runs have been emitted so we don't double-emit.
        boolean[] emitted = new boolean[candidates.size()];
        int i = 0;
        while (i < ringSize) {
            if (!isGlued[i]) {
                // Non-glued node — keep as-is
                Node node = boundaryNodes.get(i);
                resultGeometry.add(node.getEastNorth());
                resultReusedNodes.add(node);
                i++;
            } else {
                // Check if any run starts at this index
                List<Integer> starting = runsStartingAt.get(i);
                if (starting != null) {
                    // Emit all runs starting at this index
                    int maxEndIdx = i;
                    for (int runIdx : starting) {
                        if (emitted[runIdx]) continue;
                        emitted[runIdx] = true;
                        UngluePlan.GluedRun run = gluedRuns.get(runIdx);
                        GluedRunCandidate c = candidates.get(runIdx);
                        for (EastNorth en : run.getOffsetPoints()) {
                            resultGeometry.add(en);
                            resultReusedNodes.add(null); // new node needed
                        }
                        // Track the furthest endIdx to know how far to advance
                        if (c.endIdx >= c.startIdx) {
                            maxEndIdx = Math.max(maxEndIdx, c.endIdx);
                        } else {
                            // Wrap-around: endIdx < startIdx means it goes past ring end
                            maxEndIdx = ringSize; // will exit the loop
                        }
                    }

                    // Advance past the emitted run(s), but check each index along
                    // the way for additional runs that start mid-span
                    int next = (i + 1) % ringSize;
                    while (next != (maxEndIdx + 1) % ringSize && next != i) {
                        List<Integer> midStarts = runsStartingAt.get(next);
                        if (midStarts != null) {
                            for (int runIdx : midStarts) {
                                if (emitted[runIdx]) continue;
                                emitted[runIdx] = true;
                                UngluePlan.GluedRun run = gluedRuns.get(runIdx);
                                GluedRunCandidate c = candidates.get(runIdx);
                                for (EastNorth en : run.getOffsetPoints()) {
                                    resultGeometry.add(en);
                                    resultReusedNodes.add(null);
                                }
                                if (c.endIdx >= c.startIdx) {
                                    maxEndIdx = Math.max(maxEndIdx, c.endIdx);
                                } else {
                                    maxEndIdx = ringSize;
                                }
                            }
                        }
                        next = (next + 1) % ringSize;
                        if (next == 0 && maxEndIdx >= ringSize) break;
                    }

                    if (maxEndIdx >= ringSize) {
                        break; // wrap-around run consumed the rest
                    }
                    i = maxEndIdx + 1;
                } else {
                    // Glued but not a run start — this node is in the middle of a run
                    // that was already emitted. Skip it.
                    i++;
                }
            }
        }
    }
}
