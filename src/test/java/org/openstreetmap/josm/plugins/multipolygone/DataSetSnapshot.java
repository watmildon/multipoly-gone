package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Captures a canonical, ID-independent snapshot of a DataSet's state
 * for regression comparison. Identity is based on geometry (coordinate
 * sequences) and tags rather than node/way/relation IDs, since the fixer
 * creates new primitives with unstable auto-assigned IDs.
 */
class DataSetSnapshot {

    /** Snapshot of a surviving relation. */
    static class RelationSnapshot {
        final long originalId;
        final Map<String, String> tags;
        final List<MemberSnapshot> members;

        RelationSnapshot(long originalId, Map<String, String> tags, List<MemberSnapshot> members) {
            this.originalId = originalId;
            this.tags = tags;
            this.members = members;
        }
    }

    /** Snapshot of a relation member (way). */
    static class MemberSnapshot {
        final String role;
        final String geometryKey;
        final Map<String, String> wayTags;
        final boolean closed;

        MemberSnapshot(String role, String geometryKey, Map<String, String> wayTags, boolean closed) {
            this.role = role;
            this.geometryKey = geometryKey;
            this.wayTags = wayTags;
            this.closed = closed;
        }
    }

    /** Snapshot of a standalone way (not in any surviving relation). */
    static class WaySnapshot {
        final String geometryKey;
        final Map<String, String> tags;
        final boolean closed;

        WaySnapshot(String geometryKey, Map<String, String> tags, boolean closed) {
            this.geometryKey = geometryKey;
            this.tags = tags;
            this.closed = closed;
        }
    }

    final List<RelationSnapshot> relations;
    final List<WaySnapshot> standaloneWays;

    private DataSetSnapshot(List<RelationSnapshot> relations, List<WaySnapshot> standaloneWays) {
        this.relations = relations;
        this.standaloneWays = standaloneWays;
    }

    /**
     * Captures a snapshot of the current DataSet state.
     */
    static DataSetSnapshot capture(DataSet ds) {
        // Surviving relations, sorted by original ID for stability
        List<RelationSnapshot> relations = ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .sorted(Comparator.comparingLong(Relation::getUniqueId))
            .map(DataSetSnapshot::snapshotRelation)
            .collect(Collectors.toList());

        // Ways that are in surviving relations
        var waysInRelations = ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .flatMap(r -> r.getMembers().stream())
            .filter(RelationMember::isWay)
            .map(m -> m.getWay().getUniqueId())
            .collect(Collectors.toSet());

        // Standalone ways: surviving, not in any surviving relation
        List<WaySnapshot> standaloneWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted())
            .filter(w -> !waysInRelations.contains(w.getUniqueId()))
            .map(DataSetSnapshot::snapshotWay)
            .sorted(Comparator.comparing(ws -> ws.geometryKey))
            .collect(Collectors.toList());

