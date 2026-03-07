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
 * Merges an outer ring with a touching inner ring into one or more closed ways.
 * Handles single-shared-node (figure-8), multi-shared-node, and cross-segment
 * intersection cases.
 */
class TouchingInnerMerger {

    /**
     * Result of merging an outer ring with a touching inner ring.
     */
    static class MergeResult {
        final List<List<Node>> mergedWays;
        final List<Node> newNodes;

        MergeResult(List<List<Node>> mergedWays, List<Node> newNodes) {
            this.mergedWays = mergedWays;
            this.newNodes = newNodes;
        }
    }

    /**
     * Try to merge a single outer ring with a single inner that share nodes.
     * Returns the merged way node lists and any new intersection nodes, or null if they don't share enough nodes.
     */
    static MergeResult tryTouchingInnerMerge(WayChainBuilder.Ring outerRing, List<Node> innerNodes) {
        List<Node> outerNodes = outerRing.getNodes();

        Set<Node> innerNodeSet = new HashSet<>();
        int innerSize = innerNodes.size() - 1;
        for (int i = 0; i < innerSize; i++) {
            innerNodeSet.add(innerNodes.get(i));
        }

        Set<Node> sharedSet = new HashSet<>();
        int outerSize = outerNodes.size() - 1;
        for (int i = 0; i < outerSize; i++) {
            if (innerNodeSet.contains(outerNodes.get(i))) {
                sharedSet.add(outerNodes.get(i));
            }
        }

        if (sharedSet.isEmpty()) {
            return null;
        }

        if (sharedSet.size() == 1) {
            List<List<Node>> ways = mergeSingleSharedNode(outerNodes, outerSize, innerNodes, innerSize,
                sharedSet.iterator().next());
            return ways != null ? new MergeResult(ways, null) : null;
        }

        return mergeOuterInner(outerNodes, innerNodes, sharedSet);
    }

    /**
     * Merges an outer ring and an inner ring sharing exactly 1 node into a single
     * closed way that passes through the shared node twice (figure-8 style).
     * Result: shared → outer[1..n] → shared → inner[1..m] → shared
     */
    private static List<List<Node>> mergeSingleSharedNode(
            List<Node> outerNodes, int outerSize,
            List<Node> innerNodes, int innerSize, Node shared) {
        // Find shared node index in outer
        int outerIdx = -1;
        for (int i = 0; i < outerSize; i++) {
            if (outerNodes.get(i).equals(shared)) {
                outerIdx = i;
                break;
            }
        }
        // Find shared node index in inner
        int innerIdx = -1;
        for (int i = 0; i < innerSize; i++) {
            if (innerNodes.get(i).equals(shared)) {
                innerIdx = i;
                break;
            }
        }
        if (outerIdx == -1 || innerIdx == -1) {
            return null;
        }

        // Build: shared → outer[1..n] → shared → inner[1..m] → shared
        List<Node> way = new ArrayList<>();
        way.add(shared);
        for (int step = 1; step < outerSize; step++) {
            way.add(outerNodes.get((outerIdx + step) % outerSize));
        }
        way.add(shared);
        for (int step = 1; step < innerSize; step++) {
            way.add(innerNodes.get((innerIdx + step) % innerSize));
        }
        way.add(shared);

        return List.of(way);
    }

