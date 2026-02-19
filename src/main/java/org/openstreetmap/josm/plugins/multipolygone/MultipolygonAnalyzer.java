package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

public class MultipolygonAnalyzer {

    public enum FixType {
        SEPARATE_CLOSED_WAYS,
        CHAINABLE_OUTERS
    }

    public static class FixableRelation {
        private final Relation relation;
        private final FixType fixType;
        private final String description;
        private final List<Node> chainedNodes;

        public FixableRelation(Relation relation, FixType fixType, String description, List<Node> chainedNodes) {
            this.relation = relation;
            this.fixType = fixType;
            this.description = description;
            this.chainedNodes = chainedNodes;
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
        List<Way> memberWays = new ArrayList<>();

        for (RelationMember member : relation.getMembers()) {
            // Skip if any member has role "inner" — geometrically necessary
            if ("inner".equals(member.getRole())) {
                return null;
            }
            // All members must be ways
            if (!member.isWay()) {
                return null;
            }
            Way way = member.getWay();
            if (way.isDeleted() || way.isIncomplete() || way.getNodesCount() < 2) {
                return null;
            }
            memberWays.add(way);
        }

        if (memberWays.isEmpty()) {
            return null;
        }

        // Check if all members are already closed ways
        boolean allClosed = memberWays.stream().allMatch(Way::isClosed);
        if (allClosed) {
            String primaryTag = getPrimaryTag(relation);
            String desc = memberWays.size() == 1
                ? String.format("1 closed outer (%s)", primaryTag)
                : String.format("%d separate closed outers (%s)", memberWays.size(), primaryTag);
            return new FixableRelation(relation, FixType.SEPARATE_CLOSED_WAYS, desc, null);
        }

        // Try to chain the ways into a closed loop
        Optional<List<Node>> chain = WayChainBuilder.buildChain(memberWays);
        if (chain.isPresent()) {
            List<Node> nodes = chain.get();
            // Check node count limit (subtract 1 because the closing node is a duplicate)
            if (nodes.size() - 1 > MAX_WAY_NODES) {
                return null;
            }
            String primaryTag = getPrimaryTag(relation);
            String desc = String.format("%d ways forming loop (%s)", memberWays.size(), primaryTag);
            return new FixableRelation(relation, FixType.CHAINABLE_OUTERS, desc, nodes);
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
