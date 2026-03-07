package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Logging;

public class MultipolygonAnalyzer {

    public enum FixOpType {
        /** Chain open outer ways into closed rings within the relation. */
        CONSOLIDATE_RINGS,
        /** Merge abutting closed inner rings sharing edges into fewer larger rings. */
        CONSOLIDATE_INNERS,
        /** Decompose self-intersecting rings into non-self-intersecting sub-rings. */
        DECOMPOSE_SELF_INTERSECTIONS,
        /** Extract edge-sharing tagged inner rings from the relation as standalone ways. */
        EXTRACT_INNERS,
        /** Extract standalone outer rings (no inners) as independent tagged ways. */
        EXTRACT_OUTERS,
        /** Push relation tags to remaining outer ways, delete relation. */
        DISSOLVE,
        /** Merge 1 outer + 1 inner sharing nodes into closed way(s), delete relation. */
        TOUCHING_INNER_MERGE,
        /** Split relation into disconnected components, each analyzed independently. */
        SPLIT_RELATION
    }

    /** Reason why a multipolygon/boundary relation was not fixable. */
    public enum SkipReason {
        INCOMPLETE_MEMBERS("Has incomplete members",
            "Download the full relation to see all geometry"),
        NON_WAY_MEMBER("Contains non-Way members",
            "Remove node/relation members or reclassify them"),
        DELETED_OR_INCOMPLETE_WAY("Contains deleted, incomplete, or degenerate ways",
            "Fix or remove the problematic way"),
        UNEXPECTED_WAY_ROLE("Way member has unexpected role",
            "Way members should have role 'outer', 'inner', or empty"),
        NO_OUTER_WAYS("No outer ways",
            "Add outer members to the relation"),
        IDENTITY_PROTECTED_MULTI_COMPONENT("Identity-protected relation with disconnected components",
            "This named/ref'd feature cannot be auto-split into separate relations"),
        SINGLE_COMPONENT_NOT_FIXABLE("Single component, no simplification possible",
            "Outer ways do not form valid closed rings"),
        OUTERS_CANT_FORM_RINGS("Outer ways cannot form closed rings",
            "Check way connectivity \u2014 endpoints may not match"),
        CONSOLIDATED_RING_TOO_LARGE("Consolidated ring would exceed 2000 nodes",
            "The merged way would be too large for OSM"),
        NESTED_OUTER_RINGS("Nested outer rings detected (mapping error)",
            "One outer ring is entirely inside another \u2014 fix the role assignments"),
        BOUNDARY_NO_OPS("Boundary relation with nothing to consolidate",
            "All ways are already closed \u2014 no action needed"),
        IDENTITY_PROTECTED_NO_OPS("Identity-protected relation with no consolidation ops",
            "Relation has multiple disjoint outers but identity tags prevent splitting"),
        INNER_WAYS_CANT_FORM_RINGS("Inner ways cannot form closed rings",
            "Check inner way connectivity"),
        INNER_RING_TOO_LARGE("Inner ring would exceed 2000 nodes",
            "The consolidated inner way would be too large for OSM"),
        INNER_NOT_CONTAINED_IN_OUTER("Inner ring not spatially contained in any outer",
            "An inner ring lies outside all outer rings \u2014 fix the geometry"),
        NO_OPERATIONS_APPLICABLE("No simplification operations apply",
            "The relation structure is too complex for automatic simplification"),
        MULTI_COMPONENT_SPLIT_NOT_WORTHWHILE("Multi-component split has no fixable components",
            "Components could be split but none simplify individually"),
        HAS_PARENT_RELATION("Referenced by parent relation",
            "This relation is a member of another relation and cannot be dissolved or split");

        private final String message;
        private final String hint;

        SkipReason(String message, String hint) {
            this.message = message;
            this.hint = hint;
        }

        public String getMessage() { return message; }
        public String getHint() { return hint; }
    }

    /** A relation that was analyzed but not fixable, with the reason why. */
    public static class SkipResult {
        private final Relation relation;
        private final SkipReason reason;
        private final String detail;

        SkipResult(Relation relation, SkipReason reason, String detail) {
            this.relation = relation;
            this.reason = reason;
            this.detail = detail;
        }

        public Relation getRelation() { return relation; }
        public SkipReason getReason() { return reason; }
        public String getDetail() { return detail; }
    }

    /** Combined result of analyzing all relations: both fixable plans and skip reasons. */
    public static class AnalysisResult {
        private final List<FixPlan> fixPlans;
        private final List<SkipResult> skipResults;

        AnalysisResult(List<FixPlan> fixPlans, List<SkipResult> skipResults) {
            this.fixPlans = fixPlans;
            this.skipResults = skipResults;
        }

        public List<FixPlan> getFixPlans() { return fixPlans; }
        public List<SkipResult> getSkipResults() { return skipResults; }
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

        /**
         * Validates that this component result will not produce invalid multipolygon states.
         * @param relId identifier string for error messages
         */
        void validate(String relId) {
            // Non-dissolving component must have outer ways
            if (!dissolvesCompletely && outerWays.isEmpty()) {
                throw new IllegalStateException(
                    relId + ": non-dissolving component has no outer ways");
            }

            // Non-dissolving component with inners must have retained outers
            if (!dissolvesCompletely && !innerWays.isEmpty()) {
                if (retainedOuterRings == null || retainedOuterRings.isEmpty()) {
                    // Check if there are operations that account for all outers (e.g., all extracted)
                    // If so, the component would leave inners without any outer
                    boolean hasExtractOrDissolve = operations.stream()
                        .anyMatch(op -> op.getType() == FixOpType.EXTRACT_OUTERS
                            || op.getType() == FixOpType.DISSOLVE
                            || op.getType() == FixOpType.TOUCHING_INNER_MERGE);
                    if (!hasExtractOrDissolve) {
                        // No-op component retained as sub-relation — outerWays are present, this is fine
                    } else {
                        throw new IllegalStateException(
                            relId + ": non-dissolving component has inners but no retained outer rings");
                    }
                }
            }

            // Validate sub-operations
            for (FixOp op : operations) {
                switch (op.getType()) {
                    case CONSOLIDATE_RINGS ->
                        FixPlan.validateRingsClosed(op.getRings(), relId, "component CONSOLIDATE_RINGS");
                    case CONSOLIDATE_INNERS -> {
                        if (op.getConsolidatedInnerGroups() != null) {
                            for (ConsolidatedInnerGroup group : op.getConsolidatedInnerGroups()) {
                                FixPlan.validateRingsClosed(
                                    List.of(group.getMergedRing()), relId, "component CONSOLIDATE_INNERS");
                            }
                        }
                    }
                    case DECOMPOSE_SELF_INTERSECTIONS -> {
                        if (op.getDecomposedRings() != null) {
                            for (DecomposedRing decomp : op.getDecomposedRings()) {
                                FixPlan.validateRingsClosed(decomp.getSubRings(), relId, "component DECOMPOSE sub-ring");
                            }
                        }
                    }
                    case EXTRACT_INNERS ->
                        FixPlan.validateRingsClosed(op.getRings(), relId, "component EXTRACT_INNERS");
                    case TOUCHING_INNER_MERGE -> {
                        if (op.getMergedWays() != null) {
                            for (int i = 0; i < op.getMergedWays().size(); i++) {
                                List<Node> wayNodes = op.getMergedWays().get(i);
                                if (wayNodes.size() < 4) {
                                    throw new IllegalStateException(
                                        relId + ": component TOUCHING_INNER_MERGE way " + i
                                        + " has only " + wayNodes.size() + " nodes (minimum 4)");
                                }
                                if (!wayNodes.get(0).equals(wayNodes.get(wayNodes.size() - 1))) {
                                    throw new IllegalStateException(
                                        relId + ": component TOUCHING_INNER_MERGE way " + i + " is not closed");
                                }
                            }
                        }
                    }
                    default -> { }
                }
            }
        }

        /**
         * Returns a canonical fingerprint for this component for cross-run comparison.
         */
        String fingerprint() {
            TreeSet<Long> outerIds = new TreeSet<>();
            for (Way w : outerWays) outerIds.add(w.getUniqueId());
            TreeSet<Long> innerIds = new TreeSet<>();
            for (Way w : innerWays) innerIds.add(w.getUniqueId());
            StringBuilder sb = new StringBuilder();
            sb.append("{outers=").append(outerIds);
            sb.append(" inners=").append(innerIds);
            sb.append(" dissolves=").append(dissolvesCompletely);
            sb.append(" ops=[");
            for (int i = 0; i < operations.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(FixPlan.fingerprintOp(operations.get(i)));
            }
            sb.append("]}");
            return sb.toString();
        }
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

    /**
     * Result of merging abutting closed inner rings into a single larger ring.
     */
    public static class ConsolidatedInnerGroup {
        private final List<WayChainBuilder.Ring> sourceRings;
        private final WayChainBuilder.Ring mergedRing;