    /**
     * Merges an outer ring and a touching inner ring into one or more closed ways.
     * Driven by the inner ring's topology: each inner-only segment (bounded by shared
     * nodes) is paired with the corresponding outer path between the same boundary
     * nodes to form a closed way.
     */
    static MergeResult mergeOuterInner(List<Node> outerNodes, List<Node> innerNodes, Set<Node> sharedSet) {
        int innerSize = innerNodes.size() - 1;
        int outerSize = outerNodes.size() - 1;

        Map<Node, Integer> outerIndexMap = new HashMap<>();
        for (int i = 0; i < outerSize; i++) {
            outerIndexMap.put(outerNodes.get(i), i);
        }

        // Walk the inner ring to find inner-only segments bounded by shared nodes.
        int startIdx = -1;
        for (int i = 0; i < innerSize; i++) {
            boolean currShared = sharedSet.contains(innerNodes.get(i));
            boolean prevShared = sharedSet.contains(innerNodes.get((i - 1 + innerSize) % innerSize));
            if (currShared && !prevShared) {
                startIdx = i;
                break;
            }
        }
        if (startIdx == -1) {
            return null;
        }

        List<List<Node>> innerOnlySegments = new ArrayList<>();
        List<Node> segStartBoundary = new ArrayList<>();
        List<Node> segEndBoundary = new ArrayList<>();

        List<Node> currentSegment = null;
        Node lastSharedNode = null;

        for (int step = 0; step < innerSize; step++) {
            int i = (startIdx + step) % innerSize;
            Node node = innerNodes.get(i);
            boolean isShared = sharedSet.contains(node);

            if (isShared) {
                if (currentSegment != null) {
                    segEndBoundary.add(node);
                    innerOnlySegments.add(currentSegment);
                    currentSegment = null;
                }
                lastSharedNode = node;
            } else {
                if (currentSegment == null) {
                    currentSegment = new ArrayList<>();
                    segStartBoundary.add(lastSharedNode);
                }
                currentSegment.add(node);
            }
        }

        if (currentSegment != null) {
            segEndBoundary.add(innerNodes.get(startIdx));
            innerOnlySegments.add(currentSegment);
        }

        if (innerOnlySegments.isEmpty()) {
            return null;
        }

        // Check for cross-segment self-intersections in the inner ring.
        if (innerOnlySegments.size() == 2) {
            MergeResult crossResult = tryMergeWithCrossSegmentIntersections(
                outerNodes, innerOnlySegments, segStartBoundary, segEndBoundary, sharedSet);
            if (crossResult != null) {
                return crossResult;
            }
        }

        Set<Node> allBoundaryNodes = new HashSet<>();
        for (int s = 0; s < innerOnlySegments.size(); s++) {
            allBoundaryNodes.add(segStartBoundary.get(s));
            allBoundaryNodes.add(segEndBoundary.get(s));
        }

        List<List<Node>> result = new ArrayList<>();

        // Compute both forward and backward outer paths for each segment
        List<List<Node>> forwardPaths = new ArrayList<>();
        List<List<Node>> backwardPaths = new ArrayList<>();

        for (int s = 0; s < innerOnlySegments.size(); s++) {
            Node boundaryA = segStartBoundary.get(s);
            Node boundaryB = segEndBoundary.get(s);

            Integer outerIdxA = outerIndexMap.get(boundaryA);
            Integer outerIdxB = outerIndexMap.get(boundaryB);
            if (outerIdxA == null || outerIdxB == null) {
                return null;
            }

            Set<Node> otherBoundaries = new HashSet<>(allBoundaryNodes);
            otherBoundaries.remove(boundaryA);
            otherBoundaries.remove(boundaryB);

            List<Node> fwd = computeDirectionalPath(outerNodes, outerSize, outerIdxB, outerIdxA, otherBoundaries, true);
            List<Node> bwd = computeDirectionalPath(outerNodes, outerSize, outerIdxB, outerIdxA, otherBoundaries, false);
            forwardPaths.add(fwd);
            backwardPaths.add(bwd);
        }

        // Choose outer path directions so that together they cover all outer-only nodes.
        List<List<Node>> chosenPaths = chooseComplementaryPaths(
            outerNodes, outerSize, sharedSet, allBoundaryNodes,
            forwardPaths, backwardPaths, innerOnlySegments.size());

        if (chosenPaths == null) {
            return null;
        }

        for (int s = 0; s < innerOnlySegments.size(); s++) {
            List<Node> way = buildMergedWay(segStartBoundary.get(s), segEndBoundary.get(s),
                innerOnlySegments.get(s), chosenPaths.get(s));
            if (way == null) {
                return null;
            }
            result.add(way);
        }

        // For 2-segment cases, validate the pairing using area comparison.
        if (result.size() == 2 && innerOnlySegments.size() == 2) {
            double outerArea = Math.abs(GeometryUtils.computeSignedArea(outerNodes));
            double innerArea = Math.abs(GeometryUtils.computeSignedArea(innerNodes));
            double expectedArea = outerArea - innerArea;

            double currentAreaSum = Math.abs(GeometryUtils.computeSignedArea(result.get(0)))
                                  + Math.abs(GeometryUtils.computeSignedArea(result.get(1)));

            // Build alternative with swapped outer paths
            List<List<Node>> altPaths = new ArrayList<>();
            for (int s = 0; s < 2; s++) {
                List<Node> fwd = forwardPaths.get(s);
                List<Node> bwd = backwardPaths.get(s);
                List<Node> current = chosenPaths.get(s);
                altPaths.add((current == fwd) ? bwd : fwd);
            }
            boolean altValid = true;
            List<List<Node>> altResult = new ArrayList<>();
            for (int s = 0; s < 2; s++) {
                List<Node> altWay = buildMergedWay(segStartBoundary.get(s), segEndBoundary.get(s),
                    innerOnlySegments.get(s), altPaths.get(s));
                if (altWay == null) { altValid = false; break; }
                altResult.add(altWay);
            }

            if (altValid) {
                double altAreaSum = Math.abs(GeometryUtils.computeSignedArea(altResult.get(0)))
                                  + Math.abs(GeometryUtils.computeSignedArea(altResult.get(1)));
                double currentDiff = Math.abs(currentAreaSum - expectedArea);
                double altDiff = Math.abs(altAreaSum - expectedArea);
                if (altDiff < currentDiff) {
                    result.clear();
                    result.addAll(altResult);
                    for (int s = 0; s < 2; s++) {
                        chosenPaths.set(s, altPaths.get(s));
                    }
                }
            }
        }

        // When 3+ consecutive shared nodes exist between two boundaries AND there are
        // multiple inner-only segments, produce additional closed ways for shared
        // intermediate nodes.
        if (innerOnlySegments.size() >= 2) {
            for (int s = 0; s < innerOnlySegments.size(); s++) {
                Node bStart = segStartBoundary.get(s);
                Node bEnd = segEndBoundary.get(s);

                Integer outerIdxStart = outerIndexMap.get(bStart);
                Integer outerIdxEnd = outerIndexMap.get(bEnd);

                List<Node> sharedIntermediates = new ArrayList<>();
                boolean allShared = true;
                boolean hasNonBoundary = false;
                for (int i = (outerIdxEnd + 1) % outerSize; i != outerIdxStart; i = (i + 1) % outerSize) {
                    Node n = outerNodes.get(i);
                    if (!sharedSet.contains(n)) {
                        allShared = false;
                        break;
                    }
                    sharedIntermediates.add(n);
                    if (!allBoundaryNodes.contains(n)) {
                        hasNonBoundary = true;
                    }
                }

                if (allShared && hasNonBoundary) {
                    List<Node> way2 = new ArrayList<>();
                    way2.add(bEnd);
                    way2.addAll(sharedIntermediates);
                    way2.add(bStart);
                    List<Node> innerSeg = innerOnlySegments.get(s);
                    for (int i = innerSeg.size() - 1; i >= 0; i--) {
                        way2.add(innerSeg.get(i));
                    }
                    way2.add(bEnd);
                    result.add(way2);
                }
            }
        }

        return new MergeResult(result, null);
    }

