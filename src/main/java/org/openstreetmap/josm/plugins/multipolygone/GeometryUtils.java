package org.openstreetmap.josm.plugins.multipolygone;

import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;

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
}