        ConsolidatedInnerGroup(List<WayChainBuilder.Ring> sourceRings, WayChainBuilder.Ring mergedRing) {
            this.sourceRings = sourceRings;
            this.mergedRing = mergedRing;
        }

        public List<WayChainBuilder.Ring> getSourceRings() { return sourceRings; }
        public WayChainBuilder.Ring getMergedRing() { return mergedRing; }
    }

    public static class FixOp {
        private final FixOpType type;
        private final List<WayChainBuilder.Ring> rings;
        private final List<List<Node>> mergedWays;
        private final List<ComponentResult> components;
        private final List<DecomposedRing> decomposedRings;
        private final List<ConsolidatedInnerGroup> consolidatedInnerGroups;
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
            this.consolidatedInnerGroups = null;
            this.newNodes = newNodes;
        }

        FixOp(FixOpType type, List<ComponentResult> components) {
            this.type = type;
            this.rings = null;
            this.mergedWays = null;
            this.components = components;
            this.decomposedRings = null;
            this.consolidatedInnerGroups = null;
            this.newNodes = null;
        }

        FixOp(FixOpType type, List<DecomposedRing> decomposedRings, @SuppressWarnings("unused") Void marker) {
            this.type = type;
            this.rings = null;
            this.mergedWays = null;
            this.components = null;
            this.decomposedRings = decomposedRings;
            this.consolidatedInnerGroups = null;
            this.newNodes = null;
        }

        FixOp(FixOpType type, List<ConsolidatedInnerGroup> groups, @SuppressWarnings("unused") int marker) {
            this.type = type;
            this.rings = null;
            this.mergedWays = null;
            this.components = null;
            this.decomposedRings = null;
            this.consolidatedInnerGroups = groups;
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

        public List<ConsolidatedInnerGroup> getConsolidatedInnerGroups() {
            return consolidatedInnerGroups;
        }

        public List<Node> getNewNodes() {
            return newNodes;
        }
    }

    public static class FixPlan {
        private final Relation relation;
        private final List<FixOp> operations;
        private final String description;
        private final boolean boundary;

        FixPlan(Relation relation, List<FixOp> operations, String description) {
            this(relation, operations, description, false);
        }

        FixPlan(Relation relation, List<FixOp> operations, String description, boolean boundary) {
            this.relation = relation;
            this.operations = operations;
            this.description = description;
            this.boundary = boundary;
        }

        public boolean isBoundary() {
            return boundary;
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
            if (boundary) return false;
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

        /** Returns the set of all member ways referenced by this plan's relation. */
        public Set<Way> getMemberWays() {
            Set<Way> ways = new HashSet<>();
            for (RelationMember member : relation.getMembers()) {
                if (member.isWay()) {
                    ways.add(member.getWay());
                }
            }
            return ways;
        }

        /**
         * Validates that this plan will not produce invalid multipolygon states.
         * Throws IllegalStateException if any invariant is violated.
         * Called during development to catch bugs early.
         */
        public void validate() {
            String relId = "relation " + relation.getUniqueId();

            for (FixOp op : operations) {
                switch (op.getType()) {
                    case CONSOLIDATE_RINGS -> validateRingsClosed(op.getRings(), relId, "CONSOLIDATE_RINGS");
                    case CONSOLIDATE_INNERS -> {
                        if (op.getConsolidatedInnerGroups() != null) {
                            for (ConsolidatedInnerGroup group : op.getConsolidatedInnerGroups()) {
                                validateRingsClosed(
                                    List.of(group.getMergedRing()), relId, "CONSOLIDATE_INNERS");
                            }
                        }
                    }
                    case DECOMPOSE_SELF_INTERSECTIONS -> {
                        if (op.getDecomposedRings() != null) {
                            for (DecomposedRing decomp : op.getDecomposedRings()) {
                                validateRingsClosed(decomp.getSubRings(), relId, "DECOMPOSE sub-ring");
                            }
                        }
                    }
                    case EXTRACT_INNERS -> validateRingsClosed(op.getRings(), relId, "EXTRACT_INNERS");
                    case TOUCHING_INNER_MERGE -> {
                        if (op.getMergedWays() != null) {
                            for (int i = 0; i < op.getMergedWays().size(); i++) {
                                List<Node> wayNodes = op.getMergedWays().get(i);
                                if (wayNodes.size() < 4) {
                                    throw new IllegalStateException(
                                        relId + ": TOUCHING_INNER_MERGE way " + i
                                        + " has only " + wayNodes.size() + " nodes (minimum 4)");
                                }
                                if (!wayNodes.get(0).equals(wayNodes.get(wayNodes.size() - 1))) {
                                    throw new IllegalStateException(
                                        relId + ": TOUCHING_INNER_MERGE way " + i + " is not closed");
                                }
                            }
                        }
                    }
                    case SPLIT_RELATION -> {
                        if (op.getComponents() != null) {
                            for (ComponentResult comp : op.getComponents()) {
                                if (comp != null) {
                                    comp.validate(relId);
                                }
                            }
                        }
                    }
                    default -> { }
                }
            }

            // If the relation survives, verify it will still have at least one outer
            if (!dissolvesRelation()) {
                validateSurvivingRelationHasOuters(relId);
            }
        }

        private void validateSurvivingRelationHasOuters(String relId) {
            // Count current outer members
            long outerCount = relation.getMembers().stream()
                .filter(m -> !"inner".equals(m.getRole()) && m.isWay())
                .count();

            // Subtract outers removed by EXTRACT_OUTERS
            for (FixOp op : operations) {
                if (op.getType() == FixOpType.EXTRACT_OUTERS && op.getRings() != null) {
                    // Each extracted ring removes its source ways from the relation
                    Set<Way> extractedSources = new HashSet<>();
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        extractedSources.addAll(ring.getSourceWays());
                    }
                    outerCount -= relation.getMembers().stream()
                        .filter(m -> !"inner".equals(m.getRole()) && m.isWay()
                            && extractedSources.contains(m.getWay()))
                        .count();
                }
            }

            if (outerCount <= 0) {
                throw new IllegalStateException(
                    relId + ": plan does not dissolve relation but would leave 0 outer members");
            }
        }

        private static void validateRingsClosed(List<WayChainBuilder.Ring> rings, String relId, String context) {
            if (rings == null) return;
            for (int i = 0; i < rings.size(); i++) {
                WayChainBuilder.Ring ring = rings.get(i);
                List<Node> nodes = ring.getNodes();
                if (nodes.size() < 4) {
                    throw new IllegalStateException(
                        relId + ": " + context + " ring " + i
                        + " has only " + nodes.size() + " nodes (minimum 4)");
                }
                if (!nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
                    throw new IllegalStateException(
                        relId + ": " + context + " ring " + i + " is not closed"
                        + " (first=" + nodes.get(0).getUniqueId()
                        + ", last=" + nodes.get(nodes.size() - 1).getUniqueId() + ")");
                }
            }
        }

