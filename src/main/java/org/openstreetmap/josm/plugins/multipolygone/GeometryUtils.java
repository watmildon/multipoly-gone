package org.openstreetmap.josm.plugins.multipolygone;

import java.util.List;

import org.locationtech.jts.algorithm.Area;
import org.locationtech.jts.algorithm.PointLocation;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.operation.valid.IsSimpleOp;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/**
 * Shared geometry primitives used by multiple classes in the plugin.
 * Delegates to JTS where possible.
 */
class GeometryUtils {

    private static final GeometryFactory GF = new GeometryFactory();
    private static final RobustLineIntersector INTERSECTOR = new RobustLineIntersector();

    /**
     * Computes the signed area of a closed polygon.
     * Positive = counterclockwise, negative = clockwise.
     * Delegates to JTS {@link Area#ofRingSigned(CoordinateSequence)}.
     */
    static double computeSignedArea(List<Node> way) {
        Coordinate[] coords = nodesToClosedCoords(way);
        return Area.ofRingSigned(new CoordinateArraySequence(coords));
    }

    /**
     * Checks if a closed polygon has any non-adjacent edge crossings (self-intersection).
     * Delegates to JTS {@link IsSimpleOp}.
     */
    static boolean hasNonAdjacentEdgeCrossing(List<Node> way) {
        Coordinate[] coords = nodesToClosedCoords(way);
        LinearRing ring = GF.createLinearRing(coords);
        return !new IsSimpleOp(ring).isSimple();
    }

    /**
     * Returns true if segments (a1-a2) and (b1-b2) properly cross each other.
     * Delegates to JTS {@link RobustLineIntersector}.
     */
    static boolean segmentsCross(EastNorth a1, EastNorth a2, EastNorth b1, EastNorth b2) {
        INTERSECTOR.computeIntersection(
            toCoord(a1), toCoord(a2),
            toCoord(b1), toCoord(b2));
        return INTERSECTOR.isProper();
    }

    /**
     * Returns true if two EastNorth points are within tolerance of each other.
     */
    static boolean isNear(EastNorth a, EastNorth b, double tol) {
        return Math.abs(a.east() - b.east()) < tol && Math.abs(a.north() - b.north()) < tol;
    }

    /**
     * Point-in-polygon test. Handles closed polygons
     * (where first == last point) by trimming the duplicate endpoint.
     * Delegates to JTS {@link PointLocation#isInRing(Coordinate, Coordinate[])}.
     */
    static boolean pointInsideOrOnPolygon(EastNorth point, List<EastNorth> polygon) {
        int n = polygon.size();
        if (n < 3) return false;

        // Build coordinate array, ensuring closure
        int last = n;
        if (isNear(polygon.get(0), polygon.get(n - 1), 1e-6)) {
            last = n - 1;
        }

        Coordinate[] coords = new Coordinate[last + 1];
        for (int i = 0; i < last; i++) {
            coords[i] = toCoord(polygon.get(i));
        }
        coords[last] = coords[0]; // close the ring

        return PointLocation.isInRing(toCoord(point), coords);
    }

    /**
     * Computes two points offset perpendicular to a line direction at a given point.
     *
     * @param lineStart start of the line segment defining direction
     * @param lineEnd   end of the line segment defining direction
     * @param point     the point to offset from
     * @param offsetMeters offset distance in meters (positive)
     * @return array of two EastNorth: [left, right] where left is to the left
     *         of the direction from lineStart to lineEnd
     */
    static EastNorth[] perpendicularOffsets(EastNorth lineStart, EastNorth lineEnd,
            EastNorth point, double offsetMeters) {
        double dx = lineEnd.east() - lineStart.east();
        double dy = lineEnd.north() - lineStart.north();

        // Convert meters to EastNorth units using the projection's scale
        double metersPerUnit = ProjectionRegistry.getProjection().getMetersPerUnit();

        // For degree-based projections (EPSG:4326), east and north have
        // different metric scales: 1° longitude = cos(lat) × 1° latitude.
        // Compute the perpendicular in metric space to get correct offsets.
        double eastScale = 1.0;
        if (metersPerUnit > 10000) {
            double cosLat = Math.cos(Math.toRadians(point.north()));
            if (cosLat > 0.01) {
                eastScale = cosLat;
            }
        }

        // Scale dx to metric units for proper perpendicular computation
        double dxMetric = dx * eastScale;
        double dyMetric = dy;
        double lenMetric = Math.sqrt(dxMetric * dxMetric + dyMetric * dyMetric);
        if (lenMetric < 1e-10) {
            return new EastNorth[]{point, point};
        }

        // Unit perpendicular in metric space: rotate direction 90° left
        double pxMetric = -dyMetric / lenMetric;
        double pyMetric = dxMetric / lenMetric;

        // Convert offset from meters to EastNorth units
        double offsetUnits = offsetMeters / metersPerUnit;

        // Apply perpendicular offset, converting back from metric to EastNorth
        double offsetEast = pxMetric * offsetUnits / eastScale;
        double offsetNorth = pyMetric * offsetUnits;

        return new EastNorth[]{
            new EastNorth(point.east() + offsetEast, point.north() + offsetNorth),
            new EastNorth(point.east() - offsetEast, point.north() - offsetNorth)
        };
    }

    // -- Conversion helpers --

    /** Converts an EastNorth to a JTS Coordinate. */
    static Coordinate toCoord(EastNorth en) {
        return new Coordinate(en.east(), en.north());
    }

    /**
     * Converts a list of Nodes to a closed JTS Coordinate array.
     * If the input is already closed (first == last node), uses it as-is.
     * Otherwise appends the first coordinate to close the ring.
     */
    static Coordinate[] nodesToClosedCoords(List<Node> nodes) {
        int n = nodes.size();
        boolean alreadyClosed = n >= 2 && nodes.get(0) == nodes.get(n - 1);
        Coordinate[] coords;
        if (alreadyClosed) {
            coords = new Coordinate[n];
            for (int i = 0; i < n; i++) {
                EastNorth en = nodes.get(i).getEastNorth();
                coords[i] = new Coordinate(en.east(), en.north());
            }
        } else {
            coords = new Coordinate[n + 1];
            for (int i = 0; i < n; i++) {
                EastNorth en = nodes.get(i).getEastNorth();
                coords[i] = new Coordinate(en.east(), en.north());
            }
            coords[n] = new Coordinate(coords[0].x, coords[0].y);
        }
        return coords;
    }

    /**
     * Converts a list of Nodes to a JTS Polygon (exterior ring only, no holes).
     */
    static org.locationtech.jts.geom.Polygon nodesToJtsPolygon(List<Node> nodes) {
        return GF.createPolygon(GF.createLinearRing(nodesToClosedCoords(nodes)));
    }
}