        return new DataSetSnapshot(relations, standaloneWays);
    }

    private static RelationSnapshot snapshotRelation(Relation rel) {
        Map<String, String> tags = filterTags(rel.getKeys());

        List<MemberSnapshot> members = new ArrayList<>();
        for (RelationMember m : rel.getMembers()) {
            if (m.isWay()) {
                Way way = m.getWay();
                members.add(new MemberSnapshot(
                    m.getRole(),
                    GeometryCanonicalizer.wayGeometryKey(way),
                    filterTags(way.getKeys()),
                    way.isClosed()
                ));
            }
        }

        return new RelationSnapshot(rel.getUniqueId(), tags, members);
    }

    private static WaySnapshot snapshotWay(Way way) {
        return new WaySnapshot(
            GeometryCanonicalizer.wayGeometryKey(way),
            filterTags(way.getKeys()),
            way.isClosed()
        );
    }

    /** Filter out test metadata tags (keys starting with _). */
    private static Map<String, String> filterTags(Map<String, String> tags) {
        TreeMap<String, String> filtered = new TreeMap<>();
        for (var entry : tags.entrySet()) {
            if (!entry.getKey().startsWith("_")) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    // ===================================================================
    // Diff
    // ===================================================================

    /**
     * Compares two snapshots and returns a human-readable diff report.
     * Returns an empty string if the snapshots are identical.
     */
    static String diff(DataSetSnapshot expected, DataSetSnapshot actual) {
        StringBuilder sb = new StringBuilder();

        diffRelations(expected.relations, actual.relations, sb);
        diffStandaloneWays(expected.standaloneWays, actual.standaloneWays, sb);
        diffSummary(expected, actual, sb);

        return sb.toString();
    }

    private static void diffRelations(List<RelationSnapshot> expected, List<RelationSnapshot> actual,
                                       StringBuilder sb) {
        // Separate stable-ID relations (positive IDs from input) from
        // new relations (negative IDs, unstable between runs)
        Map<Long, RelationSnapshot> expectedStable = new LinkedHashMap<>();
        List<RelationSnapshot> expectedNew = new ArrayList<>();
        for (RelationSnapshot r : expected) {
            if (r.originalId > 0) expectedStable.put(r.originalId, r);
            else expectedNew.add(r);
        }

        Map<Long, RelationSnapshot> actualStable = new LinkedHashMap<>();
        List<RelationSnapshot> actualNew = new ArrayList<>();
        for (RelationSnapshot r : actual) {
            if (r.originalId > 0) actualStable.put(r.originalId, r);
            else actualNew.add(r);
        }

        boolean headerWritten = false;

        // --- Stable-ID relations: match by ID ---
        for (var entry : expectedStable.entrySet()) {
            if (!actualStable.containsKey(entry.getKey())) {
                if (!headerWritten) { sb.append("== RELATIONS ==\n"); headerWritten = true; }
                sb.append("  RELATION ").append(entry.getKey()).append(": REMOVED ")
                    .append(formatTags(entry.getValue().tags)).append("\n");
            }
        }
        for (var entry : actualStable.entrySet()) {
            if (!expectedStable.containsKey(entry.getKey())) {
                if (!headerWritten) { sb.append("== RELATIONS ==\n"); headerWritten = true; }
                sb.append("  RELATION ").append(entry.getKey()).append(": ADDED ")
                    .append(formatTags(entry.getValue().tags)).append("\n");
            }
        }
        for (var entry : expectedStable.entrySet()) {
            RelationSnapshot act = actualStable.get(entry.getKey());
            if (act == null) continue;
            RelationSnapshot exp = entry.getValue();

            StringBuilder relDiff = new StringBuilder();
            diffTags(exp.tags, act.tags, relDiff, "    ");
            diffMembers(exp.members, act.members, relDiff);

            if (relDiff.length() > 0) {
                if (!headerWritten) { sb.append("== RELATIONS ==\n"); headerWritten = true; }
                sb.append("  RELATION ").append(entry.getKey()).append(":\n");
                sb.append(relDiff);
            }
        }

        // --- New relations (negative IDs): match by content key (tags + members) ---
        List<String> expectedNewKeys = expectedNew.stream()
            .map(DataSetSnapshot::relationContentKey)
            .sorted()
            .collect(Collectors.toList());
        List<String> actualNewKeys = actualNew.stream()
            .map(DataSetSnapshot::relationContentKey)
            .sorted()
            .collect(Collectors.toList());

        List<String> unmatchedExpected = new ArrayList<>(expectedNewKeys);
        List<String> unmatchedActual = new ArrayList<>(actualNewKeys);
        for (String key : expectedNewKeys) {
            unmatchedActual.remove(key);
        }
        for (String key : actualNewKeys) {
            unmatchedExpected.remove(key);
        }

        for (String key : unmatchedExpected) {
            if (!headerWritten) { sb.append("== RELATIONS ==\n"); headerWritten = true; }
            sb.append("  RELATION (new): REMOVED ").append(abbreviateRelationKey(key)).append("\n");
        }
        for (String key : unmatchedActual) {
            if (!headerWritten) { sb.append("== RELATIONS ==\n"); headerWritten = true; }
            sb.append("  RELATION (new): ADDED ").append(abbreviateRelationKey(key)).append("\n");
        }

        if (headerWritten) sb.append("\n");
    }

    /** Content-based key for matching newly created relations across runs. */
    private static String relationContentKey(RelationSnapshot r) {
        String tagsPart = formatTags(r.tags);
        String membersPart = r.members.stream()
            .map(DataSetSnapshot::memberKey)
            .sorted()
            .collect(Collectors.joining(";"));
        return tagsPart + "|" + membersPart;
    }

    private static String abbreviateRelationKey(String key) {
        if (key.length() <= 120) return key;
        return key.substring(0, 117) + "...";
    }

    private static void diffMembers(List<MemberSnapshot> expected, List<MemberSnapshot> actual,
                                     StringBuilder sb) {
        // Build sorted canonical keys for comparison
        List<String> expectedKeys = expected.stream()
            .map(DataSetSnapshot::memberKey)
            .sorted()
            .collect(Collectors.toList());
        List<String> actualKeys = actual.stream()
            .map(DataSetSnapshot::memberKey)
            .sorted()
            .collect(Collectors.toList());

        // Simple set-based diff
        var expectedSet = new ArrayList<>(expectedKeys);
        var actualSet = new ArrayList<>(actualKeys);

        for (String key : expectedKeys) {
            if (!actualSet.remove(key)) {
                sb.append("    MEMBER REMOVED: ").append(key).append("\n");
            }
        }
        for (String key : actualKeys) {
            if (!expectedSet.remove(key)) {
                sb.append("    MEMBER ADDED: ").append(key).append("\n");
            }
        }
    }

    private static String memberKey(MemberSnapshot m) {
        String geomAbbrev = abbreviateGeometry(m.geometryKey);
        return m.role + " " + geomAbbrev + " " + formatTags(m.wayTags)
            + (m.closed ? " closed" : " open");
    }

    private static void diffStandaloneWays(List<WaySnapshot> expected, List<WaySnapshot> actual,
                                            StringBuilder sb) {
        // Match by geometry key
        Map<String, WaySnapshot> expectedByGeom = new LinkedHashMap<>();
        for (WaySnapshot w : expected) expectedByGeom.put(w.geometryKey, w);

        Map<String, WaySnapshot> actualByGeom = new LinkedHashMap<>();
        for (WaySnapshot w : actual) actualByGeom.put(w.geometryKey, w);

        boolean headerWritten = false;

        // Ways in expected but not in actual
        for (var entry : expectedByGeom.entrySet()) {
            if (!actualByGeom.containsKey(entry.getKey())) {
                if (!headerWritten) { sb.append("== STANDALONE WAYS ==\n"); headerWritten = true; }
                WaySnapshot w = entry.getValue();
                sb.append("  WAY REMOVED: ").append(abbreviateGeometry(w.geometryKey))
                    .append(" ").append(formatTags(w.tags))
                    .append(w.closed ? " closed" : " open").append("\n");
            }
        }

        // Ways in actual but not in expected
        for (var entry : actualByGeom.entrySet()) {
            if (!expectedByGeom.containsKey(entry.getKey())) {
                if (!headerWritten) { sb.append("== STANDALONE WAYS ==\n"); headerWritten = true; }
                WaySnapshot w = entry.getValue();
                sb.append("  WAY ADDED: ").append(abbreviateGeometry(w.geometryKey))
                    .append(" ").append(formatTags(w.tags))
                    .append(w.closed ? " closed" : " open").append("\n");
            }
        }

        // Ways with same geometry but different tags
        for (var entry : expectedByGeom.entrySet()) {
            WaySnapshot act = actualByGeom.get(entry.getKey());
            if (act == null) continue;
            WaySnapshot exp = entry.getValue();

            StringBuilder wayDiff = new StringBuilder();
            diffTags(exp.tags, act.tags, wayDiff, "    ");
            if (exp.closed != act.closed) {
                wayDiff.append("    closed: ").append(exp.closed).append(" -> ").append(act.closed).append("\n");
            }

            if (wayDiff.length() > 0) {
                if (!headerWritten) { sb.append("== STANDALONE WAYS ==\n"); headerWritten = true; }
                sb.append("  WAY CHANGED: ").append(abbreviateGeometry(entry.getKey())).append("\n");
                sb.append(wayDiff);
            }
        }

        if (headerWritten) sb.append("\n");
    }

    private static void diffTags(Map<String, String> expected, Map<String, String> actual,
                                  StringBuilder sb, String indent) {
        for (var entry : expected.entrySet()) {
            String actVal = actual.get(entry.getKey());
            if (actVal == null) {
                sb.append(indent).append("TAG REMOVED: ").append(entry.getKey())
                    .append("=").append(entry.getValue()).append("\n");
            } else if (!actVal.equals(entry.getValue())) {
                sb.append(indent).append("TAG CHANGED: ").append(entry.getKey())
                    .append(" = ").append(entry.getValue())
                    .append(" -> ").append(actVal).append("\n");
            }
        }
        for (var entry : actual.entrySet()) {
            if (!expected.containsKey(entry.getKey())) {
                sb.append(indent).append("TAG ADDED: ").append(entry.getKey())
                    .append("=").append(entry.getValue()).append("\n");
            }
        }
    }

    private static void diffSummary(DataSetSnapshot expected, DataSetSnapshot actual, StringBuilder sb) {
        boolean headerWritten = false;

        if (expected.relations.size() != actual.relations.size()) {
            if (!headerWritten) { sb.append("== SUMMARY ==\n"); headerWritten = true; }
            sb.append("  relations: ").append(expected.relations.size())
                .append(" -> ").append(actual.relations.size()).append("\n");
        }
        if (expected.standaloneWays.size() != actual.standaloneWays.size()) {
            if (!headerWritten) { sb.append("== SUMMARY ==\n"); headerWritten = true; }
            sb.append("  standalone ways: ").append(expected.standaloneWays.size())
                .append(" -> ").append(actual.standaloneWays.size()).append("\n");
        }
    }

    // ===================================================================
    // Formatting helpers
    // ===================================================================

    private static String formatTags(Map<String, String> tags) {
        if (tags.isEmpty()) return "[]";
        return "[" + tags.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", ")) + "]";
    }

    /** Abbreviate a long geometry key to first/last coord for readability. */
    private static String abbreviateGeometry(String geometryKey) {
        if (geometryKey.length() <= 80) return "(" + geometryKey + ")";
        String[] parts = geometryKey.split("\\|");
        if (parts.length <= 2) return "(" + geometryKey + ")";
        return "(" + parts[0] + "|...|" + parts[parts.length - 1]
            + " [" + parts.length + " nodes])";
    }
}
