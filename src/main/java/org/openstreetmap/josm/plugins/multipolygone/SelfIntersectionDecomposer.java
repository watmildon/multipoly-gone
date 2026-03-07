package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.tools.Geometry;

/**
 * Detects self-intersecting rings and decomposes them into non-self-intersecting
 * sub-rings. Handles both segment-crossing intersections and vertex-touching
 * (repeated node / bowtie) cases.
 */
class SelfIntersectionDecomposer {

    /**
     * Internal crossing representation for segment-segment intersections.
     */
    static class Crossing {
        final int segmentA;
        final int segmentB;
        final EastNorth point;
        Node node;

        Crossing(int segmentA, int segmentB, EastNorth point) {
            this.segmentA = segmentA;
            this.segmentB = segmentB;
            this.point = point;
        }
    }

    /**
     * Finds all self-intersections in a closed ring's node list.
     * Checks every non-adjacent segment pair for crossings.
     */
    static List<Crossing> findSelfIntersections(List<Node> ringNodes) {
        int segCount = ringNodes.size() - 1;
        List<Crossing> crossings = new ArrayList<>();

        for (int i = 0; i < segCount; i++) {
            for (int j = i + 2; j < segCount; j++) {
                // Skip adjacent wrap-around pair
                if (i == 0 && j == segCount - 1) continue;

                EastNorth p1 = ringNodes.get(i).getEastNorth();
                EastNorth p2 = ringNodes.get(i + 1).getEastNorth();
                EastNorth p3 = ringNodes.get(j).getEastNorth();
                EastNorth p4 = ringNodes.get(j + 1).getEastNorth();

                // Skip if any coordinate is missing
                if (p1 == null || p2 == null || p3 == null || p4 == null) {
                    continue;
                }

                // Skip if segments share any endpoint node
                if (ringNodes.get(i) == ringNodes.get(j) || ringNodes.get(i) == ringNodes.get(j + 1)
                    || ringNodes.get(i + 1) == ringNodes.get(j) || ringNodes.get(i + 1) == ringNodes.get(j + 1)) {
                    continue;
                }

                EastNorth intersection = Geometry.getSegmentSegmentIntersection(p1, p2, p3, p4);
                if (intersection != null) {
                    double tol = 1e-6;
                    if (GeometryUtils.isNear(intersection, p1, tol)
                        || GeometryUtils.isNear(intersection, p2, tol)
                        || GeometryUtils.isNear(intersection, p3, tol)
                        || GeometryUtils.isNear(intersection, p4, tol)) {
                        continue;
                    }
                    crossings.add(new Crossing(i, j, intersection));
                }
            }
        }
        return crossings;
    }

    /**
     * Decomposes a self-intersecting ring into non-self-intersecting sub-rings.
     * Handles both segment-crossing intersections and vertex-touching (repeated node) cases.
     * Returns null if the ring does not self-intersect.
     */
    static DecomposedRing decomposeIfSelfIntersecting(WayChainBuilder.Ring ring) {
        List<Node> ringNodes = ring.getNodes();

        // First check for segment-segment crossings
        List<Crossing> crossings = findSelfIntersections(ringNodes);
        if (!crossings.isEmpty()) {
            List<Node> newNodes = new ArrayList<>();
            for (Crossing c : crossings) {
                LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(c.point);
                c.node = new Node(ll);
                newNodes.add(c.node);
            }

            List<List<Node>> subRingNodeLists = splitAtCrossings(ringNodes, crossings);
            if (subRingNodeLists != null && subRingNodeLists.size() > 1) {
                List<WayChainBuilder.Ring> subRings = new ArrayList<>();
                for (List<Node> subNodes : subRingNodeLists) {
                    subRings.add(new WayChainBuilder.Ring(subNodes, ring.getSourceWays()));
                }
                return new DecomposedRing(ring, subRings, newNodes);
            }
        }

        // Then check for vertex-touching: a node that appears more than once.
        // Only decompose chained rings — already-closed ways with repeated nodes are
        // pre-existing mapping errors that the plugin should not attempt to fix.
        if (!ring.isAlreadyClosed()) {
            DecomposedRing vertexDecomp = decomposeAtRepeatedNodes(ring);
            if (vertexDecomp != null) {
                return vertexDecomp;
            }
        }

        return null;
    }