    /**
     * Cross-segment intersection node for bridging inner merges.
     */
    private static class InnerCrossing {
        final int segAIdx;
        final int segAEdge;
        final int segBIdx;
        final int segBEdge;
        final EastNorth point;
        Node node;

        InnerCrossing(int segAIdx, int segAEdge, int segBIdx, int segBEdge, EastNorth point) {
            this.segAIdx = segAIdx;
            this.segAEdge = segAEdge;
            this.segBIdx = segBIdx;
            this.segBEdge = segBEdge;
            this.point = point;
        }
    }

    /**
     * When 2 inner-only segments have edges that cross each other (self-intersecting inner),
     * the inner ring "bridges" across the outer polygon. This method detects such crossings,
     * inserts new intersection nodes, and builds merged ways that trace the boundaries of
     * each region correctly.
     *
     * Returns null if no cross-segment intersections are found.
     */
    private static MergeResult tryMergeWithCrossSegmentIntersections(
            List<Node> outerNodes,
            List<List<Node>> innerOnlySegments,
            List<Node> segStartBoundary,
            List<Node> segEndBoundary,
            Set<Node> sharedSet) {

        int outerSize = outerNodes.size() - 1;

        // Build full segments including boundary nodes
        List<List<Node>> fullSegments = new ArrayList<>();
        for (int s = 0; s < 2; s++) {
            List<Node> full = new ArrayList<>();
            full.add(segStartBoundary.get(s));
            full.addAll(innerOnlySegments.get(s));
            full.add(segEndBoundary.get(s));
            fullSegments.add(full);
        }

        // Find all crossings between edges of segment 0 and edges of segment 1
        List<InnerCrossing> crossings = new ArrayList<>();
        List<Node> seg0 = fullSegments.get(0);
        List<Node> seg1 = fullSegments.get(1);

        for (int i = 0; i < seg0.size() - 1; i++) {
            EastNorth p1 = seg0.get(i).getEastNorth();
            EastNorth p2 = seg0.get(i + 1).getEastNorth();
            for (int j = 0; j < seg1.size() - 1; j++) {
                EastNorth p3 = seg1.get(j).getEastNorth();
                EastNorth p4 = seg1.get(j + 1).getEastNorth();

                // Skip if segments share an endpoint node
                if (seg0.get(i) == seg1.get(j) || seg0.get(i) == seg1.get(j + 1)
                    || seg0.get(i + 1) == seg1.get(j) || seg0.get(i + 1) == seg1.get(j + 1)) {
                    continue;
                }

                EastNorth intersection = Geometry.getSegmentSegmentIntersection(p1, p2, p3, p4);
                if (intersection != null) {
                    double tol = 1e-6;
                    if (GeometryUtils.isNear(intersection, p1, tol) || GeometryUtils.isNear(intersection, p2, tol)
                        || GeometryUtils.isNear(intersection, p3, tol) || GeometryUtils.isNear(intersection, p4, tol)) {
                        continue;
                    }
                    crossings.add(new InnerCrossing(0, i, 1, j, intersection));
                }
            }
        }

        if (crossings.isEmpty()) {
            return null;
        }

        // Create nodes at crossing points
        List<Node> newNodes = new ArrayList<>();
        for (InnerCrossing c : crossings) {
            LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(c.point);
            c.node = new Node(ll);
            newNodes.add(c.node);
        }

        // Insert crossing nodes into both segments
        List<Node> augSeg0 = buildAugmentedSegment(seg0, crossings, 0);
        List<Node> augSeg1 = buildAugmentedSegment(seg1, crossings, 1);

        Node sharedA = segStartBoundary.get(0);
        Node sharedB = segEndBoundary.get(0);

        // Build outer index map
        Map<Node, Integer> outerIndexMap = new HashMap<>();
        for (int i = 0; i < outerSize; i++) {
            outerIndexMap.put(outerNodes.get(i), i);
        }
        Integer outerIdxA = outerIndexMap.get(sharedA);
        Integer outerIdxB = outerIndexMap.get(sharedB);
        if (outerIdxA == null || outerIdxB == null) {
            return null;
        }

        // Compute the two outer paths (A->B forward and A->B backward)
        List<Node> outerPathFwd = computeDirectionalPath(outerNodes, outerSize, outerIdxA, outerIdxB, Set.of(), true);
        List<Node> outerPathBwd = computeDirectionalPath(outerNodes, outerSize, outerIdxA, outerIdxB, Set.of(), false);

        if (outerPathFwd == null || outerPathBwd == null) {
            return null;
        }

        // Trace inner paths with both starting directions
        List<Node> innerPathA = traceInnerPathCrossSegment(augSeg0, augSeg1, sharedB, sharedA, newNodes, false);
        List<Node> innerPathB = traceInnerPathCrossSegment(augSeg0, augSeg1, sharedB, sharedA, newNodes, true);

        if (innerPathA == null || innerPathB == null) {
            return null;
        }

        // Try both pairings of inner paths with outer paths
        List<List<Node>> bestWays = null;
        double bestAreaSum = -1;

        for (int pairing = 0; pairing < 2; pairing++) {
            List<Node> innerForFwd = (pairing == 0) ? innerPathA : innerPathB;
            List<Node> innerForBwd = (pairing == 0) ? innerPathB : innerPathA;

            List<Node> way1 = new ArrayList<>(outerPathFwd);
            for (int i = 1; i < innerForFwd.size(); i++) {
                way1.add(innerForFwd.get(i));
            }
            if (!way1.get(way1.size() - 1).equals(way1.get(0))) {
                way1.add(sharedA);
            }

            List<Node> way2 = new ArrayList<>(outerPathBwd);
            for (int i = 1; i < innerForBwd.size(); i++) {
                way2.add(innerForBwd.get(i));
            }
            if (!way2.get(way2.size() - 1).equals(way2.get(0))) {
                way2.add(sharedA);
            }

            double areaSum = Math.abs(GeometryUtils.computeSignedArea(way1)) + Math.abs(GeometryUtils.computeSignedArea(way2));
            if (bestWays == null || areaSum < bestAreaSum) {
                bestAreaSum = areaSum;
                bestWays = List.of(way1, way2);
            }
        }

        if (bestWays != null) {
            return new MergeResult(bestWays, newNodes);
        }
        return null;
    }

