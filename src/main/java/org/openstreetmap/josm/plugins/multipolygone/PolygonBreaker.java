package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Geometry;

/**
 * Analyzes a selected polygon and finds intersecting roads that can split it.
 * Produces a {@link BreakPlan} describing the split.
 *
 * <p>Handles road networks: roads split into multiple Way segments that share
 * nodes inside the polygon are chained together. T-junctions and branches
 * at interior nodes create additional corridors.</p>
 */
class PolygonBreaker {

    /**
     * Analyzes whether the given primitive (closed way or multipolygon relation)
     * can be split by intersecting roads.
     *
     * @return a BreakPlan, or null if no splitting is possible
     */
    static BreakPlan analyze(OsmPrimitive selected, DataSet ds) {
        if (selected instanceof Way way) {
            if (!way.isClosed() || way.getNodesCount() < 4) return null;
            return analyzeClosedWay(way, way, ds, null);
        } else if (selected instanceof Relation rel) {
            if (!"multipolygon".equals(rel.get("type")) && !"boundary".equals(rel.get("type"))) {
                return null;
            }
            return analyzeMultipolygon(rel, ds);
        }
        return null;
    }

    private static BreakPlan analyzeClosedWay(OsmPrimitive source, Way polygon,
            DataSet ds, List<Way> innerWays) {
        List<Node> polyNodes = polygon.getNodes();
        Map<String, Double> roadWidths = MultipolyGonePreferences.getRoadWidths();
        if (roadWidths.isEmpty()) return null;

        // Collect inner ring nodes for exclusion check
        List<List<Node>> innerRings = new ArrayList<>();
        if (innerWays != null) {
            for (Way inner : innerWays) {
                if (inner.isClosed() && inner.getNodesCount() >= 4) {
                    innerRings.add(inner.getNodes());
                }
            }
        }

        // Build road graph and find corridors + interior loops
        RoadGraphResult roadResult = buildRoadGraph(
            polyNodes, polygon, ds, roadWidths, innerRings);
        List<BreakPlan.RoadCorridor> corridors = buildCorridorsFromGraph(
            roadResult, polyNodes);
        List<InteriorLoop> interiorLoops = findInteriorLoops(
            roadResult.graph, 7.0);

        if (corridors.isEmpty() && interiorLoops.isEmpty()) return null;

        List<List<EastNorth>> resultPolygons;
        boolean corridorsSplit = false;
        if (!corridors.isEmpty()) {
            resultPolygons = splitPolygon(polyNodes, corridors);
            if (resultPolygons != null && resultPolygons.size() >= 2) {
                corridorsSplit = true;
            } else {
                if (interiorLoops.isEmpty()) return null;
                resultPolygons = new ArrayList<>();
                List<EastNorth> wholePoly = new ArrayList<>();
                for (Node n : polyNodes) wholePoly.add(n.getEastNorth());
                resultPolygons.add(wholePoly);
            }
        } else {
            resultPolygons = new ArrayList<>();
            List<EastNorth> wholePoly = new ArrayList<>();
            for (Node n : polyNodes) wholePoly.add(n.getEastNorth());
            resultPolygons.add(wholePoly);
        }

        // For interior loops: if corridors split the polygon, replace any
        // narrow corridor-gap polygon with the proper loop interior polygon.
        // If corridors didn't split, use slit-carving to subtract loops.
        Set<Integer> loopProtected = new HashSet<>();
        if (!interiorLoops.isEmpty()) {
            if (corridorsSplit) {
                loopProtected.addAll(
                    replaceLoopGapPolygons(resultPolygons, interiorLoops));
            } else {
                applyInteriorLoops(resultPolygons, interiorLoops, roadResult.graph);
                // Mark any added loop interior polygons
                for (InteriorLoop loop : interiorLoops) {
                    for (int pi = 0; pi < resultPolygons.size(); pi++) {
                        if (resultPolygons.get(pi) == loop.innerPolygon) {
                            loopProtected.add(pi);
                        }
                    }
                }
            }
        }

        if (resultPolygons == null || resultPolygons.size() < 2) return null;

        // Fix inter-polygon edge crossings caused by corridor offsets at sharp bends.
        resolveInterPolygonCrossings(resultPolygons);

        // Filter out junction-gap polygons (artifacts from multi-way junctions).
        // At junctions, offset polylines from different road directions don't quite
        // meet, creating small gap polygons. These are characterised by being both
        // small (< 5% of original area) and non-compact (spiky/thin shape).
        // A polygon that is small but compact (e.g. a real piece from a corner clip)
        // is preserved. Compactness = perimeter²/(4π×area), 1.0 = perfect circle.

        double originalArea = Math.abs(signedAreaEN(
            polyNodes.stream().map(Node::getEastNorth).collect(
                java.util.stream.Collectors.toList())));
        double minArea = originalArea * 0.05;
        for (int pi = resultPolygons.size() - 1; pi >= 0; pi--) {
            if (loopProtected.contains(pi)) continue;
            List<EastNorth> poly = resultPolygons.get(pi);
            double area = Math.abs(signedAreaEN(poly));
            if (area >= minArea) continue;
            double perimeter = 0;
            for (int qi = 0; qi < poly.size() - 1; qi++) {
                EastNorth a = poly.get(qi);
                EastNorth b = poly.get(qi + 1);
                perimeter += Math.sqrt(
                    Math.pow(b.east() - a.east(), 2) + Math.pow(b.north() - a.north(), 2));
            }
            double compactness = (perimeter * perimeter) / (4 * Math.PI * area);
            if (compactness > 3.0) {
                resultPolygons.remove(pi);
            }
        }
        if (resultPolygons.size() < 2) return null;

        // Reject any self-intersecting result polygons — these are always
        // incorrect and indicate a bug in corridor/loop processing.
        for (int pi = resultPolygons.size() - 1; pi >= 0; pi--) {
            if (isSelfIntersecting(resultPolygons.get(pi))) {
                resultPolygons.remove(pi);
            }
        }
        if (resultPolygons.size() < 2) return null;

        int splitCount = corridors.size();
        String desc = tr("{0} roads split polygon into {1} parts",
            splitCount, resultPolygons.size());
        return new BreakPlan(source, corridors, resultPolygons, innerWays, desc);
    }

    private static BreakPlan analyzeMultipolygon(Relation rel, DataSet ds) {
        List<Way> outerWays = new ArrayList<>();
        List<Way> innerWays = new ArrayList<>();
        for (RelationMember m : rel.getMembers()) {
            if (!(m.getMember() instanceof Way way)) continue;
            if (way.isDeleted() || !way.isClosed() || way.getNodesCount() < 4) continue;
            String role = m.getRole();
            if ("outer".equals(role) || role.isEmpty()) {
                outerWays.add(way);
            } else if ("inner".equals(role)) {
                innerWays.add(way);
            }
        }
        if (outerWays.isEmpty()) return null;
        // TODO: multi-outer support
        if (outerWays.size() != 1) return null;
        return analyzeClosedWay(rel, outerWays.get(0), ds, innerWays);
    }

    // -----------------------------------------------------------------------
    // Road network graph construction
    // -----------------------------------------------------------------------

    /** Result of building the road graph: the graph plus component analysis. */
    private static class RoadGraphResult {
        final RoadGraph graph;
        final List<List<GraphNode>> components;

        RoadGraphResult(RoadGraph graph, List<List<GraphNode>> components) {
            this.graph = graph;
            this.components = components;
        }
    }

    /**
     * Builds the road network graph inside a polygon.
     */
    private static RoadGraphResult buildRoadGraph(
            List<Node> polyNodes, Way polygon, DataSet ds,
            Map<String, Double> roadWidths, List<List<Node>> innerRings) {

        BBox polyBBox = polygon.getBBox();
        List<RoadWayInfo> roadInfos = new ArrayList<>();
        for (Way road : ds.getWays()) {
            if (road.isDeleted() || road == polygon) continue;
            String highway = road.get("highway");
            if (highway == null || !roadWidths.containsKey(highway)) continue;
            if (!road.getBBox().intersects(polyBBox)) continue;
            if (roadIntersectsAnyRing(road, innerRings)) continue;

            double width = roadWidths.get(highway);
            List<RawCrossing> crossings = findCrossings(polyNodes, road);
            roadInfos.add(new RoadWayInfo(road, width, crossings));
        }

        RoadGraph graph = buildRoadGraph(roadInfos, polyNodes);
        List<List<GraphNode>> components = graph.findConnectedComponents();
        return new RoadGraphResult(graph, components);
    }

