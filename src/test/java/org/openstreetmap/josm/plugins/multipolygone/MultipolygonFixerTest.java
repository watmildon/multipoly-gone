package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOpType;
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

    // --- Test case 11: CONSOLIDATE + DISSOLVE with tagged source way ---

    @Test
    void testCase11_deletesRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "11");
        assertNotNull(plan);

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after dissolve");
    }

    @Test
    void testCase11_taggedWayUnchanged() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "11");

        // Find the highway way that is a member of the relation
        Way highwayWay = null;
        for (var member : plan.getRelation().getMembers()) {
            if (member.isWay() && "residential".equals(member.getWay().get("highway"))) {
                highwayWay = member.getWay();
                break;
            }
        }
        assertNotNull(highwayWay, "Highway way should exist as relation member");
        int nodeCountBefore = highwayWay.getNodesCount();

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(highwayWay.isDeleted(), "Highway way should NOT be deleted");
        assertEquals("residential", highwayWay.get("highway"),
            "Highway way should still have highway=residential");
        assertEquals("Foo", highwayWay.get("name"),
            "Highway way should still have name=Foo");
        assertFalse(highwayWay.hasKey("natural"),
            "Highway way should NOT get relation tags");
        assertEquals(nodeCountBefore, highwayWay.getNodesCount(),
            "Highway way should have same node count");
    }

    @Test
    void testCase11_untaggedWayReusedAndTagged() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "11");

        // Find the untagged way that is a member of the relation
        Way untaggedWay = null;
        for (var member : plan.getRelation().getMembers()) {
            if (member.isWay() && !member.getWay().hasKey("highway")) {
                untaggedWay = member.getWay();
                break;
            }
        }
        assertNotNull(untaggedWay, "Untagged way should exist as relation member");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(untaggedWay.isDeleted(), "Untagged way should NOT be deleted (reused)");
        assertTrue(untaggedWay.isClosed(), "Reused way should be closed");
        assertEquals("grassland", untaggedWay.get("natural"),
            "Reused way should have natural=grassland from relation");
    }

    // --- Test case 12: EXTRACT_OUTERS + TOUCHING_INNER_MERGE ---

    @Test
    void testCase12_deletesRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "12");
        assertNotNull(plan);

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted");
    }

    @Test
    void testCase12_extractedOutersSurvive() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "12");

        // Identify the 2 standalone outers (not touching inner)
        var relation = plan.getRelation();
        Way outerWithInner = null;
        for (var member : relation.getMembers()) {
            if (member.isWay() && "inner".equals(member.getRole())) {
                // Find the outer that shares nodes with this inner
                Way innerWay = member.getWay();
                for (var m2 : relation.getMembers()) {
                    if (m2.isWay() && "outer".equals(m2.getRole())) {
                        for (var n : innerWay.getNodes()) {
                            if (m2.getWay().getNodes().contains(n)) {
                                outerWithInner = m2.getWay();
                                break;
                            }
                        }
                    }
                    if (outerWithInner != null) break;
                }
            }
        }
        assertNotNull(outerWithInner, "Should find outer that shares nodes with inner");
        Way outer1 = outerWithInner;

        List<Way> standaloneOuters = relation.getMembers().stream()
            .filter(m -> m.isWay() && "outer".equals(m.getRole()) && m.getWay() != outer1)
            .map(m -> m.getWay())
            .collect(Collectors.toList());
        assertEquals(2, standaloneOuters.size(), "Should have 2 standalone outers");

        MultipolygonFixer.fixRelations(List.of(plan));

        // Extracted outers should NOT be deleted — they should be tagged
        for (Way w : standaloneOuters) {
            assertFalse(w.isDeleted(),
                "Extracted outer way should NOT be deleted");
            assertEquals("water", w.get("natural"),
                "Extracted outer should have natural=water");
        }
    }

    @Test
    void testCase12_mergeProducesClosedWay() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "12");

        MultipolygonFixer.fixRelations(List.of(plan));

        // There should be newly created closed ways with natural=water
        // (from the touching inner merge) plus the 2 extracted outers
        List<Way> waterWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "water".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertTrue(waterWays.size() >= 3,
            "Should have at least 3 ways with natural=water (2 extracted + 1+ merged), got " + waterWays.size());
        for (Way w : waterWays) {
            assertTrue(w.isClosed(), "Every resulting way should be closed");
        }
    }

    // --- Test case 13: TOUCHING_INNER_MERGE with 1 shared node ---

    @Test
    void testCase13_deletesRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "13");
        assertNotNull(plan);

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted");
    }

    @Test
    void testCase13_producesClosedWayWithTag() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "13");

        MultipolygonFixer.fixRelations(List.of(plan));

        List<Way> waterWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "water".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertFalse(waterWays.isEmpty(), "Should have way with natural=water");
        for (Way w : waterWays) {
            assertTrue(w.isClosed(), "Merged way should be closed");
        }
    }

    @Test
    void testCase13_sharedNodeAppearsMultipleTimes() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "13");

        // Find the shared node (node that appears in both outer and inner)
        Way foundOuter = null;
        Way foundInner = null;
        for (var member : plan.getRelation().getMembers()) {
            if (member.isWay() && "outer".equals(member.getRole())) foundOuter = member.getWay();
            if (member.isWay() && "inner".equals(member.getRole())) foundInner = member.getWay();
        }
        assertNotNull(foundOuter);
        assertNotNull(foundInner);
        final Way outerWay = foundOuter;
        final Way innerWay = foundInner;
        var sharedNodes = outerWay.getNodes().stream()
            .filter(n -> innerWay.getNodes().contains(n))
            .distinct()
            .collect(Collectors.toList());
        assertEquals(1, sharedNodes.size(), "Should share exactly 1 unique node");

        MultipolygonFixer.fixRelations(List.of(plan));

        // The merged way should contain the shared node more than once (figure-8)
        List<Way> waterWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "water".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertEquals(1, waterWays.size(), "Should produce exactly 1 merged way");
        Way mergedWay = waterWays.get(0);
        long sharedNodeCount = mergedWay.getNodes().stream()
            .filter(n -> n.equals(sharedNodes.get(0)))
            .count();
        assertTrue(sharedNodeCount >= 3,
            "Shared node should appear at least 3 times (start, middle, end), got " + sharedNodeCount);
    }

    // --- Test case 14: SPLIT_RELATION (2 disconnected outers with inners) ---

    @Test
    void testCase14_splitRelation_keepsOriginalRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "14");
        assertNotNull(plan, "Test case 14 should be fixable");

        var relation = plan.getRelation();
        int membersBefore = relation.getMembersCount();
        assertEquals(4, membersBefore, "Relation should have 4 members before fix");

        MultipolygonFixer.fixRelations(List.of(plan));

        // Original relation should be modified (not deleted) with 2 members (1 outer + 1 inner)
        assertFalse(relation.isDeleted(), "Original relation should be modified, not deleted");
        assertEquals(2, relation.getMembersCount(),
            "Original relation should have 2 members (1 outer + 1 inner)");
        assertTrue(relation.getMembers().stream()
            .anyMatch(m -> "outer".equals(m.getRole())),
            "Original relation should still have an outer member");
        assertTrue(relation.getMembers().stream()
            .anyMatch(m -> "inner".equals(m.getRole())),
            "Original relation should still have an inner member");
    }

    @Test
    void testCase14_splitRelation_createsNewSubRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "14");

        var originalRelation = plan.getRelation();
        Set<Relation> relsBefore = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "multipolygon".equals(r.get("type")))
            .collect(java.util.stream.Collectors.toSet());

        MultipolygonFixer.fixRelations(List.of(plan));

        // Find the newly created sub-relation (not in relsBefore)
        var newRelations = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "multipolygon".equals(r.get("type")))
            .filter(r -> !relsBefore.contains(r))
            .collect(java.util.stream.Collectors.toList());

        assertEquals(1, newRelations.size(),
            "Should have exactly 1 new multipolygon relation after split");

        Relation newRel = newRelations.get(0);
        assertEquals("water", newRel.get("natural"),
            "New sub-relation should have natural=water tag");
        assertEquals(2, newRel.getMembersCount(),
            "New sub-relation should have 2 members (1 outer + 1 inner)");
    }

    // --- Test case 15: DECOMPOSE_SELF_INTERSECTIONS (bowtie) ---

    @Test
    void testCase15_deletesRelation() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "15");
        assertNotNull(plan, "Test case 15 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after decompose + dissolve");
    }

    @Test
    void testCase15_producesMultipleClosedWays() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "15");

        MultipolygonFixer.fixRelations(List.of(plan));

        List<Way> grassWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grass".equals(w.get("landuse")))
            .collect(Collectors.toList());
        assertTrue(grassWays.size() >= 3,
            "Should produce at least 3 closed ways with landuse=grass, got " + grassWays.size());
        for (Way w : grassWays) {
            assertTrue(w.isClosed(), "Each decomposed way should be closed");
        }
    }

    @Test
    void testCase15_noOrphanConsolidatedWay() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "15");

        // Count untagged ways before fix
        Set<Way> waysBefore = new HashSet<>(ds.getWays());

        MultipolygonFixer.fixRelations(List.of(plan));

        // Any newly created way (not in waysBefore, not deleted) should have tags —
        // the consolidated bowtie way must NOT be left around untagged
        List<Way> orphanNewWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && !waysBefore.contains(w) && w.getKeys().isEmpty())
            .collect(Collectors.toList());
        assertTrue(orphanNewWays.isEmpty(),
            "No orphan untagged new ways should remain after decompose, found " + orphanNewWays.size());
    }

    // --- Test case 59 (from testdata-proposed.osm): CONSOLIDATE + DECOMPOSE + DISSOLVE ---

    @Test
    void testCase59_deletesRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "59");
        assertNotNull(plan, "Test case 59 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after decompose + dissolve");
    }

    @Test
    void testCase59_producesTaggedClosedWaysAndNoOrphans() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "59");

        // Track ways before fix
        Set<Way> waysBefore = new HashSet<>(ds.getWays());

        MultipolygonFixer.fixRelations(List.of(plan));

        // Should produce tagged closed ways
        List<Way> grassWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grass".equals(w.get("landuse")))
            .collect(Collectors.toList());
        assertTrue(grassWays.size() >= 2,
            "Should produce at least 2 closed ways with landuse=grass, got " + grassWays.size());
        for (Way w : grassWays) {
            assertTrue(w.isClosed(), "Each decomposed way should be closed");
        }

        // No orphan untagged new ways should remain (the consolidated bowtie must be cleaned up)
        List<Way> orphanNewWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && !waysBefore.contains(w) && w.getKeys().isEmpty())
            .collect(Collectors.toList());
        assertTrue(orphanNewWays.isEmpty(),
            "No orphan untagged new ways should remain after decompose, found " + orphanNewWays.size());
    }

    // --- Test case 100 (from testdata-proposed.osm): DECOMPOSE already-closed bowtie ---

    @Test
    void testCase100_deletesRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "100");
        assertNotNull(plan, "Test case 100 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after decompose + dissolve");
    }

    @Test
    void testCase100_decomposesBowtieIntoClosedWays() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "100");

        // The original bowtie way
        Way bowtieWay = plan.getRelation().getMembers().get(0).getWay();
        Set<Way> waysBefore = new HashSet<>(ds.getWays());

        MultipolygonFixer.fixRelations(List.of(plan));

        // Original bowtie way should be deleted
        assertTrue(bowtieWay.isDeleted(), "Original bowtie way should be deleted");

        // Should produce 2 new tagged closed ways (two triangles from the bowtie)
        List<Way> newGrassWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && !waysBefore.contains(w) && "grass".equals(w.get("landuse")))
            .collect(Collectors.toList());
        assertEquals(2, newGrassWays.size(),
            "Should produce exactly 2 new closed ways with landuse=grass, got " + newGrassWays.size());
        for (Way w : newGrassWays) {
            assertTrue(w.isClosed(), "Each decomposed way should be closed");
        }

        // No orphan untagged new ways
        List<Way> orphanNewWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && !waysBefore.contains(w) && w.getKeys().isEmpty())
            .collect(Collectors.toList());
        assertTrue(orphanNewWays.isEmpty(),
            "No orphan untagged new ways should remain, found " + orphanNewWays.size());
    }

    // --- Test case 30: TOUCHING_INNER_MERGE with 3 shared nodes ---

    @Test
    void testCase30_deletesRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "30");
        assertNotNull(plan, "Test case 30 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted");
    }

    @Test
    void testCase30_noOrphanedNodes() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "30");

        // Collect nodes used by the outer and inner ways before fix
        Set<org.openstreetmap.josm.data.osm.Node> relatedNodes = new HashSet<>();
        for (var member : plan.getRelation().getMembers()) {
            if (member.isWay()) {
                relatedNodes.addAll(member.getWay().getNodes());
            }
        }

        MultipolygonFixer.fixRelations(List.of(plan));

        // Every node that was part of the original ways should still be referenced
        // by at least one non-deleted way
        for (var node : relatedNodes) {
            if (node.isDeleted()) continue;
            boolean referencedByWay = node.getReferrers().stream()
                .anyMatch(r -> r instanceof Way && !r.isDeleted());
            assertTrue(referencedByWay,
                "Node " + node.getId() + " should be referenced by a surviving way");
        }
    }

    // --- Test case 31: TOUCHING_INNER_MERGE with 4 shared nodes ---

    @Test
    void testCase31_deletesRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "31");
        assertNotNull(plan, "Test case 31 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted");
    }

    @Test
    void testCase31_noOrphanedNodes() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "31");

        Set<org.openstreetmap.josm.data.osm.Node> relatedNodes = new HashSet<>();
        for (var member : plan.getRelation().getMembers()) {
            if (member.isWay()) {
                relatedNodes.addAll(member.getWay().getNodes());
            }
        }

        MultipolygonFixer.fixRelations(List.of(plan));

        for (var node : relatedNodes) {
            if (node.isDeleted()) continue;
            boolean referencedByWay = node.getReferrers().stream()
                .anyMatch(r -> r instanceof Way && !r.isDeleted());
            assertTrue(referencedByWay,
                "Node " + node.getId() + " should be referenced by a surviving way");
        }
    }

    // --- Test case 51 (from testdata-proposed.osm): 1 closed outer, natural=tree_group ---

    @Test
    void testCase51_dissolve_deletesRelationAndTagsWay() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "51");
        assertNotNull(plan, "Test case 51 should be fixable");

        // Verify plan structure
        assertEquals(1, plan.getOperations().size(), "Should have 1 operation");
        assertEquals(MultipolygonAnalyzer.FixOpType.DISSOLVE, plan.getOperations().get(0).getType());

        var relation = plan.getRelation();
        Way outerWay = relation.getMembers().get(0).getWay();

        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after dissolve");
        assertEquals("tree_group", outerWay.get("natural"),
            "Outer way should have natural=tree_group after dissolve");
    }

    // --- Test case 114 (from testdata-proposed.osm): DISSOLVE with tagged outer (coastline bug) ---

    @Test
    void testCase114_dissolve_deletesRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "114");
        assertNotNull(plan, "Test case 114 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted after dissolve");
    }

    @Test
    void testCase114_dissolve_preservesCoastlineWay() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "114");

        Way coastlineWay = plan.getRelation().getMembers().get(0).getWay();
        assertEquals("coastline", coastlineWay.get("natural"),
            "Precondition: outer way should have natural=coastline");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(coastlineWay.isDeleted(), "Coastline way should not be deleted");
        assertEquals("coastline", coastlineWay.get("natural"),
            "Coastline way should still have natural=coastline after dissolve");
        assertNull(coastlineWay.get("surface"),
            "Coastline way should not have acquired surface=sand from the relation");
    }

    @Test
    void testCase114_dissolve_createsNewBeachWay() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "114");

        MultipolygonFixer.fixRelations(List.of(plan));

        List<Way> beachWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "beach".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertEquals(1, beachWays.size(),
            "Should have exactly one way with natural=beach after dissolve");
        assertEquals("sand", beachWays.get(0).get("surface"),
            "New beach way should have surface=sand");
        assertTrue(beachWays.get(0).isClosed(),
            "New beach way should be closed");
    }

    // --- Test case 115 (from testdata-proposed.osm): DISSOLVE with mixed tagged/untagged outers ---

    @Test
    void testCase115_dissolve_preservesTaggedWayAndReusesUntagged() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "115");
        assertNotNull(plan, "Test case 115 should be fixable");

        Relation relation = plan.getRelation();
        // Find ways by tag rather than assuming member order
        Way taggedWay = relation.getMembers().stream()
            .filter(m -> m.isWay() && "coastline".equals(m.getWay().get("natural")))
            .findFirst().get().getWay();
        Way untaggedWay = relation.getMembers().stream()
            .filter(m -> m.isWay() && !m.getWay().hasKeys())
            .findFirst().get().getWay();

        MultipolygonFixer.fixRelations(List.of(plan));

        assertTrue(relation.isDeleted(), "Relation should be deleted");
        // Tagged way preserved
        assertFalse(taggedWay.isDeleted(), "Coastline way should not be deleted");
        assertEquals("coastline", taggedWay.get("natural"),
            "Coastline way should still have natural=coastline");
        // Untagged way reused with relation tags
        assertFalse(untaggedWay.isDeleted(), "Untagged way should not be deleted");
        assertEquals("beach", untaggedWay.get("natural"),
            "Untagged way should have been tagged with natural=beach");
    }

    @Test
    void testCase115_dissolve_createsNewWayForTaggedOuter() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "115");

        MultipolygonFixer.fixRelations(List.of(plan));

        List<Way> beachWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "beach".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertEquals(2, beachWays.size(),
            "Should have 2 ways with natural=beach (one new, one reused)");
    }

    // --- Test case 116 (from testdata-proposed.osm): EXTRACT with tagged outer ---

    @Test
    void testCase116_extract_preservesFootwayTag() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "116");
        assertNotNull(plan, "Test case 116 should be fixable");

        Relation relation = plan.getRelation();
        Way footwayWay = relation.getMembers().stream()
            .filter(m -> m.isWay() && "footway".equals(m.getWay().get("highway")))
            .findFirst().get().getWay();

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(footwayWay.isDeleted(), "Footway way should not be deleted");
        assertEquals("footway", footwayWay.get("highway"),
            "Footway way should still have highway=footway after extract");
        assertNull(footwayWay.get("landuse"),
            "Footway way should not have acquired landuse=grass from the relation");
    }

    @Test
    void testCase116_extract_createsNewWayWithRelationTags() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "116");

        Relation relation = plan.getRelation();
        Way footwayWay = relation.getMembers().stream()
            .filter(m -> m.isWay() && "footway".equals(m.getWay().get("highway")))
            .findFirst().get().getWay();

        MultipolygonFixer.fixRelations(List.of(plan));

        // A new way should exist with the same nodes as the footway, tagged with landuse=grass
        List<Way> grassWaysWithFootwayNodes = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grass".equals(w.get("landuse"))
                && w.getNodes().containsAll(footwayWay.getNodes()))
            .collect(Collectors.toList());
        assertEquals(1, grassWaysWithFootwayNodes.size(),
            "Should have a new way with landuse=grass sharing the footway geometry");
    }

    // --- Test case 117: CONSOLIDATE_RINGS reuses existing matching way ---

    @Test
    void testCase117_reuseExistingWay_relationSurvives() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "117");
        assertNotNull(plan, "Test case 117 should be fixable");

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        // 1 outer + 1 non-touching inner → relation survives with consolidated inner
        assertFalse(relation.isDeleted(),
            "Relation should survive — 1 outer + 1 non-touching inner is a valid MP");
    }

    @Test
    void testCase117_reuseExistingWay_existingWayBecomesInner() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "117");

        // Find way -4237 (the pre-existing natural=water way that is NOT a relation member)
        Way existingWaterWay = ds.getWays().stream()
            .filter(w -> "117".equals(w.get("_test_case")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Pre-existing natural=water way should exist"));

        var relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(existingWaterWay.isDeleted(),
            "Pre-existing natural=water way should NOT be deleted — it was reused");
        assertTrue(existingWaterWay.isClosed(),
            "Pre-existing way should still be closed after reuse");
        assertEquals("water", existingWaterWay.get("natural"),
            "Pre-existing way should retain its natural=water tag");

        // The existing way should now be an inner member of the relation
        boolean isInnerMember = relation.getMembers().stream()
            .anyMatch(m -> m.isWay() && m.getWay().equals(existingWaterWay)
                && "inner".equals(m.getRole()));
        assertTrue(isInnerMember,
            "Existing water way should now be an inner member of the relation");
    }

    @Test
    void testCase117_reuseExistingWay_sourceWaysDeleted() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "117");

        // Collect the 2 open inner source ways before the fix
        List<Way> openInners = plan.getRelation().getMembers().stream()
            .filter(m -> m.isWay() && "inner".equals(m.getRole()) && !m.getWay().isClosed())
            .map(RelationMember::getWay)
            .collect(Collectors.toList());
        assertEquals(2, openInners.size(), "Should have 2 open inner ways before fix");

        MultipolygonFixer.fixRelations(List.of(plan));

        for (Way src : openInners) {
            assertTrue(src.isDeleted(),
                "Open inner source way " + src.getUniqueId() + " should be deleted after consolidation");
        }
    }

    @Test
    void testCase117_reuseExistingWay_noDuplicateWayCreated() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "117");

        // Find the existing water way before fix
        Way existingWaterWay = ds.getWays().stream()
            .filter(w -> "117".equals(w.get("_test_case")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Pre-existing water way should exist"));

        // Collect node IDs of existing water way to check for duplicates
        var existingNodeIds = existingWaterWay.getNodes().stream()
            .map(n -> n.getUniqueId())
            .collect(Collectors.toList());

        MultipolygonFixer.fixRelations(List.of(plan));

        // No new non-deleted way should have been created with the same nodes
        long matchingWayCount = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w != existingWaterWay
                && w.getNodesCount() == existingWaterWay.getNodesCount()
                && w.isClosed())
            .filter(w -> {
                var nodeIds = w.getNodes().stream()
                    .map(n -> n.getUniqueId())
                    .collect(Collectors.toSet());
                return nodeIds.containsAll(existingNodeIds.subList(0, existingNodeIds.size() - 1));
            })
            .count();
        assertEquals(0L, matchingWayCount,
            "No duplicate way should be created — the existing water way should be reused");
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

    // --- Cross-plan shared way protection ---

    @Test
    void sharedWay_shouldNotBeDeletedWhenStillNeededByOtherRelation() {
        DataSet ds = freshDataSet();

        // Find test case 2 (CONSOLIDATE + DISSOLVE) which consolidates open ways into a ring.
        // One of its source ways will be shared with a second relation.
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan2 = findPlanByTestId(plans, "2");
        assertNotNull(plan2);

        // Get one of the source outer ways from test case 2's relation
        Way sharedWay = null;
        for (RelationMember m : plan2.getRelation().getMembers()) {
            if (m.isWay() && "outer".equals(m.getRole())) {
                sharedWay = m.getWay();
                break;
            }
        }
        assertNotNull(sharedWay, "Should find an outer way in test case 2");

        // Create a second relation that uses the shared way as an outer member
        // (a simple closed-way outer that happens to be the shared way — unrealistic
        // geometry but sufficient to test the cleanup logic)
        Relation otherRelation = new Relation();
        otherRelation.put("type", "multipolygon");
        otherRelation.put("landuse", "forest");
        otherRelation.addMember(new RelationMember("outer", sharedWay));
        ds.addPrimitive(otherRelation);

        // Fix all — both relations should be processed
        plans = MultipolygonAnalyzer.findFixableRelations(ds);
        MultipolygonFixer.fixRelations(plans);

        // The shared way must NOT be deleted — it's still a member of otherRelation
        assertFalse(sharedWay.isDeleted(),
            "Shared way should not be deleted when still referenced by another relation");
    }

    // --- Duplicate member deduplication ---

    @Test
    void testCase14_duplicateInner_shouldBeDeduplicatedAfterFix() {
        DataSet ds = freshDataSet();
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "14");
        assertNotNull(plan);

        // Inject a duplicate inner member into the relation
        Relation relation = plan.getRelation();
        List<RelationMember> members = new java.util.ArrayList<>(relation.getMembers());
        RelationMember innerMember = members.stream()
            .filter(m -> "inner".equals(m.getRole()))
            .findFirst().orElseThrow();
        members.add(innerMember);
        relation.setMembers(members);

        // Re-analyze with the duplicate member
        plans = MultipolygonAnalyzer.findFixableRelations(ds);
        plan = findPlanByTestId(plans, "14");
        assertNotNull(plan, "Should still be fixable with duplicate inner");

        MultipolygonFixer.fixRelations(List.of(plan));

        // Verify no surviving relation has duplicate way members
        for (Relation r : ds.getRelations()) {
            if (r.isDeleted()) continue;
            Set<Way> seenWays = new HashSet<>();
            for (RelationMember m : r.getMembers()) {
                if (m.isWay()) {
                    assertTrue(seenWays.add(m.getWay()),
                        "Relation " + r.getUniqueId() + " has duplicate way member " + m.getWay().getUniqueId());
                }
            }
        }
    }

    // --- Test case 118 (from testdata-proposed.osm): CONSOLIDATE_INNERS (2 abutting inners) ---

    @Test
    void testCase118_analyzerDetectsConsolidateInners() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "118");
        assertNotNull(plan, "Test case 118 should be fixable");

        boolean hasConsolidateInners = plan.getOperations().stream()
            .anyMatch(op -> op.getType() == FixOpType.CONSOLIDATE_INNERS);
        assertTrue(hasConsolidateInners, "Plan should include CONSOLIDATE_INNERS");
    }

    @Test
    void testCase118_merges2InnersInto1() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "118");
        assertNotNull(plan);

        Relation relation = plan.getRelation();
        long innersBefore = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(2, innersBefore, "Should start with 2 inner members");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Relation should survive (has inners)");
        long innersAfter = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, innersAfter, "Should have 1 inner after merging 2 abutting inners");

        // Verify the inner way is closed
        Way innerWay = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole()))
            .map(m -> m.getWay())
            .findFirst().orElseThrow();
        assertTrue(innerWay.isClosed(), "Merged inner way should be closed");
    }

    @Test
    void testCase118_undoRestoresOriginal() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "118");
        assertNotNull(plan);

        Relation relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));
        UndoRedoHandler.getInstance().undo();

        long innersAfterUndo = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(2, innersAfterUndo, "After undo, should have 2 inner members again");
    }

    // --- Test case 119 (from testdata-proposed.osm): CONSOLIDATE_INNERS (3 chained inners) ---

    @Test
    void testCase119_merges3InnersInto1() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "119");
        assertNotNull(plan, "Test case 119 should be fixable");

        boolean hasConsolidateInners = plan.getOperations().stream()
            .anyMatch(op -> op.getType() == FixOpType.CONSOLIDATE_INNERS);
        assertTrue(hasConsolidateInners, "Plan should include CONSOLIDATE_INNERS");

        Relation relation = plan.getRelation();
        long innersBefore = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(3, innersBefore, "Should start with 3 inner members");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Relation should survive");
        long innersAfter = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, innersAfter, "Should have 1 inner after merging 3 chained inners");
    }

    // --- Test case 120 (from testdata-proposed.osm): No merge (1 shared node only) ---

    @Test
    void testCase120_noConsolidateInnersFor1SharedNode() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "120");
        // This relation should either not be fixable, or if fixable, should NOT have CONSOLIDATE_INNERS
        if (plan != null) {
            boolean hasConsolidateInners = plan.getOperations().stream()
                .anyMatch(op -> op.getType() == FixOpType.CONSOLIDATE_INNERS);
            assertFalse(hasConsolidateInners,
                "Should NOT include CONSOLIDATE_INNERS when inners share only 1 node");
        }
    }

    // --- Test case 121 (from testdata-proposed.osm): open inner absorbed + abutting merge ---

    @Test
    void testCase121_analyzerHandlesOpenInnerAbsorption() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "121");
        assertNotNull(plan, "Test case 121 should be fixable (not INNER_WAYS_CANT_FORM_RINGS)");
    }

    @Test
    void testCase121_fixProducesSingleInner() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "121");
        assertNotNull(plan);

        Relation relation = plan.getRelation();
        long innersBefore = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(3, innersBefore, "Should start with 3 inner members");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Relation should survive");
        long innersAfter = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, innersAfter, "Should have 1 inner after absorption + merge");

        Way innerWay = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole()))
            .map(m -> m.getWay())
            .findFirst().orElseThrow();
        assertTrue(innerWay.isClosed(), "Final inner way should be closed");
    }

    @Test
    void testCase121_undoRestoresOriginal() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "121");
        assertNotNull(plan);

        Relation relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));
        UndoRedoHandler.getInstance().undo();

        long innersAfterUndo = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(3, innersAfterUndo, "After undo, should have 3 inner members again");
    }

    // --- Test case 122 (from testdata-proposed.osm): standalone open inner absorption ---

    @Test
    void testCase122_standaloneAbsorption() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "122");
        assertNotNull(plan, "Test case 122 should be fixable");

        Relation relation = plan.getRelation();
        long innersBefore = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(2, innersBefore, "Should start with 2 inner members");

        MultipolygonFixer.fixRelations(List.of(plan));

        assertFalse(relation.isDeleted(), "Relation should survive");
        long innersAfter = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, innersAfter, "Should have 1 inner after absorption");

        Way innerWay = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole()))
            .map(m -> m.getWay())
            .findFirst().orElseThrow();
        assertTrue(innerWay.isClosed(), "Absorbed inner way should be closed");
    }

    // --- Test case 123 (from testdata-proposed.osm): no absorption (endpoints on different rings) ---

    @Test
    void testCase123_noAbsorptionDifferentRings() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "123");
        // This relation should NOT be fixable — open way can't be absorbed
        assertNull(plan, "Test case 123 should not be fixable (endpoints on different rings)");
    }

    // --- Test case 124 (from testdata-proposed.osm): EXTRACT_INNERS (edge-sharing tagged inner) ---

    @Test
    void testCase124_extractEdgeSharingInner() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "124");
        assertNotNull(plan, "Test case 124 should be fixable");

        // Should have EXTRACT_INNERS op
        boolean hasExtractInners = plan.getOperations().stream()
            .anyMatch(op -> op.getType() == FixOpType.EXTRACT_INNERS);
        assertTrue(hasExtractInners, "Should have EXTRACT_INNERS operation");

        Relation relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        // Relation should survive (not deleted)
        assertFalse(relation.isDeleted(), "Relation should survive");

        // Should have 1 outer + 1 inner member after fix
        long outerCount = relation.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole()) || "".equals(m.getRole())).count();
        long innerCount = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, outerCount, "Should have 1 outer member");
        assertEquals(1, innerCount, "Should have 1 inner member");

        // The extracted inner way should NOT be deleted and should keep its tag
        List<Way> islandWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "island".equals(w.get("place")))
            .toList();
        assertEquals(1, islandWays.size(), "Extracted inner should survive with place=island");
        assertTrue(islandWays.get(0).isClosed(), "Extracted inner should be closed");

        // The extracted inner should NOT be in the relation
        boolean innerInRelation = relation.getMembers().stream()
            .anyMatch(m -> m.isWay() && m.getWay().equals(islandWays.get(0)));
        assertFalse(innerInRelation, "Extracted inner should not be in the relation");
    }

    // --- Test case 125 (from testdata-proposed.osm): untagged edge-sharing inner NOT extracted ---

    @Test
    void testCase125_noExtractUntaggedEdgeSharingInner() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        FixPlan plan = findPlanByTestId(plans, "125");
        assertNotNull(plan, "Test case 125 should be fixable");

        // Should NOT have EXTRACT_INNERS op
        boolean hasExtractInners = plan.getOperations().stream()
            .anyMatch(op -> op.getType() == FixOpType.EXTRACT_INNERS);
        assertFalse(hasExtractInners, "Should NOT have EXTRACT_INNERS for untagged inner");

        Relation relation = plan.getRelation();
        MultipolygonFixer.fixRelations(List.of(plan));

        // Relation should survive with 2 inners still in it
        assertFalse(relation.isDeleted(), "Relation should survive");
        long innerCount = relation.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(2, innerCount, "Should still have 2 inner members");
    }
}