    /**
     * Inserts crossing nodes into a segment's node list at the appropriate positions.
     */
    private static List<Node> buildAugmentedSegment(List<Node> segment, List<InnerCrossing> crossings, int segIdx) {
        Map<Integer, List<InnerCrossing>> edgeCrossings = new HashMap<>();
        for (InnerCrossing c : crossings) {
            int edgeIdx;
            if (c.segAIdx == segIdx) {
                edgeIdx = c.segAEdge;
            } else if (c.segBIdx == segIdx) {
                edgeIdx = c.segBEdge;
            } else {
                continue;
            }
            edgeCrossings.computeIfAbsent(edgeIdx, k -> new ArrayList<>()).add(c);
        }

        List<Node> result = new ArrayList<>();
        for (int i = 0; i < segment.size() - 1; i++) {
            result.add(segment.get(i));
            List<InnerCrossing> edgeCross = edgeCrossings.get(i);
            if (edgeCross != null) {
                EastNorth start = segment.get(i).getEastNorth();
                edgeCross.sort((a, b) -> {
                    double distA = a.point.distance(start);
                    double distB = b.point.distance(start);
                    return Double.compare(distA, distB);
                });
                for (InnerCrossing c : edgeCross) {
                    result.add(c.node);
                }
            }
        }
        result.add(segment.get(segment.size() - 1));
        return result;
    }

