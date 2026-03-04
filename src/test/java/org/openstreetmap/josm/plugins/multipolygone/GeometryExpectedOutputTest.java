package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

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
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.GeometryCanonicalizer.Coord;
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
    // Geometry matching helpers (delegating to GeometryCanonicalizer)
    // ===================================================================

    private List<Way> findExpectedWays(String testId) {
        return expected.getWays().stream()
            .filter(w -> testId.equals(w.get("_test_id")))
            .collect(Collectors.toList());
    }

    private Set<String> getExpectedNodeCoordKeys(List<Way> expectedWays) {
        Set<String> keys = new HashSet<>();
        for (Way w : expectedWays) {
            for (Node n : w.getNodes()) {
                keys.add(String.format("%.6f,%.6f", n.lat(), n.lon()));
            }
        }
        return keys;
    }

    private List<Way> findActualWays(DataSet ds, Set<Way> waysBefore, List<Way> expectedWays) {
        Set<String> expectedCoordKeys = getExpectedNodeCoordKeys(expectedWays);

        return ds.getWays().stream()
            .filter(w -> !w.isDeleted())
            .filter(w -> "water".equals(w.get("natural")))
            .filter(w -> w.getNodes().stream().anyMatch(n ->
                expectedCoordKeys.contains(String.format("%.6f,%.6f", n.lat(), n.lon()))))
            .collect(Collectors.toList());
    }

    private void assertGeometryMatch(String testId, List<Way> actualWays, List<Way> expectedWays) {
        assertEquals(expectedWays.size(), actualWays.size(),
            "Test " + testId + ": expected " + expectedWays.size()
            + " ways but got " + actualWays.size());

        List<List<Coord>> expectedCanonical = expectedWays.stream()
            .map(w -> GeometryCanonicalizer.canonicalize(GeometryCanonicalizer.extractCoords(w)))
            .sorted(Comparator.comparing(GeometryCanonicalizer::coordListKey))
            .collect(Collectors.toList());

        List<List<Coord>> actualCanonical = actualWays.stream()
            .map(w -> GeometryCanonicalizer.canonicalize(GeometryCanonicalizer.extractCoords(w)))
            .sorted(Comparator.comparing(GeometryCanonicalizer::coordListKey))
            .collect(Collectors.toList());

        for (int i = 0; i < expectedCanonical.size(); i++) {
            List<Coord> exp = expectedCanonical.get(i);
            List<Coord> act = actualCanonical.get(i);
            assertTrue(GeometryCanonicalizer.coordListsMatch(act, exp),
                "Test " + testId + ": way " + i + " geometry mismatch.\n"
                + "  Expected: " + GeometryCanonicalizer.coordListKey(exp) + "\n"
                + "  Actual:   " + GeometryCanonicalizer.coordListKey(act));
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

    @Test
    void testCase281_producesExpectedGeometry() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "281");
        assertNotNull(plan, "Test case 281 should be fixable");

        Set<Way> waysBefore = new HashSet<>(ds.getWays());
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted");

        DataSet expected281 = JosmTestSetup.loadDataSet("expected/testdata-test281-expected.osm");
        List<Way> expectedWays = expected281.getWays().stream()
            .filter(w -> "281".equals(w.get("_test_id")))
            .collect(Collectors.toList());
        assertFalse(expectedWays.isEmpty(), "Expected output should have ways for test 281");

        List<Way> actualWays = findActualWays(ds, waysBefore, expectedWays);
        assertGeometryMatch("281", actualWays, expectedWays);
    }

    @Test
    void testCase281_noOrphanedNodes() {
        DataSet ds = freshInput();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "281");

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
