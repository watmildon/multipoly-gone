package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.CascadedPolygonUnion;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Logging;

/**
 * Analyzes an area feature (closed way or multipolygon) and detects where its
 * boundary shares nodes with centerline features (roads, waterways, railways, etc.).
 * Produces an {@link UngluePlan} that clips the area boundary away from the
 * centerline using JTS buffer/difference operations.
 */
class PolygonUngluer {

    /** Tag keys whose ways are considered "centerline" linear features. */
    private static final Set<String> CENTERLINE_KEYS = Set.of(
        "highway", "waterway", "railway"
    );

    /** Tolerance for matching JTS result coordinates back to original nodes. */
    private static final double NODE_MATCH_TOLERANCE = 1e-8;

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
     * Core analysis: finds centerline ways sharing nodes with the area boundary,
     * builds corridor buffers, and computes the clipped result geometry.
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

        // Stage 1: Centerline detection
        // For each boundary node, find which centerline ways it belongs to
        List<Set<Way>> nodeCenterlines = new ArrayList<>(ringSize);
        for (int i = 0; i < ringSize; i++) {
            Node node = boundaryNodes.get(i);
            Set<Way> centerlines = new LinkedHashSet<>();
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (!(referrer instanceof Way w)) continue;
                if (areaWays.contains(w)) continue;
                if (w.isDeleted() || w.isIncomplete()) continue;
                if (isCenterlineWay(w, tagWidths)) {
                    centerlines.add(w);
                }
            }
            nodeCenterlines.add(centerlines);
        }

        // Stage 2: Only keep centerlines that share an edge (2+ consecutive
        // boundary nodes). A road that merely touches the area at a single
        // node (e.g., a T-junction) should NOT be buffered away.
        Set<Way> edgeCenterlines = findEdgeSharingCenterlines(
            boundaryNodes, ringSize, nodeCenterlines);

        if (edgeCenterlines.isEmpty()) return null;

        List<UngluePlan.CenterlineCorridor> corridors = new ArrayList<>();
        for (Way w : edgeCenterlines) {
            corridors.add(new UngluePlan.CenterlineCorridor(
                w, getWayWidth(w, tagWidths)));
        }

        // Stage 3: JTS buffer + difference
        List<EastNorth> resultGeometry = new ArrayList<>();
        List<Node> resultReusedNodes = new ArrayList<>();

        if (!computeBufferedResult(areaWay, corridors, resultGeometry, resultReusedNodes)) {
            return null;
        }

        String desc = tr("{0} centerline(s) — clipped by half-width corridor buffer",
            corridors.size());

        return new UngluePlan(source, corridors, resultGeometry, resultReusedNodes, desc);
    }

    // -----------------------------------------------------------------------
    // Glued segment detection
    // -----------------------------------------------------------------------

    /**
     * Returns the set of centerline ways that share at least one edge
     * (2+ consecutive boundary nodes) with the area. Centerlines that only
     * touch at a single node (e.g., a T-junction road) are excluded.
     */
    private static Set<Way> findEdgeSharingCenterlines(
            List<Node> boundaryNodes, int ringSize,
            List<Set<Way>> nodeCenterlines) {
        Set<Way> result = new LinkedHashSet<>();
        for (int i = 0; i < ringSize; i++) {
            int next = (i + 1) % ringSize;
            // Find centerlines present at both consecutive boundary nodes
            for (Way w : nodeCenterlines.get(i)) {
                if (nodeCenterlines.get(next).contains(w)) {
                    result.add(w);
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // JTS buffer + difference
    // -----------------------------------------------------------------------

    /**
     * Computes the clipped area geometry using JTS buffer/difference.
     * For each centerline corridor, builds a half-width buffer and subtracts it
     * from the area polygon.
     *
     * @return true if a valid result was produced, false otherwise
     */
    private static boolean computeBufferedResult(Way areaWay,
            List<UngluePlan.CenterlineCorridor> corridors,
            List<EastNorth> resultGeometry, List<Node> resultReusedNodes) {

        Polygon jtsPoly = GeometryUtils.wayToJtsPolygon(areaWay);
        if (jtsPoly == null) return false;

        // Compute centroid latitude for projection conversion
        Coordinate centroid = jtsPoly.getCentroid().getCoordinate();
        // In EPSG:4326, north = lat. In metric projections, use a rough conversion.
        double centroidLat = centroid.y;
        double metersPerUnit = org.openstreetmap.josm.data.projection.ProjectionRegistry
            .getProjection().getMetersPerUnit();
        if (metersPerUnit < 10) {
            // Metric projection — centroid.y is in meters, convert to degrees roughly
            centroidLat = 0; // use equator as fallback (metersToProjectionUnits handles cos(lat))
        }

        // Build corridor buffers
        List<Geometry> buffers = new ArrayList<>();
        for (UngluePlan.CenterlineCorridor corridor : corridors) {
            LineString jtsLine = GeometryUtils.wayToJtsLineString(corridor.getCenterlineWay());
            if (jtsLine == null) continue;

            double halfWidth = PolygonBreaker.metersToProjectionUnits(
                corridor.getWidthMeters() / 2.0, centroidLat);

            BufferParameters params = new BufferParameters();
            params.setEndCapStyle(BufferParameters.CAP_FLAT);
            params.setQuadrantSegments(8);

            Geometry buffer = BufferOp.bufferOp(jtsLine, halfWidth, params);
            if (buffer != null && !buffer.isEmpty()) {
                buffers.add(buffer);
            }
        }

        if (buffers.isEmpty()) return false;

        // Union all corridor buffers
        Geometry corridorUnion;
        if (buffers.size() == 1) {
            corridorUnion = buffers.get(0);
        } else {
            corridorUnion = CascadedPolygonUnion.union(buffers);
        }

        // Compute difference
        Geometry result;
        try {
            result = jtsPoly.difference(corridorUnion);
        } catch (Exception e) {
            Logging.warn("Multipoly-Gone: JTS difference failed: " + e.getMessage());
            // Try cleaning with buffer(0)
            try {
                result = jtsPoly.buffer(0).difference(corridorUnion.buffer(0));
            } catch (Exception e2) {
                Logging.warn("Multipoly-Gone: JTS difference failed after cleanup: " + e2.getMessage());
                return false;
            }
        }

        if (result == null || result.isEmpty()) return false;

        // Extract the largest polygon from the result
        Polygon resultPoly = extractLargestPolygon(result);
        if (resultPoly == null) return false;

        // Extract coordinates and match back to original nodes
        Coordinate[] coords = resultPoly.getExteriorRing().getCoordinates();
        if (coords.length < 4) return false;

        // Build a lookup of original boundary nodes by position
        List<Node> origNodes = areaWay.getNodes();

        for (Coordinate coord : coords) {
            EastNorth en = new EastNorth(coord.x, coord.y);
            Node matched = findMatchingOriginalNode(en, origNodes);
            resultGeometry.add(en);
            resultReusedNodes.add(matched);
        }

        // Ensure closure
        if (!resultGeometry.isEmpty()) {
            EastNorth first = resultGeometry.get(0);
            EastNorth last = resultGeometry.get(resultGeometry.size() - 1);
            if (!GeometryUtils.isNear(first, last, 1e-10)) {
                resultGeometry.add(first);
                resultReusedNodes.add(resultReusedNodes.get(0));
            }
        }

        return resultGeometry.size() >= 4;
    }

    /**
     * Extracts the largest polygon (by area) from a JTS Geometry result.
     */
    private static Polygon extractLargestPolygon(Geometry geom) {
        if (geom instanceof Polygon) {
            return (Polygon) geom;
        }

        Polygon largest = null;
        double largestArea = 0;
        for (int i = 0; i < geom.getNumGeometries(); i++) {
            Geometry sub = geom.getGeometryN(i);
            if (sub instanceof Polygon poly) {
                double area = poly.getArea();
                if (area > largestArea) {
                    largestArea = area;
                    largest = poly;
                }
            }
        }
        return largest;
    }

    /**
     * Finds an original boundary node whose position matches the given EastNorth
     * within a tight tolerance.
     */
    private static Node findMatchingOriginalNode(EastNorth target, List<Node> origNodes) {
        for (Node node : origNodes) {
            EastNorth en = node.getEastNorth();
            if (en != null && GeometryUtils.isNear(target, en, NODE_MATCH_TOLERANCE)) {
                return node;
            }
        }
        return null;
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
}