        /**
         * Returns a canonical fingerprint string for this plan that can be compared
         * across multiple analysis runs to detect non-determinism. The fingerprint
         * captures the structural content (op types, way IDs, node IDs) in a sorted,
         * order-independent form so that semantically identical plans with different
         * internal ordering produce the same fingerprint.
         */
        public String fingerprint() {
            StringBuilder sb = new StringBuilder();
            sb.append("rel=").append(relation.getUniqueId());
            sb.append(" dissolves=").append(dissolvesRelation());
            sb.append(" ops=[");
            for (int i = 0; i < operations.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(fingerprintOp(operations.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }

        private static String fingerprintOp(FixOp op) {
            StringBuilder sb = new StringBuilder();
            sb.append(op.getType());
            switch (op.getType()) {
                case CONSOLIDATE_RINGS -> {
                    if (op.getRings() != null) {
                        sb.append(" rings=").append(fingerprintRings(op.getRings()));
                    }
                }
                case CONSOLIDATE_INNERS -> {
                    if (op.getConsolidatedInnerGroups() != null) {
                        List<String> gs = new ArrayList<>();
                        for (ConsolidatedInnerGroup g : op.getConsolidatedInnerGroups()) {
                            gs.add("sources=" + fingerprintRings(g.getSourceRings())
                                + " merged=" + fingerprintRing(g.getMergedRing()));
                        }
                        gs.sort(String::compareTo);
                        sb.append(" groups=").append(gs);
                    }
                }
                case DECOMPOSE_SELF_INTERSECTIONS -> {
                    if (op.getDecomposedRings() != null) {
                        // Sort decomposed rings by original ring's sorted source way IDs
                        List<String> decomps = new ArrayList<>();
                        for (DecomposedRing d : op.getDecomposedRings()) {
                            StringBuilder ds = new StringBuilder();
                            ds.append("orig=").append(fingerprintRing(d.getOriginalRing()));
                            ds.append(" subs=").append(fingerprintRings(d.getSubRings()));
                            ds.append(" newNodes=").append(d.getNewIntersectionNodes().size());
                            decomps.add(ds.toString());
                        }
                        decomps.sort(String::compareTo);
                        sb.append(" ").append(decomps);
                    }
                }
                case EXTRACT_INNERS, EXTRACT_OUTERS, DISSOLVE -> {
                    if (op.getRings() != null) {
                        sb.append(" rings=").append(fingerprintRings(op.getRings()));
                    }
                }
                case TOUCHING_INNER_MERGE -> {
                    if (op.getMergedWays() != null) {
                        // Fingerprint each merged way by its sorted node IDs
                        List<String> ways = new ArrayList<>();
                        for (List<Node> wayNodes : op.getMergedWays()) {
                            ways.add(fingerprintNodeList(wayNodes));
                        }
                        ways.sort(String::compareTo);
                        sb.append(" mergedWays=").append(ways);
                    }
                }
                case SPLIT_RELATION -> {
                    if (op.getComponents() != null) {
                        List<String> comps = new ArrayList<>();
                        for (ComponentResult c : op.getComponents()) {
                            comps.add(c != null ? c.fingerprint() : "null");
                        }
                        comps.sort(String::compareTo);
                        sb.append(" components=").append(comps);
                    }
                }
            }
            return sb.toString();
        }

        /**
         * Fingerprint a list of rings. Each ring is fingerprinted individually,
         * then the list is sorted for order-independence.
         */
        private static String fingerprintRings(List<WayChainBuilder.Ring> rings) {
            List<String> fps = new ArrayList<>();
            for (WayChainBuilder.Ring ring : rings) {
                fps.add(fingerprintRing(ring));
            }
            fps.sort(String::compareTo);
            return fps.toString();
        }

        /**
         * Fingerprint a single ring by its sorted source way IDs and node count.
         */
        private static String fingerprintRing(WayChainBuilder.Ring ring) {
            TreeSet<Long> wayIds = new TreeSet<>();
            for (Way w : ring.getSourceWays()) {
                wayIds.add(w.getUniqueId());
            }
            return "{ways=" + wayIds + " nodes=" + ring.getNodes().size() + "}";
        }

        /**
         * Fingerprint a node list by its ordered node IDs (order matters for geometry).
         */
        private static String fingerprintNodeList(List<Node> nodes) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < nodes.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(nodes.get(i).getUniqueId());
            }
            sb.append("]");
            return sb.toString();
        }
    }

    private static final int MAX_WAY_NODES = 2000;

    /**
     * Returns true if the debug mode preference is enabled.
     */
    static boolean isDebugMode() {
        return Config.getPref().getBoolean(MultipolyGonePreferences.PREF_DEBUG_MODE, false);
    }

    /**
     * Default tag key prefixes/patterns that indicate a relation has a unified
     * real-world identity and should not be dissolved into separate independent ways.
     * Semicolon-delimited. Supports prefix matching with trailing '*' (e.g. "name:*"
     * matches name:en, name:fr, etc.).
     */
    static final String DEFAULT_IDENTITY_TAGS =
        "name;name:*;alt_name;old_name;loc_name;short_name;official_name;reg_name;nat_name;int_name;"
        + "wikidata;wikipedia;ref;ref:*;"
        + "place;boundary;admin_level;protect_class;"
        + "operator;brand;owner";

