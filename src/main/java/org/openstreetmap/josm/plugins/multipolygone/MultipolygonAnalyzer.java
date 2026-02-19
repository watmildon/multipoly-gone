package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Geometry;

public class MultipolygonAnalyzer {

    public enum FixType {
        /** All outers are already individual closed ways, no inners. Dissolve completely. */
        SEPARATE_CLOSED_WAYS,
        /** All outers chain into a single closed loop, no inners. Dissolve completely. */
        CHAINABLE_OUTERS,
        /** Outers form multiple rings (some may need chaining), no inners. Dissolve completely. */
        DISJOINT_OUTERS,
        /** Has inners. Some outer rings have no inner and can be extracted. */
        EXTRACTABLE_OUTERS
    }

    public static class FixableRelation {
        private final Relation relation;
        private final FixType fixType;
        private final String description;
        private final List<Node> chainedNodes;
        private final List<WayChainBuilder.Ring> extractableRings;
        private final List<WayChainBuilder.Ring> retainedRings;

        /** Constructor for SEPARATE_CLOSED_WAYS (no extra data needed). */
        public FixableRelation(Relation relation, FixType fixType, String description) {
            this(relation, fixType, description, null, null, null);
        }

        /** Constructor for CHAINABLE_OUTERS (single chain). */
        public FixableRelation(Relation relation, FixType fixType, String description, List<Node> chainedNodes) {
            this(relation, fixType, description, chainedNodes, null, null);
        }

        /** Full constructor. */
        public FixableRelation(Relation relation, FixType fixType, String description,
                List<Node> chainedNodes, List<WayChainBuilder.Ring> extractableRings,
                List<WayChainBuilder.Ring> retainedRings) {
            this.relation = relation;
            this.fixType = fixType;
            this.description = description;
            this.chainedNodes = chainedNodes;
            this.extractableRings = extractableRings;
            this.retainedRings = retainedRings;
        }

        public Relation getRelation() {
            return relation;
        }

        public FixType getFixType() {
            return fixType;
        }

        public String getDescription() {
            return description;
        }

        public List<Node> getChainedNodes() {
            return chainedNodes;
        }

        public List<WayChainBuilder.Ring> getExtractableRings() {
            return extractableRings;
        }

        /** Outer rings that stay in the relation (have inners). May need chaining. */
        public List<WayChainBuilder.Ring> getRetainedRings() {
            return retainedRings;
        }
    }

    private static final int MAX_WAY_NODES = 2000;

    public static List<FixableRelation> findFixableRelations(DataSet dataSet) {
        List<FixableRelation> results = new ArrayList<>();
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

            FixableRelation fixable = analyze(relation);
            if (fixable != null) {
                results.add(fixable);
            }
        }
        return results;
    }

    private static FixableRelation analyze(Relation relation) {
        List<Way> outerWays = new ArrayList<>();
        List<Way> innerWays = new ArrayList<>();
        boolean hasInners = false;

        for (RelationMember member : relation.getMembers()) {
            // All members must be ways
            if (!member.isWay()) {
                return null;
            }
            Way way = member.getWay();
            if (way.isDeleted() || way.isIncomplete() || way.getNodesCount() < 2) {
                return null;
            }
            if ("inner".equals(member.getRole())) {
                hasInners = true;
                innerWays.add(way);
            } else {
                outerWays.add(way);
            }
        }

        if (outerWays.isEmpty()) {
            return null;
        }

        if (hasInners) {
            return analyzeWithInners(relation, outerWays, innerWays);
        }

        return analyzeOutersOnly(relation, outerWays);
    }

    private static FixableRelation analyzeOutersOnly(Relation relation, List<Way> outerWays) {
        // Check if all members are already closed ways
        boolean allClosed = outerWays.stream().allMatch(Way::isClosed);
        if (allClosed) {
            String primaryTag = getPrimaryTag(relation);
            String desc = outerWays.size() == 1
                ? String.format("1 closed outer (%s)", primaryTag)
                : String.format("%d separate closed outers (%s)", outerWays.size(), primaryTag);
            return new FixableRelation(relation, FixType.SEPARATE_CLOSED_WAYS, desc);
        }

        // Try to chain all ways into a single closed loop
        Optional<List<Node>> chain = WayChainBuilder.buildChain(outerWays);
        if (chain.isPresent()) {
            List<Node> nodes = chain.get();
            if (nodes.size() - 1 > MAX_WAY_NODES) {
                return null;
            }
            String primaryTag = getPrimaryTag(relation);
            String desc = String.format("%d ways forming loop (%s)", outerWays.size(), primaryTag);
            return new FixableRelation(relation, FixType.CHAINABLE_OUTERS, desc, nodes);
        }

        // Try to group into multiple disjoint rings
        Optional<List<WayChainBuilder.Ring>> rings = WayChainBuilder.buildRings(outerWays);
        if (rings.isPresent()) {
            List<WayChainBuilder.Ring> ringList = rings.get();
            if (ringList.size() < 2) {
                // Single ring should have been caught above
                return null;
            }
            // Check node limits on any ring that needs chaining
            for (WayChainBuilder.Ring ring : ringList) {
                if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                    return null;
                }
            }
            String primaryTag = getPrimaryTag(relation);
            String desc = String.format("%d disjoint outer rings (%s)", ringList.size(), primaryTag);
            return new FixableRelation(relation, FixType.DISJOINT_OUTERS, desc, null, ringList, null);
        }

        return null;
    }

    private static FixableRelation analyzeWithInners(Relation relation, List<Way> outerWays, List<Way> innerWays) {
        // All inners must be closed ways
        if (!innerWays.stream().allMatch(Way::isClosed)) {
            return null;
        }

        // Group outers into rings
        Optional<List<WayChainBuilder.Ring>> ringsOpt = WayChainBuilder.buildRings(outerWays);
        if (ringsOpt.isEmpty()) {
            return null;
        }

        List<WayChainBuilder.Ring> outerRings = ringsOpt.get();

        // Need at least 2 outer rings for partial extraction to make sense
        if (outerRings.size() < 2) {
            return null;
        }

        // Check node limits on rings that need chaining
        for (WayChainBuilder.Ring ring : outerRings) {
            if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                return null;
            }
        }

        // For each inner, determine which outer ring contains it
        Map<WayChainBuilder.Ring, Integer> innerCount = new HashMap<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            innerCount.put(ring, 0);
        }

        for (Way inner : innerWays) {
            WayChainBuilder.Ring containingRing = findContainingRing(inner, outerRings);
            if (containingRing == null) {
                return null;
            }
            innerCount.merge(containingRing, 1, Integer::sum);
        }

        // Separate rings into extractable (no inners) and retained (has inners)
        List<WayChainBuilder.Ring> extractable = new ArrayList<>();
        List<WayChainBuilder.Ring> retained = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            if (innerCount.get(ring) == 0) {
                extractable.add(ring);
            } else {
                retained.add(ring);
            }
        }

        if (extractable.isEmpty() || extractable.size() == outerRings.size()) {
            return null;
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = extractable.size() == 1
            ? String.format("1 extractable outer ring (%s)", primaryTag)
            : String.format("%d extractable outer rings (%s)", extractable.size(), primaryTag);
        return new FixableRelation(relation, FixType.EXTRACTABLE_OUTERS, desc, null, extractable, retained);
    }

    private static WayChainBuilder.Ring findContainingRing(Way inner, List<WayChainBuilder.Ring> outerRings) {
        for (WayChainBuilder.Ring ring : outerRings) {
            if (Geometry.nodeInsidePolygon(inner.firstNode(), ring.getNodes())) {
                return ring;
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
