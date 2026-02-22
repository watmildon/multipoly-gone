package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;

/**
 * Tests that verify the plugin's output matches the expected geometry
 * defined in testdata-geometry-actual.osm. Each test loads the input data,
 * runs the fixer on specific test cases, and compares the resulting ways
 * against the expected output by matching coordinate sequences.
 *
 * Matching is geometry-based rather than node-ID-based, because the fixer
 * creates new nodes (e.g., intersection nodes) with different IDs than
 * those in the expected file.
 */
class GeometryExpectedOutputTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    /** Tolerance for coordinate comparison (~1 meter at mid-latitudes). */
    private static final double COORD_TOL = 1e-5;

    /** Expected output loaded from testdata-geometry-actual.osm */
    private DataSet expected;

    @BeforeEach
    void setUp() {
        UndoRedoHandler.getInstance().clean();
        expected = JosmTestSetup.loadDataSet("expected/testdata-geometry-actual.osm");
    }

    private DataSet freshInput() {
        return JosmTestSetup.loadDataSet("testdata-geometry.osm");
    }

    private FixPlan findPlanByTestId(List<FixPlan> plans, String testId) {
        return plans.stream()
            .filter(p -> testId.equals(p.getRelation().get("_test_id")))
            .findFirst()
            .orElse(null);
    }

    // ===================================================================
    // Geometry matching helpers
    // ===================================================================

    /**
     * A coordinate pair with tolerance-based equality.
     */
    private static class Coord {
        final double lat;
        final double lon;

        Coord(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        boolean matches(Coord other) {
            return Math.abs(lat - other.lat) < COORD_TOL
                && Math.abs(lon - other.lon) < COORD_TOL;
        }

        @Override
        public String toString() {
            return String.format("(%.7f, %.7f)", lat, lon);
        }
    }

    /**
     * Returns expected ways for a given test ID from the expected output dataset.
     */
    private List<Way> findExpectedWays(String testId) {
        return expected.getWays().stream()
            .filter(w -> testId.equals(w.get("_test_id")))
            .collect(Collectors.toList());
    }

    /**
     * Extracts all unique (lat, lon) coordinate pairs from the given ways.
     */
    private Set<String> getExpectedNodeCoordKeys(List<Way> expectedWays) {
        Set<String> keys = new HashSet<>();
        for (Way w : expectedWays) {
            for (Node n : w.getNodes()) {
                keys.add(coordKey(n.lat(), n.lon()));
            }
        }
        return keys;
    }

    private String coordKey(double lat, double lon) {
        // Round to ~1cm precision for set-based lookup
        return String.format("%.6f,%.6f", lat, lon);
    }

    /**
     * Finds actual output ways after running the fixer, by matching their
     * node coordinates against expected node coordinates.
     *
     * A way qualifies if:
     * - It is not deleted
     * - It has natural=water
     * - At least one of its nodes has coordinates matching an expected node
     */
    private List<Way> findActualWays(DataSet ds, Set<Way> waysBefore, List<Way> expectedWays) {
        Set<String> expectedCoordKeys = getExpectedNodeCoordKeys(expectedWays);

        return ds.getWays().stream()
            .filter(w -> !w.isDeleted())
            .filter(w -> "water".equals(w.get("natural")))
            .filter(w -> w.getNodes().stream().anyMatch(n ->
                expectedCoordKeys.contains(coordKey(n.lat(), n.lon()))))
            .collect(Collectors.toList());
    }

    /**
     * Extracts the coordinate sequence from a closed way, excluding the
     * closing duplicate node.
     */
    private List<Coord> extractCoords(Way way) {
        List<Node> nodes = way.getNodes();
        if (nodes.isEmpty()) return List.of();

        int size = nodes.size();
        // Remove closing duplicate
        if (size > 1 && nodes.get(0).getId() == nodes.get(size - 1).getId()) {
            size--;
        } else if (size > 1) {
            // Check by coordinate proximity (for new nodes with different IDs)
            Node first = nodes.get(0);
            Node last = nodes.get(size - 1);
            if (Math.abs(first.lat() - last.lat()) < COORD_TOL
                && Math.abs(first.lon() - last.lon()) < COORD_TOL) {
                size--;
            }
        }

        List<Coord> coords = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            coords.add(new Coord(nodes.get(i).lat(), nodes.get(i).lon()));
        }
        return coords;
    }

    /**
     * Normalizes a coordinate sequence to canonical form:
     * 1. Find the index of the coordinate with the smallest lat (then lon as tiebreaker)
     * 2. Try both directions (forward and reverse from that index)
     * 3. Pick the lexicographically smaller direction
     *
     * Returns the normalized coordinate list.
     */
    private List<Coord> canonicalize(List<Coord> coords) {
        if (coords.isEmpty()) return coords;
        int size = coords.size();

        // Find the start index: smallest lat, then smallest lon as tiebreaker
        int minIdx = 0;
        for (int i = 1; i < size; i++) {
            Coord c = coords.get(i);
            Coord m = coords.get(minIdx);
            if (c.lat < m.lat - COORD_TOL
                || (Math.abs(c.lat - m.lat) < COORD_TOL && c.lon < m.lon - COORD_TOL)) {
                minIdx = i;
            }
        }

        // Build forward and reverse from minIdx
        List<Coord> forward = new ArrayList<>();
        List<Coord> reverse = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            forward.add(coords.get((minIdx + i) % size));
            reverse.add(coords.get((minIdx - i + size) % size));
        }

        // Pick canonical direction by lexicographic comparison
        int cmp = compareCoordLists(forward, reverse);
        return cmp <= 0 ? forward : reverse;
    }

    /**
     * Lexicographically compares two coordinate lists.
     * Compares lat first, then lon, with tolerance.
     */
    private int compareCoordLists(List<Coord> a, List<Coord> b) {
        int size = Math.min(a.size(), b.size());
        for (int i = 0; i < size; i++) {
            Coord ca = a.get(i);
            Coord cb = b.get(i);
            if (Math.abs(ca.lat - cb.lat) >= COORD_TOL) {
                return Double.compare(ca.lat, cb.lat);
            }
            if (Math.abs(ca.lon - cb.lon) >= COORD_TOL) {
                return Double.compare(ca.lon, cb.lon);
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /**
     * Converts a canonical coordinate list to a string for comparison and sorting.
     */
    private String coordListKey(List<Coord> coords) {
        StringBuilder sb = new StringBuilder();
        for (Coord c : coords) {
            if (sb.length() > 0) sb.append("|");
            sb.append(String.format("%.6f,%.6f", c.lat, c.lon));
        }
        return sb.toString();
    }

    /**
     * Checks if two canonical coordinate lists match within tolerance.
     */
    private boolean coordListsMatch(List<Coord> a, List<Coord> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).matches(b.get(i))) return false;
        }
        return true;
    }

    /**
     * Asserts that the actual ways match the expected ways by geometry.
     * Each actual way's canonical coordinate sequence must match exactly one
     * expected way's canonical coordinate sequence (1:1 bijection).
     */
    private void assertGeometryMatch(String testId, List<Way> actualWays, List<Way> expectedWays) {
        assertEquals(expectedWays.size(), actualWays.size(),
            "Test " + testId + ": expected " + expectedWays.size()
            + " ways but got " + actualWays.size());

        // Canonicalize all expected ways
        List<List<Coord>> expectedCanonical = expectedWays.stream()
            .map(w -> canonicalize(extractCoords(w)))
            .sorted(Comparator.comparing(this::coordListKey))
            .collect(Collectors.toList());

        // Canonicalize all actual ways
        List<List<Coord>> actualCanonical = actualWays.stream()
            .map(w -> canonicalize(extractCoords(w)))
            .sorted(Comparator.comparing(this::coordListKey))
            .collect(Collectors.toList());

        // Match each pair
        for (int i = 0; i < expectedCanonical.size(); i++) {
            List<Coord> exp = expectedCanonical.get(i);
            List<Coord> act = actualCanonical.get(i);
            assertTrue(coordListsMatch(act, exp),
                "Test " + testId + ": way " + i + " geometry mismatch.\n"
                + "  Expected: " + coordListKey(exp) + "\n"
                + "  Actual:   " + coordListKey(act));
        }
    }

    // ===================================================================
    // No-orphaned-nodes helper
    // ===================================================================

    private void assertNoOrphanedNodes(FixPlan plan) {
        Set<Node> relatedNodes = new HashSet<>();
        for (var member : plan.getRelation().getMembers()) {
            if (member.isWay()) relatedNodes.addAll(member.getWay().getNodes());
        }

        MultipolygonFixer.fixRelations(List.of(plan));

        for (Node node : relatedNodes) {
            if (node.isDeleted()) continue;
            boolean referenced = node.getReferrers().stream()
                .anyMatch(r -> r instanceof Way && !r.isDeleted());
            assertTrue(referenced, "Node " + node.getId() + " should be referenced");
        }
    }

    // ===================================================================
    // Test case 201: DECOMPOSE vertex-touching bowtie
    // ===================================================================

    @Test
    void testCase201_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "201");
        assertNotNull(plan, "Test case 201 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("201");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 201");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("201", actualWays, expectedWays);
    }

    @Test
    void testCase201_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "201");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Test case 260: self-intersecting inner merge with intersection nodes
    // ===================================================================

    @Test
    void testCase260_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "260");
        assertNotNull(plan, "Test case 260 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("260");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 260");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("260", actualWays, expectedWays);
    }

    @Test
    void testCase260_createsIntersectionNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "260");

        Set<Node> nodesBefore = new HashSet<>(ds.getNodes());
        MultipolygonFixer.fixRelations(List.of(plan));

        // The inner segments cross each other (bridging inner with overlapping edges),
        // so the merge must insert intersection nodes where the inner edges cross.
        List<Node> newNodes = ds.getNodes().stream()
            .filter(n -> !n.isDeleted() && !nodesBefore.contains(n))
            .collect(Collectors.toList());
        assertEquals(2, newNodes.size(),
            "Should create 2 intersection nodes where inner edges cross, got " + newNodes.size());
    }

    @Test
    void testCase260_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "260");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Test case 262: EXTRACT + TOUCHING_INNER_MERGE
    // ===================================================================

    @Test
    void testCase262_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "262");
        assertNotNull(plan, "Test case 262 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("262");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 262");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("262", actualWays, expectedWays);
    }

    @Test
    void testCase262_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "262");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Test case 263: chain of 3 dissolves (test_ids 263, 264, 265)
    // ===================================================================

    @Test
    void testCase263_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        // Find all 3 chain relations (263, 264, 265)
        List<FixPlan> chainPlans = plans.stream()
            .filter(p -> {
                String id = p.getRelation().get("_test_id");
                return "263".equals(id) || "264".equals(id) || "265".equals(id);
            })
            .collect(Collectors.toList());
        assertFalse(chainPlans.isEmpty(), "Should find chain relation plans");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(chainPlans);

        List<Way> expectedWays = findExpectedWays("263");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 263");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("263", actualWays, expectedWays);
    }

    // ===================================================================
    // Test case 280: TOUCHING_INNER_MERGE with complementary paths
    // ===================================================================

    @Test
    void testCase280_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "280");
        assertNotNull(plan, "Test case 280 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("280");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 280");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("280", actualWays, expectedWays);
    }

    @Test
    void testCase280_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "280");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Test case 260 with Mercator projection (matches JOSM default)
    // ===================================================================

    @Test
    void testCase260_worksWithMercator() {
        // Switch to Mercator projection like JOSM uses by default
        org.openstreetmap.josm.data.projection.Projection oldProj =
            org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        try {
            org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(
                org.openstreetmap.josm.data.projection.Projections.getProjectionByCode("EPSG:3857"));

            DataSet ds = freshInput();
            List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
            FixPlan plan = findPlanByTestId(plans, "260");
            assertNotNull(plan, "Test case 260 should be fixable under Mercator projection");

            Set<Way> waysBefore = new HashSet<>(ds.getWays());
            MultipolygonFixer.fixRelations(List.of(plan));

            assertTrue(plan.getRelation().isDeleted(),
                "Relation should be deleted under Mercator projection");

            List<Way> expectedWays = findExpectedWays("260");
            List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
            assertGeometryMatch("260", actualWays, expectedWays);
        } finally {
            // Restore original projection
            org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(oldProj);
        }
    }

    // ===================================================================
    // Test case 206: large inner sharing 5 consecutive nodes with outer
    // ===================================================================

    @Test
    void testCase206_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "206");
        assertNotNull(plan, "Test case 206 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("206");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 206");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("206", actualWays, expectedWays);
    }

    @Test
    void testCase206_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "206");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Test case 207: large inner sharing 6 of 12 nodes (half the ring)
    // ===================================================================

    @Test
    void testCase207_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "207");
        assertNotNull(plan, "Test case 207 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("207");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 207");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("207", actualWays, expectedWays);
    }

    @Test
    void testCase207_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "207");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Test case 208: inner sharing 2 non-adjacent nodes (opposite sides)
    // ===================================================================

    @Test
    void testCase208_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "208");
        assertNotNull(plan, "Test case 208 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        List<Way> expectedWays = findExpectedWays("208");
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 208");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("208", actualWays, expectedWays);
    }

    @Test
    void testCase208_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "208");

        assertNoOrphanedNodes(plan);
    }

    // ===================================================================
    // Undo/Redo consistency
    // ===================================================================

    @Test
    void undoRedo_consistentForGeometryTests() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        int relsBefore = (int) ds.getRelations().stream().filter(r -> !r.isDeleted()).count();

        MultipolygonFixer.fixRelations(plans);

        // Undo
        assertTrue(UndoRedoHandler.getInstance().hasUndoCommands());
        UndoRedoHandler.getInstance().undo();

        int relsAfterUndo = (int) ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
        assertEquals(relsBefore, relsAfterUndo, "Undo should restore all relations");

        // Redo
        assertTrue(UndoRedoHandler.getInstance().hasRedoCommands());
        UndoRedoHandler.getInstance().redo();
    }
}