    /**
     * Returns true if the relation has any tag key that matches the configured
     * identity tag patterns. Relations with identity tags represent a unified
     * real-world feature and should not be dissolved into separate ways.
     */
    static boolean hasIdentityTags(Relation relation) {
        Set<String> patterns = getIdentityTagPatterns();
        for (String key : relation.getKeys().keySet()) {
            if (matchesAnyPattern(key, patterns)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the set of identity tag patterns from preferences, falling back to defaults.
     */
    static Set<String> getIdentityTagPatterns() {
        Set<String> patterns = new HashSet<>();
        String pref = Config.getPref().get(
            MultipolyGonePreferences.PREF_IDENTITY_TAGS, DEFAULT_IDENTITY_TAGS);
        for (String tag : pref.split(";")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    /**
     * Returns true if the given key matches any of the patterns.
     * A pattern ending with '*' is treated as a prefix match.
     */
    private static boolean matchesAnyPattern(String key, Set<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (key.startsWith(prefix)) {
                    return true;
                }
            } else if (pattern.equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static List<FixPlan> findFixableRelations(DataSet dataSet) {
        return analyzeAll(dataSet, null).getFixPlans();
    }

    /**
     * Finds fixable relations in the dataset, optionally shuffling member order
     * to perturb HashMap/HashSet iteration order for non-determinism detection.
     * @param dataSet the dataset to analyze
     * @param rng if non-null, shuffles way lists before analysis to expose order-dependent bugs
     */
    public static List<FixPlan> findFixableRelations(DataSet dataSet, Random rng) {
        return analyzeAll(dataSet, rng).getFixPlans();
    }

    /**
     * Analyzes all multipolygon/boundary relations in the dataset, returning both
     * fixable plans and skip reasons for relations that cannot be fixed.
     */
    public static AnalysisResult analyzeAll(DataSet dataSet, Random rng) {
        boolean debug = isDebugMode();
        List<FixPlan> fixPlans = new ArrayList<>();
        List<SkipResult> skipResults = new ArrayList<>();
        if (dataSet == null) {
            return new AnalysisResult(fixPlans, skipResults);
        }

        List<Relation> relations = new ArrayList<>(dataSet.getRelations());
        if (rng != null) {
            Collections.shuffle(relations, rng);
        }

        for (Relation relation : relations) {
            if (relation.isDeleted() || relation.isIncomplete()) {
                continue;
            }
            String relType = relation.get("type");
            if (!"multipolygon".equals(relType) && !"boundary".equals(relType)) {
                continue;
            }

            AnalyzeOutcome outcome = analyze(relation, rng);
            if (outcome.plan != null) {
                if (debug) {
                    Logging.info("Multipoly-Gone DEBUG: plan for relation {0}: {1}",
                        relation.getUniqueId(), outcome.plan.fingerprint());
                }
                fixPlans.add(outcome.plan);
            } else if (outcome.skip != null) {
                skipResults.add(outcome.skip);
            }
        }
        if (debug) {
            Logging.info("Multipoly-Gone DEBUG: found {0} fixable relations, {1} skipped",
                fixPlans.size(), skipResults.size());
        }
        return new AnalysisResult(fixPlans, skipResults);
    }

    /** Internal union type: either a FixPlan (fixable) or a SkipResult (not fixable). */
    private static class AnalyzeOutcome {
        final FixPlan plan;
        final SkipResult skip;

        private AnalyzeOutcome(FixPlan plan, SkipResult skip) {
            this.plan = plan;
            this.skip = skip;
        }

        static AnalyzeOutcome fix(FixPlan plan) {
            return new AnalyzeOutcome(plan, null);
        }

        static AnalyzeOutcome skip(Relation relation, SkipReason reason, String detail) {
            return new AnalyzeOutcome(null, new SkipResult(relation, reason, detail));
        }
    }

    private static AnalyzeOutcome analyze(Relation relation, Random rng) {
        boolean isBoundary = "boundary".equals(relation.get("type"));

        // Skip relations with incomplete members — we can't see all geometry
        if (relation.hasIncompleteMembers()) {
            return AnalyzeOutcome.skip(relation, SkipReason.INCOMPLETE_MEMBERS, null);
        }

        List<Way> outerWays = new ArrayList<>();
        List<Way> innerWays = new ArrayList<>();
        Set<Way> innerWaySet = new HashSet<>();

        for (RelationMember member : relation.getMembers()) {
            if (!member.isWay()) {
                if (isBoundary) {
                    continue; // skip non-Way members (label nodes, admin_centre, etc.)
                }
                return AnalyzeOutcome.skip(relation, SkipReason.NON_WAY_MEMBER,
                    "member type=" + member.getType() + " role=" + member.getRole());
            }
            Way way = member.getWay();
            if (way.isDeleted() || way.isIncomplete() || way.getNodesCount() < 2) {
                return AnalyzeOutcome.skip(relation, SkipReason.DELETED_OR_INCOMPLETE_WAY,
                    "way " + way.getUniqueId());
            }
            String role = member.getRole();
            if (!"outer".equals(role) && !"inner".equals(role) && !"".equals(role)) {
                return AnalyzeOutcome.skip(relation, SkipReason.UNEXPECTED_WAY_ROLE,
                    "way " + way.getUniqueId() + " role=" + role);
            }
            if ("inner".equals(role)) {
                innerWays.add(way);
                innerWaySet.add(way);
            } else {
                outerWays.add(way);
            }
        }

        if (outerWays.isEmpty()) {
            return AnalyzeOutcome.skip(relation, SkipReason.NO_OUTER_WAYS, null);
        }

        // Shuffle way lists to perturb downstream HashMap/HashSet iteration order
        if (rng != null) {
            Collections.shuffle(outerWays, rng);
            Collections.shuffle(innerWays, rng);
        }

        boolean identityProtected = hasIdentityTags(relation);
        // Boundaries are always identity-protected (never dissolved or split)
        if (isBoundary) {
            identityProtected = true;
        }
        // Relations that are members of other relations cannot be safely dissolved
        // or split — doing so would break the parent relation's member references.
        boolean parentProtected = relation.getReferrers().stream()
            .anyMatch(r -> r instanceof Relation && !r.isDeleted());

        // Check if open outer ways form multiple disconnected ring groups.
        // If so, split into sub-relations up front so we reach the simplest
        // form in one pass. Without this, buildRings() succeeds globally on
        // disconnected ring groups, producing a consolidated relation that
        // still needs splitting on a second pass.
        // Already-closed outers are handled fine by the single-relation path
        // (EXTRACT_OUTERS / DISSOLVE), so we only check open ways.
        // Identity-protected relations and boundaries are never split.
        if (!identityProtected && !parentProtected) {
            List<Way> openOuterWays = new ArrayList<>();
            for (Way w : outerWays) {
                if (!w.isClosed()) {
                    openOuterWays.add(w);
                }
            }

            if (!openOuterWays.isEmpty()) {
                List<Set<Way>> openComponents = findConnectedComponents(openOuterWays);

                if (openComponents.size() > 1) {
                    // Open outers form multiple disconnected ring groups.
                    // Rebuild full component sets including all ways.
                    List<Way> allWays = new ArrayList<>(outerWays);
                    allWays.addAll(innerWays);
                    if (rng != null) {
                        Collections.shuffle(allWays, rng);
                    }
                    List<Set<Way>> components = findConnectedComponents(allWays);

                    if (rng != null) {
                        Collections.shuffle(components, rng);
                    }
                    // Multiple components: analyze each independently
                    return analyzeMultiComponent(relation, components, innerWaySet);
                }
            }
        }

        // Single connected outer group (or identity-protected/boundary):
        // use the single-relation path.
        SkipReason[] singleSkip = new SkipReason[1];
        FixPlan singleResult = analyzeSingleRelation(relation, outerWays, innerWays,
            identityProtected, isBoundary, parentProtected, singleSkip);
        if (singleResult != null) {
            return AnalyzeOutcome.fix(singleResult);
        }

        // Single-relation path failed (e.g., multiple closed outers with inners).
        // Try splitting into disconnected components as a fallback.
        // Don't split if the failure was NESTED_OUTER_RINGS — that's a structural error
        // that splitting would mask by putting each outer in its own component.
        if (!identityProtected && !parentProtected && singleSkip[0] != SkipReason.NESTED_OUTER_RINGS) {
            List<Way> allWays = new ArrayList<>(outerWays);
            allWays.addAll(innerWays);
            if (rng != null) {
                Collections.shuffle(allWays, rng);
            }
            List<Set<Way>> components = findConnectedComponents(allWays);

            if (components.size() > 1) {
                if (rng != null) {
                    Collections.shuffle(components, rng);
                }
                return analyzeMultiComponent(relation, components, innerWaySet);
            }
        }

        // Not splittable — report the skip reason.
        // Prefer the specific reason from component analysis; fall back to structural reasons.
        SkipReason reason;
        if (singleSkip[0] == SkipReason.HAS_PARENT_RELATION || parentProtected) {
            reason = SkipReason.HAS_PARENT_RELATION;
        } else if (singleSkip[0] != null) {
            reason = singleSkip[0];
        } else if (identityProtected) {
            reason = SkipReason.IDENTITY_PROTECTED_MULTI_COMPONENT;
        } else {
            reason = SkipReason.SINGLE_COMPONENT_NOT_FIXABLE;
        }
        return AnalyzeOutcome.skip(relation, reason, null);
    }

    /**
     * Original single-component analysis path. Unchanged from before SPLIT_RELATION.
     * @param skipOut if non-null and the component is not fixable, skipOut[0] is set to the reason
     */
    private static FixPlan analyzeSingleRelation(Relation relation, List<Way> outerWays,
            List<Way> innerWays, boolean identityProtected, boolean isBoundary,
            boolean parentProtected, SkipReason[] skipOut) {
        ComponentResult result = analyzeComponent(outerWays, innerWays, identityProtected, isBoundary,
            parentProtected, skipOut);
        if (result == null) {
            return null;
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = result.getDescription().isEmpty()
            ? primaryTag
            : result.getDescription() + " (" + primaryTag + ")";

        FixPlan plan = new FixPlan(relation, result.getOperations(), desc, isBoundary);
        plan.validate();
        return plan;
    }

    /**
     * Analyze a relation with multiple disconnected components.
     * Inner-only components (no outers) are assigned to the outer component
     * that spatially contains them.
     */
    private static AnalyzeOutcome analyzeMultiComponent(Relation relation, List<Set<Way>> components, Set<Way> innerWaySet) {
        boolean debug = isDebugMode();
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

        if (debug) {
            Logging.info("Multipoly-Gone DEBUG: relation {0} split into {1} outer components, {2} inner-only components",
                relation.getUniqueId(), outerComponents.size(), innerOnlyComponents.size());
            for (int c = 0; c < outerComponents.size(); c++) {
                TreeSet<Long> outerIds = new TreeSet<>();
                for (Way w : outerComponents.get(c)) outerIds.add(w.getUniqueId());
                TreeSet<Long> innerIds = new TreeSet<>();
                for (Way w : outerCompInners.get(c)) innerIds.add(w.getUniqueId());
                Logging.info("  component {0}: outers={1} inners={2}", c, outerIds, innerIds);
            }
        }

        if (outerComponents.size() <= 1 && innerOnlyComponents.isEmpty()) {
            // Degenerate: only one real component, shouldn't normally happen
            List<Way> allOuters = outerComponents.isEmpty() ? new ArrayList<>() : outerComponents.get(0);
            List<Way> allInners = outerCompInners.isEmpty() ? new ArrayList<>() : outerCompInners.get(0);
            SkipReason[] skipOut = new SkipReason[1];
            FixPlan plan = analyzeSingleRelation(relation, allOuters, allInners, false, false, false, skipOut);
            if (plan != null) return AnalyzeOutcome.fix(plan);
            return AnalyzeOutcome.skip(relation,
                skipOut[0] != null ? skipOut[0] : SkipReason.SINGLE_COMPONENT_NOT_FIXABLE, null);
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
                if (debug) {
                    TreeSet<Long> innerIds = new TreeSet<>();
                    for (Way w : innerComp) innerIds.add(w.getUniqueId());
                    Logging.info("  inner-only component {0} assigned to outer component {1}",
                        innerIds, bestComp);
                }
            } else if (debug) {
                TreeSet<Long> innerIds = new TreeSet<>();
                for (Way w : innerComp) innerIds.add(w.getUniqueId());
                Logging.info("  inner-only component {0} orphaned (no containing outer found)", innerIds);
            }
            // If no containing outer found, the inner-only component is orphaned — skip it
        }

        // If only 1 outer component after inner assignment, use the simple single-relation path
        if (outerComponents.size() == 1) {
            SkipReason[] skipOut = new SkipReason[1];
            FixPlan plan = analyzeSingleRelation(relation, outerComponents.get(0), outerCompInners.get(0),
                false, false, false, skipOut);
            if (plan != null) return AnalyzeOutcome.fix(plan);
            return AnalyzeOutcome.skip(relation,
                skipOut[0] != null ? skipOut[0] : SkipReason.SINGLE_COMPONENT_NOT_FIXABLE, null);
        }

        // Now analyze each outer component with its assigned inners
        List<ComponentResult> results = new ArrayList<>();
        int dissolvedCount = 0;
        int retainedCount = 0;

        for (int c = 0; c < outerComponents.size(); c++) {
            List<Way> compOuters = outerComponents.get(c);
            List<Way> compInners = outerCompInners.get(c);
            ComponentResult cr = analyzeComponent(compOuters, compInners, false, false, false, null);
            if (cr == null && !compInners.isEmpty()) {
                // Component can't be simplified internally, but in a multi-component split
                // it still needs to become its own sub-relation. Create a no-op result.
                cr = new ComponentResult(compOuters, compInners, new ArrayList<>(), false,
                    null, null, "retained as sub-relation");
            }
            results.add(cr);
            if (cr != null) {
                if (debug) {
                    Logging.info("  component {0} result: {1} — {2}",
                        c, cr.dissolvesCompletely() ? "dissolves" : "retained", cr.getDescription());
                }
                if (cr.dissolvesCompletely()) {
                    dissolvedCount++;
                } else {
                    retainedCount++;
                }
            } else if (debug) {
                Logging.info("  component {0} result: null (not fixable)", c);
            }
        }

        // A split is only worthwhile if there are multiple outer components
        // and at least one component is actually fixable
        if (outerComponents.size() < 2) {
            return AnalyzeOutcome.skip(relation, SkipReason.MULTI_COMPONENT_SPLIT_NOT_WORTHWHILE, null);
        }
        boolean anyNonNull = results.stream().anyMatch(r -> r != null);
        if (!anyNonNull) {
            return AnalyzeOutcome.skip(relation, SkipReason.MULTI_COMPONENT_SPLIT_NOT_WORTHWHILE, null);
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = String.format("split %d components (%d extracted, %d retained) (%s)",
            outerComponents.size(), dissolvedCount, retainedCount, primaryTag);

        List<FixOp> ops = new ArrayList<>();
        ops.add(new FixOp(FixOpType.SPLIT_RELATION, results));
        FixPlan plan = new FixPlan(relation, ops, desc);
        plan.validate();
        return AnalyzeOutcome.fix(plan);
    }

    /**
     * Analyze a single connected component of ways through the full pipeline.
     * Returns a ComponentResult, or null if the component is not fixable.
     * @param parentProtected true if the relation has a parent relation and must not be deleted
     * @param skipOut if non-null and the component is not fixable, skipOut[0] is set to the reason
     */
    private static ComponentResult analyzeComponent(List<Way> outerWays, List<Way> innerWays,
            boolean identityProtected, boolean boundaryMode, boolean parentProtected,
            SkipReason[] skipOut) {
        // Partial mode: when some steps fail but others succeed, accumulate
        // whatever consolidation ops are possible and keep the relation alive.
        boolean partialMode = false;

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

        List<WayChainBuilder.Ring> outerRings;
        if (ringsOpt.isEmpty()) {
            // Full ring building failed — try partial: form rings from subsets that chain
            WayChainBuilder.PartialRingsResult partial = WayChainBuilder.buildRingsPartial(outerWays);
            if (partial.getRings().isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.OUTERS_CANT_FORM_RINGS;
                return null;
            }
            outerRings = partial.getRings();
            partialMode = true;
        } else {
            outerRings = ringsOpt.get();
        }

        // Check node limits on rings that need chaining — skip oversized rings
        List<WayChainBuilder.Ring> oversizedOuters = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                oversizedOuters.add(ring);
            }
        }
        if (!oversizedOuters.isEmpty()) {
            outerRings.removeAll(oversizedOuters);
            partialMode = true;
        }

        // Skip if any outer ring is nested inside another with no inners to justify
        // the nesting. When inners exist, defer the check until inner rings are built
        // so we can validate the island-within-a-hole pattern (outer > inner > outer).
        // In partial mode, skip this check: the incomplete ring set could give false positives.
        if (!partialMode && innerWays.isEmpty() && hasNestedOuters(outerRings)) {
            if (skipOut != null) skipOut[0] = SkipReason.NESTED_OUTER_RINGS;
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

        // Boundary mode: only CONSOLIDATE + DECOMPOSE for outers, plus inner consolidation.
        // The relation always survives — no dissolve, extract, merge, or split.
        if (boundaryMode) {
            // Also consolidate inner rings if present
            if (!innerWays.isEmpty()) {
                Optional<List<WayChainBuilder.Ring>> innerRingsOpt = WayChainBuilder.buildRings(innerWays);
                if (innerRingsOpt.isEmpty()) {
                    innerRingsOpt = buildRingsWithAbsorption(innerWays);
                }
                List<WayChainBuilder.Ring> innerRings;
                if (innerRingsOpt.isPresent()) {
                    innerRings = innerRingsOpt.get();
                } else {
                    // Try partial inner ring building
                    WayChainBuilder.PartialRingsResult partialInner =
                        WayChainBuilder.buildRingsPartial(innerWays);
                    innerRings = partialInner.getRings();
                }
                if (!innerRings.isEmpty()) {
                    List<WayChainBuilder.Ring> innerNeedsChaining = new ArrayList<>();
                    for (WayChainBuilder.Ring ring : innerRings) {
                        if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 <= MAX_WAY_NODES) {
                            innerNeedsChaining.add(ring);
                        }
                    }
                    if (!innerNeedsChaining.isEmpty()) {
                        ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, innerNeedsChaining, null));
                        descParts.add(innerNeedsChaining.size() == 1
                            ? "1 inner ring chained"
                            : innerNeedsChaining.size() + " inner rings chained");
                    }
                    // Merge abutting closed inner rings
                    List<ConsolidatedInnerGroup> bndInnerConsolidations =
                        mergeAdjoiningInnerRings(innerRings);
                    if (!bndInnerConsolidations.isEmpty()) {
                        ops.add(new FixOp(FixOpType.CONSOLIDATE_INNERS, bndInnerConsolidations, 0));
                        int totalSrc = bndInnerConsolidations.stream()
                            .mapToInt(g -> g.getSourceRings().size()).sum();
                        int resCnt = bndInnerConsolidations.size();
                        descParts.add(totalSrc + " inners merged into " + resCnt);
                    }
                }
            }
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.BOUNDARY_NO_OPS;
                return null; // nothing to fix
            }
            descParts.add("boundary updated");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, new ArrayList<>(), String.join(", ", descParts));
        }

        // No inners: dissolve everything (unless partial or identity-protected)
        if (innerWays.isEmpty()) {
            if (partialMode) {
                // Don't dissolve in partial mode — oversized/unchainable outers remain
                if (ops.isEmpty()) {
                    if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
                    return null;
                }
                descParts.add("partial improvement");
                return new ComponentResult(outerWays, innerWays, ops, false,
                    outerRings, new ArrayList<>(), String.join(", ", descParts));
            }
            if (parentProtected) {
                // Parent-protected: relation must survive (parent references it by ID).
                // Return consolidation ops only; don't dissolve regardless of outer count.
                if (ops.isEmpty()) {
                    if (skipOut != null) skipOut[0] = SkipReason.HAS_PARENT_RELATION;
                    return null;
                }
                descParts.add("parent-protected, kept as relation");
                return new ComponentResult(outerWays, innerWays, ops, false,
                    outerRings, new ArrayList<>(), String.join(", ", descParts));
            }
            if (identityProtected && outerRings.size() > 1) {
                // Identity-protected: don't dissolve disjoint outers into separate ways.
                // Only return if we have consolidation or decomposition ops to apply.
                if (ops.isEmpty()) {
                    if (skipOut != null) skipOut[0] = SkipReason.IDENTITY_PROTECTED_NO_OPS;
                    return null;
                }
                descParts.add("identity-protected, kept as relation");
                return new ComponentResult(outerWays, innerWays, ops, false,
                    outerRings, new ArrayList<>(), String.join(", ", descParts));
            }
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
            // Try absorbing open inner ways into closed inner rings
            innerRingsOpt = buildRingsWithAbsorption(innerWays);
        }
        List<WayChainBuilder.Ring> innerRings;
        if (innerRingsOpt.isEmpty()) {
            // Full inner ring building failed — try partial
            WayChainBuilder.PartialRingsResult partialInner = WayChainBuilder.buildRingsPartial(innerWays);
            if (!partialInner.getRings().isEmpty()) {
                innerRings = partialInner.getRings();
                partialMode = true;
            } else {
                // No inner rings could be formed at all — return whatever outer ops we have
                if (!ops.isEmpty()) {
                    descParts.add("partial improvement");
                    return new ComponentResult(outerWays, innerWays, ops, false,
                        outerRings, new ArrayList<>(), String.join(", ", descParts));
                }
                if (skipOut != null) skipOut[0] = SkipReason.INNER_WAYS_CANT_FORM_RINGS;
                return null;
            }
        } else {
            innerRings = innerRingsOpt.get();
        }

        // Check node limits on inner rings that need chaining — skip oversized
        List<WayChainBuilder.Ring> oversizedInners = new ArrayList<>();
        for (WayChainBuilder.Ring ring : innerRings) {
            if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                oversizedInners.add(ring);
            }
        }
        if (!oversizedInners.isEmpty()) {
            innerRings.removeAll(oversizedInners);
            partialMode = true;
        }

        // Deferred nested outer check: now that inner rings are built, validate
        // that any nested outers are justified by the island-within-a-hole pattern
        // (outer > inner > outer). In partial mode, skip: incomplete ring sets
        // may give false positives.
        if (!partialMode && hasInvalidNestedOuters(outerRings, innerRings)) {
            if (skipOut != null) skipOut[0] = SkipReason.NESTED_OUTER_RINGS;
            return null;
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

        // Merge abutting closed inner rings that share edges
        List<ConsolidatedInnerGroup> innerConsolidations = mergeAdjoiningInnerRings(innerRings);
        if (!innerConsolidations.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_INNERS, innerConsolidations, 0));
            int totalSources = innerConsolidations.stream()
                .mapToInt(g -> g.getSourceRings().size()).sum();
            int resultCount = innerConsolidations.size();
            descParts.add(totalSources + " inners merged into " + resultCount);
        }

