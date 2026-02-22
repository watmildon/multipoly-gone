package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.tools.Geometry;

public class MultipolygonAnalyzer {

    public enum FixOpType {
        /** Chain open outer ways into closed rings within the relation. */
        CONSOLIDATE_RINGS,
        /** Decompose self-intersecting rings into non-self-intersecting sub-rings. */
        DECOMPOSE_SELF_INTERSECTIONS,
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

    /**
     * Result of decomposing a self-intersecting ring into non-self-intersecting sub-rings.
     */
    public static class DecomposedRing {
        private final WayChainBuilder.Ring originalRing;
        private final List<WayChainBuilder.Ring> subRings;
        private final List<Node> newIntersectionNodes;

        DecomposedRing(WayChainBuilder.Ring originalRing, List<WayChainBuilder.Ring> subRings,
                List<Node> newIntersectionNodes) {
            this.originalRing = originalRing;
            this.subRings = subRings;
            this.newIntersectionNodes = newIntersectionNodes;
        }

        public WayChainBuilder.Ring getOriginalRing() { return originalRing; }
        public List<WayChainBuilder.Ring> getSubRings() { return subRings; }
        public List<Node> getNewIntersectionNodes() { return newIntersectionNodes; }
    }

    public static class FixOp {
        private final FixOpType type;
        private final List<WayChainBuilder.Ring> rings;
        private final List<List<Node>> mergedWays;
        private final List<ComponentResult> components;
        private final List<DecomposedRing> decomposedRings;
        private final List<Node> newNodes;

        FixOp(FixOpType type, List<WayChainBuilder.Ring> rings, List<List<Node>> mergedWays) {
            this(type, rings, mergedWays, null);
        }

        FixOp(FixOpType type, List<WayChainBuilder.Ring> rings, List<List<Node>> mergedWays, List<Node> newNodes) {
            this.type = type;
            this.rings = rings;
            this.mergedWays = mergedWays;
            this.components = null;
            this.decomposedRings = null;
            this.newNodes = newNodes;
        }

        FixOp(FixOpType type, List<ComponentResult> components) {
            this.type = type;
            this.rings = null;
            this.mergedWays = null;
            this.components = components;
            this.decomposedRings = null;
            this.newNodes = null;
        }