    /**
     * Traces an inner path through augmented segments for the cross-segment merge case.
     * Switches between segments at intersection nodes, reversing direction at each crossing.
     */
    private static List<Node> traceInnerPathCrossSegment(
            List<Node> augSeg0, List<Node> augSeg1,
            Node fromNode, Node toNode,
            List<Node> crossingNodes, boolean startWithSeg1) {

        Set<Node> crossingSet = new HashSet<>(crossingNodes);

        int currentSeg;
        int pos;
        int dir;

        if (startWithSeg1) {
            currentSeg = 1;
            pos = 0;
            dir = 1;
            if (!augSeg1.get(0).equals(fromNode)) return null;
        } else {
            currentSeg = 0;
            pos = augSeg0.size() - 1;
            dir = -1;
            if (!augSeg0.get(pos).equals(fromNode)) return null;
        }

        List<Node> result = new ArrayList<>();
        result.add(fromNode);

        int maxSteps = augSeg0.size() + augSeg1.size();
        for (int step = 0; step < maxSteps; step++) {
            pos += dir;
            List<Node> seg = (currentSeg == 0) ? augSeg0 : augSeg1;
            if (pos < 0 || pos >= seg.size()) return null;

            Node n = seg.get(pos);
            result.add(n);

            if (n.equals(toNode)) {
                return result;
            }

            if (crossingSet.contains(n)) {
                int otherSeg = 1 - currentSeg;
                List<Node> otherSegList = (otherSeg == 0) ? augSeg0 : augSeg1;
                int otherPos = -1;
                for (int i = 0; i < otherSegList.size(); i++) {
                    if (otherSegList.get(i).equals(n)) {
                        otherPos = i;
                        break;
                    }
                }
                if (otherPos == -1) return null;

                currentSeg = otherSeg;
                pos = otherPos;
                dir = -dir;
            }
        }

        return null;
    }

    // ---- Geometry helpers (delegated to GeometryUtils) ----

    /**
     * Computes an outer path in a single direction (forward=CW or backward=CCW).
     * Returns null if the path is blocked by a forbidden node.
     */
    private static List<Node> computeDirectionalPath(List<Node> outerNodes, int outerSize,
            int fromIdx, int toIdx, Set<Node> forbidden, boolean forward) {
        List<Node> path = new ArrayList<>();
        path.add(outerNodes.get(fromIdx));
        int step = forward ? 1 : -1;
        for (int i = (fromIdx + step + outerSize) % outerSize; i != toIdx; i = (i + step + outerSize) % outerSize) {
            Node n = outerNodes.get(i);
            if (forbidden.contains(n)) {
                return null;
            }
            path.add(n);
        }
        path.add(outerNodes.get(toIdx));
        return path;
    }

