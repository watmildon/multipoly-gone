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