        FixOp(FixOpType type, List<DecomposedRing> decomposedRings, @SuppressWarnings("unused") Void marker) {
            this.type = type;
            this.rings = null;
            this.mergedWays = null;
            this.components = null;
            this.decomposedRings = decomposedRings;
            this.newNodes = null;
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

        public List<DecomposedRing> getDecomposedRings() {
            return decomposedRings;
        }

        public List<Node> getNewNodes() {
            return newNodes;
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
        // and at least one component is actually fixable
        if (outerComponents.size() < 2) {
            return null;
        }
        boolean anyNonNull = results.stream().anyMatch(r -> r != null);
        if (!anyNonNull) {
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

        // Skip if any outer ring is nested inside another — ambiguous mapping error
        if (hasNestedOuters(outerRings)) {
            return null;
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

        // Check all outer rings for self-intersections and decompose
        List<DecomposedRing> decompositions = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            DecomposedRing decomp = decomposeIfSelfIntersecting(ring);
            if (decomp != null) {
                // Check node limits on sub-rings
                boolean withinLimits = true;
                for (WayChainBuilder.Ring sub : decomp.getSubRings()) {
                    if (sub.getNodes().size() - 1 > MAX_WAY_NODES) {
                        withinLimits = false;
                        break;
                    }
                }
                if (withinLimits) {
                    decompositions.add(decomp);
                }
            }
        }
        if (!decompositions.isEmpty()) {
            ops.add(new FixOp(FixOpType.DECOMPOSE_SELF_INTERSECTIONS, decompositions, null));
            // Replace decomposed rings with their sub-rings in outerRings
            for (DecomposedRing d : decompositions) {
                outerRings.remove(d.getOriginalRing());
                outerRings.addAll(d.getSubRings());
            }
            int totalSubs = decompositions.stream()
                .mapToInt(d -> d.getSubRings().size())
                .sum();
            descParts.add(totalSubs + " sub-rings from decomposition");
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
            MergeResult mergeResult = tryTouchingInnerMerge(retained.get(0), retainedInners.get(0).getNodes());
            if (mergeResult != null) {
                for (List<Node> way : mergeResult.mergedWays) {
                    if (way.size() - 1 > MAX_WAY_NODES) {
                        if (ops.isEmpty()) return null;
                        return new ComponentResult(outerWays, innerWays, ops, false,
                            retained, retainedInners, String.join(", ", descParts));
                    }
                }
                ops.add(new FixOp(FixOpType.TOUCHING_INNER_MERGE, null, mergeResult.mergedWays, mergeResult.newNodes));
                if (mergeResult.mergedWays.size() == 1) {
                    descParts.add("touching inner merged");
                } else {
                    descParts.add("inner split into " + mergeResult.mergedWays.size() + " ways");
                }
                return new ComponentResult(outerWays, innerWays, ops, true,
                    null, null, String.join(", ", descParts));
            }

            // Inner doesn't touch outer — relation must be kept with this outer+inner pair
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
    private static MergeResult tryTouchingInnerMerge(WayChainBuilder.Ring outerRing, List<Node> innerNodes) {
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
        // When 2 segments have edges that cross each other, the inner ring is
        // self-intersecting (e.g., a "bridging" inner that divides the outer into
        // two disconnected regions). We need to insert intersection nodes and
        // build the merged ways by tracing through those nodes.
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
        List<Integer> outerIdxAs = new ArrayList<>();
        List<Integer> outerIdxBs = new ArrayList<>();

        for (int s = 0; s < innerOnlySegments.size(); s++) {
            Node boundaryA = segStartBoundary.get(s);
            Node boundaryB = segEndBoundary.get(s);

            Integer outerIdxA = outerIndexMap.get(boundaryA);
            Integer outerIdxB = outerIndexMap.get(boundaryB);
            if (outerIdxA == null || outerIdxB == null) {
                return null;
            }
            outerIdxAs.add(outerIdxA);
            outerIdxBs.add(outerIdxB);

            Set<Node> otherBoundaries = new HashSet<>(allBoundaryNodes);
            otherBoundaries.remove(boundaryA);
            otherBoundaries.remove(boundaryB);

            List<Node> fwd = computeDirectionalPath(outerNodes, outerSize, outerIdxB, outerIdxA, otherBoundaries, true);
            List<Node> bwd = computeDirectionalPath(outerNodes, outerSize, outerIdxB, outerIdxA, otherBoundaries, false);
            forwardPaths.add(fwd);
            backwardPaths.add(bwd);
        }

        // Choose outer path directions so that together they cover all outer-only nodes.
        // For each segment, pick forward or backward; then verify full coverage.
        List<List<Node>> chosenPaths = chooseComplementaryPaths(
            outerNodes, outerSize, sharedSet, allBoundaryNodes,
            forwardPaths, backwardPaths, innerOnlySegments.size());

        if (chosenPaths == null) {
            return null;
        }

        for (int s = 0; s < innerOnlySegments.size(); s++) {
            Node boundaryA = segStartBoundary.get(s);
            Node boundaryB = segEndBoundary.get(s);
            List<Node> innerSegment = innerOnlySegments.get(s);
            List<Node> outerPath = chosenPaths.get(s);

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

        // Validate geometric consistency: for the 2-segment case, try both pairings
        // and pick the one where the merged ways form simple (non-self-intersecting) polygons.
        // A correct pairing produces two simple polygons whose absolute signed areas sum
        // to the full outer-inner region. A wrong pairing produces self-intersecting ways
        // with smaller absolute areas (the crossed lobes partially cancel in the shoelace sum).
        if (result.size() == 2 && innerOnlySegments.size() == 2) {
            double currentAreaSum = Math.abs(computeSignedArea(result.get(0)))
                                  + Math.abs(computeSignedArea(result.get(1)));

            // Build alternative pairing with swapped outer paths
            List<List<Node>> altResult = new ArrayList<>();
            List<List<Node>> altPaths = new ArrayList<>();
            for (int s = 0; s < 2; s++) {
                List<Node> fwd = forwardPaths.get(s);
                List<Node> bwd = backwardPaths.get(s);
                List<Node> current = chosenPaths.get(s);
                altPaths.add((current == fwd) ? bwd : fwd);
            }
            boolean altValid = true;
            for (int s = 0; s < 2; s++) {
                Node bndA = segStartBoundary.get(s);
                Node bndB = segEndBoundary.get(s);
                List<Node> innerSeg = innerOnlySegments.get(s);
                List<Node> altOuter = altPaths.get(s);
                if (altOuter == null) { altValid = false; break; }
                List<Node> way = new ArrayList<>();
                way.add(bndA);
                way.addAll(innerSeg);
                way.add(bndB);
                for (int i = 1; i < altOuter.size(); i++) {
                    way.add(altOuter.get(i));
                }
                if (!way.get(way.size() - 1).equals(way.get(0))) {
                    way.add(bndA);
                }
                altResult.add(way);
            }

            if (altValid) {
                double altAreaSum = Math.abs(computeSignedArea(altResult.get(0)))
                                  + Math.abs(computeSignedArea(altResult.get(1)));
                if (altAreaSum > currentAreaSum) {
                    // Alternative pairing produces larger total area = correct simple polygons
                    result.clear();
                    result.addAll(altResult);
                    for (int s = 0; s < 2; s++) {
                        chosenPaths.set(s, altPaths.get(s));
                    }
                }
            }
        }

        // When 3+ consecutive shared nodes exist between two boundaries, the outer path
        // prefers the outer-only direction, skipping intermediate shared nodes. Walk the
        // outer ring to find runs of non-boundary shared nodes that aren't covered by any
        // merged way, and produce additional closed ways for them.
        for (int s = 0; s < innerOnlySegments.size(); s++) {
            Node bStart = segStartBoundary.get(s); // last shared before inner-only
            Node bEnd = segEndBoundary.get(s);     // first shared after inner-only

            Integer outerIdxStart = outerIndexMap.get(bStart);
            Integer outerIdxEnd = outerIndexMap.get(bEnd);

            // Walk outer from bEnd toward bStart through shared nodes.
            // Only produce an extra way if there are intermediate shared nodes
            // that are NOT boundary nodes of other segments (those are already covered).
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
                // Build way: bEnd → shared intermediates → bStart → inner(reversed) → bEnd
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

        return new MergeResult(result, null);
    }

    /**
     * Cross-segment intersection node for bridging inner merges.
     * Records which edge from each of the two segments crosses.
     */
    private static class InnerCrossing {
        final int segAIdx;      // segment index (0 or 1)
        final int segAEdge;     // edge index within the full segment (including boundary nodes)
        final int segBIdx;      // segment index (0 or 1)
        final int segBEdge;     // edge index within the full segment
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
     * the inner ring "bridges" across the outer polygon and divides it into two regions.
     * This method detects such crossings, inserts new intersection nodes, and builds
     * merged ways that trace the boundaries of each region correctly.
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

        // Build full segments including boundary nodes: [startBoundary, ...innerOnly..., endBoundary]
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
                    if (isNear(intersection, p1, tol) || isNear(intersection, p2, tol)
                        || isNear(intersection, p3, tol) || isNear(intersection, p4, tol)) {
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

        // Insert crossing nodes into both segments.
        // For each segment, collect the crossings that affect it, sorted by edge index
        // and parameter along that edge.
        List<Node> augSeg0 = buildAugmentedSegment(seg0, crossings, 0);
        List<Node> augSeg1 = buildAugmentedSegment(seg1, crossings, 1);

        // Now build the two merged ways by tracing through the intersection nodes.
        // Each way follows one outer path and then traces through both inner segments,
        // switching at each intersection node.
        //
        // The two shared boundary nodes are the same for both segments (just in opposite order):
        // Segment 0: startBoundary[0] -> ... -> endBoundary[0]
        // Segment 1: startBoundary[1] -> ... -> endBoundary[1]
        // where endBoundary[0] == startBoundary[1] and endBoundary[1] == startBoundary[0]
        // (since there are exactly 2 shared nodes and 2 segments between them).

        Node sharedA = segStartBoundary.get(0); // = segEndBoundary.get(1)
        Node sharedB = segEndBoundary.get(0);   // = segStartBoundary.get(1)

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

        // Build the inner path for each way by tracing through both augmented segments,
        // switching between them at intersection nodes.
        // There are two possible pairings: inner trace starting on seg0-reversed or seg1.
        // We try both and pick the pairing where the first inner-only node of each path
        // is geometrically on the same side as the corresponding outer path.
        List<Node> innerPathA = traceInnerPath(augSeg0, augSeg1, sharedB, sharedA, newNodes, false);
        List<Node> innerPathB = traceInnerPath(augSeg0, augSeg1, sharedB, sharedA, newNodes, true);

        if (innerPathA == null || innerPathB == null) {
            return null;
        }

        // Build candidate ways for both possible pairings and pick the one with
        // correct geometry (both ways have positive signed area = CCW winding).
        // Pairing 1: outerFwd + innerPathA, outerBwd + innerPathB
        // Pairing 2: outerFwd + innerPathB, outerBwd + innerPathA
        for (int pairing = 0; pairing < 2; pairing++) {
            List<Node> innerForFwd = (pairing == 0) ? innerPathA : innerPathB;
            List<Node> innerForBwd = (pairing == 0) ? innerPathB : innerPathA;

            List<Node> way1 = new ArrayList<>();
            for (Node n : outerPathFwd) {
                way1.add(n);
            }
            for (int i = 1; i < innerForFwd.size(); i++) {
                way1.add(innerForFwd.get(i));
            }
            if (!way1.get(way1.size() - 1).equals(way1.get(0))) {
                way1.add(sharedA);
            }

            List<Node> way2 = new ArrayList<>();
            for (Node n : outerPathBwd) {
                way2.add(n);
            }
            for (int i = 1; i < innerForBwd.size(); i++) {
                way2.add(innerForBwd.get(i));
            }
            if (!way2.get(way2.size() - 1).equals(way2.get(0))) {
                way2.add(sharedA);
            }

            // Check if both ways have valid geometry (non-self-intersecting).
            // Use signed area: if both are positive (CCW) or both negative (CW),
            // this pairing is geometrically consistent.
            double area1 = computeSignedArea(way1);
            double area2 = computeSignedArea(way2);
            if ((area1 > 0 && area2 > 0) || (area1 < 0 && area2 < 0)) {
                return new MergeResult(List.of(way1, way2), newNodes);
            }
        }

        // Neither pairing produced consistent geometry — fall back to null
        return null;
    }

    /**
     * Inserts crossing nodes into a segment's node list at the appropriate positions.
     * Crossings are sorted by edge index and parameter along the edge.
     */
    private static List<Node> buildAugmentedSegment(List<Node> segment, List<InnerCrossing> crossings, int segIdx) {
        // Collect crossings for this segment, keyed by edge index
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
                // Sort by parameter along the edge (distance from start node)
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
     * Traces an inner path from one shared node to another through augmented inner segments,
     * switching between segments at intersection nodes.
     *
     * @param augSeg0 augmented segment 0 (from sharedA to sharedB)
     * @param augSeg1 augmented segment 1 (from sharedB to sharedA)
     * @param fromNode starting shared node
     * @param toNode ending shared node
     * @param crossingNodes set of intersection nodes where we switch segments
     * @param startWithSeg1 if true, start tracing on segment 1; if false, start on segment 0
     * @return path from fromNode to toNode
     */
    private static List<Node> traceInnerPath(
            List<Node> augSeg0, List<Node> augSeg1,
            Node fromNode, Node toNode,
            List<Node> crossingNodes, boolean startWithSeg1) {

        Set<Node> crossingSet = new HashSet<>(crossingNodes);

        // augSeg0 goes from sharedA to sharedB; augSeg1 goes from sharedB to sharedA.
        // We need to trace from fromNode (=sharedB) to toNode (=sharedA).
        // Segment 1 goes sharedB -> sharedA (forward direction for our purpose).
        // Segment 0 goes sharedA -> sharedB, so reversed it goes sharedB -> sharedA.

        // Build reversed augSeg0 for walking from sharedB to sharedA
        List<Node> augSeg0Rev = new ArrayList<>(augSeg0);
        java.util.Collections.reverse(augSeg0Rev);

        // We have two paths from sharedB to sharedA:
        // Path via seg1 (forward): augSeg1
        // Path via seg0 (reversed): augSeg0Rev
        // Both start at sharedB and end at sharedA.

        // The tracing algorithm: start on one path, walk until hitting a crossing node,
        // then switch to the other path (find the crossing node in the other path and
        // continue from there).

        List<Node> pathA = startWithSeg1 ? augSeg1 : augSeg0Rev;
        List<Node> pathB = startWithSeg1 ? augSeg0Rev : augSeg1;

        List<Node> result = new ArrayList<>();
        List<Node> currentPath = pathA;
        List<Node> otherPath = pathB;

        // Find starting position: fromNode should be the first node
        int pos = 0;
        if (!currentPath.get(0).equals(fromNode)) {
            return null;
        }
        result.add(fromNode);
        pos = 1;

        while (pos < currentPath.size()) {
            Node n = currentPath.get(pos);
            result.add(n);
            if (n.equals(toNode)) {
                return result;
            }
            if (crossingSet.contains(n)) {
                // Switch to the other path: find this crossing node in the other path
                int otherPos = -1;
                for (int i = 0; i < otherPath.size(); i++) {
                    if (otherPath.get(i).equals(n)) {
                        otherPos = i;
                        break;
                    }
                }
                if (otherPos == -1) {
                    return null;
                }
                // Swap paths
                List<Node> temp = currentPath;
                currentPath = otherPath;
                otherPath = temp;
                pos = otherPos + 1;
            } else {
                pos++;
            }
        }

        return null; // Didn't reach toNode
    }

    /**
     * Computes the signed area of a closed polygon using the shoelace formula.
     * Positive = counterclockwise, negative = clockwise.
     * Uses EastNorth (projected) coordinates for accuracy.
     */
    private static double computeSignedArea(List<Node> way) {
        double area = 0;
        int n = way.size() - 1; // exclude closing node
        for (int i = 0; i < n; i++) {
            EastNorth curr = way.get(i).getEastNorth();
            EastNorth next = way.get((i + 1) % n).getEastNorth();
            area += curr.east() * next.north() - next.east() * curr.north();
        }
        return area / 2.0;
    }

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
     * Chooses outer path directions for all segments so that together they cover
     * all outer-only nodes (nodes not in sharedSet and not boundary nodes).
     * Falls back to the old heuristic (prefer outer-only, then shorter) when
     * complementary coverage isn't needed or can't be achieved.
     */
    private static List<List<Node>> chooseComplementaryPaths(
            List<Node> outerNodes, int outerSize, Set<Node> sharedSet, Set<Node> allBoundaryNodes,
            List<List<Node>> forwardPaths, List<List<Node>> backwardPaths, int segCount) {

        // Collect all outer-only nodes that need to be covered
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

        // Check if all outer-only nodes are covered by the chosen paths
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

            // Try swapping this segment
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
            // Revert if no improvement
            chosen.set(s, current);
        }

        // Couldn't achieve full coverage — return the default choices
        // (some outer nodes may be covered by the shared-intermediate-node logic below)
        return chosen;
    }

    private static List<Node> findOuterPath(List<Node> outerNodes, int outerSize,
            int fromIdx, int toIdx, Set<Node> forbidden, Set<Node> sharedSet) {
        List<Node> forward = computeDirectionalPath(outerNodes, outerSize, fromIdx, toIdx, forbidden, true);
        List<Node> backward = computeDirectionalPath(outerNodes, outerSize, fromIdx, toIdx, forbidden, false);

        boolean forwardValid = forward != null;
        boolean backwardValid = backward != null;

        if (forwardValid && backwardValid) {
            boolean forwardHasOuterOnly = forward.stream().anyMatch(n -> !sharedSet.contains(n));
            boolean backwardHasOuterOnly = backward.stream().anyMatch(n -> !sharedSet.contains(n));
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

    /**
     * Returns true if any outer ring is geometrically contained within another outer ring.
     * This indicates an ambiguous mapping error (likely a missing inner role).
     */
    private static boolean hasNestedOuters(List<WayChainBuilder.Ring> outerRings) {
        if (outerRings.size() < 2) {
            return false;
        }
        for (int i = 0; i < outerRings.size(); i++) {
            WayChainBuilder.Ring candidate = outerRings.get(i);
            for (int j = 0; j < outerRings.size(); j++) {
                if (i == j) continue;
                WayChainBuilder.Ring container = outerRings.get(j);
                Set<Node> containerNodes = new HashSet<>(container.getNodes());
                for (Node testNode : candidate.getNodes()) {
                    if (containerNodes.contains(testNode)) {
                        continue;
                    }
                    if (Geometry.nodeInsidePolygon(testNode, container.getNodes())) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    // ---- Self-intersection detection and decomposition ----

    private static class Crossing {
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

                // Skip if segments share any endpoint node (not a true crossing)
                if (ringNodes.get(i) == ringNodes.get(j) || ringNodes.get(i) == ringNodes.get(j + 1)
                    || ringNodes.get(i + 1) == ringNodes.get(j) || ringNodes.get(i + 1) == ringNodes.get(j + 1)) {
                    continue;
                }

                EastNorth intersection = Geometry.getSegmentSegmentIntersection(p1, p2, p3, p4);
                if (intersection != null) {
                    // Verify the intersection is not at a segment endpoint (tolerance-based)
                    double tol = 1e-6;
                    if (isNear(intersection, p1, tol) || isNear(intersection, p2, tol)
                        || isNear(intersection, p3, tol) || isNear(intersection, p4, tol)) {
                        continue;
                    }
                    crossings.add(new Crossing(i, j, intersection));
                }
            }
        }
        return crossings;
    }

    private static boolean isNear(EastNorth a, EastNorth b, double tol) {
        return Math.abs(a.east() - b.east()) < tol && Math.abs(a.north() - b.north()) < tol;
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
            // Create nodes at crossing points
            List<Node> newNodes = new ArrayList<>();
            for (Crossing c : crossings) {
                LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(c.point);
                c.node = new Node(ll);
                newNodes.add(c.node);
            }

            // Build augmented ring with crossing nodes inserted
            List<List<Node>> subRingNodeLists = splitAtCrossings(ringNodes, crossings);
            if (subRingNodeLists != null && subRingNodeLists.size() > 1) {
                List<WayChainBuilder.Ring> subRings = new ArrayList<>();
                for (List<Node> subNodes : subRingNodeLists) {
                    subRings.add(new WayChainBuilder.Ring(subNodes, ring.getSourceWays()));
                }
                return new DecomposedRing(ring, subRings, newNodes);
            }
        }

        // Then check for vertex-touching: a node that appears more than once in the ring
        // (e.g., a bowtie that meets at a shared node rather than crossing segments)
        DecomposedRing vertexDecomp = decomposeAtRepeatedNodes(ring);
        if (vertexDecomp != null) {
            return vertexDecomp;
        }

        return null;
    }

    /**
     * Decomposes a ring at repeated nodes (vertices that appear more than once).
     * This handles bowties where two loops share a vertex without segments crossing.
     * Returns null if no repeated nodes are found.
     */
    static DecomposedRing decomposeAtRepeatedNodes(WayChainBuilder.Ring ring) {
        List<Node> ringNodes = ring.getNodes();
        int ringSize = ringNodes.size() - 1; // exclude closing duplicate

        // Find all nodes that appear more than once
        Map<Node, List<Integer>> occurrences = new HashMap<>();
        for (int i = 0; i < ringSize; i++) {
            occurrences.computeIfAbsent(ringNodes.get(i), k -> new ArrayList<>()).add(i);
        }

        // Collect repeated nodes (appear 2+ times)
        List<Node> repeatedNodes = new ArrayList<>();
        for (Map.Entry<Node, List<Integer>> entry : occurrences.entrySet()) {
            if (entry.getValue().size() >= 2) {
                repeatedNodes.add(entry.getKey());
            }
        }

        if (repeatedNodes.isEmpty()) {
            return null;
        }

        // Split at the first repeated node
        Node splitNode = repeatedNodes.get(0);
        List<Integer> positions = occurrences.get(splitNode);
        int p1 = positions.get(0);
        int p2 = positions.get(1);

        // Sub-ring 1: from p1 to p2 (inclusive) — already closed because
        // ringNodes[p1] and ringNodes[p2] are the same (repeated) node
        List<Node> sub1 = new ArrayList<>();
        for (int i = p1; i <= p2; i++) {
            sub1.add(ringNodes.get(i));
        }

        // Sub-ring 2: from p2 to end, wrap to start, up to p1 (inclusive) — already
        // closed because ringNodes[p2] and ringNodes[p1] are the same node
        List<Node> sub2 = new ArrayList<>();
        for (int i = p2; i < ringSize; i++) {
            sub2.add(ringNodes.get(i));
        }
        for (int i = 0; i <= p1; i++) {
            sub2.add(ringNodes.get(i));
        }

        if (sub1.size() < 4 || sub2.size() < 4) {
            return null; // degenerate sub-rings
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

        // No new intersection nodes needed — the repeated node already exists
        return new DecomposedRing(ring, finalRings, new ArrayList<>());
    }

    /**
     * Splits a ring at crossing points into multiple non-self-intersecting sub-rings.
     *
     * Algorithm:
     * 1. Group crossings by segment and sort by parametric distance along each segment.
     * 2. Build an augmented node list with crossing nodes inserted.
     * 3. Record each crossing node's two positions in the augmented list.
     * 4. Trace sub-rings by walking the augmented list and switching at crossing nodes.
     */
    static List<List<Node>> splitAtCrossings(List<Node> ringNodes, List<Crossing> crossings) {
        int segCount = ringNodes.size() - 1;

        // For each segment, collect the crossings that intersect it, with parametric t
        // A crossing at segmentA intersects that segment, and also at segmentB
        @SuppressWarnings("unchecked")
        List<double[]>[] segCrossings = new List[segCount];
        for (int i = 0; i < segCount; i++) {
            segCrossings[i] = new ArrayList<>();
        }

        for (int ci = 0; ci < crossings.size(); ci++) {
            Crossing c = crossings.get(ci);

            // Parametric t on segmentA
            EastNorth a1 = ringNodes.get(c.segmentA).getEastNorth();
            EastNorth a2 = ringNodes.get(c.segmentA + 1).getEastNorth();
            double tA = paramT(a1, a2, c.point);
            segCrossings[c.segmentA].add(new double[]{tA, ci});

            // Parametric t on segmentB
            EastNorth b1 = ringNodes.get(c.segmentB).getEastNorth();
            EastNorth b2 = ringNodes.get(c.segmentB + 1).getEastNorth();
            double tB = paramT(b1, b2, c.point);
            segCrossings[c.segmentB].add(new double[]{tB, ci});
        }

        // Sort each segment's crossings by t
        for (int i = 0; i < segCount; i++) {
            segCrossings[i].sort((a, b) -> Double.compare(a[0], b[0]));
        }

        // Build augmented node list: original nodes with crossing nodes inserted
        List<Object> augmented = new ArrayList<>(); // Node or crossing-index marker
        // Also track which positions in augmented belong to each crossing node
        // Each crossing node appears twice (once per segment)
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
        // Close: the augmented ring wraps back to position 0
        // (don't add the closing node — we handle wrap via modular arithmetic)

        int augLen = augmented.size();
        Set<Node> crossingNodeSet = new HashSet<>();
        for (Crossing c : crossings) {
            crossingNodeSet.add(c.node);
        }

        // Build a map from crossing Node -> its two positions
        Map<Node, int[]> nodePositions = new HashMap<>();
        for (int ci = 0; ci < crossings.size(); ci++) {
            nodePositions.put(crossings.get(ci).node, crossingPositions[ci]);
        }

        // Trace sub-rings
        boolean[] usedEdge = new boolean[augLen]; // edge from pos to (pos+1)%augLen
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

                // If the next node is a crossing node, switch to its other occurrence
                Node nextNode = (Node) augmented.get(nextPos);
                if (crossingNodeSet.contains(nextNode)) {
                    int[] positions = nodePositions.get(nextNode);
                    // nextPos is one of the two positions; switch to the other
                    if (nextPos == positions[0]) {
                        pos = positions[1];
                    } else {
                        pos = positions[0];
                    }
                } else {
                    pos = nextPos;
                }
            } while (pos != start);

            if (!valid || subRing.size() < 3) continue;

            // Close the sub-ring
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

    static String getPrimaryTag(Relation relation) {
        for (String key : relation.getKeys().keySet()) {
            if (!"type".equals(key) && !key.startsWith("_")) {
                return key + "=" + relation.get(key);
            }
        }
        return "multipolygon";
    }
}
