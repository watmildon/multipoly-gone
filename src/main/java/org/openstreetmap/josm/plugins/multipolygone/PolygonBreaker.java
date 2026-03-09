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

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.tools.Logging;

/**
 * Analyzes a selected polygon and finds intersecting roads that can split it.
 * Uses JTS polygon boolean operations (buffer + difference) to compute the split.
 */
class PolygonBreaker {

    private static final GeometryFactory GF = new GeometryFactory();

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
            return analyzeMultipolygon(rel, ds);
        }
        return null;
    }

    private static BreakPlan analyzeMultipolygon(Relation rel, DataSet ds) {
        String type = rel.get("type");
        if (!"multipolygon".equals(type) && !"boundary".equals(type)) return null;

        // Find outer way(s) and inner way(s)
        List<Way> outerWays = new ArrayList<>();
        List<Way> innerWays = new ArrayList<>();
        Set<Way> memberWays = new HashSet<>();

        for (RelationMember m : rel.getMembers()) {
            if (!(m.getMember() instanceof Way w)) continue;
            memberWays.add(w);
            String role = m.getRole();
            if ("outer".equals(role) || role.isEmpty()) {
                outerWays.add(w);
            } else if ("inner".equals(role)) {
                innerWays.add(w);
            }
        }

        if (outerWays.isEmpty()) return null;

        // For now, handle single closed outer way only
        if (outerWays.size() != 1 || !outerWays.get(0).isClosed()) return null;

        return analyzeClosedWay(outerWays.get(0), rel, ds, innerWays);
    }

    /**
     * Core analysis: finds roads crossing the polygon, buffers them,
     * computes the difference, and builds a BreakPlan.
     *
     * @param polygonWay the closed outer way defining the polygon boundary
     * @param source     the original primitive (Way or Relation) for the plan
     * @param ds         the dataset to search for roads
     * @param innerWays  inner ways (for multipolygons), or null
     */
    private static BreakPlan analyzeClosedWay(Way polygonWay, OsmPrimitive source,
                                               DataSet ds, List<Way> innerWays) {
        // 1. Convert polygon to JTS
        Polygon jtsPolygon = wayToJtsPolygon(polygonWay);
        if (jtsPolygon == null) return null;

        // 2. Find and chain crossing roads
        Set<Way> excludeWays = new HashSet<>();
        if (source instanceof Relation rel) {
            for (RelationMember m : rel.getMembers()) {
                if (m.getMember() instanceof Way w) excludeWays.add(w);
            }
        } else {
            excludeWays.add(polygonWay);
        }

        List<RoadChain> roadChains = findAndChainRoads(polygonWay, ds, excludeWays);
        if (roadChains.isEmpty()) return null;

        // 3. Get road widths from preferences
        Map<String, Double> roadWidths = MultipolyGonePreferences.getRoadWidths();

        // 4. Buffer each road chain and build corridors
        List<Geometry> corridorGeometries = new ArrayList<>();
        List<BreakPlan.RoadCorridor> corridors = new ArrayList<>();

        for (RoadChain chain : roadChains) {
            double width = getChainWidth(chain, roadWidths);
            double halfWidth = metersToProjectionUnits(width / 2.0, polygonWay);

            org.locationtech.jts.geom.LineString jtsLine = chainToJtsLineString(chain);
            if (jtsLine == null) continue;

            // Buffer with flat endcaps
            BufferParameters params = new BufferParameters();
            params.setEndCapStyle(BufferParameters.CAP_FLAT);
            params.setQuadrantSegments(8);
            Geometry corridor = BufferOp.bufferOp(jtsLine, halfWidth, params);

            if (!corridor.isEmpty()) {
                corridorGeometries.add(corridor);
                corridors.add(new BreakPlan.RoadCorridor(chain.sourceWays, width));
            }
        }

        if (corridorGeometries.isEmpty()) return null;

        // 5. Union all corridors
        Geometry corridorUnion;
        if (corridorGeometries.size() == 1) {
            corridorUnion = corridorGeometries.get(0);
        } else {
            corridorUnion = CascadedPolygonUnion.union(corridorGeometries);
        }

        // 6. Compute difference: polygon minus corridors
        Geometry difference;
        try {
            difference = jtsPolygon.difference(corridorUnion);
        } catch (Exception e) {
            Logging.warn("Multipoly-Gone: JTS difference failed: " + e.getMessage());
            return null;
        }

        if (difference.isEmpty()) return null;

        // 7. Extract result polygons
        List<Polygon> resultJtsPolygons = extractPolygons(difference);
        if (resultJtsPolygons.size() < 2) return null; // no actual split

        // 8. Convert to BreakPlan with node reuse
        List<Node> originalNodes = polygonWay.getNodes();
        List<BreakPlan.ResultPolygon> resultPolygons = new ArrayList<>();

        for (Polygon resultPoly : resultJtsPolygons) {
            Coordinate[] coords = resultPoly.getExteriorRing().getCoordinates();
            List<EastNorth> points = new ArrayList<>();
            List<Node> reusedNodes = new ArrayList<>();

            for (Coordinate c : coords) {
                EastNorth en = new EastNorth(c.x, c.y);
                points.add(en);
                reusedNodes.add(findMatchingNode(en, originalNodes));
            }

            resultPolygons.add(new BreakPlan.ResultPolygon(points, reusedNodes));
        }

        // 9. Validate
        List<org.locationtech.jts.geom.LineString> roadLineStrings = new ArrayList<>();
        for (RoadChain chain : roadChains) {
            org.locationtech.jts.geom.LineString ls = chainToJtsLineString(chain);
            if (ls != null) roadLineStrings.add(ls);
        }

        if (!validate(resultJtsPolygons, roadLineStrings, jtsPolygon, corridorUnion)) {
            Logging.info("Multipoly-Gone: break validation failed");
            return null;
        }

        // 10. Build description
        String desc = tr("{0} road(s) split polygon into {1} pieces",
            corridors.size(), resultPolygons.size());

        return new BreakPlan(source, corridors, resultPolygons,
            innerWays != null ? innerWays : Collections.emptyList(), desc);
    }

    // -----------------------------------------------------------------------
    // Road discovery and chaining
    // -----------------------------------------------------------------------

    /** A chain of connected road ways forming a logical road. */
    private static class RoadChain {
        final List<Way> sourceWays;
        final List<Node> nodes; // ordered nodes of the full chain

        RoadChain(List<Way> sourceWays, List<Node> nodes) {
            this.sourceWays = sourceWays;
            this.nodes = nodes;
        }
    }

    /**
     * Finds highway ways that cross or lie inside the polygon and chains
     * connected ways into logical road polylines.
     */
    private static List<RoadChain> findAndChainRoads(Way polygonWay, DataSet ds,
                                                      Set<Way> excludeWays) {
        BBox bbox = polygonWay.getBBox();
        Polygon jtsPolygon = wayToJtsPolygon(polygonWay);

        // Find candidate highway ways by bbox
        List<Way> candidates = new ArrayList<>();
        for (Way w : ds.searchWays(bbox)) {
            if (w.isDeleted() || w.getNodesCount() < 2) continue;
            if (!w.hasKey("highway")) continue;
            if (excludeWays.contains(w)) continue;
            // Don't skip closed highway ways — internal ring roads need to be
            // included as dividers (they create cutouts inside the polygon)

            // Check if any segment actually intersects or is inside the polygon
            if (wayIntersectsOrInsidePolygon(w, jtsPolygon)) {
                candidates.add(w);
            }
        }

        if (candidates.isEmpty()) return Collections.emptyList();

        // Chain connected ways by shared endpoints
        return chainWays(candidates);
    }

    /**
     * Chains ways sharing endpoints into logical road polylines.
     * Only chains through degree-2 junctions (exactly 2 ways share an endpoint).
     * Junctions with 3+ ways (T-junctions, crossroads) break the chain.
     */
    private static List<RoadChain> chainWays(List<Way> ways) {
        if (ways.size() == 1) {
            Way w = ways.get(0);
            return List.of(new RoadChain(List.of(w), w.getNodes()));
        }

        // Build adjacency: node → list of ways that have that node as an endpoint
        Map<Node, List<Way>> endpointIndex = new HashMap<>();
        for (Way w : ways) {
            Node first = w.firstNode();
            Node last = w.lastNode();
            endpointIndex.computeIfAbsent(first, k -> new ArrayList<>()).add(w);
            if (first != last) {
                endpointIndex.computeIfAbsent(last, k -> new ArrayList<>()).add(w);
            }
        }

        // Find which nodes are simple continuations (exactly 2 ways meet)
        // vs junctions (3+ ways meet)
        Set<Node> junctionNodes = new HashSet<>();
        for (Map.Entry<Node, List<Way>> entry : endpointIndex.entrySet()) {
            if (entry.getValue().size() > 2) {
                junctionNodes.add(entry.getKey());
            }
        }

        // Trace chains: only follow through degree-2 connections
        Set<Way> visited = new HashSet<>();
        List<RoadChain> chains = new ArrayList<>();

        for (Way startWay : ways) {
            if (visited.contains(startWay)) continue;

            // Walk forward from startWay, chaining through degree-2 junctions only
            LinkedList<Way> chainWays = new LinkedList<>();
            chainWays.add(startWay);
            visited.add(startWay);

            // Extend forward from last node
            extendChain(chainWays, true, endpointIndex, junctionNodes, visited);
            // Extend backward from first node
            extendChain(chainWays, false, endpointIndex, junctionNodes, visited);

            // Build ordered node list from chain
            List<Node> orderedNodes = buildOrderedNodes(chainWays);
            if (orderedNodes.size() >= 2) {
                chains.add(new RoadChain(new ArrayList<>(chainWays), orderedNodes));
            }
        }

        return chains;
    }

    /**
     * Extends a chain of ways forward or backward through degree-2 junctions.
     */
    private static void extendChain(LinkedList<Way> chain, boolean forward,
                                     Map<Node, List<Way>> endpointIndex,
                                     Set<Node> junctionNodes, Set<Way> visited) {
        while (true) {
            Way tip = forward ? chain.getLast() : chain.getFirst();
            Node endpoint = forward ? tip.lastNode() : tip.firstNode();

            // Stop at junctions
            if (junctionNodes.contains(endpoint)) break;

            // Find the unvisited way at this endpoint
            List<Way> connected = endpointIndex.getOrDefault(endpoint, Collections.emptyList());
            Way next = null;
            for (Way w : connected) {
                if (!visited.contains(w)) {
                    next = w;
                    break;
                }
            }
            if (next == null) break;

            visited.add(next);
            if (forward) {
                chain.addLast(next);
            } else {
                chain.addFirst(next);
            }
        }
    }

    /**
     * Builds an ordered node list from a chain of ways, handling direction flips.
     */
    private static List<Node> buildOrderedNodes(List<Way> chain) {
        if (chain.size() == 1) {
            return new ArrayList<>(chain.get(0).getNodes());
        }

        List<Node> result = new ArrayList<>();

        for (int i = 0; i < chain.size(); i++) {
            Way w = chain.get(i);
            List<Node> wayNodes = w.getNodes();

            if (i == 0) {
                // Determine direction from connection to next way
                Way next = chain.get(1);
                Node sharedNode = findSharedEndpoint(w, next);
                if (sharedNode != null && sharedNode.equals(w.firstNode())) {
                    // Need to reverse this way
                    List<Node> reversed = new ArrayList<>(wayNodes);
                    Collections.reverse(reversed);
                    wayNodes = reversed;
                }
                result.addAll(wayNodes);
            } else {
                // Connect to previous: shared node should match result's last node
                Node lastResult = result.get(result.size() - 1);
                if (w.firstNode().equals(lastResult)) {
                    // Same direction: skip first node (it's the shared one)
                    for (int j = 1; j < wayNodes.size(); j++) {
                        result.add(wayNodes.get(j));
                    }
                } else if (w.lastNode().equals(lastResult)) {
                    // Reversed: walk backward, skip last node
                    for (int j = wayNodes.size() - 2; j >= 0; j--) {
                        result.add(wayNodes.get(j));
                    }
                } else {
                    // No shared node — shouldn't happen in a valid chain
                    result.addAll(wayNodes);
                }
            }
        }

        return result;
    }

    /** Finds the shared endpoint node between two ways, or null. */
    private static Node findSharedEndpoint(Way a, Way b) {
        if (a.firstNode().equals(b.firstNode()) || a.firstNode().equals(b.lastNode()))
            return a.firstNode();
        if (a.lastNode().equals(b.firstNode()) || a.lastNode().equals(b.lastNode()))
            return a.lastNode();
        return null;
    }

    // -----------------------------------------------------------------------
    // JTS conversion
    // -----------------------------------------------------------------------

    /** Converts a closed OSM Way to a JTS Polygon. */
    private static Polygon wayToJtsPolygon(Way way) {
        List<Node> nodes = way.getNodes();
        if (nodes.size() < 4) return null;

        Coordinate[] coords = new Coordinate[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            EastNorth en = nodes.get(i).getEastNorth();
            coords[i] = new Coordinate(en.east(), en.north());
        }
        // Ensure closure
        if (!coords[0].equals2D(coords[coords.length - 1])) {
            Coordinate[] closed = new Coordinate[coords.length + 1];
            System.arraycopy(coords, 0, closed, 0, coords.length);
            closed[closed.length - 1] = new Coordinate(coords[0].x, coords[0].y);
            coords = closed;
        }

        try {
            LinearRing ring = GF.createLinearRing(coords);
            return GF.createPolygon(ring);
        } catch (Exception e) {
            Logging.warn("Multipoly-Gone: failed to create JTS polygon: " + e.getMessage());
            return null;
        }
    }

    /** Converts a RoadChain to a JTS LineString. */
    private static org.locationtech.jts.geom.LineString chainToJtsLineString(RoadChain chain) {
        if (chain.nodes.size() < 2) return null;

        Coordinate[] coords = new Coordinate[chain.nodes.size()];
        for (int i = 0; i < chain.nodes.size(); i++) {
            EastNorth en = chain.nodes.get(i).getEastNorth();
            coords[i] = new Coordinate(en.east(), en.north());
        }

        try {
            return GF.createLineString(coords);
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Geometry helpers
    // -----------------------------------------------------------------------

    /** Checks if any segment of a way intersects or lies inside the polygon. */
    private static boolean wayIntersectsOrInsidePolygon(Way way, Polygon jtsPolygon) {
        for (int i = 0; i < way.getNodesCount(); i++) {
            EastNorth en = way.getNode(i).getEastNorth();
            Coordinate c = new Coordinate(en.east(), en.north());
            if (jtsPolygon.contains(GF.createPoint(c))) {
                return true;
            }
        }
        // Also check if any segment crosses the polygon boundary
        for (int i = 0; i < way.getNodesCount() - 1; i++) {
            EastNorth en1 = way.getNode(i).getEastNorth();
            EastNorth en2 = way.getNode(i + 1).getEastNorth();
            org.locationtech.jts.geom.LineString seg = GF.createLineString(new Coordinate[]{
                new Coordinate(en1.east(), en1.north()),
                new Coordinate(en2.east(), en2.north())
            });
            if (jtsPolygon.intersects(seg)) {
                return true;
            }
        }
        return false;
    }

    /** Converts meters to projection units, accounting for EPSG:4326 latitude scaling. */
    private static double metersToProjectionUnits(double meters, Way refWay) {
        double metersPerUnit = ProjectionRegistry.getProjection().getMetersPerUnit();
        if (metersPerUnit < 1e-6) return meters; // already in meters

        double units = meters / metersPerUnit;

        // For degree-based projections, apply latitude correction
        if (metersPerUnit > 10000) {
            // Approximate latitude from the way's centroid
            double latSum = 0;
            int count = refWay.getNodesCount() - 1; // exclude closure
            for (int i = 0; i < count; i++) {
                latSum += refWay.getNode(i).getEastNorth().north();
            }
            double avgLat = latSum / count;
            double cosLat = Math.cos(Math.toRadians(avgLat));
            if (cosLat > 0.01) {
                // For degree-based projections, the "units" value is already
                // in degrees. The buffer will apply uniformly in coordinate space,
                // so we use the north-south scale (1 degree ≈ 111km).
                // No additional correction needed since JTS buffer works in
                // coordinate units and the polygon is also in degrees.
            }
        }

        return units;
    }

    /** Determines the buffer width for a road chain based on highway type preferences. */
    private static double getChainWidth(RoadChain chain, Map<String, Double> roadWidths) {
        // Use the widest highway type in the chain
        double maxWidth = 0;
        for (Way w : chain.sourceWays) {
            String highway = w.get("highway");
            if (highway != null && roadWidths.containsKey(highway)) {
                maxWidth = Math.max(maxWidth, roadWidths.get(highway));
            }
        }
        // Default width if highway type not in preferences
        return maxWidth > 0 ? maxWidth : 3.5;
    }

    /** Extracts all Polygon geometries from a JTS Geometry (handles Multi/GeometryCollection). */
    private static List<Polygon> extractPolygons(Geometry geom) {
        List<Polygon> result = new ArrayList<>();
        if (geom instanceof Polygon p) {
            if (!p.isEmpty() && p.getArea() > 0) {
                result.add(p);
            }
        } else if (geom instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                Geometry sub = mp.getGeometryN(i);
                if (sub instanceof Polygon p && !p.isEmpty() && p.getArea() > 0) {
                    result.add(p);
                }
            }
        } else if (geom instanceof org.locationtech.jts.geom.GeometryCollection gc) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                result.addAll(extractPolygons(gc.getGeometryN(i)));
            }
        }
        return result;
    }

    /**
     * Finds a node from the original polygon that matches the given coordinate
     * within a small tolerance, or returns null if no match.
     */
    private static Node findMatchingNode(EastNorth point, List<Node> originalNodes) {
        double tol = 1e-8; // tight tolerance for coordinate matching
        for (Node n : originalNodes) {
            EastNorth en = n.getEastNorth();
            if (Math.abs(en.east() - point.east()) < tol
                && Math.abs(en.north() - point.north()) < tol) {
                return n;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    /**
     * Validates the break result before offering it to the user.
     * Returns false if any invariant is violated.
     */
    private static boolean validate(List<Polygon> resultPolygons,
                                     List<org.locationtech.jts.geom.LineString> roadLines,
                                     Polygon originalPolygon,
                                     Geometry corridorUnion) {
        // 1. No overlap between result polygons
        for (int i = 0; i < resultPolygons.size(); i++) {
            for (int j = i + 1; j < resultPolygons.size(); j++) {
                Geometry intersection = resultPolygons.get(i).intersection(resultPolygons.get(j));
                if (intersection.getArea() > 1e-10) {
                    Logging.warn("Multipoly-Gone: result polygons {0} and {1} overlap (area={2})",
                        i, j, intersection.getArea());
                    return false;
                }
            }
        }

        // 2. No result polygon interior intersects a road centerline
        for (int i = 0; i < resultPolygons.size(); i++) {
            Polygon rp = resultPolygons.get(i);
            for (org.locationtech.jts.geom.LineString road : roadLines) {
                Geometry clipped = road.intersection(rp);
                // Allow touching (boundary) but not crossing through interior
                if (clipped.getLength() > 1e-8 && rp.contains(clipped)) {
                    Logging.warn("Multipoly-Gone: road crosses through result polygon {0}", i);
                    return false;
                }
            }
        }

        return true;
    }
}
