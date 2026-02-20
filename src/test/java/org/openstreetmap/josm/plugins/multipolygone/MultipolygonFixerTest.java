package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;

class MultipolygonFixerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeEach
    void clearUndoStack() {
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }
    }

    private DataSet freshDataSet() {
        return JosmTestSetup.loadDataSet("testdata.osm");
    }

    private FixPlan findPlanByTestId(List<FixPlan> plans, String testId) {
        return plans.stream()
            .filter(p -> testId.equals(p.getRelation().get("_test_id")))
            .findFirst()
            .orElse(null);
    }

    // --- Test case 1: DISSOLVE ---

    @Test
    void testCase1_dissolve_deletesRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "1");
        assertNotNull(plan);

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after dissolve");
    }

    @Test
    void testCase1_dissolve_transfersTags() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "1");

        MultipolygonFixer.fixRelations(List.of(plan));

        List<Way> taggedWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "wood".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertFalse(taggedWays.isEmpty(),
            "At least one way should have natural=wood after dissolve");
    }

    // --- Test case 2: CONSOLIDATE + DISSOLVE ---

    @Test
    void testCase2_consolidateAndDissolve_createsClosedWay() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "2");
        assertNotNull(plan);

        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted());
        List<Way> grassWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grass".equals(w.get("landuse")))
            .collect(Collectors.toList());
        assertFalse(grassWays.isEmpty(), "Should have way with landuse=grass");
        for (Way w : grassWays) {
            assertTrue(w.isClosed(), "Consolidated way should be closed");
        }
    }

    // --- Test case 5: EXTRACT_OUTERS (relation kept) ---

    @Test
    void testCase5_extractOuters_keepsRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "5");
        assertNotNull(plan);

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Relation should be kept after extract");
        assertEquals(2, relation.getMembersCount(),
            "Relation should have 1 outer + 1 inner remaining");
        assertTrue(relation.getMembers().stream()
            .anyMatch(m -> "inner".equals(m.getRole())),
            "Relation should still have an inner member");

        // Extracted outer should be a standalone tagged way
        List<Way> extractedWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grass".equals(w.get("landuse"))
                && w.getReferrers().stream().noneMatch(r -> r instanceof org.openstreetmap.josm.data.osm.Relation))
            .collect(Collectors.toList());
        assertFalse(extractedWays.isEmpty(),
            "Should have at least one standalone way with landuse=grass");
    }

    // --- Test case 6: EXTRACT_OUTERS (relation kept) ---

    @Test
    void testCase6_extractOuters_keepsRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "6");
        assertNotNull(plan);

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Relation should be kept after extract");
        assertTrue(relation.getMembers().stream()
            .anyMatch(m -> "inner".equals(m.getRole())),
            "Relation should still have an inner member");
        assertTrue(relation.getMembers().stream()
            .anyMatch(m -> "outer".equals(m.getRole())),
            "Relation should still have an outer member");
    }

    // --- Test case 6.2: cross-relation consolidation preserves tags ---

    @Test
    void testCase6_2_consolidatedInnerRetainsTags() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        // Find both 6.2 relations: one is the outer (landuse=grass, 3 outers)
        // and the other is the container (natural=water, 1 outer + 3 inners).
        // When both are fixed together, the consolidated inner way should keep
        // the landuse=grass tag from the dissolved relation.
        List<FixPlan> plans6_2 = plans.stream()
            .filter(p -> "6.2".equals(p.getRelation().get("_test_id")))
            .collect(Collectors.toList());
        assertEquals(2, plans6_2.size(), "Should find 2 relations with _test_id=6.2");

        FixPlan waterPlan = plans6_2.stream()
            .filter(p -> "water".equals(p.getRelation().get("natural")))
            .findFirst().orElseThrow();

        MultipolygonFixer.fixRelations(plans6_2);

        // The water relation should be kept with its inner
        var waterRelation = waterPlan.getRelation();
        assertFalse(waterRelation.isDeleted(), "Water relation should be kept");

        // The inner member should be a closed way with landuse=grass
        var innerMember = waterRelation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole()))
            .findFirst().orElseThrow();
        Way innerWay = innerMember.getWay();
        assertTrue(innerWay.isClosed(), "Inner way should be closed");
        assertEquals("grass", innerWay.get("landuse"),
            "Inner way should have landuse=grass from the dissolved relation");
    }

    // --- Test case 8: TOUCHING_INNER_MERGE ---

    @Test
    void testCase8_touchingInnerMerge_createsClosedWay() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "8");
        assertNotNull(plan);

        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted());
        List<Way> waterWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "water".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertFalse(waterWays.isEmpty(), "Should have way with natural=water");
        for (Way w : waterWays) {
            assertTrue(w.isClosed(), "Merged way should be closed");
        }
    }

    // --- Test case 9: TOUCHING_INNER_MERGE with split ---

    @Test
    void testCase9_touchingInnerMerge_createsMultipleClosedWays() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "9");
        assertNotNull(plan);

        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted());
        List<Way> grassWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grass".equals(w.get("landuse")))
            .collect(Collectors.toList());
        assertTrue(grassWays.size() >= 2,
            "Test 9 should produce at least 2 ways, got " + grassWays.size());
        for (Way w : grassWays) {
            assertTrue(w.isClosed(), "Each merged way should be closed");
        }
    }

    // --- Test case 10: CONSOLIDATE + DISSOLVE (touching rings / figure-8) ---

    @Test
    void testCase10_touchingRings_dissolvesRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "10");
        assertNotNull(plan);

        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(plan.getRelation().isDeleted(), "Relation should be deleted after dissolve");
    }

    @Test
    void testCase10_touchingRings_createsTwoClosedWays() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "10");

        MultipolygonFixer.fixRelations(List.of(plan));

        List<Way> waterWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "water".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertEquals(2, waterWays.size(),
            "Test 10 should produce 2 standalone ways with natural=water");
        for (Way w : waterWays) {
            assertTrue(w.isClosed(), "Each consolidated way should be closed");
        }
    }

    // --- Undo ---

    @Test
    void undoRevertsAllChanges() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        int relationsBefore = (int) ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .count();

        MultipolygonFixer.fixRelations(plans);

        assertTrue(UndoRedoHandler.getInstance().hasUndoCommands());
        UndoRedoHandler.getInstance().undo();

        int relationsAfter = (int) ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .count();
        assertEquals(relationsBefore, relationsAfter,
            "Undo should restore all relations");
    }
}