    /**
     * Builds a merged way from an inner-only segment and an outer path,
     * choosing the inner segment direction that produces a non-self-intersecting polygon.
     */
    private static List<Node> buildMergedWay(Node boundaryA, Node boundaryB,
            List<Node> innerSegment, List<Node> outerPath) {
        if (outerPath == null) {
            return null;
        }

        List<Node> fwdWay = buildMergedWayWithDirection(boundaryA, boundaryB, innerSegment, outerPath, false);
        if (innerSegment.size() <= 1) {
            return fwdWay;
        }
        List<Node> revWay = buildMergedWayWithDirection(boundaryA, boundaryB, innerSegment, outerPath, true);

        // Pick the non-self-intersecting version
        boolean fwdSelfIntersects = GeometryUtils.hasNonAdjacentEdgeCrossing(fwdWay);
        boolean revSelfIntersects = GeometryUtils.hasNonAdjacentEdgeCrossing(revWay);
        if (fwdSelfIntersects != revSelfIntersects) {
            return fwdSelfIntersects ? revWay : fwdWay;
        }
        return fwdWay;
    }

    /**
     * Builds a merged way with a specific inner direction.
     */
    private static List<Node> buildMergedWayWithDirection(Node boundaryA, Node boundaryB,
            List<Node> innerSegment, List<Node> outerPath, boolean reverse) {
        List<Node> way = new ArrayList<>();
        way.add(boundaryA);
        if (reverse) {
            for (int i = innerSegment.size() - 1; i >= 0; i--) {
                way.add(innerSegment.get(i));
            }
        } else {
            way.addAll(innerSegment);
        }
        way.add(boundaryB);
        for (int i = 1; i < outerPath.size(); i++) {
            way.add(outerPath.get(i));
        }
        if (!way.get(way.size() - 1).equals(way.get(0))) {
            way.add(boundaryA);
        }
        return way;
    }

    /**
     * Chooses outer path directions for all segments so that together they cover
     * all outer-only nodes.
     */
    private static List<List<Node>> chooseComplementaryPaths(
            List<Node> outerNodes, int outerSize, Set<Node> sharedSet, Set<Node> allBoundaryNodes,
            List<List<Node>> forwardPaths, List<List<Node>> backwardPaths, int segCount) {

        Set<Node> outerOnlyNodes = new HashSet<>();
        for (int i = 0; i < outerSize; i++) {
            Node n = outerNodes.get(i);
            if (!sharedSet.contains(n)) {
                outerOnlyNodes.add(n);
            }
        }

        // Start with default choices using the old heuristic
        List<List<Node>> chosen = new ArrayList<>();
        for (int s = 0; s < segCount; s++) {
            List<Node> fwd = forwardPaths.get(s);
            List<Node> bwd = backwardPaths.get(s);
            if (fwd != null && bwd != null) {
                boolean fwdHasOuterOnly = fwd.stream().anyMatch(n -> !sharedSet.contains(n) && !allBoundaryNodes.contains(n));
                boolean bwdHasOuterOnly = bwd.stream().anyMatch(n -> !sharedSet.contains(n) && !allBoundaryNodes.contains(n));
                if (fwdHasOuterOnly != bwdHasOuterOnly) {
                    chosen.add(fwdHasOuterOnly ? fwd : bwd);
                } else {
                    chosen.add(fwd.size() <= bwd.size() ? fwd : bwd);
                }
            } else if (fwd != null) {
                chosen.add(fwd);
            } else {
                chosen.add(bwd);
            }
        }

        // Check if all outer-only nodes are covered
        Set<Node> covered = new HashSet<>();
        for (List<Node> path : chosen) {
            if (path != null) {
                covered.addAll(path);
            }
        }
        if (covered.containsAll(outerOnlyNodes)) {
            return chosen;
        }

        // Coverage is incomplete — try swapping each segment's path
        for (int s = 0; s < segCount; s++) {
            List<Node> fwd = forwardPaths.get(s);
            List<Node> bwd = backwardPaths.get(s);
            List<Node> current = chosen.get(s);
            List<Node> alternative = (current == fwd) ? bwd : fwd;
            if (alternative == null) continue;

            chosen.set(s, alternative);
            covered.clear();
            for (List<Node> path : chosen) {
                if (path != null) {
                    covered.addAll(path);
                }
            }
            if (covered.containsAll(outerOnlyNodes)) {
                return chosen;
            }
            chosen.set(s, current);
        }

        return chosen;
    }

}
