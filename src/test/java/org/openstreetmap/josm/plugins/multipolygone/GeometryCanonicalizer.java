package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Shared utilities for canonicalizing way geometry for comparison.
 * Coordinate sequences are normalized so that rotation and direction
 * don't affect equality — two ways tracing the same ring in different
 * directions or starting at different vertices produce the same canonical form.
 */
class GeometryCanonicalizer {

    /** Tolerance for coordinate comparison (~1 meter at mid-latitudes). */
    static final double COORD_TOL = 1e-5;

    /** A coordinate pair with tolerance-based equality. */
    static class Coord {
        final double lat;
        final double lon;

        Coord(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        boolean matches(Coord other) {
            return Math.abs(lat - other.lat) < COORD_TOL
                && Math.abs(lon - other.lon) < COORD_TOL;
        }

        @Override
        public String toString() {
            return String.format("(%.7f, %.7f)", lat, lon);
        }
    }

    /**
     * Extracts the coordinate sequence from a way, excluding the
     * closing duplicate node (if the way is closed).
     */
    static List<Coord> extractCoords(Way way) {
        List<Node> nodes = way.getNodes();
        if (nodes.isEmpty()) return List.of();

        int size = nodes.size();
        // Remove closing duplicate
        if (size > 1 && nodes.get(0).getId() == nodes.get(size - 1).getId()) {
            size--;
        } else if (size > 1) {
            // Check by coordinate proximity (for new nodes with different IDs)
            Node first = nodes.get(0);
            Node last = nodes.get(size - 1);
            if (Math.abs(first.lat() - last.lat()) < COORD_TOL
                && Math.abs(first.lon() - last.lon()) < COORD_TOL) {
                size--;
            }
        }

        List<Coord> coords = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            coords.add(new Coord(nodes.get(i).lat(), nodes.get(i).lon()));
        }
        return coords;
    }

    /**
     * Normalizes a coordinate sequence to canonical form:
     * 1. Find the index of the coordinate with the smallest lat (then lon as tiebreaker)
     * 2. Try both directions (forward and reverse from that index)
     * 3. Pick the lexicographically smaller direction
     */
    static List<Coord> canonicalize(List<Coord> coords) {
        if (coords.isEmpty()) return coords;
        int size = coords.size();

        // Find the start index: smallest lat, then smallest lon as tiebreaker
        int minIdx = 0;
        for (int i = 1; i < size; i++) {
            Coord c = coords.get(i);
            Coord m = coords.get(minIdx);
            if (c.lat < m.lat - COORD_TOL
                || (Math.abs(c.lat - m.lat) < COORD_TOL && c.lon < m.lon - COORD_TOL)) {
                minIdx = i;
            }
        }

        // Build forward and reverse from minIdx
        List<Coord> forward = new ArrayList<>();
        List<Coord> reverse = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            forward.add(coords.get((minIdx + i) % size));
            reverse.add(coords.get((minIdx - i + size) % size));
        }

        // Pick canonical direction by lexicographic comparison
        int cmp = compareCoordLists(forward, reverse);
        return cmp <= 0 ? forward : reverse;
    }

    /**
     * Lexicographically compares two coordinate lists.
     * Compares lat first, then lon, with tolerance.
     */
    static int compareCoordLists(List<Coord> a, List<Coord> b) {
        int size = Math.min(a.size(), b.size());
        for (int i = 0; i < size; i++) {
            Coord ca = a.get(i);
            Coord cb = b.get(i);
            if (Math.abs(ca.lat - cb.lat) >= COORD_TOL) {
                return Double.compare(ca.lat, cb.lat);
            }
            if (Math.abs(ca.lon - cb.lon) >= COORD_TOL) {
                return Double.compare(ca.lon, cb.lon);
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /**
     * Converts a canonical coordinate list to a stable string key
     * for comparison and sorting.
     */
    static String coordListKey(List<Coord> coords) {
        StringBuilder sb = new StringBuilder();
        for (Coord c : coords) {
            if (sb.length() > 0) sb.append("|");
            sb.append(String.format("%.6f,%.6f", c.lat, c.lon));
        }
        return sb.toString();
    }

    /**
     * Checks if two canonical coordinate lists match within tolerance.
     */
    static boolean coordListsMatch(List<Coord> a, List<Coord> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).matches(b.get(i))) return false;
        }
        return true;
    }

    /**
     * Convenience: extract, canonicalize, and key a way in one call.
     */
    static String wayGeometryKey(Way way) {
        return coordListKey(canonicalize(extractCoords(way)));
    }
}
