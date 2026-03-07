package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;


class BoundaryFixerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeEach
    void clearUndoStack() {
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }
    }

    private DataSet freshDataSet() {
        return JosmTestSetup.loadDataSet("testdata-boundary.osm");
    }

    private FixPlan findPlanByTestId(List<FixPlan> plans, String testId) {
        return plans.stream()
            .filter(p -> testId.equals(p.getRelation().get("_test_id")))
            .findFirst()
            .orElse(null);
    }

    private static List<FixOpType> opTypes(FixPlan plan) {
        return plan.getOperations().stream()
            .map(FixOp::getType)
            .collect(Collectors.toList());
    }

    // --- Test B1: all closed ways + label node → not fixable ---

    @Test
    void testB1_allClosedWays_notFixable() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B1");
        assertNull(plan, "B1 should not be fixable (all ways already closed)");
    }

    // --- Test B2: 2 open outers → CONSOLIDATE_RINGS ---

    @Test
    void testB2_analyzer_producesConsolidate() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B2");
        assertNotNull(plan, "B2 should be fixable");
        assertTrue(plan.isBoundary());
        assertFalse(plan.dissolvesRelation());
        assertEquals(List.of(FixOpType.CONSOLIDATE_RINGS), opTypes(plan));
    }

    @Test
    void testB2_fixer_relationSurvives() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B2");
        assertNotNull(plan);

        Relation relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Boundary relation must survive");
    }

    @Test
    void testB2_fixer_consolidatedWayIsClosed() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B2");

        MultipolygonFixer.fixRelations(List.of(plan));

        Relation relation = plan.getRelation();
        List<Way> outerWays = relation.getMembers().stream()
            .filter(m -> m.isWay() && "outer".equals(m.getRole()))
            .map(RelationMember::getWay)
            .collect(Collectors.toList());
        assertFalse(outerWays.isEmpty(), "Relation should still have outer way members");
        for (Way w : outerWays) {
            assertTrue(w.isClosed(), "Consolidated outer way should be closed");
        }
    }

    @Test
    void testB2_fixer_noTagTransfer() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B2");

        MultipolygonFixer.fixRelations(List.of(plan));

        Relation relation = plan.getRelation();
        // Relation should keep its tags
        assertEquals("administrative", relation.get("boundary"));
        assertEquals("6", relation.get("admin_level"));
        assertEquals("Test Boundary B2", relation.get("name"));

        // Consolidated ways should NOT have boundary tags
        for (RelationMember member : relation.getMembers()) {
            if (member.isWay()) {
                Way way = member.getWay();
                assertNull(way.get("boundary"),
                    "Consolidated way should not have boundary tag");
                assertNull(way.get("admin_level"),
                    "Consolidated way should not have admin_level tag");
                assertNull(way.get("name"),
                    "Consolidated way should not have name tag");
            }
        }
    }

    @Test
    void testB2_fixer_undoRestoresOriginalState() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B2");
        int membersBefore = plan.getRelation().getMembersCount();

        MultipolygonFixer.fixRelations(List.of(plan));
        UndoRedoHandler.getInstance().undo();

        assertEquals(membersBefore, plan.getRelation().getMembersCount(),
            "Undo should restore original member count");
    }

    // --- Test B3: 3 open outers + label + admin_centre → CONSOLIDATE, non-Way preserved ---

    @Test
    void testB3_analyzer_producesConsolidate() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B3");
        assertNotNull(plan, "B3 should be fixable");
        assertTrue(plan.isBoundary());
        assertFalse(plan.dissolvesRelation());
        assertEquals(List.of(FixOpType.CONSOLIDATE_RINGS), opTypes(plan));
    }

    @Test
    void testB3_fixer_nonWayMembersPreserved() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B3");

        Relation relation = plan.getRelation();
        long nonWayMembersBefore = relation.getMembers().stream()
            .filter(m -> !m.isWay()).count();
        assertEquals(2, nonWayMembersBefore, "B3 should have 2 non-Way members (label + admin_centre)");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted());
        long nonWayMembersAfter = relation.getMembers().stream()
            .filter(m -> !m.isWay()).count();
        assertEquals(nonWayMembersBefore, nonWayMembersAfter,
            "Non-way members (label, admin_centre) must be preserved after fix");

        // Verify roles preserved
        List<String> nonWayRoles = relation.getMembers().stream()
            .filter(m -> !m.isWay())
            .map(RelationMember::getRole)
            .sorted()
            .collect(Collectors.toList());
        assertTrue(nonWayRoles.contains("label"), "Label member should be preserved");
        assertTrue(nonWayRoles.contains("admin_centre"), "Admin centre member should be preserved");
    }

    // --- Test B4: bowtie → CONSOLIDATE + DECOMPOSE ---

    @Test
    void testB4_analyzer_producesConsolidateAndDecompose() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B4");
        assertNotNull(plan, "B4 should be fixable");
        assertTrue(plan.isBoundary());
        assertFalse(plan.dissolvesRelation());
        List<FixOpType> types = opTypes(plan);
        assertTrue(types.contains(FixOpType.CONSOLIDATE_RINGS), "B4 should include CONSOLIDATE_RINGS");
        assertTrue(types.contains(FixOpType.DECOMPOSE_SELF_INTERSECTIONS), "B4 should include DECOMPOSE");
    }

    @Test
    void testB4_fixer_relationSurvivesWithClosedWays() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B4");

        Relation relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Boundary relation must survive");
        List<Way> outerWays = relation.getMembers().stream()
            .filter(m -> m.isWay() && "outer".equals(m.getRole()))
            .map(RelationMember::getWay)
            .collect(Collectors.toList());
        assertTrue(outerWays.size() >= 2, "Decomposed bowtie should produce at least 2 outer ways");
        for (Way w : outerWays) {
            assertTrue(w.isClosed(), "Decomposed way should be closed");
        }
    }

    // --- Test B5: open inner + open outer → CONSOLIDATE both ---

    @Test
    void testB5_analyzer_producesConsolidateForBothOuterAndInner() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B5");
        assertNotNull(plan, "B5 should be fixable");
        assertTrue(plan.isBoundary());
        assertFalse(plan.dissolvesRelation());

        // Should have 2 CONSOLIDATE_RINGS ops (one for outer, one for inner)
        long consolidateCount = plan.getOperations().stream()
            .filter(op -> op.getType() == FixOpType.CONSOLIDATE_RINGS)
            .count();
        assertEquals(2, consolidateCount,
            "B5 should have 2 CONSOLIDATE_RINGS ops (outer + inner)");
    }

    @Test
    void testB5_fixer_allWaysClosed() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B5");

        MultipolygonFixer.fixRelations(List.of(plan));

        Relation relation = plan.getRelation();
        assertFalse(relation.isDeleted());
        for (RelationMember member : relation.getMembers()) {
            if (member.isWay()) {
                assertTrue(member.getWay().isClosed(),
                    "All way members should be closed after consolidation, but " +
                    member.getRole() + " way " + member.getWay().getUniqueId() + " is not");
            }
        }
    }

    // --- Test B6: both closed → not fixable ---

    @Test
    void testB6_allClosedWays_notFixable() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "B6");
        assertNull(plan, "B6 should not be fixable (all ways already closed)");
    }

    // --- Integration: fix all then re-analyze ---

    @Test
    void fixAllBoundaries_thenReanalyze_shouldFindNoFixableRelations() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertFalse(plans.isEmpty(), "Should find fixable boundary relations");

        MultipolygonFixer.fixRelations(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing all, no relations should be fixable. Remaining: " +
            remaining.stream()
                .map(p -> p.getRelation().get("_test_id"))
                .toList());
    }

    @Test
    void fixAllBoundaries_undoAll_shouldRestoreOriginalState() {
        DataSet ds = freshDataSet();
        List<FixPlan> plansBefore = MultipolygonAnalyzer.findFixableRelations(ds);
        int fixableCountBefore = plansBefore.size();

        MultipolygonFixer.fixRelations(plansBefore);
        UndoRedoHandler.getInstance().undo();

        List<FixPlan> plansAfterUndo = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(fixableCountBefore, plansAfterUndo.size(),
            "After undo, should find same number of fixable relations");
    }

    // --- Verify boundary relations never produce forbidden operations ---

    @Test
    void allBoundaryPlans_neverDissolveOrExtractOrMergeOrSplit() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        for (FixPlan plan : plans) {
            if (plan.isBoundary()) {
                for (FixOp op : plan.getOperations()) {
                    assertNotEquals(FixOpType.DISSOLVE, op.getType(),
                        "Boundary plans must never contain DISSOLVE");
                    assertNotEquals(FixOpType.EXTRACT_OUTERS, op.getType(),
                        "Boundary plans must never contain EXTRACT_OUTERS");
                    assertNotEquals(FixOpType.TOUCHING_INNER_MERGE, op.getType(),
                        "Boundary plans must never contain TOUCHING_INNER_MERGE");
                    assertNotEquals(FixOpType.SPLIT_RELATION, op.getType(),
                        "Boundary plans must never contain SPLIT_RELATION");
                }
            }
        }
    }
}
