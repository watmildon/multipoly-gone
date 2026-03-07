package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Data model classes for the multipolygon analysis pipeline.
 * These were extracted from MultipolygonAnalyzer to reduce file size.
 */
class AnalyzerModel {
    // This class exists only as a namespace. All model classes are top-level in this file.
    private AnalyzerModel() {}
}

// ---- Enums ----

enum FixOpType {
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
enum SkipReason {
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

// ---- Data Classes ----

/** A relation that was analyzed but not fixable, with the reason why. */
class SkipResult {
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
class AnalysisResult {
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
class ComponentResult {
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
class DecomposedRing {
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
class ConsolidatedInnerGroup {
    private final List<WayChainBuilder.Ring> sourceRings;
    private final WayChainBuilder.Ring mergedRing;

    ConsolidatedInnerGroup(List<WayChainBuilder.Ring> sourceRings, WayChainBuilder.Ring mergedRing) {
        this.sourceRings = sourceRings;
        this.mergedRing = mergedRing;
    }

    public List<WayChainBuilder.Ring> getSourceRings() { return sourceRings; }
    public WayChainBuilder.Ring getMergedRing() { return mergedRing; }
}

class FixOp {
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

class FixPlan {
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

    static void validateRingsClosed(List<WayChainBuilder.Ring> rings, String relId, String context) {
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

    static String fingerprintOp(FixOp op) {
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