    /**
     * Builds road corridors from a pre-built road graph.
     */
    private static List<BreakPlan.RoadCorridor> buildCorridorsFromGraph(
            RoadGraphResult roadResult, List<Node> polyNodes) {

        List<BreakPlan.RoadCorridor> corridors = new ArrayList<>();

        for (List<GraphNode> component : roadResult.components) {
            List<GraphNode> boundaryCrossings = new ArrayList<>();
            List<GraphNode> junctions = new ArrayList<>();
            for (GraphNode gn : component) {
                if (gn.isBoundaryCrossing) boundaryCrossings.add(gn);
                if (gn.edges.size() >= 3) junctions.add(gn);
            }

            if (boundaryCrossings.size() < 2) continue;

            if (junctions.isEmpty()) {
                if (boundaryCrossings.size() % 2 != 0) continue;
                boundaryCrossings.sort((a, b) -> {
                    int cmp = Integer.compare(a.polySegmentIndex, b.polySegmentIndex);
                    return cmp != 0 ? cmp : Double.compare(a.polyT, b.polyT);
                });

                for (int i = 0; i < boundaryCrossings.size(); i += 2) {
                    GraphNode entry = boundaryCrossings.get(i);
                    GraphNode exit = boundaryCrossings.get(i + 1);
                    List<GraphEdge> path = findPath(entry, exit, roadResult.graph);
                    if (path == null || path.isEmpty()) continue;
                    BreakPlan.RoadCorridor corridor = buildCorridorFromPath(
                        entry, exit, path, polyNodes, null);
                    if (corridor != null) corridors.add(corridor);
                }
            } else {
                corridors.addAll(buildCorridorsFromJunctionComponent(
                    component, boundaryCrossings, junctions, polyNodes));
            }
        }

        return corridors;
    }

    /**
     * Builds corridors from a component that contains junction nodes (degree 3+).
     *
     * <p>Strategy: sort boundary crossings by polygon perimeter position, then
     * pair consecutive crossings (i, i+1 mod n). For each pair, find the
     * shortest path through the graph and build a corridor.</p>
     */
    private static List<BreakPlan.RoadCorridor> buildCorridorsFromJunctionComponent(
            List<GraphNode> component, List<GraphNode> boundaryCrossings,
            List<GraphNode> junctions, List<Node> polyNodes) {

        List<BreakPlan.RoadCorridor> corridors = new ArrayList<>();

        boundaryCrossings.sort((a, b) -> {
            int cmp = Integer.compare(a.polySegmentIndex, b.polySegmentIndex);
            return cmp != 0 ? cmp : Double.compare(a.polyT, b.polyT);
        });

        int n = boundaryCrossings.size();
        if (n < 2) return corridors;

        RoadGraph graph = new RoadGraph();

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            GraphNode entry = boundaryCrossings.get(i);
            GraphNode exit = boundaryCrossings.get(j);

            List<GraphEdge> path = findPath(entry, exit, graph);
            if (path == null || path.isEmpty()) continue;

            BreakPlan.RoadCorridor corridor = buildCorridorFromPath(
                entry, exit, path, polyNodes, null);
            if (corridor != null) corridors.add(corridor);
        }