        // Extract tagged inner rings that share a contiguous edge with any outer ring
        List<WayChainBuilder.Ring> edgeSharingInners = findEdgeSharingInners(outerRings, innerRings);
        if (!edgeSharingInners.isEmpty()) {
            ops.add(new FixOp(FixOpType.EXTRACT_INNERS, edgeSharingInners, null));
            innerRings.removeAll(edgeSharingInners);
            for (WayChainBuilder.Ring r : edgeSharingInners) {
                innerWays.removeAll(r.getSourceWays());
            }
            descParts.add(edgeSharingInners.size() == 1
                ? "1 edge-sharing inner extracted"
                : edgeSharingInners.size() + " edge-sharing inners extracted");
        }

        // If all inners were extracted, dissolve (same logic as no-inners case)
        if (innerRings.isEmpty()) {
            if (partialMode) {
                if (ops.isEmpty()) {
                    if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
                    return null;
                }
                descParts.add("partial improvement");
                return new ComponentResult(outerWays, innerWays, ops, false,
                    outerRings, new ArrayList<>(), String.join(", ", descParts));
            }
            if (parentProtected) {
                if (ops.isEmpty()) {
                    if (skipOut != null) skipOut[0] = SkipReason.HAS_PARENT_RELATION;
                    return null;
                }
                descParts.add("parent-protected, kept as relation");
                return new ComponentResult(outerWays, innerWays, ops, false,
                    outerRings, new ArrayList<>(), String.join(", ", descParts));
            }
            if (identityProtected && outerRings.size() > 1) {
                if (ops.isEmpty()) {
                    if (skipOut != null) skipOut[0] = SkipReason.IDENTITY_PROTECTED_NO_OPS;
                    return null;
                }
                descParts.add("identity-protected, kept as relation");
                return new ComponentResult(outerWays, innerWays, ops, false,
                    outerRings, new ArrayList<>(), String.join(", ", descParts));
            }
            ops.add(new FixOp(FixOpType.DISSOLVE, outerRings, null));
            int count = outerRings.size();
            descParts.add(count == 1
                ? "dissolved"
                : count + " outers dissolved");
            return new ComponentResult(outerWays, innerWays, ops, true,
                null, null, String.join(", ", descParts));
        }

