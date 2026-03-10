package org.openstreetmap.josm.plugins.multipolygone;

import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/**
 * Shared geometry primitives used by multiple classes in the plugin.
 */
class GeometryUtils {

    /**
     * Computes the signed area of a closed polygon using the shoelace formula.
     * Positive = counterclockwise, negative = clockwise.
     */
    static double computeSignedArea(List<Node> way) {
        double area = 0;
        int n = way.size() - 1;
        for (int i = 0; i < n; i++) {
            EastNorth curr = way.get(i).getEastNorth();
            EastNorth next = way.get((i + 1) % n).getEastNorth();
            area += curr.east() * next.north() - next.east() * curr.north();
        }
        return area / 2.0;
    }

    /**
     * Checks if a closed polygon has any non-adjacent edge crossings (self-intersection).
     */
    static boolean hasNonAdjacentEdgeCrossing(List<Node> way) {
        int n = way.size() - 1;
        for (int i = 0; i < n; i++) {
            EastNorth a1 = way.get(i).getEastNorth();
            EastNorth a2 = way.get(i + 1).getEastNorth();
            for (int j = i + 2; j < n; j++) {
                if (j == n - 1 && i == 0) continue;
                EastNorth b1 = way.get(j).getEastNorth();
                EastNorth b2 = way.get(j + 1).getEastNorth();
                if (segmentsCross(a1, a2, b1, b2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if segments (a1-a2) and (b1-b2) properly cross each other.
     */
    static boolean segmentsCross(EastNorth a1, EastNorth a2, EastNorth b1, EastNorth b2) {
        double d1 = cross(a1, a2, b1);
        double d2 = cross(a1, a2, b2);
        double d3 = cross(b1, b2, a1);
        double d4 = cross(b1, b2, a2);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
            && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return false;
    }

    /** Cross product of vectors (p1->p2) and (p1->p3). */
    static double cross(EastNorth p1, EastNorth p2, EastNorth p3) {
        return (p2.east() - p1.east()) * (p3.north() - p1.north())
             - (p2.north() - p1.north()) * (p3.east() - p1.east());
    }

    /**
     * Returns true if two EastNorth points are within tolerance of each other.
     */
    static boolean isNear(EastNorth a, EastNorth b, double tol) {
        return Math.abs(a.east() - b.east()) < tol && Math.abs(a.north() - b.north()) < tol;
    }

    /**
     * Point-in-polygon test using ray casting. Handles closed polygons
     * (where first == last point) by trimming the duplicate endpoint.
     */
    static boolean pointInsideOrOnPolygon(EastNorth point, List<EastNorth> polygon) {
        int n = polygon.size();
        if (n < 3) return false;

        int last = n - 1;
        if (isNear(polygon.get(0), polygon.get(last), 1e-6)) {
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
}
