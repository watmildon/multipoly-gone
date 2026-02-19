package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Geometry;

public class MultipolygonAnalyzer {

    public enum FixOpType {
        /** Chain open outer ways into closed rings within the relation. */
        CONSOLIDATE_RINGS,
        /** Extract standalone outer rings (no inners) as independent tagged ways. */
        EXTRACT_OUTERS,
        /** Push relation tags to remaining outer ways, delete relation. */
        DISSOLVE,
        /** Merge 1 outer + 1 inner sharing nodes into closed way(s), delete relation. */
        TOUCHING_INNER_MERGE
    }

    public static class FixOp {
        private final FixOpType type;
        private final List<WayChainBuilder.Ring> rings;
        private final List<List<Node>> mergedWays;

        FixOp(FixOpType type, List<WayChainBuilder.Ring> rings, List<List<Node>> mergedWays) {
            this.type = type;
            this.rings = rings;
            this.mergedWays = mergedWays;
        }

        public FixOpType getType() {
            return type;
        }

        public List<WayChainBuilder.Ring> getRings() {
            return rings;
        }

        public List<List<Node>> getMergedWays() {
            return mergedWays;
        }
    }

    public static class FixPlan {
        private final Relation relation;
        private final List<FixOp> operations;
        private final String description;

        FixPlan(Relation relation, List<FixOp> operations, String description) {
            this.relation = relation;
            this.operations = operations;
            this.description = description;
        }

        public Relation getRelation() {
            return relation;
        }

        public List<FixOp> getOperations() {
            return operations;
        }

        public String getDescription() {
            return description;
        }

        public boolean dissolvesRelation() {
            return operations.stream().anyMatch(op ->
                op.getType() == FixOpType.DISSOLVE || op.getType() == FixOpType.TOUCHING_INNER_MERGE);
        }
    }

    private static final int MAX_WAY_NODES = 2000;

    public static List<FixPlan> findFixableRelations(DataSet dataSet) {
        List<FixPlan> results = new ArrayList<>();
        if (dataSet == null) {
            return results;
        }

        for (Relation relation : dataSet.getRelations()) {
            if (relation.isDeleted() || relation.isIncomplete()) {
                continue;
            }
            if (!"multipolygon".equals(relation.get("type"))) {
                continue;
            }

            FixPlan plan = analyze(relation);
            if (plan != null) {
                results.add(plan);
            }
        }
        return results;
    }

    private static FixPlan analyze(Relation relation) {
        List<Way> outerWays = new ArrayList<>();
        List<Way> innerWays = new ArrayList<>();

        for (RelationMember member : relation.getMembers()) {
            if (!member.isWay()) {
                return null;
            }
            Way way = member.getWay();
            if (way.isDeleted() || way.isIncomplete() || way.getNodesCount() < 2) {
                return null;
            }
            if ("inner".equals(member.getRole())) {
                innerWays.add(way);
            } else {
                outerWays.add(way);
            }
        }

        if (outerWays.isEmpty()) {
            return null;
        }

        // Phase 1: Build rings from outer ways
        Optional<List<WayChainBuilder.Ring>> ringsOpt = WayChainBuilder.buildRings(outerWays);
        if (ringsOpt.isEmpty()) {
            return null;
        }

        List<WayChainBuilder.Ring> outerRings = ringsOpt.get();

        // Check node limits on rings that need chaining
        for (WayChainBuilder.Ring ring : outerRings) {
            if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                return null;
            }
        }

        List<FixOp> ops = new ArrayList<>();
        List<String> descParts = new ArrayList<>();
        String primaryTag = getPrimaryTag(relation);