    /**
     * Decomposes a ring at repeated nodes (vertices that appear more than once).
     * Returns null if no repeated nodes are found.
     */
    static DecomposedRing decomposeAtRepeatedNodes(WayChainBuilder.Ring ring) {
        List<Node> ringNodes = ring.getNodes();
        int ringSize = ringNodes.size() - 1;

        Map<Node, List<Integer>> occurrences = new HashMap<>();
        for (int i = 0; i < ringSize; i++) {
            occurrences.computeIfAbsent(ringNodes.get(i), k -> new ArrayList<>()).add(i);
        }

        List<Node> repeatedNodes = new ArrayList<>();
        for (Map.Entry<Node, List<Integer>> entry : occurrences.entrySet()) {
            if (entry.getValue().size() >= 2) {
                repeatedNodes.add(entry.getKey());
            }
        }

        if (repeatedNodes.isEmpty()) {
            return null;
        }

        Node splitNode = repeatedNodes.get(0);
        List<Integer> positions = occurrences.get(splitNode);
        int p1 = positions.get(0);
        int p2 = positions.get(1);

        List<Node> sub1 = new ArrayList<>();
        for (int i = p1; i <= p2; i++) {
            sub1.add(ringNodes.get(i));
        }

        List<Node> sub2 = new ArrayList<>();
        for (int i = p2; i < ringSize; i++) {
            sub2.add(ringNodes.get(i));
        }
        for (int i = 0; i <= p1; i++) {
            sub2.add(ringNodes.get(i));
        }

        if (sub1.size() < 4 || sub2.size() < 4) {
            return null;
        }

        List<WayChainBuilder.Ring> subRings = new ArrayList<>();
        subRings.add(new WayChainBuilder.Ring(sub1, ring.getSourceWays()));
        subRings.add(new WayChainBuilder.Ring(sub2, ring.getSourceWays()));

        // Recursively decompose sub-rings that may themselves contain repeated nodes
        List<WayChainBuilder.Ring> finalRings = new ArrayList<>();
        for (WayChainBuilder.Ring subRing : subRings) {
            DecomposedRing nested = decomposeAtRepeatedNodes(subRing);
            if (nested != null) {
                finalRings.addAll(nested.getSubRings());
            } else {
                finalRings.add(subRing);
            }
        }

        return new DecomposedRing(ring, finalRings, new ArrayList<>());
    }

    /**
     * Splits a ring at crossing points into multiple non-self-intersecting sub-rings.
     */
    static List<List<Node>> splitAtCrossings(List<Node> ringNodes, List<Crossing> crossings) {
        int segCount = ringNodes.size() - 1;

        @SuppressWarnings("unchecked")
        List<double[]>[] segCrossings = new List[segCount];
        for (int i = 0; i < segCount; i++) {
            segCrossings[i] = new ArrayList<>();
        }

        for (int ci = 0; ci < crossings.size(); ci++) {
            Crossing c = crossings.get(ci);

            EastNorth a1 = ringNodes.get(c.segmentA).getEastNorth();
            EastNorth a2 = ringNodes.get(c.segmentA + 1).getEastNorth();
            double tA = paramT(a1, a2, c.point);
            segCrossings[c.segmentA].add(new double[]{tA, ci});

            EastNorth b1 = ringNodes.get(c.segmentB).getEastNorth();
            EastNorth b2 = ringNodes.get(c.segmentB + 1).getEastNorth();
            double tB = paramT(b1, b2, c.point);
            segCrossings[c.segmentB].add(new double[]{tB, ci});
        }

        for (int i = 0; i < segCount; i++) {
            segCrossings[i].sort((a, b) -> Double.compare(a[0], b[0]));
        }

        // Build augmented node list with crossing nodes inserted
        List<Object> augmented = new ArrayList<>();
        int[][] crossingPositions = new int[crossings.size()][2];
        int[] posCount = new int[crossings.size()];

        for (int seg = 0; seg < segCount; seg++) {
            augmented.add(ringNodes.get(seg));
            for (double[] entry : segCrossings[seg]) {
                int ci = (int) entry[1];
                int pos = augmented.size();
                augmented.add(crossings.get(ci).node);
                crossingPositions[ci][posCount[ci]] = pos;
                posCount[ci]++;
            }
        }

        int augLen = augmented.size();
        Set<Node> crossingNodeSet = new HashSet<>();
        for (Crossing c : crossings) {
            crossingNodeSet.add(c.node);
        }

        Map<Node, int[]> nodePositions = new HashMap<>();
        for (int ci = 0; ci < crossings.size(); ci++) {
            nodePositions.put(crossings.get(ci).node, crossingPositions[ci]);
        }

        // Trace sub-rings
        boolean[] usedEdge = new boolean[augLen];
        List<List<Node>> subRings = new ArrayList<>();

        for (int start = 0; start < augLen; start++) {
            if (usedEdge[start]) continue;

            List<Node> subRing = new ArrayList<>();
            int pos = start;
            boolean valid = true;

            do {
                Node current = (Node) augmented.get(pos);
                subRing.add(current);

                int nextPos = (pos + 1) % augLen;
                if (usedEdge[pos]) {
                    valid = false;
                    break;
                }
                usedEdge[pos] = true;

                Node nextNode = (Node) augmented.get(nextPos);
                if (crossingNodeSet.contains(nextNode)) {
                    int[] positions2 = nodePositions.get(nextNode);
                    if (nextPos == positions2[0]) {
                        pos = positions2[1];
                    } else {
                        pos = positions2[0];
                    }
                } else {
                    pos = nextPos;
                }
            } while (pos != start);

            if (!valid || subRing.size() < 3) continue;

            subRing.add(subRing.get(0));
            subRings.add(subRing);
        }

        return subRings.size() > 1 ? subRings : null;
    }

    private static double paramT(EastNorth from, EastNorth to, EastNorth point) {
        double dx = to.east() - from.east();
        double dy = to.north() - from.north();
        double len2 = dx * dx + dy * dy;
        if (len2 < 1e-20) return 0;
        return ((point.east() - from.east()) * dx + (point.north() - from.north()) * dy) / len2;
    }
}