        // In partial mode, skip dissolution/extraction/merge — just return consolidation ops
        if (partialMode) {
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
                return null;
            }
            descParts.add("partial improvement");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, innerRings, String.join(", ", descParts));
        }

        // Map inner rings to containing outer rings
        Map<WayChainBuilder.Ring, List<WayChainBuilder.Ring>> ringToInners = new HashMap<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            ringToInners.put(ring, new ArrayList<>());
        }
        for (WayChainBuilder.Ring innerRing : innerRings) {
            WayChainBuilder.Ring containing = findContainingRing(innerRing, outerRings);
            if (containing == null) {
                // In non-partial mode, a single unmapped inner is a hard failure
                if (skipOut != null) skipOut[0] = SkipReason.INNER_NOT_CONTAINED_IN_OUTER;
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

        // Extract standalone outers if any exist — but not if identity-protected,
        // since all outers should stay in the relation as one feature
        if (!extractable.isEmpty() && !identityProtected) {
            ops.add(new FixOp(FixOpType.EXTRACT_OUTERS, extractable, null));
            descParts.add(extractable.size() == 1
                ? "1 outer extracted"
                : extractable.size() + " outers extracted");
        } else if (!extractable.isEmpty() && identityProtected) {
            // Keep extractable outers in the relation
            retained.addAll(extractable);
            extractable.clear();
        }

        // Analyze what remains after extraction
        if (retained.size() == 1 && retainedInners.size() == 1 && !parentProtected) {
            MergeResult mergeResult = tryTouchingInnerMerge(retained.get(0), retainedInners.get(0).getNodes());
            if (mergeResult != null) {
                for (List<Node> way : mergeResult.mergedWays) {
                    if (way.size() - 1 > MAX_WAY_NODES) {
                        if (ops.isEmpty()) {
                            if (skipOut != null) skipOut[0] = SkipReason.CONSOLIDATED_RING_TOO_LARGE;
                            return null;
                        }
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
            if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
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

    // ---- Adjoining inner ring consolidation ----

    /**
     * Iteratively merges abutting closed inner rings that share consecutive edges.
     * Modifies the innerRings list in-place: merged sources are removed, merged ring inserted.
     * Returns the list of consolidation groups (empty if no merges happened).
     */
    private static List<ConsolidatedInnerGroup> mergeAdjoiningInnerRings(
            List<WayChainBuilder.Ring> innerRings) {
        List<ConsolidatedInnerGroup> groups = new ArrayList<>();
        // Map from merged ring to its group (for iterative aggregation)
        Map<WayChainBuilder.Ring, ConsolidatedInnerGroup> ringToGroup = new HashMap<>();

        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < innerRings.size() && !merged; i++) {
                for (int j = i + 1; j < innerRings.size() && !merged; j++) {
                    WayChainBuilder.Ring ringA = innerRings.get(i);
                    WayChainBuilder.Ring ringB = innerRings.get(j);
                    WayChainBuilder.Ring result = tryMergeAdjoiningRings(ringA, ringB);
                    if (result != null && result.getNodes().size() - 1 <= MAX_WAY_NODES) {
                        // Collect all original source rings (handle iterative merges)
                        List<WayChainBuilder.Ring> sources = new ArrayList<>();
                        ConsolidatedInnerGroup groupA = ringToGroup.get(ringA);
                        ConsolidatedInnerGroup groupB = ringToGroup.get(ringB);
                        if (groupA != null) {
                            sources.addAll(groupA.getSourceRings());
                            groups.remove(groupA);
                            ringToGroup.remove(ringA);
                        } else {
                            sources.add(ringA);
                        }
                        if (groupB != null) {
                            sources.addAll(groupB.getSourceRings());
                            groups.remove(groupB);
                            ringToGroup.remove(ringB);
                        } else {
                            sources.add(ringB);
                        }

                        ConsolidatedInnerGroup group = new ConsolidatedInnerGroup(sources, result);
                        groups.add(group);
                        ringToGroup.put(result, group);

                        innerRings.remove(j);
                        innerRings.remove(i);
                        innerRings.add(i, result);
                        merged = true;
                    }
                }
            }
        }
        return groups;
    }

    /**
     * Tries to merge two closed inner rings that share a consecutive edge.
     * Returns the merged ring, or null if they don't share a valid consecutive edge.
     */
    private static WayChainBuilder.Ring tryMergeAdjoiningRings(
            WayChainBuilder.Ring ringA, WayChainBuilder.Ring ringB) {
        List<Node> nodesA = ringA.getNodes();
        List<Node> nodesB = ringB.getNodes();
        int sizeA = nodesA.size() - 1; // exclude closing node
        int sizeB = nodesB.size() - 1;

        // Find shared nodes
        Set<Node> nodesSetB = new HashSet<>();
        for (int i = 0; i < sizeB; i++) {
            nodesSetB.add(nodesB.get(i));
        }
        Set<Node> sharedSet = new HashSet<>();
        for (int i = 0; i < sizeA; i++) {
            if (nodesSetB.contains(nodesA.get(i))) {
                sharedSet.add(nodesA.get(i));
            }
        }

        if (sharedSet.size() < 2) {
            return null; // need at least 2 shared nodes for an edge
        }

        // Find the contiguous shared run in ring A
        int runStart = findSharedRunStart(nodesA, sizeA, sharedSet);
        if (runStart < 0) return null;

        int runLength = 0;
        for (int k = 0; k < sizeA; k++) {
            if (sharedSet.contains(nodesA.get((runStart + k) % sizeA))) {
                runLength++;
            } else {
                break;
            }
        }
        if (runLength != sharedSet.size()) {
            return null; // shared nodes are not contiguous in A
        }

        // Find matching run in B (may be forward or reversed)
        Node runFirstA = nodesA.get(runStart % sizeA);
        Node runLastA = nodesA.get((runStart + runLength - 1) % sizeA);

        int startInB = -1;
        for (int i = 0; i < sizeB; i++) {
            if (nodesB.get(i).equals(runFirstA)) {
                startInB = i;
                break;
            }
        }
        if (startInB < 0) return null;

        // Check forward match in B
        boolean forwardMatch = true;
        for (int k = 0; k < runLength; k++) {
            if (!nodesB.get((startInB + k) % sizeB).equals(nodesA.get((runStart + k) % sizeA))) {
                forwardMatch = false;
                break;
            }
        }

        // Check reverse match in B
        boolean reverseMatch = false;
        int reverseStartInB = -1;
        if (!forwardMatch) {
            for (int i = 0; i < sizeB; i++) {
                if (nodesB.get(i).equals(runLastA)) {
                    reverseStartInB = i;
                    break;
                }
            }
            if (reverseStartInB >= 0) {
                reverseMatch = true;
                for (int k = 0; k < runLength; k++) {
                    Node expectedA = nodesA.get((runStart + k) % sizeA);
                    Node actualB = nodesB.get((reverseStartInB + runLength - 1 - k) % sizeB);
                    if (!actualB.equals(expectedA)) {
                        reverseMatch = false;
                        break;
                    }
                }
            }
        }

        if (!forwardMatch && !reverseMatch) {
            return null;
        }

        // Build merged ring: junction1 → A non-shared → junction2 → B non-shared → junction1
        // junction1 = A[runStart] (first shared node in A's run)
        // junction2 = A[(runStart + runLength - 1) % sizeA] (last shared node in A's run)
        List<Node> merged = new ArrayList<>();

        // Start at junction2 (last shared), walk A's non-shared, arrive at junction1 (first shared)
        int aStart = (runStart + runLength - 1) % sizeA; // junction2 position in A
        int aNonShared = sizeA - runLength;
        for (int k = 0; k <= aNonShared + 1; k++) { // junction2, non-shared, junction1
            merged.add(nodesA.get((aStart + k) % sizeA));
        }
        // merged now ends at junction1

        // Walk B's non-shared nodes (excluding both junctions which are already in merged)
        int bNonShared = sizeB - runLength;
        if (forwardMatch) {
            // In B, shared run goes from startInB forward.
            // B's non-shared starts at (startInB + runLength) % sizeB
            int bStart = (startInB + runLength) % sizeB;
            for (int k = 0; k < bNonShared; k++) {
                merged.add(nodesB.get((bStart + k) % sizeB));
            }
        } else {
            // Reverse: A's first shared node (runFirstA) maps to
            // B[(reverseStartInB + runLength - 1) % sizeB].
            // B's non-shared starts right after that position.
            int bStart = (reverseStartInB + runLength) % sizeB;
            for (int k = 0; k < bNonShared; k++) {
                merged.add(nodesB.get((bStart + k) % sizeB));
            }
        }

        // Close the ring
        merged.add(merged.get(0));

        if (merged.size() < 4) {
            return null; // degenerate
        }

        // Combine source ways
        List<Way> sourceWays = new ArrayList<>(ringA.getSourceWays());
        sourceWays.addAll(ringB.getSourceWays());

        return new WayChainBuilder.Ring(merged, sourceWays);
    }

    /**
     * Finds the index in a ring's node list where the contiguous shared run begins.
     * Handles the wrap-around case (run spanning the closing point).
     */
    private static int findSharedRunStart(List<Node> nodes, int size, Set<Node> sharedSet) {
        // Find a transition from non-shared to shared
        for (int i = 0; i < size; i++) {
            boolean curr = sharedSet.contains(nodes.get(i));
            boolean prev = sharedSet.contains(nodes.get((i - 1 + size) % size));
            if (curr && !prev) {
                return i;
            }
        }
        // All nodes are shared (shouldn't happen for valid distinct rings)
        return -1;
    }

    /**
     * Finds the index of the ring that contains both endpoints of the open way.
     * Returns -1 if no such ring exists.
     */
    private static int findTargetRing(Way openWay, List<WayChainBuilder.Ring> rings) {
        Node p = openWay.firstNode();
        Node q = openWay.lastNode();
        for (int i = 0; i < rings.size(); i++) {
            List<Node> ringNodes = rings.get(i).getNodes();
            int size = ringNodes.size() - 1;
            boolean hasP = false, hasQ = false;
            for (int j = 0; j < size; j++) {
                if (ringNodes.get(j).equals(p)) hasP = true;
                if (ringNodes.get(j).equals(q)) hasQ = true;
                if (hasP && hasQ) return i;
            }
        }
        return -1;
    }

    /**
     * Absorbs an open way into a closed ring by replacing one arc between
     * the open way's endpoints with the open way's path. Picks the candidate
     * that produces the larger enclosed area (expanding the inner).
     *
     * @return the enlarged Ring, or null if absorption is not possible
     */
    private static WayChainBuilder.Ring absorbOpenWayIntoRing(Way openWay,
            WayChainBuilder.Ring ring) {
        Node p = openWay.firstNode();
        Node q = openWay.lastNode();
        if (p.equals(q)) return null;

        List<Node> ringNodes = ring.getNodes();
        int size = ringNodes.size() - 1; // exclude closing node

        // Find positions of P and Q in the ring
        int posP = -1, posQ = -1;
        for (int i = 0; i < size; i++) {
            if (ringNodes.get(i).equals(p)) posP = i;
            if (ringNodes.get(i).equals(q)) posQ = i;
        }
        if (posP < 0 || posQ < 0 || posP == posQ) return null;

        List<Node> openNodes = openWay.getNodes();

        // Candidate 1: arc Q→...→P (going forward from Q around ring) + open way P→...→Q
        List<Node> candidate1 = new ArrayList<>();
        for (int k = 0; k <= size; k++) {
            int idx = (posQ + k) % size;
            candidate1.add(ringNodes.get(idx));
            if (idx == posP && k > 0) break;
        }
        for (int k = 1; k < openNodes.size(); k++) {
            candidate1.add(openNodes.get(k));
        }

        // Candidate 2: arc P→...→Q (going forward from P around ring) + reversed open way Q→...→P
        List<Node> candidate2 = new ArrayList<>();
        for (int k = 0; k <= size; k++) {
            int idx = (posP + k) % size;
            candidate2.add(ringNodes.get(idx));
            if (idx == posQ && k > 0) break;
        }
        for (int k = openNodes.size() - 2; k >= 0; k--) {
            candidate2.add(openNodes.get(k));
        }

        // Validate
        if (candidate1.size() < 4 || candidate2.size() < 4) return null;
        if (!candidate1.get(0).equals(candidate1.get(candidate1.size() - 1))) return null;
        if (!candidate2.get(0).equals(candidate2.get(candidate2.size() - 1))) return null;

        // Pick the candidate with larger absolute area
        double area1 = Math.abs(computeSignedArea(candidate1));
        double area2 = Math.abs(computeSignedArea(candidate2));
        List<Node> chosen = (area1 >= area2) ? candidate1 : candidate2;

        List<Way> sourceWays = new ArrayList<>(ring.getSourceWays());
        sourceWays.add(openWay);
        return new WayChainBuilder.Ring(chosen, sourceWays);
    }

    /**
     * Fallback when buildRings(innerWays) fails: separates closed and open ways,
     * builds rings from closed ways, then absorbs open ways whose endpoints lie
     * on a closed ring. Leftover open ways are chained separately.
     */
    private static Optional<List<WayChainBuilder.Ring>> buildRingsWithAbsorption(
            List<Way> innerWays) {
        List<Way> closedWays = new ArrayList<>();
        List<Way> openWays = new ArrayList<>();
        for (Way w : innerWays) {
            if (w.isClosed()) {
                closedWays.add(w);
            } else {
                openWays.add(w);
            }
        }
        if (openWays.isEmpty() || closedWays.isEmpty()) {
            return Optional.empty();
        }

        Optional<List<WayChainBuilder.Ring>> closedRingsOpt =
            WayChainBuilder.buildRings(closedWays);
        if (closedRingsOpt.isEmpty()) {
            return Optional.empty();
        }
        List<WayChainBuilder.Ring> rings = new ArrayList<>(closedRingsOpt.get());

        // Iteratively absorb open ways into rings
        List<Way> remainingOpen = new ArrayList<>(openWays);
        boolean progress = true;
        while (progress && !remainingOpen.isEmpty()) {
            progress = false;
            Iterator<Way> it = remainingOpen.iterator();
            while (it.hasNext()) {
                Way openWay = it.next();
                int targetIdx = findTargetRing(openWay, rings);
                if (targetIdx < 0) continue;

                WayChainBuilder.Ring enlarged = absorbOpenWayIntoRing(
                    openWay, rings.get(targetIdx));
                if (enlarged != null && enlarged.getNodes().size() - 1 <= MAX_WAY_NODES) {
                    rings.set(targetIdx, enlarged);
                    it.remove();
                    progress = true;
                }
            }
        }

        if (!remainingOpen.isEmpty()) {
            // Try chaining leftover open ways
            Optional<List<WayChainBuilder.Ring>> chainedOpt =
                WayChainBuilder.buildRings(remainingOpen);
            if (chainedOpt.isEmpty()) {
                return Optional.empty();
            }
            rings.addAll(chainedOpt.get());
        }

        return Optional.of(rings);
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
            List<Node> way = buildMergedWay(segStartBoundary.get(s), segEndBoundary.get(s),
                innerOnlySegments.get(s), chosenPaths.get(s));
            if (way == null) {
                return null;
            }
            result.add(way);
        }

        // For 2-segment cases, validate the pairing using area comparison.
        // The angle test handles inner segment direction, but the outer path pairing
        // (which outer half goes with which inner segment) may be wrong. Try both
        // pairings and pick the one whose total area matches outer - inner.
        if (result.size() == 2 && innerOnlySegments.size() == 2) {
            double outerArea = Math.abs(computeSignedArea(outerNodes));
            double innerArea = Math.abs(computeSignedArea(innerNodes));
            double expectedArea = outerArea - innerArea;

            double currentAreaSum = Math.abs(computeSignedArea(result.get(0)))
                                  + Math.abs(computeSignedArea(result.get(1)));

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
                double altAreaSum = Math.abs(computeSignedArea(altResult.get(0)))
                                  + Math.abs(computeSignedArea(altResult.get(1)));
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
        // multiple inner-only segments, the outer path prefers the outer-only direction,
        // skipping intermediate shared nodes. Walk the outer ring to find runs of non-boundary
        // shared nodes and produce additional closed ways for them. This only applies when
        // there are 2+ inner-only segments (e.g., test 9); with a single segment (e.g., test 206)
        // the crescent way already covers the full region and no extra way is needed.
        if (innerOnlySegments.size() >= 2) {
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

        // Trace inner paths with both starting directions: seg0-backward and seg1-forward.
        // At crossing nodes, direction reverses to trace complementary sub-paths.
        List<Node> innerPathA = traceInnerPathCrossSegment(augSeg0, augSeg1, sharedB, sharedA, newNodes, false);
        List<Node> innerPathB = traceInnerPathCrossSegment(augSeg0, augSeg1, sharedB, sharedA, newNodes, true);

        if (innerPathA == null || innerPathB == null) {
            return null;
        }

        // Try both pairings of inner paths with outer paths. The correct pairing
        // produces non-self-intersecting ways with larger total area. A wrong pairing
        // creates self-intersecting ways whose signed area partially cancels out.
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

            double areaSum = Math.abs(computeSignedArea(way1)) + Math.abs(computeSignedArea(way2));
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
     * Traces an inner path through augmented segments for the cross-segment merge case.
     * Switches between segments at intersection nodes, reversing direction at each crossing
     * to trace the complementary sub-path.
     *
     * @param augSeg0 augmented segment 0 (from sharedA to sharedB)
     * @param augSeg1 augmented segment 1 (from sharedB to sharedA)
     * @param fromNode starting shared node (sharedB)
     * @param toNode ending shared node (sharedA)
     * @param crossingNodes intersection nodes where we switch segments
     * @param startWithSeg1 if true, start on seg1 forward; if false, start on seg0 reversed
     * @return path from fromNode to toNode
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
                dir = -dir; // reverse direction at crossings
            }
        }

        return null;
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
     * Checks if a closed polygon has any non-adjacent edge crossings (self-intersection).
     * Uses the standard 2D segment intersection test via cross products.
     * Adjacent edges (sharing an endpoint) are skipped.
     */
    private static boolean hasNonAdjacentEdgeCrossing(List<Node> way) {
        int n = way.size() - 1; // exclude closing duplicate
        for (int i = 0; i < n; i++) {
            EastNorth a1 = way.get(i).getEastNorth();
            EastNorth a2 = way.get(i + 1).getEastNorth();
            for (int j = i + 2; j < n; j++) {
                if (j == n - 1 && i == 0) continue; // last edge wraps to first — adjacent
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
     * Returns true if segments (a1-a2) and (b1-b2) properly cross each other
     * (not just touch at endpoints).
     */
    private static boolean segmentsCross(EastNorth a1, EastNorth a2, EastNorth b1, EastNorth b2) {
        double d1 = cross(a1, a2, b1);
        double d2 = cross(a1, a2, b2);
        double d3 = cross(b1, b2, a1);
        double d4 = cross(b1, b2, a2);
        // Proper crossing: endpoints of each segment on opposite sides of the other
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
            && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return false;
    }

    /** Cross product of vectors (p1→p2) and (p1→p3). */
    private static double cross(EastNorth p1, EastNorth p2, EastNorth p3) {
        return (p2.east() - p1.east()) * (p3.north() - p1.north())
             - (p2.north() - p1.north()) * (p3.east() - p1.east());
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
     * Builds a merged way from an inner-only segment and an outer path,
     * choosing the inner segment direction that produces a non-self-intersecting
     * polygon.
     *
     * Tries both forward and reversed inner traversals and picks the one with
     * larger absolute signed area. A self-intersecting polygon always has smaller
     * absolute area than the non-self-intersecting version because overlapping
     * "butterfly" regions cancel in the signed area computation.
     *
     * @param boundaryA     the shared boundary at the start of the inner segment
     * @param boundaryB     the shared boundary at the end of the inner segment
     * @param innerSegment  inner-only nodes (excluding boundaries)
     * @param outerPath     the outer path from boundaryB to boundaryA
     * @return a closed way, or null if outerPath is null
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
        boolean fwdSelfIntersects = hasNonAdjacentEdgeCrossing(fwdWay);
        boolean revSelfIntersects = hasNonAdjacentEdgeCrossing(revWay);
        if (fwdSelfIntersects != revSelfIntersects) {
            return fwdSelfIntersects ? revWay : fwdWay;
        }
        // Both or neither self-intersect — fall back to forward
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

    /**
     * Finds inner rings that share a contiguous edge (>= 2 consecutive nodes) with any
     * outer ring and have their own significant tags. Such inners can be extracted from
     * the relation as standalone ways.
     */
    private static List<WayChainBuilder.Ring> findEdgeSharingInners(
            List<WayChainBuilder.Ring> outerRings, List<WayChainBuilder.Ring> innerRings) {
        List<WayChainBuilder.Ring> result = new ArrayList<>();
        for (WayChainBuilder.Ring inner : innerRings) {
            if (!inner.isAlreadyClosed()) continue;
            Way sourceWay = inner.getSourceWays().get(0);
            if (!wayHasOwnTags(sourceWay)) continue;
            for (WayChainBuilder.Ring outer : outerRings) {
                if (sharesContiguousEdge(inner, outer)) {
                    result.add(inner);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Returns true if the way has any tags other than test annotations (keys starting with '_').
     */
    private static boolean wayHasOwnTags(Way way) {
        for (String key : way.keySet()) {
            if (!key.startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the inner ring shares a contiguous run of >= 2 consecutive nodes
     * with the outer ring. Handles wrap-around at ring closure.
     */
    private static boolean sharesContiguousEdge(WayChainBuilder.Ring inner, WayChainBuilder.Ring outer) {
        List<Node> outerNodes = outer.getNodes();
        int outerSize = outerNodes.size() - 1; // exclude closing duplicate

        Set<Node> innerNodeSet = new HashSet<>();
        List<Node> innerNodes = inner.getNodes();
        int innerSize = innerNodes.size() - 1;
        for (int i = 0; i < innerSize; i++) {
            innerNodeSet.add(innerNodes.get(i));
        }

        // Walk the outer ring counting the longest contiguous run of shared nodes.
        // To handle wrap-around, walk 2*outerSize positions but cap maxRun at outerSize.
        int maxRun = 0;
        int currentRun = 0;
        for (int i = 0; i < 2 * outerSize; i++) {
            if (innerNodeSet.contains(outerNodes.get(i % outerSize))) {
                currentRun++;
                if (currentRun > maxRun) {
                    maxRun = currentRun;
                }
            } else {
                currentRun = 0;
            }
            if (maxRun >= 2) return true;
        }
        return false;
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

    /**
     * Returns true if any outer ring is geometrically inside another outer ring
     * without a mediating inner ring justifying the nesting (island-within-a-hole).
     *
     * A nested outer O_candidate inside O_container is VALID if there exists an inner ring I
     * such that O_candidate is inside I and I is inside O_container. This represents
     * the standard OSM "island in a lake" pattern.
     */
    private static boolean hasInvalidNestedOuters(List<WayChainBuilder.Ring> outerRings,
            List<WayChainBuilder.Ring> innerRings) {
        if (outerRings.size() < 2) {
            return false;
        }
        for (int i = 0; i < outerRings.size(); i++) {
            WayChainBuilder.Ring candidate = outerRings.get(i);
            for (int j = 0; j < outerRings.size(); j++) {
                if (i == j) continue;
                WayChainBuilder.Ring container = outerRings.get(j);

                if (!isRingInsideRing(candidate, container)) {
                    continue;
                }

                // Candidate IS inside container. Check if any inner ring mediates:
                // valid if candidate is inside some inner I, and I is inside container.
                boolean justified = false;
                for (WayChainBuilder.Ring inner : innerRings) {
                    if (isRingInsideRing(candidate, inner)
                            && isRingInsideRing(inner, container)) {
                        justified = true;
                        break;
                    }
                }

                if (!justified) {
                    return true; // Invalid nesting — no mediating inner
                }
            }
        }
        return false;
    }

    /**
     * Returns true if ring A is geometrically inside ring B.
     * Tests using the first node of A that is not on B's boundary.
     */
    private static boolean isRingInsideRing(WayChainBuilder.Ring a, WayChainBuilder.Ring b) {
        Set<Node> bNodes = new HashSet<>(b.getNodes());
        for (Node testNode : a.getNodes()) {
            if (bNodes.contains(testNode)) {
                continue;
            }
            return Geometry.nodeInsidePolygon(testNode, b.getNodes());
        }
        // All nodes of A are shared with B — treat as not inside
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

                // Skip if any coordinate is missing (node without valid position)
                if (p1 == null || p2 == null || p3 == null || p4 == null) {
                    continue;
                }

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
        // (e.g., a bowtie that meets at a shared node rather than crossing segments).
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
        String type = relation.get("type");
        return type != null ? type : "relation";
    }
}
