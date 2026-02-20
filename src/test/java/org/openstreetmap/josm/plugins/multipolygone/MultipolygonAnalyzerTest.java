package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOp;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOpType;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;

class MultipolygonAnalyzerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet testData;
    private static List<FixPlan> fixPlans;
    private static Map<String, FixPlan> plansByTestId;

    @BeforeAll
    static void loadTestData() {
        testData = JosmTestSetup.loadDataSet("testdata.osm");
        fixPlans = MultipolygonAnalyzer.findFixableRelations(testData);

        plansByTestId = fixPlans.stream()
            .filter(p -> p.getRelation().get("_test_id") != null)
            .collect(Collectors.toMap(
                p -> p.getRelation().get("_test_id"),
                p -> p,
                (a, b) -> a
            ));
    }

    private static List<FixOpType> opTypes(FixPlan plan) {
        return plan.getOperations().stream()
            .map(FixOp::getType)
            .collect(Collectors.toList());
    }

    // --- Global checks ---

    @Test
    void findsExpectedNumberOfFixableRelations() {
        assertTrue(fixPlans.size() >= 9,
            "Expected at least 9 fixable relations, got " + fixPlans.size());
    }

    @Test
    void nullDataSetReturnsEmptyList() {
        assertEquals(0, MultipolygonAnalyzer.findFixableRelations(null).size());
    }

    @Test
    void emptyDataSetReturnsEmptyList() {
        assertEquals(0, MultipolygonAnalyzer.findFixableRelations(new DataSet()).size());
    }

    // --- Test case 1: 2 closed outers, no inners → DISSOLVE ---

    @Test
    void testCase1_dissolve() {
        FixPlan plan = plansByTestId.get("1");
        assertNotNull(plan, "Test case 1 should be fixable");
        assertEquals(List.of(FixOpType.DISSOLVE), opTypes(plan));
        assertTrue(plan.dissolvesRelation());
    }

    // --- Test case 2: 2 open outers forming ring → CONSOLIDATE + DISSOLVE ---

    @Test
    void testCase2_consolidateAndDissolve() {
        FixPlan plan = plansByTestId.get("2");
        assertNotNull(plan, "Test case 2 should be fixable");
        assertEquals(List.of(FixOpType.CONSOLIDATE_RINGS, FixOpType.DISSOLVE), opTypes(plan));
        assertTrue(plan.dissolvesRelation());

        FixOp consolidate = plan.getOperations().get(0);
        assertEquals(1, consolidate.getRings().size(), "Should consolidate 1 ring");
    }

    // --- Test case 3: 2 open outers forming ring → CONSOLIDATE + DISSOLVE ---

    @Test
    void testCase3_consolidateAndDissolve() {
        FixPlan plan = plansByTestId.get("3");
        assertNotNull(plan, "Test case 3 should be fixable");
        assertEquals(List.of(FixOpType.CONSOLIDATE_RINGS, FixOpType.DISSOLVE), opTypes(plan));
        assertTrue(plan.dissolvesRelation());
    }

    // --- Test case 4: 4 open outers forming ring → CONSOLIDATE + DISSOLVE ---

    @Test
    void testCase4_consolidateAndDissolve() {
        FixPlan plan = plansByTestId.get("4");
        assertNotNull(plan, "Test case 4 should be fixable");
        assertEquals(List.of(FixOpType.CONSOLIDATE_RINGS, FixOpType.DISSOLVE), opTypes(plan));
        assertTrue(plan.dissolvesRelation());

        FixOp consolidate = plan.getOperations().get(0);
        assertEquals(1, consolidate.getRings().size(), "Should consolidate into 1 ring");
    }

    // --- Test case 5: 2 closed outers + 1 inner → EXTRACT_OUTERS ---

    @Test
    void testCase5_extractOuters() {
        FixPlan plan = plansByTestId.get("5");
        assertNotNull(plan, "Test case 5 should be fixable");
        assertTrue(opTypes(plan).contains(FixOpType.EXTRACT_OUTERS),
            "Test 5 should include EXTRACT_OUTERS");
    }

    // --- Test case 6: complex extract + dissolve ---

    @Test
    void testCase6_extractAndDissolve() {
        FixPlan plan = plansByTestId.get("6");
        assertNotNull(plan, "Test case 6 should be fixable");

        List<FixOpType> types = opTypes(plan);
        assertTrue(types.contains(FixOpType.EXTRACT_OUTERS),
            "Test 6 should include EXTRACT_OUTERS");
        assertTrue(plan.dissolvesRelation());
    }

    // --- Test case 8: 1 outer + 1 inner sharing 2 nodes → TOUCHING_INNER_MERGE ---

    @Test
    void testCase8_touchingInnerMerge_oneMergedWay() {
        FixPlan plan = plansByTestId.get("8");
        assertNotNull(plan, "Test case 8 should be fixable");
        assertTrue(opTypes(plan).contains(FixOpType.TOUCHING_INNER_MERGE));
        assertTrue(plan.dissolvesRelation());

        FixOp mergeOp = plan.getOperations().stream()
            .filter(op -> op.getType() == FixOpType.TOUCHING_INNER_MERGE)
            .findFirst().orElseThrow();
        assertEquals(1, mergeOp.getMergedWays().size(),
            "Test 8 (2 shared nodes) should produce 1 merged way");
    }

    // --- Test case 9: 1 outer + 1 inner sharing 4+ nodes → TOUCHING_INNER_MERGE ---

    @Test
    void testCase9_touchingInnerMerge_twoMergedWays() {
        FixPlan plan = plansByTestId.get("9");
        assertNotNull(plan, "Test case 9 should be fixable");
        assertTrue(opTypes(plan).contains(FixOpType.TOUCHING_INNER_MERGE));
        assertTrue(plan.dissolvesRelation());

        FixOp mergeOp = plan.getOperations().stream()
            .filter(op -> op.getType() == FixOpType.TOUCHING_INNER_MERGE)
            .findFirst().orElseThrow();
        assertEquals(2, mergeOp.getMergedWays().size(),
            "Test 9 (4+ shared nodes) should produce 2 merged ways");
    }

    // --- Merged way validity checks ---

    @Test
    void testCase8_mergedWayIsClosed() {
        FixPlan plan = plansByTestId.get("8");
        FixOp mergeOp = plan.getOperations().stream()
            .filter(op -> op.getType() == FixOpType.TOUCHING_INNER_MERGE)
            .findFirst().orElseThrow();
        for (var wayNodes : mergeOp.getMergedWays()) {
            assertEquals(wayNodes.get(0), wayNodes.get(wayNodes.size() - 1),
                "Merged way should be closed (first == last node)");
        }
    }

    @Test
    void testCase9_mergedWaysAreClosed() {
        FixPlan plan = plansByTestId.get("9");
        FixOp mergeOp = plan.getOperations().stream()
            .filter(op -> op.getType() == FixOpType.TOUCHING_INNER_MERGE)
            .findFirst().orElseThrow();
        for (var wayNodes : mergeOp.getMergedWays()) {
            assertEquals(wayNodes.get(0), wayNodes.get(wayNodes.size() - 1),
                "Each merged way should be closed");
        }
    }

    // --- getPrimaryTag ---

    @Test
    void getPrimaryTag_returnsFirstNonTypeTag() {
        DataSet ds = new DataSet();
        Relation r = new Relation();
        r.put("type", "multipolygon");
        r.put("landuse", "grass");
        ds.addPrimitive(r);

        assertEquals("landuse=grass", MultipolygonAnalyzer.getPrimaryTag(r));
    }

    @Test
    void getPrimaryTag_skipsUnderscorePrefixed() {
        DataSet ds = new DataSet();
        Relation r = new Relation();
        r.put("type", "multipolygon");
        r.put("_test_id", "1");
        r.put("natural", "wood");
        ds.addPrimitive(r);

        String tag = MultipolygonAnalyzer.getPrimaryTag(r);
        assertFalse(tag.startsWith("_"), "Should skip underscore-prefixed tags");
        assertEquals("natural=wood", tag);
    }

    @Test
    void getPrimaryTag_noTags_returnsMultipolygon() {
        DataSet ds = new DataSet();
        Relation r = new Relation();
        r.put("type", "multipolygon");
        ds.addPrimitive(r);

        assertEquals("multipolygon", MultipolygonAnalyzer.getPrimaryTag(r));
    }
}