        // Record consolidation if any rings need chaining
        List<WayChainBuilder.Ring> needsChaining = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            if (!ring.isAlreadyClosed()) {
                needsChaining.add(ring);
            }
        }
        if (!needsChaining.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, needsChaining, null));
            descParts.add(needsChaining.size() == 1
                ? "1 ring chained"
                : needsChaining.size() + " rings chained");
        }

        // No inners: dissolve everything
        if (innerWays.isEmpty()) {
            ops.add(new FixOp(FixOpType.DISSOLVE, outerRings, null));
            int count = outerRings.size();
            descParts.add(count == 1
                ? "dissolved"
                : count + " outers dissolved");
            return new FixPlan(relation, ops, buildDescription(descParts, primaryTag));
        }

        // Build rings from inner ways (same as outers — closed ways stay as-is, open ways chain)
        Optional<List<WayChainBuilder.Ring>> innerRingsOpt = WayChainBuilder.buildRings(innerWays);
        if (innerRingsOpt.isEmpty()) {
            return null;
        }
        List<WayChainBuilder.Ring> innerRings = innerRingsOpt.get();

        // Check node limits on inner rings that need chaining
        for (WayChainBuilder.Ring ring : innerRings) {
            if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                return null;
            }
        }

        // Record inner consolidation if any inner rings need chaining
        List<WayChainBuilder.Ring> innerNeedsChaining = new ArrayList<>();
        for (WayChainBuilder.Ring ring : innerRings) {
            if (!ring.isAlreadyClosed()) {
                innerNeedsChaining.add(ring);
            }
        }
        if (!innerNeedsChaining.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, innerNeedsChaining, null));
            int total = needsChaining.size() + innerNeedsChaining.size();
            // Update description: replace outer-only count with combined count
            if (!needsChaining.isEmpty()) {
                descParts.remove(descParts.size() - 1);
            }
            descParts.add(total == 1
                ? "1 ring chained"
                : total + " rings chained");
        }

        // Phase 2: Map inner rings to containing outer rings
        Map<WayChainBuilder.Ring, List<WayChainBuilder.Ring>> ringToInners = new HashMap<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            ringToInners.put(ring, new ArrayList<>());
        }
        for (WayChainBuilder.Ring innerRing : innerRings) {
            WayChainBuilder.Ring containing = findContainingRing(innerRing, outerRings);
            if (containing == null) {
                return null;
            }
            ringToInners.get(containing).add(innerRing);
        }

        List<WayChainBuilder.Ring> extractable = new ArrayList<>();
        List<WayChainBuilder.Ring> retained = new ArrayList<>();
        List<WayChainBuilder.Ring> retainedInners = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            if (ringToInners.get(ring).isEmpty()) {
                extractable.add(ring);
            } else {
                retained.add(ring);
                retainedInners.addAll(ringToInners.get(ring));
            }
        }

        // Extract standalone outers if any exist
        if (!extractable.isEmpty()) {
            ops.add(new FixOp(FixOpType.EXTRACT_OUTERS, extractable, null));
            descParts.add(extractable.size() == 1
                ? "1 outer extracted"
                : extractable.size() + " outers extracted");
        }

        // Phase 3: Analyze what remains after extraction
        if (retained.size() == 1 && retainedInners.size() == 1) {
            // Try touching inner merge (works whether or not extraction happened)
            List<List<Node>> merged = tryTouchingInnerMerge(retained.get(0), retainedInners.get(0).getNodes());
            if (merged != null) {
                for (List<Node> way : merged) {
                    if (way.size() - 1 > MAX_WAY_NODES) {
                        return ops.isEmpty() ? null : new FixPlan(relation, ops, buildDescription(descParts, primaryTag));
                    }
                }
                ops.add(new FixOp(FixOpType.TOUCHING_INNER_MERGE, null, merged));
                if (merged.size() == 1) {
                    descParts.add("touching inner merged");
                } else {
                    descParts.add("inner split into " + merged.size() + " ways");
                }
                return new FixPlan(relation, ops, buildDescription(descParts, primaryTag));
            }

            // 1 outer + 1 inner, not touching: only dissolve if outers were extracted
            // (the remainder is a side-effect of extraction, not a genuine outer+inner pair)
            if (!extractable.isEmpty()) {
                ops.add(new FixOp(FixOpType.DISSOLVE, retained, null));
                descParts.add("remainder dissolved");
                return new FixPlan(relation, ops, buildDescription(descParts, primaryTag));
            }
        }

        // Relation survives with remaining members — return plan if we have any ops
        if (ops.isEmpty()) {
            return null;
        }
        return new FixPlan(relation, ops, buildDescription(descParts, primaryTag));
    }

    private static String buildDescription(List<String> parts, String primaryTag) {
        return String.join(", ", parts) + " (" + primaryTag + ")";
    }

    /**
     * Try to merge a single outer ring with a single inner that share nodes.
     * Returns the merged way node lists, or null if they don't share enough nodes.
     */
    private static List<List<Node>> tryTouchingInnerMerge(WayChainBuilder.Ring outerRing, List<Node> innerNodes) {
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

        if (sharedSet.size() < 2) {
            return null;
        }

        return mergeOuterInner(outerNodes, innerNodes, sharedSet);
    }

    /**
     * Merges an outer ring and a touching inner ring into one or more closed ways.
     * Driven by the inner ring's topology: each inner-only segment (bounded by shared
     * nodes) is paired with the corresponding outer path between the same boundary
     * nodes to form a closed way.
     */
    static List<List<Node>> mergeOuterInner(List<Node> outerNodes, List<Node> innerNodes, Set<Node> sharedSet) {
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

        Set<Node> allBoundaryNodes = new HashSet<>();
        for (int s = 0; s < innerOnlySegments.size(); s++) {
            allBoundaryNodes.add(segStartBoundary.get(s));
            allBoundaryNodes.add(segEndBoundary.get(s));
        }

        List<List<Node>> result = new ArrayList<>();

        for (int s = 0; s < innerOnlySegments.size(); s++) {
            Node boundaryA = segStartBoundary.get(s);
            Node boundaryB = segEndBoundary.get(s);
            List<Node> innerSegment = innerOnlySegments.get(s);

            Integer outerIdxA = outerIndexMap.get(boundaryA);
            Integer outerIdxB = outerIndexMap.get(boundaryB);
            if (outerIdxA == null || outerIdxB == null) {
                return null;
            }

            Set<Node> otherBoundaries = new HashSet<>(allBoundaryNodes);
            otherBoundaries.remove(boundaryA);
            otherBoundaries.remove(boundaryB);

            List<Node> outerPath = findOuterPath(outerNodes, outerSize, outerIdxB, outerIdxA, otherBoundaries, sharedSet);
            if (outerPath == null) {
                return null;
            }

            List<Node> way = new ArrayList<>();
            way.add(boundaryA);
            way.addAll(innerSegment);
            way.add(boundaryB);
            for (int i = 1; i < outerPath.size(); i++) {
                way.add(outerPath.get(i));
            }
            if (!way.get(way.size() - 1).equals(way.get(0))) {
                way.add(boundaryA);
            }

            result.add(way);
        }

        return result;
    }

    private static List<Node> findOuterPath(List<Node> outerNodes, int outerSize,
            int fromIdx, int toIdx, Set<Node> forbidden, Set<Node> sharedSet) {
        List<Node> forward = new ArrayList<>();
        forward.add(outerNodes.get(fromIdx));
        boolean forwardValid = true;
        boolean forwardHasOuterOnly = false;
        for (int i = (fromIdx + 1) % outerSize; i != toIdx; i = (i + 1) % outerSize) {
            Node n = outerNodes.get(i);
            if (forbidden.contains(n)) {
                forwardValid = false;
                break;
            }
            if (!sharedSet.contains(n)) {
                forwardHasOuterOnly = true;
            }
            forward.add(n);
        }
        if (forwardValid) {
            forward.add(outerNodes.get(toIdx));
        }

        List<Node> backward = new ArrayList<>();
        backward.add(outerNodes.get(fromIdx));
        boolean backwardValid = true;
        boolean backwardHasOuterOnly = false;
        for (int i = (fromIdx - 1 + outerSize) % outerSize; i != toIdx; i = (i - 1 + outerSize) % outerSize) {
            Node n = outerNodes.get(i);
            if (forbidden.contains(n)) {
                backwardValid = false;
                break;
            }
            if (!sharedSet.contains(n)) {
                backwardHasOuterOnly = true;
            }
            backward.add(n);
        }
        if (backwardValid) {
            backward.add(outerNodes.get(toIdx));
        }

        if (forwardValid && backwardValid) {
            if (forwardHasOuterOnly != backwardHasOuterOnly) {
                return forwardHasOuterOnly ? forward : backward;
            }
            return forward.size() <= backward.size() ? forward : backward;
        }
        if (forwardValid) return forward;
        if (backwardValid) return backward;
        return null;
    }

    private static WayChainBuilder.Ring findContainingRing(WayChainBuilder.Ring inner, List<WayChainBuilder.Ring> outerRings) {
        for (WayChainBuilder.Ring ring : outerRings) {
            Set<Node> ringNodeSet = new HashSet<>(ring.getNodes());
            // Try each inner node, skipping any that lie on the outer boundary
            for (Node testNode : inner.getNodes()) {
                if (ringNodeSet.contains(testNode)) {
                    continue;
                }
                if (Geometry.nodeInsidePolygon(testNode, ring.getNodes())) {
                    return ring;
                }
                break; // Not inside this ring
            }
        }
        return null;
    }

    static String getPrimaryTag(Relation relation) {
        for (String key : relation.getKeys().keySet()) {
            if (!"type".equals(key) && !key.startsWith("_")) {
                return key + "=" + relation.get(key);
            }
        }
        return "multipolygon";
    }
}
