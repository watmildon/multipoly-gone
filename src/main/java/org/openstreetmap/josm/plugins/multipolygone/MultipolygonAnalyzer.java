package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
        TOUCHING_INNER_MERGE,
        /** Split relation into disconnected components, each analyzed independently. */
        SPLIT_RELATION
    }

    /**
     * Result of analyzing one connected component of a split relation.
     */
    public static class ComponentResult {
        private final List<Way> outerWays;
        private final List<Way> innerWays;
        private final List<FixOp> operations;
        private final boolean dissolvesCompletely;
        private final List<WayChainBuilder.Ring> retainedOuterRings;
        private final List<WayChainBuilder.Ring> retainedInnerRings;
        private final String description;

        ComponentResult(List<Way> outerWays, List<Way> innerWays, List<FixOp> operations,
                boolean dissolvesCompletely,
                List<WayChainBuilder.Ring> retainedOuterRings,
                List<WayChainBuilder.Ring> retainedInnerRings,
                String description) {
            this.outerWays = outerWays;
            this.innerWays = innerWays;
            this.operations = operations;
            this.dissolvesCompletely = dissolvesCompletely;
            this.retainedOuterRings = retainedOuterRings;
            this.retainedInnerRings = retainedInnerRings;
            this.description = description;
        }

        public List<Way> getOuterWays() { return outerWays; }
        public List<Way> getInnerWays() { return innerWays; }
        public List<FixOp> getOperations() { return operations; }
        public boolean dissolvesCompletely() { return dissolvesCompletely; }
        public List<WayChainBuilder.Ring> getRetainedOuterRings() { return retainedOuterRings; }
        public List<WayChainBuilder.Ring> getRetainedInnerRings() { return retainedInnerRings; }
        public String getDescription() { return description; }
    }

    public static class FixOp {
        private final FixOpType type;
        private final List<WayChainBuilder.Ring> rings;
        private final List<List<Node>> mergedWays;
        private final List<ComponentResult> components;

        FixOp(FixOpType type, List<WayChainBuilder.Ring> rings, List<List<Node>> mergedWays) {
            this.type = type;
            this.rings = rings;
            this.mergedWays = mergedWays;
            this.components = null;
        }

        FixOp(FixOpType type, List<ComponentResult> components) {
            this.type = type;
            this.rings = null;
            this.mergedWays = null;
            this.components = components;
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

        public List<ComponentResult> getComponents() {
            return components;
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
            return operations.stream().anyMatch(op -> {
                if (op.getType() == FixOpType.DISSOLVE || op.getType() == FixOpType.TOUCHING_INNER_MERGE) {
                    return true;
                }
                if (op.getType() == FixOpType.SPLIT_RELATION && op.getComponents() != null) {
                    return op.getComponents().stream()
                        .allMatch(c -> c == null || c.dissolvesCompletely());
                }
                return false;
            });
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
        Set<Way> innerWaySet = new HashSet<>();

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
                innerWaySet.add(way);
            } else {
                outerWays.add(way);
            }
        }

        if (outerWays.isEmpty()) {
            return null;
        }

        // Try the single-relation path first — it handles all original test cases
        // and relations where outers form valid rings (even with disconnected closed ways).
        FixPlan singleResult = analyzeSingleRelation(relation, outerWays, innerWays);
        if (singleResult != null) {
            return singleResult;
        }

        // Single-relation path failed (e.g., outers can't form rings globally).
        // Try splitting into disconnected components (e.g., megafarmland).
        List<Way> allWays = new ArrayList<>(outerWays);
        allWays.addAll(innerWays);
        List<Set<Way>> components = findConnectedComponents(allWays);

        if (components.size() <= 1) {
            // Only one component and single-relation already failed — not fixable
            return null;
        }

        // Multiple components: analyze each independently
        return analyzeMultiComponent(relation, components, innerWaySet);
    }

    /**
     * Original single-component analysis path. Unchanged from before SPLIT_RELATION.
     */
    private static FixPlan analyzeSingleRelation(Relation relation, List<Way> outerWays, List<Way> innerWays) {
        ComponentResult result = analyzeComponent(outerWays, innerWays);
        if (result == null) {
            return null;
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = result.getDescription().isEmpty()
            ? primaryTag
            : result.getDescription() + " (" + primaryTag + ")";

        return new FixPlan(relation, result.getOperations(), desc);
    }

    /**
     * Analyze a relation with multiple disconnected components.
     * Inner-only components (no outers) are assigned to the outer component
     * that spatially contains them.
     */
    private static FixPlan analyzeMultiComponent(Relation relation, List<Set<Way>> components, Set<Way> innerWaySet) {
        // Separate components into outer-bearing and inner-only
        List<List<Way>> outerComponents = new ArrayList<>();
        List<List<Way>> outerCompInners = new ArrayList<>();
        List<List<Way>> innerOnlyComponents = new ArrayList<>();

        for (Set<Way> component : components) {
            List<Way> compOuters = new ArrayList<>();
            List<Way> compInners = new ArrayList<>();
            for (Way w : component) {
                if (innerWaySet.contains(w)) {
                    compInners.add(w);
                } else {
                    compOuters.add(w);
                }
            }
            if (compOuters.isEmpty()) {
                innerOnlyComponents.add(compInners);
            } else {
                outerComponents.add(compOuters);
                outerCompInners.add(compInners);
            }
        }

        if (outerComponents.size() <= 1 && innerOnlyComponents.isEmpty()) {
            // Degenerate: only one real component, shouldn't normally happen
            List<Way> allOuters = outerComponents.isEmpty() ? new ArrayList<>() : outerComponents.get(0);
            List<Way> allInners = outerCompInners.isEmpty() ? new ArrayList<>() : outerCompInners.get(0);
            return analyzeSingleRelation(relation, allOuters, allInners);
        }

        // Build outer rings for each outer component so we can do spatial containment
        List<List<WayChainBuilder.Ring>> outerRingsPerComp = new ArrayList<>();
        for (int c = 0; c < outerComponents.size(); c++) {
            List<Way> outers = outerComponents.get(c);
            List<Way> inners = outerCompInners.get(c);
            Optional<List<WayChainBuilder.Ring>> ringsOpt = WayChainBuilder.buildRings(outers);
            if (ringsOpt.isEmpty() && !inners.isEmpty()) {
                List<Way> adjOuters = new ArrayList<>(outers);
                List<Way> adjInners = new ArrayList<>(inners);
                if (reclassifyBridgingInners(adjOuters, adjInners)) {
                    ringsOpt = WayChainBuilder.buildRings(adjOuters);
                }
            }
            outerRingsPerComp.add(ringsOpt.orElse(null));
        }

        // Assign inner-only components to outer components via spatial containment
        // Build a set of all outer ring nodes so we can skip boundary nodes
        Set<Node> allOuterNodes = new HashSet<>();
        for (List<WayChainBuilder.Ring> rings : outerRingsPerComp) {
            if (rings == null) continue;
            for (WayChainBuilder.Ring ring : rings) {
                allOuterNodes.addAll(ring.getNodes());
            }
        }

        for (List<Way> innerComp : innerOnlyComponents) {
            int bestComp = -1;

            // Find a test node from the inner component that is NOT on any outer boundary
            Node testNode = null;
            for (Way w : innerComp) {
                for (Node n : w.getNodes()) {
                    if (!allOuterNodes.contains(n)) {
                        testNode = n;
                        break;
                    }
                }
                if (testNode != null) break;
            }

            if (testNode != null) {
                for (int c = 0; c < outerComponents.size(); c++) {
                    List<WayChainBuilder.Ring> rings = outerRingsPerComp.get(c);
                    if (rings == null) continue;
                    for (WayChainBuilder.Ring ring : rings) {
                        if (Geometry.nodeInsidePolygon(testNode, ring.getNodes())) {
                            bestComp = c;
                            break;
                        }
                    }
                    if (bestComp >= 0) break;
                }
            }

            if (bestComp >= 0) {
                outerCompInners.get(bestComp).addAll(innerComp);
            }
            // If no containing outer found, the inner-only component is orphaned — skip it
        }

        // If only 1 outer component after inner assignment, use the simple single-relation path
        if (outerComponents.size() == 1) {
            return analyzeSingleRelation(relation, outerComponents.get(0), outerCompInners.get(0));
        }

        // Now analyze each outer component with its assigned inners
        List<ComponentResult> results = new ArrayList<>();
        int dissolvedCount = 0;
        int retainedCount = 0;

        for (int c = 0; c < outerComponents.size(); c++) {
            List<Way> compOuters = outerComponents.get(c);
            List<Way> compInners = outerCompInners.get(c);
            ComponentResult cr = analyzeComponent(compOuters, compInners);
            if (cr == null && !compInners.isEmpty()) {
                // Component can't be simplified internally, but in a multi-component split
                // it still needs to become its own sub-relation. Create a no-op result.
                cr = new ComponentResult(compOuters, compInners, new ArrayList<>(), false,
                    null, null, "retained as sub-relation");
            }
            results.add(cr);
            if (cr != null) {
                if (cr.dissolvesCompletely()) {
                    dissolvedCount++;
                } else {
                    retainedCount++;
                }
            }
        }

        // A split is only worthwhile if there are multiple outer components
        // (the split itself is the fix, even if no component simplifies internally)
        if (outerComponents.size() < 2) {
            return null;
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = String.format("split %d components (%d extracted, %d retained) (%s)",
            outerComponents.size(), dissolvedCount, retainedCount, primaryTag);

        List<FixOp> ops = new ArrayList<>();
        ops.add(new FixOp(FixOpType.SPLIT_RELATION, results));
        return new FixPlan(relation, ops, desc);
    }

    /**
     * Analyze a single connected component of ways through the full pipeline.
     * Returns a ComponentResult, or null if the component is not fixable.
     */
    private static ComponentResult analyzeComponent(List<Way> outerWays, List<Way> innerWays) {
        // Try building rings from outers; if that fails, try reclassifying bridging inners
        Optional<List<WayChainBuilder.Ring>> ringsOpt = WayChainBuilder.buildRings(outerWays);

        if (ringsOpt.isEmpty() && !innerWays.isEmpty()) {
            List<Way> adjustedOuters = new ArrayList<>(outerWays);
            List<Way> adjustedInners = new ArrayList<>(innerWays);
            if (reclassifyBridgingInners(adjustedOuters, adjustedInners)) {
                ringsOpt = WayChainBuilder.buildRings(adjustedOuters);
                if (ringsOpt.isPresent()) {
                    outerWays = adjustedOuters;
                    innerWays = adjustedInners;
                }
            }
        }

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
            return new ComponentResult(outerWays, innerWays, ops, true,
                null, null, String.join(", ", descParts));
        }

        // Build rings from inner ways
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
            if (!needsChaining.isEmpty()) {
                descParts.remove(descParts.size() - 1);
            }
            descParts.add(total == 1
                ? "1 ring chained"
                : total + " rings chained");
        }

        // Map inner rings to containing outer rings
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

        // Analyze what remains after extraction
        if (retained.size() == 1 && retainedInners.size() == 1) {
            List<List<Node>> merged = tryTouchingInnerMerge(retained.get(0), retainedInners.get(0).getNodes());
            if (merged != null) {
                for (List<Node> way : merged) {
                    if (way.size() - 1 > MAX_WAY_NODES) {
                        if (ops.isEmpty()) return null;
                        return new ComponentResult(outerWays, innerWays, ops, false,
                            retained, retainedInners, String.join(", ", descParts));
                    }
                }
                ops.add(new FixOp(FixOpType.TOUCHING_INNER_MERGE, null, merged));
                if (merged.size() == 1) {
                    descParts.add("touching inner merged");
                } else {
                    descParts.add("inner split into " + merged.size() + " ways");
                }
                return new ComponentResult(outerWays, innerWays, ops, true,
                    null, null, String.join(", ", descParts));
            }

            if (!extractable.isEmpty()) {
                ops.add(new FixOp(FixOpType.DISSOLVE, retained, null));
                descParts.add("remainder dissolved");
                return new ComponentResult(outerWays, innerWays, ops, true,
                    null, null, String.join(", ", descParts));
            }
        }

        // Component survives with remaining members
        if (ops.isEmpty()) {
            return null;
        }
        return new ComponentResult(outerWays, innerWays, ops, false,
            retained, retainedInners, String.join(", ", descParts));
    }

    // ---- Connected component detection (Union-Find) ----

    /**
     * Partitions ways into connected components based on shared endpoint nodes.
     */
    private static List<Set<Way>> findConnectedComponents(List<Way> allWays) {
        Map<Way, Way> parent = new HashMap<>();
        for (Way w : allWays) {
            parent.put(w, w);
        }

        // Map each endpoint node to all ways that touch it
        Map<Node, List<Way>> endpointWays = new HashMap<>();
        for (Way way : allWays) {
            endpointWays.computeIfAbsent(way.firstNode(), k -> new ArrayList<>()).add(way);
            endpointWays.computeIfAbsent(way.lastNode(), k -> new ArrayList<>()).add(way);
        }

        // Union all ways sharing an endpoint
        for (List<Way> group : endpointWays.values()) {
            for (int i = 1; i < group.size(); i++) {
                union(parent, group.get(0), group.get(i));
            }
        }

        // Collect components
        Map<Way, Set<Way>> componentMap = new HashMap<>();
        for (Way way : allWays) {
            Way root = find(parent, way);
            componentMap.computeIfAbsent(root, k -> new HashSet<>()).add(way);
        }

        return new ArrayList<>(componentMap.values());
    }

    private static Way find(Map<Way, Way> parent, Way w) {
        while (!parent.get(w).equals(w)) {
            parent.put(w, parent.get(parent.get(w)));
            w = parent.get(w);
        }
        return w;
    }

    private static void union(Map<Way, Way> parent, Way a, Way b) {
        Way rootA = find(parent, a);
        Way rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }

    // ---- Bridging inner reclassification ----

    /**
     * Attempts to fix degree violations in the outer endpoint graph by
     * reclassifying inner ways that bridge dangling outer endpoints.
     * Modifies the lists in place. Returns true if any reclassification happened.
     */
    private static boolean reclassifyBridgingInners(List<Way> outers, List<Way> inners) {
        Map<Node, Integer> degree = new HashMap<>();
        for (Way w : outers) {
            if (!w.isClosed()) {
                degree.merge(w.firstNode(), 1, Integer::sum);
                degree.merge(w.lastNode(), 1, Integer::sum);
            }
        }

        Set<Node> dangling = new HashSet<>();
        for (Map.Entry<Node, Integer> e : degree.entrySet()) {
            if (e.getValue() != 2) {
                dangling.add(e.getKey());
            }
        }

        if (dangling.isEmpty()) return false;

        boolean changed = false;
        Iterator<Way> it = inners.iterator();
        while (it.hasNext()) {
            Way inner = it.next();
            Node first = inner.firstNode();
            Node last = inner.lastNode();
            if (dangling.contains(first) && dangling.contains(last)) {
                it.remove();
                outers.add(inner);
                degree.merge(first, 1, Integer::sum);
                degree.merge(last, 1, Integer::sum);
                if (degree.getOrDefault(first, 0) == 2) dangling.remove(first);
                if (degree.getOrDefault(last, 0) == 2) dangling.remove(last);
                changed = true;
            }
        }

        return changed;
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