        return corridors;
    }

    // -----------------------------------------------------------------------
    // Interior loop detection and processing
    // -----------------------------------------------------------------------

    /** A closed loop in the road graph entirely inside the polygon. */
    private static class InteriorLoop {
        /** The inward-offset polygon representing the loop's interior area. */
        final List<EastNorth> innerPolygon;
        /** The road cycle polygon (centerline, closed). */
        final List<EastNorth> cyclePolygon;

        InteriorLoop(List<EastNorth> innerPolygon, List<EastNorth> cyclePolygon) {
            this.innerPolygon = innerPolygon;
            this.cyclePolygon = cyclePolygon;
        }
    }

    /**
     * Finds interior loops (cycles) in the road network graph and computes
     * their inward-offset polygons. An interior loop is a cycle where all
     * nodes are inside the polygon (no boundary crossings).
     */
    private static List<InteriorLoop> findInteriorLoops(
            RoadGraph graph, double defaultWidth) {

        List<InteriorLoop> loops = new ArrayList<>();
        Set<GraphNode> usedInCycle = new HashSet<>();

        for (GraphNode start : graph.nodes) {
            if (start.isBoundaryCrossing) continue;
            if (usedInCycle.contains(start)) continue;
            if (start.edges.size() < 2) continue;

            List<GraphNode> cycle = findInteriorCycle(start, usedInCycle);
            if (cycle == null || cycle.size() < 3) continue;

            usedInCycle.addAll(cycle);

            // Compute the maximum road width along the cycle
            double maxWidth = 0;
            for (int i = 0; i < cycle.size(); i++) {
                GraphNode a = cycle.get(i);
                GraphNode b = cycle.get((i + 1) % cycle.size());
                for (GraphEdge e : a.edges) {
                    if (e.other(a) == b) {
                        maxWidth = Math.max(maxWidth, e.width);
                        break;
                    }
                }
            }
            double halfWidth = maxWidth / 2.0;

            // Build closed polygon from cycle nodes
            List<EastNorth> loopPoly = new ArrayList<>();
            for (GraphNode gn : cycle) {
                loopPoly.add(gn.position);
            }
            loopPoly.add(loopPoly.get(0));

            List<EastNorth> innerPoly = offsetClosedPolygon(loopPoly, halfWidth);
            if (innerPoly != null && innerPoly.size() >= 4) {
                loops.add(new InteriorLoop(innerPoly, loopPoly));
            }
        }
        return loops;
    }

    /**
     * Finds a simple cycle containing the given node, composed entirely of
     * interior (non-boundary-crossing) nodes.
     *
     * <p>For each pair of interior neighbors of start, BFS from one to the
     * other avoiding start. If a path is found, the cycle is:
     * start → neighborA → ... → neighborB → start.</p>
     */
    private static List<GraphNode> findInteriorCycle(GraphNode start,
            Set<GraphNode> excluded) {
        List<GraphNode> interiorNeighbors = new ArrayList<>();
        for (GraphEdge edge : start.edges) {
            GraphNode neighbor = edge.other(start);
            if (!neighbor.isBoundaryCrossing && !excluded.contains(neighbor)) {
                interiorNeighbors.add(neighbor);
            }
        }
        if (interiorNeighbors.size() < 2) return null;

        for (int ni = 0; ni < interiorNeighbors.size(); ni++) {
            GraphNode seedA = interiorNeighbors.get(ni);
            for (int nj = ni + 1; nj < interiorNeighbors.size(); nj++) {
                GraphNode seedB = interiorNeighbors.get(nj);

                Map<GraphNode, GraphNode> parent = new HashMap<>();
                LinkedList<GraphNode> queue = new LinkedList<>();
                parent.put(seedA, seedA);
                queue.add(seedA);

                boolean found = false;
                while (!queue.isEmpty() && !found) {
                    GraphNode current = queue.poll();
                    for (GraphEdge edge : current.edges) {
                        GraphNode next = edge.other(current);
                        if (next == start) continue;
                        if (next.isBoundaryCrossing) continue;
                        if (excluded.contains(next)) continue;
                        if (parent.containsKey(next)) continue;
                        parent.put(next, current);
                        if (next == seedB) { found = true; break; }
                        queue.add(next);
                    }
                }

                if (found) {
                    List<GraphNode> cycle = new ArrayList<>();
                    cycle.add(start);
                    List<GraphNode> path = new ArrayList<>();
                    GraphNode node = seedB;
                    while (node != seedA) {
                        path.add(0, node);
                        node = parent.get(node);
                    }
                    path.add(0, seedA);
                    cycle.addAll(path);
                    return cycle;
                }
            }
        }
        return null;
    }

    /**
     * Offsets a closed polygon inward by the given distance.
     * Uses perpendicular offsets at each vertex, choosing the side that
     * moves toward the polygon centroid (inward).
     */
    private static List<EastNorth> offsetClosedPolygon(List<EastNorth> poly, double dist) {
        int n = poly.size() - 1; // last == first for closed polygon
        if (n < 3) return null;

        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += poly.get(i).east();
            cy += poly.get(i).north();
        }
        cx /= n;
        cy /= n;
        EastNorth centroid = new EastNorth(cx, cy);

        List<EastNorth> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            EastNorth prev = poly.get((i - 1 + n) % n);
            EastNorth curr = poly.get(i);
            EastNorth next = poly.get((i + 1) % n);

            EastNorth[] offsets = GeometryUtils.perpendicularOffsets(prev, next, curr, dist);

            double d0 = Math.pow(offsets[0].east() - centroid.east(), 2)
                + Math.pow(offsets[0].north() - centroid.north(), 2);
            double d1 = Math.pow(offsets[1].east() - centroid.east(), 2)
                + Math.pow(offsets[1].north() - centroid.north(), 2);
            result.add(d0 < d1 ? offsets[0] : offsets[1]);
        }
        result.add(result.get(0));
        return result;
    }

    /**
     * Subtracts interior loop cutouts from result polygons.
     * For each loop, finds which result polygon contains its centroid,
     * then replaces that polygon with one that has the loop carved out
     * via a slit (zero-width cut connecting outer boundary to hole).
     */
    private static void applyInteriorLoops(List<List<EastNorth>> resultPolygons,
            List<InteriorLoop> loops, RoadGraph graph) {
        // Collect all road edges from the graph for slit crossing checks
        List<EastNorth[]> roadEdges = new ArrayList<>();
        for (GraphEdge edge : graph.edges) {
            roadEdges.add(new EastNorth[]{edge.from.position, edge.to.position});
            // Also add intermediate road node segments
            if (edge.sourceWay != null) {
                List<Node> wayNodes = edge.sourceWay.getNodes();
                for (int i = 0; i < wayNodes.size() - 1; i++) {
                    roadEdges.add(new EastNorth[]{
                        wayNodes.get(i).getEastNorth(),
                        wayNodes.get(i + 1).getEastNorth()});
                }
            }
        }

        for (InteriorLoop loop : loops) {
            List<EastNorth> hole = loop.innerPolygon;
            int hn = hole.size() - 1;
            if (hn < 3) continue;

            // Compute hole centroid
            double cx = 0, cy = 0;
            for (int i = 0; i < hn; i++) {
                cx += hole.get(i).east();
                cy += hole.get(i).north();
            }
            cx /= hn;
            cy /= hn;
            EastNorth centroid = new EastNorth(cx, cy);

            // Find which result polygon contains the centroid
            int containingIdx = -1;
            for (int pi = 0; pi < resultPolygons.size(); pi++) {
                if (pointInsideOrOnPolygon(centroid, resultPolygons.get(pi))) {
                    containingIdx = pi;
                    break;
                }
            }
            if (containingIdx < 0) continue;

            List<EastNorth> outer = resultPolygons.get(containingIdx);

            // Find closest pair of points (outer, hole) whose connecting slit
            // does not cross any corridor offset edge.
            int outerSplitIdx = 0;
            int holeSplitIdx = 0;
            double minDist = Double.MAX_VALUE;
            int outerN = outer.size() - 1; // exclude closing point
            for (int oi = 0; oi < outerN; oi++) {
                for (int hi = 0; hi < hn; hi++) {
                    double d = outer.get(oi).distanceSq(hole.get(hi));
                    if (d < minDist
                            && !slitCrossesCorridors(outer.get(oi), hole.get(hi),
                                                     roadEdges)) {
                        minDist = d;
                        outerSplitIdx = oi;
                        holeSplitIdx = hi;
                    }
                }
            }

            // Build new polygon: outer boundary with hole inserted via slit.
            // The hole must be traced in the OPPOSITE winding direction from
            // the outer boundary so it subtracts area.
            List<EastNorth> carved = new ArrayList<>();

            // Trace outer from split point around
            for (int k = 0; k <= outerN; k++) {
                carved.add(outer.get((outerSplitIdx + k) % outerN));
            }

            // Determine winding directions
            double outerArea = signedAreaEN(outer);
            double holeArea = signedAreaEN(hole);
            // If outer and hole have the same winding, reverse the hole
            // so it subtracts. If they have opposite winding, trace forward.
            boolean reverseHole = (outerArea > 0) == (holeArea > 0);

            if (reverseHole) {
                for (int k = 0; k <= hn; k++) {
                    carved.add(hole.get((holeSplitIdx - k + hn) % hn));
                }
            } else {
                for (int k = 0; k <= hn; k++) {
                    carved.add(hole.get((holeSplitIdx + k) % hn));
                }
            }

            // Close the polygon (back to start via the slit)
            carved.add(carved.get(0));

            // Replace the containing polygon with the carved version
            resultPolygons.set(containingIdx, carved);

            // Add the hole interior as a separate result polygon
            resultPolygons.add(hole);
        }
    }

    /**
     * Handles interior loops after corridor splitting.
     *
     * <p>When corridors split the polygon around an interior loop, the face tracer
     * produces 3 faces: two "real" pieces on either side of the loop, and a narrow
     * gap polygon between the two corridor offset polylines. This method:</p>
     * <ol>
     *   <li>Removes the narrow gap polygon</li>
     *   <li>Finds the result polygon that contains the loop interior</li>
     *   <li>Subtracts the loop interior from that polygon (replacing shared
     *       corridor-offset edges with the loop's outer boundary)</li>
     *   <li>Adds the loop interior as a new result polygon</li>
     * </ol>
     *
     * @return indices of loop interior polygons that should be protected from
     *         the junction-gap filter
     */
    private static Set<Integer> replaceLoopGapPolygons(List<List<EastNorth>> resultPolygons,
            List<InteriorLoop> loops) {
        Set<Integer> protectedIndices = new HashSet<>();
        for (InteriorLoop loop : loops) {
            double cycleArea = Math.abs(signedAreaEN(loop.cyclePolygon));
            EastNorth cycleCentroid = polygonCentroid(loop.cyclePolygon);

            // Step 1: Find and remove the narrow gap polygon
            int gapIdx = -1;
            double gapArea = Double.MAX_VALUE;
            for (int pi = 0; pi < resultPolygons.size(); pi++) {
                List<EastNorth> poly = resultPolygons.get(pi);
                double area = Math.abs(signedAreaEN(poly));
                if (area > cycleArea * 0.5) continue;
                if (area >= gapArea) continue;
                EastNorth centroid = polygonCentroid(poly);
                if (centroid == null || cycleCentroid == null) continue;
                double dist = centroid.distance(cycleCentroid);
                double cycleRadius = Math.sqrt(cycleArea / Math.PI);
                if (dist < cycleRadius * 2) {
                    gapIdx = pi;
                    gapArea = area;
                }
            }
            if (gapIdx >= 0) {
                resultPolygons.remove(gapIdx);
            }

            // Step 2: Find the result polygon that contains the loop centroid
            EastNorth loopCentroid = polygonCentroid(loop.innerPolygon);
            int containingIdx = -1;
            for (int pi = 0; pi < resultPolygons.size(); pi++) {
                if (loopCentroid != null
                        && pointInsideOrOnPolygon(loopCentroid, resultPolygons.get(pi))) {
                    containingIdx = pi;
                    break;
                }
            }

            // Step 3: Subtract loop interior from the containing polygon
            if (containingIdx >= 0) {
                List<EastNorth> container = resultPolygons.get(containingIdx);
                List<EastNorth> clipped = subtractLoopFromPolygon(
                    container, loop.innerPolygon);
                if (clipped != null) {
                    resultPolygons.set(containingIdx, clipped);
                }
            }

            // Step 4: Add loop interior as a new result polygon
            resultPolygons.add(loop.innerPolygon);
            protectedIndices.add(resultPolygons.size() - 1);
        }
        return protectedIndices;
    }

    /**
     * Subtracts a hole polygon from a container polygon by finding shared
     * edges (where corridor offsets overlap with hole boundary) and replacing
     * the shared portion with the non-shared boundary of the container.
     *
     * <p>The container polygon has vertices that follow the corridor offsets
     * around the loop interior. These vertices are (approximately) the same
     * as some hole boundary vertices. This method traces the container boundary,
     * skipping vertices that are inside the hole, and inserts hole boundary
     * vertices where the container enters/exits the hole region.</p>
     */
    private static List<EastNorth> subtractLoopFromPolygon(
            List<EastNorth> container, List<EastNorth> hole) {
        int cn = container.size() - 1; // exclude closing point
        int hn = hole.size() - 1;
        if (cn < 3 || hn < 3) return null;

        // Find container vertices that are inside or on the hole boundary.
        // These are the corridor-offset vertices that trace inside the loop.
        double tolerance = 5e-5; // ~5m in degrees — corridor offsets vs loop offsets differ slightly
        boolean[] insideHole = new boolean[cn];
        int firstOutside = -1;
        for (int i = 0; i < cn; i++) {
            insideHole[i] = pointInsideOrOnPolygon(container.get(i), hole)
                || isNearAnyVertex(container.get(i), hole, tolerance);
            if (!insideHole[i] && firstOutside < 0) firstOutside = i;
        }
        if (firstOutside < 0) return null; // all inside — shouldn't happen

        // Trace the container starting from firstOutside.
        // When we hit a vertex inside the hole, skip the inside vertices and
        // instead trace the hole boundary from the entry point to the exit point.
        // We try both directions around the hole and pick the one that produces
        // the larger-area result (correct subtraction = container minus hole).
        // Collect entry/exit info first.
        int entryContainerIdx = -1;
        int exitContainerIdx = -1;
        for (int step = 0; step < cn; step++) {
            int i = (firstOutside + step) % cn;
            int next = (i + 1) % cn;
            if (!insideHole[i] && insideHole[next] && entryContainerIdx < 0) {
                entryContainerIdx = i;
            }
            if (insideHole[i] && !insideHole[(i + 1) % cn] && exitContainerIdx < 0) {
                exitContainerIdx = (i + 1) % cn;
            }
        }
        if (entryContainerIdx < 0 || exitContainerIdx < 0) return null;

        int holeEntry = closestHoleVertex(container.get(entryContainerIdx), hole, hn);
        int holeExit = closestHoleVertex(container.get(exitContainerIdx), hole, hn);

        // Try both directions around the hole and pick the better result
        List<EastNorth> bestResult = null;
        double bestArea = -1;
        for (int dir = 0; dir < 2; dir++) {
            List<EastNorth> result = new ArrayList<>();
            // Trace outside container vertices
            for (int step = 0; step < cn; step++) {
                int i = (firstOutside + step) % cn;
                if (!insideHole[i]) {
                    result.add(container.get(i));
                    int next = (i + 1) % cn;
                    if (insideHole[next]) {
                        // Insert hole boundary vertices
                        if (dir == 0) {
                            // Forward direction around hole
                            for (int h = 0; h <= hn; h++) {
                                int hi = (holeEntry + h) % hn;
                                result.add(hole.get(hi));
                                if (hi == holeExit) break;
                            }
                        } else {
                            // Reverse direction around hole
                            for (int h = 0; h <= hn; h++) {
                                int hi = (holeEntry - h + hn) % hn;
                                result.add(hole.get(hi));
                                if (hi == holeExit) break;
                            }
                        }
                    }
                }
            }
            if (result.size() < 3) continue;
            result.add(result.get(0));
            double area = Math.abs(signedAreaEN(result));
            // The correct subtraction produces area ≈ container - hole,
            // which is SMALLER than the container. Pick the smaller result.
            if (bestResult == null || area < bestArea) {
                bestArea = area;
                bestResult = result;
            }
        }

        return bestResult;
    }

    /** Finds the closest hole vertex to a given point. */
    private static int closestHoleVertex(EastNorth pt, List<EastNorth> hole, int hn) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < hn; i++) {
            double d = pt.distanceSq(hole.get(i));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Returns true if pt is within tolerance of any vertex in poly. */
    private static boolean isNearAnyVertex(EastNorth pt, List<EastNorth> poly, double tol) {
        double tolSq = tol * tol;
        for (int i = 0; i < poly.size() - 1; i++) {
            if (pt.distanceSq(poly.get(i)) < tolSq) return true;
        }
        return false;
    }

    /**
     * Returns true if the closed polygon has any self-intersections
     * (non-adjacent edges that properly cross each other).
     */
    private static boolean isSelfIntersecting(List<EastNorth> poly) {
        int n = poly.size() - 1; // exclude closing point
        for (int i = 0; i < n; i++) {
            EastNorth a1 = poly.get(i);
            EastNorth a2 = poly.get(i + 1);
            for (int j = i + 2; j < n; j++) {
                if (j == n - 1 && i == 0) continue; // skip adjacent closing edge
                EastNorth b1 = poly.get(j);
                EastNorth b2 = poly.get(j + 1);
                if (GeometryUtils.segmentsCross(a1, a2, b1, b2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Computes the centroid of a closed polygon. */
    private static EastNorth polygonCentroid(List<EastNorth> poly) {
        int n = poly.size() - 1; // exclude closing point
        if (n < 3) return null;
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += poly.get(i).east();
            cy += poly.get(i).north();
        }
        return new EastNorth(cx / n, cy / n);
    }

    /** Returns true if the segment from a to b crosses any corridor offset edge. */
    private static boolean slitCrossesCorridors(EastNorth a, EastNorth b,
            List<EastNorth[]> roadEdges) {
        for (EastNorth[] edge : roadEdges) {
            if (Geometry.getSegmentSegmentIntersection(a, b, edge[0], edge[1]) != null) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Road graph data structures
    // -----------------------------------------------------------------------

    /** A node in the road network graph. */
    private static class GraphNode {
        final EastNorth position;
        final Node osmNode;           // null for synthetic crossing points
        final boolean isBoundaryCrossing;
        int polySegmentIndex = -1;     // for boundary crossings
        double polyT = 0;             // for boundary crossings
        int roadSegmentIndex = -1;     // segment index within the road Way
        final List<GraphEdge> edges = new ArrayList<>();

        GraphNode(EastNorth position, Node osmNode, boolean isBoundaryCrossing) {
            this.position = position;
            this.osmNode = osmNode;
            this.isBoundaryCrossing = isBoundaryCrossing;
        }
    }

    /** An edge in the road network graph (a road segment between two graph nodes). */
    private static class GraphEdge {
        final GraphNode from;
        final GraphNode to;
        final List<Node> intermediateNodes; // interior road nodes between from/to
        final double width;
        final Way sourceWay;

        GraphEdge(GraphNode from, GraphNode to, List<Node> intermediateNodes,
                  double width, Way sourceWay) {
            this.from = from;
            this.to = to;
            this.intermediateNodes = intermediateNodes;
            this.width = width;
            this.sourceWay = sourceWay;
        }

        GraphNode other(GraphNode n) {
            return n == from ? to : from;
        }
    }

    /** Info about one road Way for graph construction. */
    private static class RoadWayInfo {
        final Way way;
        final double width;
        final List<RawCrossing> crossings;

        RoadWayInfo(Way way, double width, List<RawCrossing> crossings) {
            this.way = way;
            this.width = width;
            this.crossings = crossings;
        }
    }

    /** The road network graph. */
    private static class RoadGraph {
        final List<GraphNode> nodes = new ArrayList<>();
        final List<GraphEdge> edges = new ArrayList<>();

        /**
         * Finds connected components using BFS.
         */
        List<List<GraphNode>> findConnectedComponents() {
            Set<GraphNode> visited = new HashSet<>();
            List<List<GraphNode>> components = new ArrayList<>();

            for (GraphNode start : nodes) {
                if (visited.contains(start)) continue;
                List<GraphNode> component = new ArrayList<>();
                LinkedList<GraphNode> queue = new LinkedList<>();
                queue.add(start);
                visited.add(start);

                while (!queue.isEmpty()) {
                    GraphNode current = queue.poll();
                    component.add(current);
                    for (GraphEdge edge : current.edges) {
                        GraphNode neighbor = edge.other(current);
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
                components.add(component);
            }
            return components;
        }
    }

    // -----------------------------------------------------------------------
    // Road graph construction
    // -----------------------------------------------------------------------

    /**
     * Builds a road graph from road way information.
     *
     * <p>For each road Way:</p>
     * <ul>
     *   <li>Boundary crossing points become graph nodes</li>
     *   <li>Road nodes inside the polygon become graph nodes</li>
     *   <li>Road nodes outside the polygon are ignored (except as edge endpoints)</li>
     *   <li>Edges connect consecutive inside/crossing nodes</li>
     * </ul>
     *
     * <p>Roads sharing an OSM Node (same object) are automatically connected
     * in the graph because they share the same GraphNode.</p>
     */
    private static RoadGraph buildRoadGraph(List<RoadWayInfo> roadInfos,
            List<Node> polyNodes) {
        RoadGraph graph = new RoadGraph();

        // Map from OSM Node → GraphNode, for connecting shared nodes
        Map<Node, GraphNode> nodeMap = new HashMap<>();

        for (RoadWayInfo info : roadInfos) {
            Way road = info.way;
            List<Node> roadNodes = road.getNodes();
            double width = info.width;

            // Sort crossings by road segment index (so we process them in order)
            List<RawCrossing> crossings = new ArrayList<>(info.crossings);
            crossings.sort((a, b) -> {
                int cmp = Integer.compare(a.roadSegmentIndex, b.roadSegmentIndex);
                return cmp != 0 ? cmp : Double.compare(
                    paramT(roadNodes.get(a.roadSegmentIndex).getEastNorth(),
                           roadNodes.get(a.roadSegmentIndex + 1).getEastNorth(),
                           a.point),
                    paramT(roadNodes.get(b.roadSegmentIndex).getEastNorth(),
                           roadNodes.get(b.roadSegmentIndex + 1).getEastNorth(),
                           b.point));
            });

            // Build a sequence of graph nodes along this road: road nodes inside
            // the polygon + crossing points, in road order
            List<GraphNode> roadGraphNodes = new ArrayList<>();
            int crossIdx = 0;

            for (int ri = 0; ri < roadNodes.size(); ri++) {
                // Insert any crossing points that fall on segment [ri-1, ri]
                if (ri > 0) {
                    while (crossIdx < crossings.size()
                           && crossings.get(crossIdx).roadSegmentIndex == ri - 1) {
                        RawCrossing cx = crossings.get(crossIdx);
                        GraphNode gn = new GraphNode(cx.point, null, true);
                        gn.polySegmentIndex = cx.segmentIndex;
                        gn.polyT = cx.t;
                        gn.roadSegmentIndex = cx.roadSegmentIndex;
                        graph.nodes.add(gn);
                        roadGraphNodes.add(gn);
                        crossIdx++;
                    }
                }

                // Add the road node itself if it's inside the polygon
                Node osmNode = roadNodes.get(ri);
                if (Geometry.nodeInsidePolygon(osmNode, polyNodes)) {
                    GraphNode gn = nodeMap.get(osmNode);
                    if (gn == null) {
                        gn = new GraphNode(osmNode.getEastNorth(), osmNode, false);
                        nodeMap.put(osmNode, gn);
                        graph.nodes.add(gn);
                    }
                    roadGraphNodes.add(gn);
                }
            }

            // Insert any remaining crossings (on the last segment)
            while (crossIdx < crossings.size()) {
                RawCrossing cx = crossings.get(crossIdx);
                GraphNode gn = new GraphNode(cx.point, null, true);
                gn.polySegmentIndex = cx.segmentIndex;
                gn.polyT = cx.t;
                gn.roadSegmentIndex = cx.roadSegmentIndex;
                graph.nodes.add(gn);
                roadGraphNodes.add(gn);
                crossIdx++;
            }

            // Create edges between consecutive graph nodes.
            // Skip edges where the road segment between two consecutive
            // boundary crossings is outside the polygon (re-entrant roads).
            for (int i = 0; i < roadGraphNodes.size() - 1; i++) {
                GraphNode from = roadGraphNodes.get(i);
                GraphNode to = roadGraphNodes.get(i + 1);

                // If both endpoints are boundary crossings and neither is an
                // interior node, check whether the road between them is inside
                // the polygon. If the midpoint is outside, skip this edge.
                if (from.isBoundaryCrossing && to.isBoundaryCrossing) {
                    EastNorth mid = new EastNorth(
                        (from.position.east() + to.position.east()) / 2,
                        (from.position.north() + to.position.north()) / 2);
                    List<EastNorth> polyEN = new ArrayList<>(polyNodes.size());
                    for (Node pn : polyNodes) polyEN.add(pn.getEastNorth());
                    if (!pointInsideOrOnPolygon(mid, polyEN)) {
                        continue; // road exits and re-enters — don't connect
                    }
                }

                // Collect intermediate road nodes between from and to
                List<Node> intermediates = collectIntermediates(
                    from, to, roadNodes, polyNodes);

                GraphEdge edge = new GraphEdge(from, to, intermediates, width, road);
                from.edges.add(edge);
                to.edges.add(edge);
                graph.edges.add(edge);
            }
        }

        return graph;
    }

    /**
     * Collects road nodes between two graph nodes that are inside the polygon,
     * for use as intermediate offset points in corridor construction.
     */
    private static List<Node> collectIntermediates(GraphNode from, GraphNode to,
            List<Node> roadNodes, List<Node> polyNodes) {
        // For direct connections (no intermediate road nodes), return empty
        // The intermediates are the road nodes between the two graph nodes
        // that weren't included as graph nodes themselves (i.e., they're inside
        // but only serve as shape points, not junctions/crossings)
        return Collections.emptyList(); // Graph nodes already include all inside nodes
    }

    // -----------------------------------------------------------------------
    // Path finding in the road graph
    // -----------------------------------------------------------------------

    /**
     * Finds a path from entry to exit through the road graph using BFS.
     */
    private static List<GraphEdge> findPath(GraphNode start, GraphNode end,
            RoadGraph graph) {
        if (start == end) return null;

        Map<GraphNode, GraphEdge> cameFrom = new HashMap<>();
        Set<GraphNode> visited = new HashSet<>();
        LinkedList<GraphNode> queue = new LinkedList<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            GraphNode current = queue.poll();
            if (current == end) {
                // Reconstruct path
                List<GraphEdge> path = new ArrayList<>();
                GraphNode node = end;
                while (node != start) {
                    GraphEdge edge = cameFrom.get(node);
                    path.add(0, edge);
                    node = edge.other(node);
                }
                return path;
            }

            for (GraphEdge edge : current.edges) {
                GraphNode neighbor = edge.other(current);
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    cameFrom.put(neighbor, edge);
                    queue.add(neighbor);
                }
            }
        }
        return null; // no path found
    }

    // -----------------------------------------------------------------------
    // Corridor construction from graph paths
    // -----------------------------------------------------------------------

    /**
     * Builds a RoadCorridor from a path through the road graph.
     * The path is a sequence of edges from entry crossing to exit crossing.
     *
     * @param junctionOffsets if non-null, caches offset points at junction nodes
     *        (degree 3+) so that multiple corridors sharing a junction use
     *        the same offset, preventing self-intersecting result polygons.
     *        The first corridor to reach a junction computes and caches;
     *        subsequent corridors reuse the cached value (swapping left/right
     *        if the traversal direction is reversed).
     */
    private static BreakPlan.RoadCorridor buildCorridorFromPath(
            GraphNode entry, GraphNode exit,
            List<GraphEdge> path, List<Node> polyNodes,
            Map<GraphNode, EastNorth[]> junctionOffsets) {

        // Collect the ordered sequence of graph nodes along the path
        List<GraphNode> pathNodes = new ArrayList<>();
        pathNodes.add(entry);
        GraphNode current = entry;
        for (GraphEdge edge : path) {
            current = edge.other(current);
            pathNodes.add(current);
        }

        // Use the maximum width along the path
        double maxWidth = 0;
        for (GraphEdge edge : path) {
            maxWidth = Math.max(maxWidth, edge.width);
        }
        double halfWidth = maxWidth / 2.0;

        // Build offset polylines
        List<EastNorth> leftOffsets = new ArrayList<>();
        List<EastNorth> rightOffsets = new ArrayList<>();

        for (int i = 0; i < pathNodes.size(); i++) {
            GraphNode gn = pathNodes.get(i);

            // Use cached offsets to ensure consistency across corridors
            // sharing any node (junction or boundary crossing).
            // The cached offsets have a fixed left/right assignment from the
            // first corridor that computed them. For subsequent corridors
            // approaching from different directions, we may need to swap
            // left/right to maintain consistency with the current path.
            if (junctionOffsets != null && i > 0) {
                EastNorth[] cached = junctionOffsets.get(gn);
                if (cached != null) {
                    EastNorth[] offsets = new EastNorth[]{cached[0], cached[1]};
                    // Check side consistency against previous left offset
                    EastNorth prevLeft = leftOffsets.get(leftOffsets.size() - 1);
                    EastNorth prevCenter = pathNodes.get(i - 1).position;
                    double segDx = gn.position.east() - prevCenter.east();
                    double segDy = gn.position.north() - prevCenter.north();
                    double segLen = Math.sqrt(segDx * segDx + segDy * segDy);
                    if (segLen > 1e-15) {
                        double prevSide = segDx * (prevLeft.north() - prevCenter.north())
                            - segDy * (prevLeft.east() - prevCenter.east());
                        double curSide = segDx * (offsets[0].north() - gn.position.north())
                            - segDy * (offsets[0].east() - gn.position.east());
                        if (prevSide * curSide < 0) {
                            EastNorth tmp = offsets[0];
                            offsets[0] = offsets[1];
                            offsets[1] = tmp;
                        }
                    }
                    leftOffsets.add(offsets[0]);
                    rightOffsets.add(offsets[1]);
                    continue;
                }
            }

            // Compute offset points for this node
            EastNorth[] offsets;
            if (i == 0 || i == pathNodes.size() - 1) {
                // At entry/exit crossing, find portal points ON the polygon
                // boundary by walking until we're halfWidth from the road.
                GraphEdge edge = (i == 0) ? path.get(0) : path.get(path.size() - 1);
                EastNorth roadStart = getSegmentDirectionAt(gn, edge, true);
                EastNorth roadEnd = getSegmentDirectionAt(gn, edge, false);
                EastNorth fwd = findBoundaryPortalPoint(polyNodes,
                    gn.polySegmentIndex, gn.polyT, roadStart, roadEnd,
                    halfWidth, true);
                EastNorth bwd = findBoundaryPortalPoint(polyNodes,
                    gn.polySegmentIndex, gn.polyT, roadStart, roadEnd,
                    halfWidth, false);
                if (fwd != null && bwd != null) {
                    offsets = new EastNorth[]{fwd, bwd};
                } else {
                    // Fallback to perpendicular offsets if boundary walk fails
                    offsets = GeometryUtils.perpendicularOffsets(
                        roadStart, roadEnd, gn.position, halfWidth);
                }
            } else {
                // Interior node: compute bisector offsets
                EastNorth prevPos = pathNodes.get(i - 1).position;
                EastNorth nextPos = pathNodes.get(i + 1).position;
                offsets = computeMiterJoin(
                    prevPos, gn.position, nextPos, halfWidth);
                if (offsets == null) {
                    offsets = GeometryUtils.perpendicularOffsets(
                        prevPos, nextPos, gn.position, halfWidth);
                }
            }

            if (i == 0) {
                // At entry: determine which offset is "right" (the side
                // facing the polygon boundary that arrives at this entry).
                // The polygon boundary vertex just before the entry crossing
                // (in clockwise ring order) tells us which direction the
                // boundary was coming from. "Right" should be on THAT side.
                int prevPolyIdx = entry.polySegmentIndex;
                EastNorth prevPolyVtx = polyNodes.get(prevPolyIdx).getEastNorth();
                // Road direction at the entry
                GraphEdge firstEdge = path.get(0);
                EastNorth roadP = getSegmentDirectionAt(gn, firstEdge, true);
                EastNorth roadN = getSegmentDirectionAt(gn, firstEdge, false);
                double rdx = roadN.east() - roadP.east();
                double rdy = roadN.north() - roadP.north();
                // Side of previous polygon vertex relative to road direction
                double boundarySide = rdx * (prevPolyVtx.north() - gn.position.north())
                    - rdy * (prevPolyVtx.east() - gn.position.east());
                // Side of offsets[1] (currently "right" from raw road direction)
                double rightSide = rdx * (offsets[1].north() - gn.position.north())
                    - rdy * (offsets[1].east() - gn.position.east());
                // If they disagree, swap so offsets[1] faces the boundary
                if (boundarySide * rightSide < 0) {
                    EastNorth tmp = offsets[0];
                    offsets[0] = offsets[1];
                    offsets[1] = tmp;
                }
            } else {
                // For all non-entry nodes, ensure consistent side assignment
                // by checking against the PREVIOUS node's left offset.
                EastNorth prevLeft = leftOffsets.get(leftOffsets.size() - 1);
                EastNorth prevCenter = pathNodes.get(i - 1).position;
                double segDx = gn.position.east() - prevCenter.east();
                double segDy = gn.position.north() - prevCenter.north();
                double segLen = Math.sqrt(segDx * segDx + segDy * segDy);
                if (segLen > 1e-15) {
                    double prevSide = segDx * (prevLeft.north() - prevCenter.north())
                        - segDy * (prevLeft.east() - prevCenter.east());
                    double curSide = segDx * (offsets[0].north() - gn.position.north())
                        - segDy * (offsets[0].east() - gn.position.east());
                    if (prevSide * curSide < 0) {
                        EastNorth tmp = offsets[0];
                        offsets[0] = offsets[1];
                        offsets[1] = tmp;
                    }
                }
            }
            leftOffsets.add(offsets[0]);
            rightOffsets.add(offsets[1]);
            if (junctionOffsets != null) junctionOffsets.put(gn, offsets);
        }

        BreakPlan.CrossingPair pair = new BreakPlan.CrossingPair(
            new BreakPlan.BoundaryPosition(entry.polySegmentIndex, entry.polyT,
                entry.position),
            new BreakPlan.BoundaryPosition(exit.polySegmentIndex, exit.polyT,
                exit.position),
            leftOffsets, rightOffsets);

        // Collect all source ways
        List<Way> sourceWays = new ArrayList<>();
        for (GraphEdge edge : path) {
            if (!sourceWays.contains(edge.sourceWay)) {
                sourceWays.add(edge.sourceWay);
            }
        }

        return new BreakPlan.RoadCorridor(sourceWays, maxWidth,
            Collections.singletonList(pair));
    }

    /**
     * Gets the road direction at a graph node within an edge.
     * Returns the segment start or end point for perpendicular computation.
     */
    private static EastNorth getSegmentDirectionAt(GraphNode node, GraphEdge edge,
            boolean wantPrev) {
        List<Node> roadNodes = edge.sourceWay.getNodes();

        if (node.isBoundaryCrossing && node.roadSegmentIndex >= 0) {
            // Use the road segment that contains this crossing
            if (wantPrev) {
                return roadNodes.get(node.roadSegmentIndex).getEastNorth();
            } else {
                return roadNodes.get(node.roadSegmentIndex + 1).getEastNorth();
            }
        }

        // For interior nodes, find the node in the road and use neighbors
        if (node.osmNode != null) {
            for (int i = 0; i < roadNodes.size(); i++) {
                if (roadNodes.get(i) == node.osmNode) {
                    if (wantPrev) {
                        if (i > 0) {
                            return roadNodes.get(i - 1).getEastNorth();
                        } else if (roadNodes.size() > 1) {
                            // At start of way: reflect next node through this node
                            // so perpendicular is computed from the single segment
                            EastNorth nextN = roadNodes.get(1).getEastNorth();
                            return new EastNorth(
                                2 * node.position.east() - nextN.east(),
                                2 * node.position.north() - nextN.north());
                        } else {
                            return node.position;
                        }
                    } else {
                        if (i < roadNodes.size() - 1) {
                            return roadNodes.get(i + 1).getEastNorth();
                        } else if (roadNodes.size() > 1) {
                            // At end of way: reflect prev node through this node
                            EastNorth prevN = roadNodes.get(i - 1).getEastNorth();
                            return new EastNorth(
                                2 * node.position.east() - prevN.east(),
                                2 * node.position.north() - prevN.north());
                        } else {
                            return node.position;
                        }
                    }
                }
            }
        }

        return node.position;
    }

    /**
     * Fixes miter-join crossings between left and right offset polylines.
     *
     * <p>At sharp road bends, the perpendicular offset points on opposite sides
     * can create edges that cross each other (the "miter join" problem). This
     * method detects where corresponding left and right edges cross and clamps
     * both sides to the intersection point, eliminating the crossing.</p>
     *
     * <p>Modifies the lists in place.</p>
     */
    /**
     * Fixes miter-join crossings between left and right offset polylines
     * using bevel joins.
     *
     * <p>At sharp road bends, the perpendicular offset points on opposite sides
     * can create edges that cross each other. This replaces the single miter
     * point with two bevel points pulled back along the incoming/outgoing road
     * segments, each offset perpendicular to its own segment. This keeps each
     * offset on its correct side of the road.</p>
     */
    // (fixCorridorCrossings removed — no longer needed)

    /**
     * Computes the miter join at a road bend: the intersection of the left
     * offset lines from the incoming and outgoing segments, and similarly
     * for the right offset lines.
     *
     * <p>Uses {@link GeometryUtils#perpendicularOffsets} to compute offset
     * points that correctly handle non-uniform EastNorth coordinate scaling
     * (e.g., EPSG:4326 where 1° longitude ≠ 1° latitude in meters).</p>
     *
     * @param prev  the previous road node
     * @param point the current road node (bend vertex)
     * @param next  the next road node
     * @param hw    half-width of the corridor in meters
     * @return [leftMiter, rightMiter], or null if segments are nearly collinear
     */
    /**
     * Computes bisector offset points at a road bend.
     *
     * <p>At interior road nodes where the road changes direction, computes
     * offset points along the perpendicular to the angle bisector of the
     * incoming and outgoing segments, at the configured distance from the
     * road node. Returns a 2-element array [left, right] or null if
     * segments are nearly collinear (in which case simple perpendicular
     * offsets suffice).</p>
     */
    private static EastNorth[] computeMiterJoin(EastNorth prev, EastNorth point,
            EastNorth next, double hw) {
        // Nearly collinear — simple perpendicular will do
        double inDx = point.east() - prev.east();
        double inDy = point.north() - prev.north();
        double inLen = Math.sqrt(inDx * inDx + inDy * inDy);
        double outDx = next.east() - point.east();
        double outDy = next.north() - point.north();
        double outLen = Math.sqrt(outDx * outDx + outDy * outDy);

        if (inLen < 1e-15 || outLen < 1e-15) return null;

        double dot = inDx * outDx + inDy * outDy;
        if (dot / (inLen * outLen) > 0.95) {
            return null;
        }

        // Compute bisector direction: average of normalized incoming and outgoing
        double inNx = inDx / inLen, inNy = inDy / inLen;
        double outNx = outDx / outLen, outNy = outDy / outLen;
        double bisX = inNx + outNx;
        double bisY = inNy + outNy;
        double bisLen = Math.sqrt(bisX * bisX + bisY * bisY);

        if (bisLen < 1e-15) {
            // 180° turn — use incoming perpendicular
            return null;
        }

        // Offset perpendicular to the bisector direction, at distance hw
        // from the road node. Use perpendicularOffsets with bisector as
        // the "line direction" so it handles coordinate scaling correctly.
        EastNorth bisPt = new EastNorth(
            point.east() + bisX / bisLen,
            point.north() + bisY / bisLen);
        return GeometryUtils.perpendicularOffsets(point, bisPt, point, hw);
    }

    // (mid and computeJunctionBisectorPoint removed — no longer needed)

    /**
     * Finds a point ON the polygon boundary that is {@code bufferMeters} away
     * from the road line, walking along the boundary from the crossing point.
     *
     * <p>Instead of placing portal nodes perpendicular to the road (which
     * creates new geometry deviating from the original polygon), this walks
     * along the existing polygon boundary until it finds a point at the
     * required buffer distance from the road centerline. The result polygon
     * edges follow the original polygon path.</p>
     *
     * @param polyNodes     closed polygon node list (first == last)
     * @param segIdx        polygon segment index where the road crosses
     * @param segT          parametric t on that segment (0–1) for the crossing
     * @param roadLineStart start of the road segment at the crossing
     * @param roadLineEnd   end of the road segment at the crossing
     * @param bufferMeters  required distance from road centerline (halfWidth)
     * @param forward       true to walk forward (increasing segment index),
     *                      false to walk backward
     * @return the point on the polygon boundary at bufferMeters from the road,
     *         or null if no such point exists within a reasonable distance
     */
    private static EastNorth findBoundaryPortalPoint(
            List<Node> polyNodes, int segIdx, double segT,
            EastNorth roadLineStart, EastNorth roadLineEnd,
            double bufferMeters, boolean forward) {

        int segCount = polyNodes.size() - 1;
        double metersPerUnit = org.openstreetmap.josm.data.projection.ProjectionRegistry
            .getProjection().getMetersPerUnit();

        // Road direction vector (in EastNorth units)
        double rdx = roadLineEnd.east() - roadLineStart.east();
        double rdy = roadLineEnd.north() - roadLineStart.north();
        double roadLen = Math.sqrt(rdx * rdx + rdy * rdy);
        if (roadLen < 1e-15) return null;
        // Unit normal to road (perpendicular)
        double rnx = -rdy / roadLen;
        double rny = rdx / roadLen;

        // Convert buffer from meters to EastNorth units
        double bufferUnits = bufferMeters / metersPerUnit;

        // For degree-based projections, adjust for latitude
        double eastScale = 1.0;
        if (metersPerUnit > 10000) {
            // Approximate latitude from the crossing point
            EastNorth crossPt = interpolate(
                polyNodes.get(segIdx).getEastNorth(),
                polyNodes.get(segIdx + 1).getEastNorth(), segT);
            double cosLat = Math.cos(Math.toRadians(crossPt.north()));
            if (cosLat > 0.01) eastScale = cosLat;
        }

        // Start walking from the crossing point along the polygon boundary.
        // The crossing is on segment segIdx at parameter segT.
        EastNorth prevPt = interpolate(
            polyNodes.get(segIdx).getEastNorth(),
            polyNodes.get(segIdx + 1).getEastNorth(), segT);
        double prevDist = 0; // distance from road at crossing is 0

        // Walk up to segCount steps (full ring)
        int step = forward ? 1 : -1;
        // First partial step: from crossing point to next vertex in walk direction
        int nextVertIdx;
        if (forward) {
            nextVertIdx = segIdx + 1;
        } else {
            nextVertIdx = segIdx;
        }

        for (int walked = 0; walked < segCount; walked++) {
            int vi = ((nextVertIdx % segCount) + segCount) % segCount;
            EastNorth vtx = polyNodes.get(vi).getEastNorth();
            if (vtx == null) break;

            // Compute signed perpendicular distance from this vertex to road line
            // using the road normal. Account for east/north scaling.
            double dpEast = (vtx.east() - roadLineStart.east()) * eastScale;
            double dpNorth = vtx.north() - roadLineStart.north();
            double dist = Math.abs(
                dpEast * (rnx * eastScale) + dpNorth * rny)
                / Math.sqrt(rnx * rnx * eastScale * eastScale + rny * rny)
                * metersPerUnit;

            // Actually, simpler: perpendicular distance in metric units
            // d = |cross(road_dir, point - road_start)| / |road_dir| in metric
            double vdxM = (vtx.east() - roadLineStart.east()) * eastScale * metersPerUnit;
            double vdyM = (vtx.north() - roadLineStart.north()) * metersPerUnit;
            double rdxM = rdx * eastScale * metersPerUnit;
            double rdyM = rdy * metersPerUnit;
            double roadLenM = Math.sqrt(rdxM * rdxM + rdyM * rdyM);
            dist = Math.abs(vdxM * rdyM - vdyM * rdxM) / roadLenM;

            if (dist >= bufferMeters) {
                // Interpolate between prevPt and vtx to find exact point at bufferMeters
                if (dist == prevDist) return vtx; // avoid div by zero
                double frac = (bufferMeters - prevDist) / (dist - prevDist);
                frac = Math.max(0, Math.min(1, frac));
                return interpolate(prevPt, vtx, frac);
            }

            prevPt = vtx;
            prevDist = dist;
            nextVertIdx += step;
        }

        return null; // couldn't find a point far enough from the road
    }

    /** Linear interpolation between two EastNorth points. */
    private static EastNorth interpolate(EastNorth a, EastNorth b, double t) {
        return new EastNorth(
            a.east() + t * (b.east() - a.east()),
            a.north() + t * (b.north() - a.north()));
    }

    /**
     * Computes the intersection point of two line segments.
     * Returns null if segments are parallel.
     */
    private static EastNorth segmentIntersection(EastNorth a1, EastNorth a2,
            EastNorth b1, EastNorth b2) {
        double dx1 = a2.east() - a1.east();
        double dy1 = a2.north() - a1.north();
        double dx2 = b2.east() - b1.east();
        double dy2 = b2.north() - b1.north();

        double denom = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(denom) < 1e-20) return null;

        double t = ((b1.east() - a1.east()) * dy2 - (b1.north() - a1.north()) * dx2) / denom;
        return new EastNorth(a1.east() + t * dx1, a1.north() + t * dy1);
    }

    // -----------------------------------------------------------------------
    // Crossing detection
    // -----------------------------------------------------------------------

    static class RawCrossing implements Comparable<RawCrossing> {
        final int segmentIndex;
        final double t;
        final EastNorth point;
        final int roadSegmentIndex;

        RawCrossing(int segmentIndex, double t, EastNorth point, int roadSegmentIndex) {
            this.segmentIndex = segmentIndex;
            this.t = t;
            this.point = point;
            this.roadSegmentIndex = roadSegmentIndex;
        }

        @Override
        public int compareTo(RawCrossing o) {
            int cmp = Integer.compare(this.segmentIndex, o.segmentIndex);
            return cmp != 0 ? cmp : Double.compare(this.t, o.t);
        }
    }

    static List<RawCrossing> findCrossings(List<Node> polyNodes, Way road) {
        List<RawCrossing> crossings = new ArrayList<>();
        int polySegCount = polyNodes.size() - 1;
        List<Node> roadNodes = road.getNodes();
        int roadSegCount = roadNodes.size() - 1;

        for (int pi = 0; pi < polySegCount; pi++) {
            EastNorth p1 = polyNodes.get(pi).getEastNorth();
            EastNorth p2 = polyNodes.get(pi + 1).getEastNorth();
            if (p1 == null || p2 == null) continue;

            for (int ri = 0; ri < roadSegCount; ri++) {
                EastNorth r1 = roadNodes.get(ri).getEastNorth();
                EastNorth r2 = roadNodes.get(ri + 1).getEastNorth();
                if (r1 == null || r2 == null) continue;

                EastNorth ix = Geometry.getSegmentSegmentIntersection(p1, p2, r1, r2);
                if (ix != null) {
                    double tol = 1e-6;
                    if (GeometryUtils.isNear(ix, p1, tol) || GeometryUtils.isNear(ix, p2, tol)
                        || GeometryUtils.isNear(ix, r1, tol) || GeometryUtils.isNear(ix, r2, tol)) {
                        continue;
                    }
                    double t = paramT(p1, p2, ix);
                    crossings.add(new RawCrossing(pi, t, ix, ri));
                }
            }
        }
        return crossings;
    }

    private static boolean roadIntersectsAnyRing(Way road, List<List<Node>> innerRings) {
        List<Node> roadNodes = road.getNodes();
        for (List<Node> ring : innerRings) {
            int ringSegCount = ring.size() - 1;
            for (int ri = 0; ri < roadNodes.size() - 1; ri++) {
                EastNorth r1 = roadNodes.get(ri).getEastNorth();
                EastNorth r2 = roadNodes.get(ri + 1).getEastNorth();
                if (r1 == null || r2 == null) continue;
                for (int ii = 0; ii < ringSegCount; ii++) {
                    EastNorth i1 = ring.get(ii).getEastNorth();
                    EastNorth i2 = ring.get(ii + 1).getEastNorth();
                    if (i1 == null || i2 == null) continue;
                    if (Geometry.getSegmentSegmentIntersection(r1, r2, i1, i2) != null) return true;
                }
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Polygon splitting — augmented ring approach
    // -----------------------------------------------------------------------

    /**
     * Splits a closed polygon using road corridor crossing pairs.
     */
    static List<List<EastNorth>> splitPolygon(List<Node> polyNodes,
            List<BreakPlan.RoadCorridor> corridors) {

        // Collect ALL cut events (entry and exit for each crossing pair)
        List<CutEvent> events = new ArrayList<>();
        int cutId = 0;
        for (BreakPlan.RoadCorridor corridor : corridors) {
            for (BreakPlan.CrossingPair pair : corridor.getCrossingPairs()) {
                events.add(new CutEvent(pair.entry, pair.exit, pair.leftOffsets,
                    pair.rightOffsets, cutId));
                cutId++;
            }
        }
        if (events.isEmpty()) return null;

        // Build augmented ring with portal points at crossings
        List<PortalInsertion> insertions = new ArrayList<>();
        for (CutEvent ev : events) {
            insertions.add(new PortalInsertion(ev.entry.segmentIndex, ev.entry.t, ev, true));
            insertions.add(new PortalInsertion(ev.exit.segmentIndex, ev.exit.t, ev, false));
        }
        Collections.sort(insertions);

        int segCount = polyNodes.size() - 1;
        List<RingNode> ring = new ArrayList<>();
        int insIdx = 0;

        for (int si = 0; si < segCount; si++) {
            ring.add(new RingNode(polyNodes.get(si).getEastNorth()));
            while (insIdx < insertions.size() && insertions.get(insIdx).segmentIndex == si) {
                PortalInsertion ins = insertions.get(insIdx);
                addPortalNodes(ring, ins);
                insIdx++;
            }
        }
        while (insIdx < insertions.size()) {
            addPortalNodes(ring, insertions.get(insIdx));
            insIdx++;
        }

        // Build portal jump map: entryRight → exitRight, exitLeft → entryLeft
        for (CutEvent ev : events) {
            RingNode entryLeft = null, entryRight = null;
            RingNode exitLeft = null, exitRight = null;
            for (RingNode rn : ring) {
                if (rn.cutId == ev.cutId) {
                    if (rn.isEntry && rn.isLeft) entryLeft = rn;
                    if (rn.isEntry && !rn.isLeft) entryRight = rn;
                    if (!rn.isEntry && rn.isLeft) exitLeft = rn;
                    if (!rn.isEntry && !rn.isLeft) exitRight = rn;
                }
            }
            if (entryLeft == null || entryRight == null
                || exitLeft == null || exitRight == null) return null;

            // Build offset paths (intermediate points between entry and exit)
            List<EastNorth> rightFwd = new ArrayList<>(
                ev.rightOffsets.subList(1, ev.rightOffsets.size() - 1));
            List<EastNorth> leftFwd = new ArrayList<>(
                ev.leftOffsets.subList(1, ev.leftOffsets.size() - 1));
            List<EastNorth> leftRev = new ArrayList<>(leftFwd);
            Collections.reverse(leftRev);

            // entryRight → exitRight via right offset polyline
            entryRight.jumpTarget = exitRight;
            entryRight.jumpPath = rightFwd;

            // exitLeft → entryLeft via reversed left offset polyline
            exitLeft.jumpTarget = entryLeft;
            exitLeft.jumpPath = leftRev;
        }

        return traceAllFaces(ring);
    }

    private static void addPortalNodes(List<RingNode> ring, PortalInsertion ins) {
        CutEvent ev = ins.event;
        if (ins.isEntry) {
            EastNorth right = ev.rightOffsets.get(0);
            EastNorth left = ev.leftOffsets.get(0);
            ring.add(new RingNode(right, ev.cutId, true, false));
            ring.add(new RingNode(left, ev.cutId, true, true));
        } else {
            EastNorth left = ev.leftOffsets.get(ev.leftOffsets.size() - 1);
            EastNorth right = ev.rightOffsets.get(ev.rightOffsets.size() - 1);
            ring.add(new RingNode(left, ev.cutId, false, true));
            ring.add(new RingNode(right, ev.cutId, false, false));
        }
    }

    private static List<List<EastNorth>> traceAllFaces(List<RingNode> ring) {
        int n = ring.size();
        boolean[] visited = new boolean[n];
        List<List<EastNorth>> faces = new ArrayList<>();

        for (int start = 0; start < n; start++) {
            if (visited[start]) continue;

            List<EastNorth> face = new ArrayList<>();
            int current = start;

            while (true) {
                if (visited[current] && current == start && !face.isEmpty()) {
                    break;
                }
                if (visited[current] && current != start) {
                    break;
                }
                visited[current] = true;
                RingNode rn = ring.get(current);
                face.add(rn.point);

                if (rn.jumpTarget != null) {
                    List<EastNorth> path = rn.jumpPath;
                    if (path != null && !path.isEmpty()) {
                        face.addAll(path);
                    }
                    int targetIdx = ring.indexOf(rn.jumpTarget);
                    if (targetIdx < 0) break;
                    current = targetIdx;
                } else {
                    current = (current + 1) % n;
                }

                if (face.size() > n * 3) break;
            }

            if (face.size() >= 3) {
                face.add(face.get(0));
                faces.add(face);
            }
        }

        return faces.size() >= 2 ? faces : null;
    }

    // -----------------------------------------------------------------------
    // Data classes
    // -----------------------------------------------------------------------

    private static class RingNode {
        final EastNorth point;
        final int cutId;
        final boolean isEntry;
        final boolean isLeft;

        RingNode jumpTarget;
        List<EastNorth> jumpPath;

        RingNode(EastNorth point) {
            this(point, -1, false, false);
        }

        RingNode(EastNorth point, int cutId, boolean isEntry, boolean isLeft) {
            this.point = point;
            this.cutId = cutId;
            this.isEntry = isEntry;
            this.isLeft = isLeft;
        }
    }

    private static class CutEvent {
        final BreakPlan.BoundaryPosition entry;
        final BreakPlan.BoundaryPosition exit;
        final List<EastNorth> leftOffsets;
        final List<EastNorth> rightOffsets;
        final int cutId;

        CutEvent(BreakPlan.BoundaryPosition entry, BreakPlan.BoundaryPosition exit,
                 List<EastNorth> leftOffsets, List<EastNorth> rightOffsets, int cutId) {
            this.entry = entry;
            this.exit = exit;
            this.leftOffsets = leftOffsets;
            this.rightOffsets = rightOffsets;
            this.cutId = cutId;
        }
    }

    private static class PortalInsertion implements Comparable<PortalInsertion> {
        final int segmentIndex;
        final double t;
        final CutEvent event;
        final boolean isEntry;

        PortalInsertion(int segmentIndex, double t, CutEvent event, boolean isEntry) {
            this.segmentIndex = segmentIndex;
            this.t = t;
            this.event = event;
            this.isEntry = isEntry;
        }

        @Override
        public int compareTo(PortalInsertion o) {
            int cmp = Integer.compare(this.segmentIndex, o.segmentIndex);
            if (cmp != 0) return cmp;
            cmp = Double.compare(this.t, o.t);
            if (cmp != 0) return cmp;
            // At the same boundary position, exits come before entries.
            // This ensures correct portal ordering when a boundary crossing
            // is shared by two corridors (one's exit, another's entry).
            return Boolean.compare(this.isEntry, o.isEntry);
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /** Shoelace signed area for EastNorth coordinate lists. */
    private static double signedAreaEN(List<EastNorth> poly) {
        double area = 0;
        int n = poly.size() - 1; // exclude closure point
        if (n < 1) return 0;
        for (int i = 0; i < n; i++) {
            EastNorth curr = poly.get(i);
            EastNorth next = poly.get((i + 1) % n);
            area += curr.east() * next.north() - next.east() * curr.north();
        }
        return area / 2.0;
    }

    static boolean pointInsideOrOnPolygon(EastNorth point, List<EastNorth> polygon) {
        int n = polygon.size();
        if (n < 3) return false;

        int last = n - 1;
        if (GeometryUtils.isNear(polygon.get(0), polygon.get(last), 1e-6)) {
            last = n - 2;
        }

        boolean inside = false;
        double px = point.east();
        double py = point.north();

        for (int i = 0, j = last; i <= last; j = i++) {
            double iy = polygon.get(i).north();
            double jy = polygon.get(j).north();
            if (((iy > py) != (jy > py))
                && (px < (polygon.get(j).east() - polygon.get(i).east())
                    * (py - iy) / (jy - iy) + polygon.get(i).east())) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double paramT(EastNorth from, EastNorth to, EastNorth point) {
        double dx = to.east() - from.east();
        double dy = to.north() - from.north();
        double len2 = dx * dx + dy * dy;
        if (len2 < 1e-20) return 0;
        return ((point.east() - from.east()) * dx + (point.north() - from.north()) * dy) / len2;
    }

    /**
     * Resolves edge crossings between result polygons.
     *
     * <p>When two result polygons have edges that properly cross, the crossing
     * point is inserted into both polygons. Then the outside portion of each
     * polygon (the vertex that ended up on the wrong side of the other polygon)
     * is moved to the crossing point, eliminating the overlap.</p>
     *
     * <p>Iterates until no more crossings are found.</p>
     */
    /**
     * Resolves edge crossings between result polygons by snapping nearby
     * vertices across polygons to shared positions.
     *
     * <p>At junctions where multiple corridors meet, each polygon gets slightly
     * different offset positions. This creates edges that cross near the junction.
     * Fix by finding clusters of nearby vertices across different polygons and
     * snapping them to their centroid.</p>
     */
    private static void resolveInterPolygonCrossings(List<List<EastNorth>> polygons) {
        // Collect ALL crossing points first, then fix atomically.
        // This avoids the cascading problem where fixing one pair
        // introduces a crossing with a third polygon.
        for (int iter = 0; iter < 10; iter++) {
            // Find all crossing points between all polygon pairs
            List<EastNorth> crossingPoints = new ArrayList<>();
            for (int i = 0; i < polygons.size(); i++) {
                List<EastNorth> polyA = polygons.get(i);
                for (int j = i + 1; j < polygons.size(); j++) {
                    List<EastNorth> polyB = polygons.get(j);
                    for (int ai = 0; ai < polyA.size() - 1; ai++) {
                        EastNorth a1 = polyA.get(ai);
                        EastNorth a2 = polyA.get(ai + 1);
                        for (int bi = 0; bi < polyB.size() - 1; bi++) {
                            EastNorth b1 = polyB.get(bi);
                            EastNorth b2 = polyB.get(bi + 1);
                            if (GeometryUtils.segmentsCross(a1, a2, b1, b2)) {
                                EastNorth ix = segmentIntersection(a1, a2, b1, b2);
                                if (ix != null) crossingPoints.add(ix);
                            }
                        }
                    }
                }
            }
            if (crossingPoints.isEmpty()) break;

            // Cluster crossing points that are close together (junction region)
            // and snap nearby vertices from ALL polygons to each cluster centroid.
            // Use a small radius (~1m in EastNorth units) to avoid snapping
            // offset points on opposite sides of a road together.
            double snapRadius = 1e-5;
            List<EastNorth> clusterCentroids = clusterPoints(crossingPoints, snapRadius);

            for (EastNorth centroid : clusterCentroids) {
                // For each polygon, find vertices near this centroid and snap them
                for (int pi = 0; pi < polygons.size(); pi++) {
                    List<EastNorth> poly = polygons.get(pi);
                    List<EastNorth> fixed = new ArrayList<>(poly);
                    boolean modified = false;
                    for (int vi = 0; vi < fixed.size() - 1; vi++) {
                        if (dist2(fixed.get(vi), centroid) < snapRadius * snapRadius) {
                            fixed.set(vi, centroid);
                            modified = true;
                        }
                    }
                    if (modified) {
                        fixClosure(fixed);
                        polygons.set(pi, fixed);
                    }
                }
            }
        }
    }

    /**
     * Clusters points that are within the given radius of each other.
     * Returns the centroid of each cluster.
     */
    private static List<EastNorth> clusterPoints(List<EastNorth> points, double radius) {
        double r2 = radius * radius;
        boolean[] used = new boolean[points.size()];
        List<EastNorth> centroids = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            double ex = points.get(i).east();
            double ny = points.get(i).north();
            int count = 1;
            for (int j = i + 1; j < points.size(); j++) {
                if (used[j]) continue;
                if (dist2(points.get(i), points.get(j)) < r2) {
                    used[j] = true;
                    ex += points.get(j).east();
                    ny += points.get(j).north();
                    count++;
                }
            }
            centroids.add(new EastNorth(ex / count, ny / count));
        }
        return centroids;
    }

    private static void fixClosure(List<EastNorth> poly) {
        if (poly.size() > 1) {
            // Ensure first == last for closed polygon
            poly.set(poly.size() - 1, poly.get(0));
        }
    }

    private static double dist2(EastNorth a, EastNorth b) {
        double dx = a.east() - b.east();
        double dy = a.north() - b.north();
        return dx * dx + dy * dy;
    }

}
