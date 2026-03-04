package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;

class AnalyzerFixerIntegrationTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeEach
    void clearUndoStack() {
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }
    }

    @Test
    void fixAll_thenReanalyze_shouldFindNoFixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertTrue(plans.size() >= 10, "Should find fixable relations initially");

        MultipolygonFixer.fixRelations(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing all, no relations should be fixable. Remaining: " +
            remaining.stream()
                .map(p -> p.getRelation().get("_test_id"))
                .toList());
    }

    @Test
    void fixAll_undoAll_shouldRestoreOriginalState() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata.osm");
        List<FixPlan> plansBefore = MultipolygonAnalyzer.findFixableRelations(ds);
        int fixableCountBefore = plansBefore.size();

        MultipolygonFixer.fixRelations(plansBefore);
        UndoRedoHandler.getInstance().undo();

        List<FixPlan> plansAfterUndo = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(fixableCountBefore, plansAfterUndo.size(),
            "After undo, should find same number of fixable relations");
    }

    // ---- Boundary tests ----

    @Test
    void boundary_fixAll_thenReanalyze_shouldFindNoFixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-boundary.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertFalse(plans.isEmpty(), "Should find fixable boundary relations");
        // All boundary plans should be marked as boundary
        for (FixPlan plan : plans) {
            assertTrue(plan.isBoundary(), "Plans from boundary test data should be boundary");
            assertFalse(plan.dissolvesRelation(), "Boundary plans should never dissolve");
        }

        MultipolygonFixer.fixRelations(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing all boundaries, no relations should be fixable");

        // All boundary relations should still exist
        long boundaryRelations = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "boundary".equals(r.get("type")))
            .count();
        assertTrue(boundaryRelations >= 4,
            "All boundary relations should survive (B1, B2, B3, B4, B5, B6 all have type=boundary)");
    }

    // ---- Megafarmland tests ----
    // Input: 1 relation (20032389), 124 outers + 21 inners, all open fragments
    // Expected: relation survives with 1 outer + 10 inners, 2 standalone landuse=farmland ways

    @Test
    void megafarmland_shouldFind1FixableRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(1, plans.size());
        assertFalse(plans.get(0).dissolvesRelation(),
            "Should not dissolve (inners remain)");
    }

    @Test
    void megafarmland_fixAll_relationSurvivesWithConsolidatedOuter() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        Relation rel = plans.get(0).getRelation();
        assertFalse(rel.isDeleted(), "Relation should survive with inners");
        assertEquals(11, rel.getMembersCount(),
            "Should have 1 outer + 10 inners");

        long outerCount = rel.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole())).count();
        long innerCount = rel.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, outerCount, "Should have 1 consolidated outer");
        assertEquals(10, innerCount, "Should have 10 inners");

        Way outer = rel.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole()))
            .findFirst().orElseThrow().getWay();
        assertTrue(outer.isClosed(), "Consolidated outer should be closed");
    }

    @Test
    void megafarmland_fixAll_extractsStandaloneOuters() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        List<Way> farmlandWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "farmland".equals(w.get("landuse"))
                && w.isClosed()
                && w.getReferrers().stream()
                    .noneMatch(r -> r instanceof Relation && !r.isDeleted()))
            .collect(Collectors.toList());
        assertEquals(2, farmlandWays.size(),
            "2 standalone outers should be extracted as landuse=farmland ways");
    }

    @Test
    void megafarmland_fixAll_allSurvivingWaysAreClosed() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<Way> unclosed = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && !w.isClosed())
            .collect(Collectors.toList());
        assertTrue(unclosed.isEmpty(),
            "All surviving ways should be closed, but found " + unclosed.size() + " unclosed");
    }

    @Test
    void megafarmland_fixAll_convergesInOnePass() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing, no relations should be fixable");
    }

    @Test
    void megafarmland_fixAll_undoRestoresAll() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        long relCountBefore = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();

        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));
        UndoRedoHandler.getInstance().undo();

        long relCountAfter = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
        assertEquals(relCountBefore, relCountAfter, "Undo should restore all relations");
        assertEquals(1, MultipolygonAnalyzer.findFixableRelations(ds).size(),
            "Undo should restore the fixable relation");
    }

    // ---- Megaheath tests ----
    // Input: 1 relation (19980748), 7 closed outers + 20 closed inners, all 27 ways closed
    // Expected: split into 7 sub-relations (1 outer + N inners each), 0 standalone ways

    @Test
    void megaheath_shouldFind1FixableRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(1, plans.size());
    }

    @Test
    void megaheath_fixAll_splitsInto7Relations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<Relation> surviving = ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .collect(Collectors.toList());
        assertEquals(7, surviving.size(),
            "Should split into 7 sub-relations");

        // Every surviving relation should have natural=heath
        for (Relation rel : surviving) {
            assertEquals("heath", rel.get("natural"),
                "Each sub-relation should have natural=heath");
            assertEquals("multipolygon", rel.get("type"),
                "Each sub-relation should have type=multipolygon");
        }
    }

    @Test
    void megaheath_fixAll_eachRelationHas1Outer() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        for (Relation rel : ds.getRelations()) {
            if (rel.isDeleted()) continue;
            long outerCount = rel.getMembers().stream()
                .filter(m -> "outer".equals(m.getRole())).count();
            assertEquals(1, outerCount,
                "Relation " + rel.getUniqueId() + " should have exactly 1 outer");

            Way outer = rel.getMembers().stream()
                .filter(m -> "outer".equals(m.getRole()))
                .findFirst().orElseThrow().getWay();
            assertTrue(outer.isClosed(),
                "Outer of relation " + rel.getUniqueId() + " should be closed");
        }
    }

    @Test
    void megaheath_fixAll_originalRelationKeptAsLargest() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        Relation original = findRelationById(ds, 19980748);
        assertNotNull(original, "Original relation should still exist");
        assertFalse(original.isDeleted(), "Original relation should survive as largest component");

        long innerCount = original.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(10, innerCount,
            "Original relation should keep 10 inners (largest component)");
    }

    @Test
    void megaheath_fixAll_noWaysDeleted() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        long waysBefore = ds.getWays().stream().filter(w -> !w.isDeleted()).count();

        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        // Megaheath has all closed ways — no consolidation needed, no ways deleted
        long waysAfter = ds.getWays().stream().filter(w -> !w.isDeleted()).count();
        assertTrue(waysAfter >= waysBefore,
            "No original ways should be deleted (all were already closed)");
    }

    @Test
    void megaheath_fixAll_noStandaloneTaggedWays() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<Way> standalone = ds.getWays().stream()
            .filter(w -> !w.isDeleted()
                && (w.hasKey("natural") || w.hasKey("landuse"))
                && w.getReferrers().stream()
                    .noneMatch(r -> r instanceof Relation && !r.isDeleted()))
            .collect(Collectors.toList());
        assertTrue(standalone.isEmpty(),
            "All tagged ways should remain in relations, but found " + standalone.size() + " standalone");
    }

    @Test
    void megaheath_fixAll_convergesInOnePass() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing, no relations should be fixable");
    }

    @Test
    void megaheath_fixAll_undoRestoresAll() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        long relCountBefore = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();

        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));
        UndoRedoHandler.getInstance().undo();

        long relCountAfter = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
        assertEquals(relCountBefore, relCountAfter, "Undo should restore original relation count");
        assertEquals(1, MultipolygonAnalyzer.findFixableRelations(ds).size(),
            "Undo should restore the fixable relation");
    }

    // ---- Megawetland tests ----
    // Input: 1 relation (19980750), 233 outers + 34 inners
    // Expected: 4 relations (original + 3 new), each 1 outer + N inners,
    //           48 standalone wetland ways, no untagged orphans

    @Test
    void megawetland_shouldFind1FixableRelation() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(1, plans.size());
    }

    @Test
    void megawetland_fixAll_splitsInto4Relations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<Relation> surviving = ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .collect(Collectors.toList());
        assertEquals(4, surviving.size(),
            "Should split into 4 sub-relations");

        for (Relation rel : surviving) {
            assertEquals("wetland", rel.get("natural"),
                "Each sub-relation should have natural=wetland");
        }
    }

    @Test
    void megawetland_fixAll_eachRelationHas1ClosedOuter() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        for (Relation rel : ds.getRelations()) {
            if (rel.isDeleted()) continue;
            long outerCount = rel.getMembers().stream()
                .filter(m -> "outer".equals(m.getRole())).count();
            assertEquals(1, outerCount,
                "Relation " + rel.getUniqueId() + " should have exactly 1 outer");

            Way outer = rel.getMembers().stream()
                .filter(m -> "outer".equals(m.getRole()))
                .findFirst().orElseThrow().getWay();
            assertTrue(outer.isClosed(),
                "Outer of relation " + rel.getUniqueId() + " should be closed");
        }
    }

    @Test
    void megawetland_fixAll_originalRelationKeptAsLargest() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        Relation original = findRelationById(ds, 19980750);
        assertNotNull(original);
        assertFalse(original.isDeleted(), "Original relation should survive as largest component");

        long innerCount = original.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(20, innerCount,
            "Original relation should keep 20 inners (largest component)");
    }

    @Test
    void megawetland_fixAll_50standaloneWetlandWays() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<Way> standaloneWetland = ds.getWays().stream()
            .filter(w -> !w.isDeleted()
                && "wetland".equals(w.get("natural"))
                && "wet_meadow".equals(w.get("wetland"))
                && w.getReferrers().stream()
                    .noneMatch(r -> r instanceof Relation && !r.isDeleted()))
            .collect(Collectors.toList());
        // 50 = 48 simple outers + 2 from bowtie decomposition (previously degenerate due to
        // duplicate node bug in decomposeAtRepeatedNodes)
        assertEquals(50, standaloneWetland.size(),
            "50 standalone outers should be extracted with wetland tags");

        for (Way w : standaloneWetland) {
            assertTrue(w.isClosed(),
                "Standalone wetland way " + w.getUniqueId() + " should be closed");
        }
    }

    @Test
    void megawetland_fixAll_noUntaggedOrphans() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        // No surviving way should be both standalone AND untagged
        List<Way> orphans = ds.getWays().stream()
            .filter(w -> !w.isDeleted()
                && w.getReferrers().stream()
                    .noneMatch(r -> r instanceof Relation && !r.isDeleted())
                && w.getKeys().keySet().stream()
                    .noneMatch(k -> !k.startsWith("_") && !"source".equals(k) && !"created_by".equals(k)))
            .filter(w -> w.getNodesCount() > 2)
            .collect(Collectors.toList());
        assertTrue(orphans.isEmpty(),
            "No ways should be orphaned (standalone + untagged), found " + orphans.size());
    }

    @Test
    void megawetland_fixAll_convergesInOnePass() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing, no relations should be fixable");
    }

    @Test
    void megawetland_fixAll_undoRestoresAll() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        long relCountBefore = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();

        MultipolygonFixer.fixRelations(MultipolygonAnalyzer.findFixableRelations(ds));
        UndoRedoHandler.getInstance().undo();

        long relCountAfter = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
        assertEquals(relCountBefore, relCountAfter, "Undo should restore original relation count");
        assertEquals(1, MultipolygonAnalyzer.findFixableRelations(ds).size(),
            "Undo should restore the fixable relation");
    }

    // ---- Real-world data tests ----

    private static Relation findRelationById(DataSet ds, long id) {
        return ds.getRelations().stream()
            .filter(r -> r.getUniqueId() == id)
            .findFirst().orElse(null);
    }

    @Test
    void realworld_shouldFind10FixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        // 11 relations total, but 20236769 (1 outer + 1 inner, non-touching) is not fixable
        assertEquals(10, plans.size(),
            "Should find 10 fixable relations (20236769 is not fixable)");
    }

    @Test
    void realworld_relation20236769_isNotFixable() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        boolean hasRel = plans.stream()
            .anyMatch(p -> p.getRelation().getUniqueId() == 20236769);
        assertFalse(hasRel,
            "Relation 20236769 (1 outer + 1 inner, non-touching) should not be fixable");
    }

    @Test
    void realworld_fixAll_thenReanalyze_shouldFindNoFixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing real-world data, no relations should be fixable");
    }

    @Test
    void realworld_fixAll_9relationsDeleted() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        long deletedCount = ds.getRelations().stream()
            .filter(Relation::isDeleted)
            .count();
        assertEquals(9, deletedCount,
            "9 relations should be deleted after fixing");
    }

    @Test
    void realworld_fixAll_2relationsSurvive() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        Set<Long> survivingIds = ds.getRelations().stream()
            .filter(r -> !r.isDeleted())
            .map(Relation::getUniqueId)
            .collect(Collectors.toSet());
        assertEquals(Set.of(20236768L, 20236769L), survivingIds,
            "Only relations 20236768 and 20236769 should survive");
    }

    @Test
    void realworld_fixAll_relation20236768_consolidatedOuterWithInners() {
        // Relation 20236768: 2 outers (open ways) + 2 inners → consolidate outers, keep relation
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        Relation rel = findRelationById(ds, 20236768);
        assertNotNull(rel);
        assertFalse(rel.isDeleted(), "Relation 20236768 should survive");
        assertEquals(3, rel.getMembersCount(),
            "Should have 1 consolidated outer + 2 inners");

        long outerCount = rel.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole())).count();
        long innerCount = rel.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, outerCount, "Should have 1 outer member");
        assertEquals(2, innerCount, "Should have 2 inner members");

        // The outer should be a closed way
        Way outer = rel.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole()))
            .findFirst().orElseThrow()
            .getWay();
        assertTrue(outer.isClosed(), "Consolidated outer should be closed");
    }

    @Test
    void realworld_fixAll_relation20236769_unchanged() {
        // Relation 20236769: 1 outer + 1 inner (non-touching) → not fixable, left as-is
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");

        Relation relBefore = findRelationById(ds, 20236769);
        int membersBefore = relBefore.getMembersCount();

        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        MultipolygonFixer.fixRelations(plans);

        Relation relAfter = findRelationById(ds, 20236769);
        assertFalse(relAfter.isDeleted());
        assertEquals(membersBefore, relAfter.getMembersCount(),
            "Relation 20236769 should be unchanged");
    }

    @Test
    void realworld_fixAll_dissolvedWaysGetCorrectTags() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        // After fixing, there should be standalone ways with natural=heath, natural=grassland,
        // natural=sand tags from dissolved relations
        long heathWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "heath".equals(w.get("natural")))
            .count();
        long grasslandWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "grassland".equals(w.get("natural")))
            .count();
        long sandWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "sand".equals(w.get("natural")))
            .count();

        assertTrue(heathWays > 0, "Should have ways tagged natural=heath");
        assertTrue(grasslandWays > 0, "Should have ways tagged natural=grassland");
        assertTrue(sandWays > 0, "Should have ways tagged natural=sand");
    }

    @Test
    void realworld_fixAll_allSurvivingWaysAreClosed() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        // Every non-deleted way that has significant tags should be closed
        List<Way> unclosedTaggedWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && !w.isClosed())
            .filter(w -> w.hasKey("natural") || w.hasKey("landuse"))
            .collect(Collectors.toList());
        assertTrue(unclosedTaggedWays.isEmpty(),
            "All tagged ways should be closed after fix, but found unclosed: " +
            unclosedTaggedWays.stream()
                .map(w -> "way " + w.getUniqueId())
                .collect(Collectors.joining(", ")));
    }

    @Test
    void realworld_fixAll_noOrphanedInners() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");

        // Track which ways are inners before fixing
        Set<Long> innerWayIds = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "multipolygon".equals(r.get("type")))
            .flatMap(r -> r.getMembers().stream())
            .filter(m -> "inner".equals(m.getRole()) && m.isWay())
            .map(m -> m.getWay().getUniqueId())
            .collect(Collectors.toSet());

        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        MultipolygonFixer.fixRelations(plans);

        // Every original inner way should either be deleted, still in a relation, or have tags
        for (long innerId : innerWayIds) {
            Way way = (Way) ds.getPrimitiveById(innerId, org.openstreetmap.josm.data.osm.OsmPrimitiveType.WAY);
            if (way == null || way.isDeleted()) continue;

            boolean inRelation = way.getReferrers().stream()
                .anyMatch(r -> r instanceof Relation && !r.isDeleted());
            boolean hasTags = way.getKeys().keySet().stream()
                .anyMatch(k -> !k.startsWith("_") && !"source".equals(k) && !"created_by".equals(k));

            assertTrue(inRelation || hasTags,
                "Inner way " + innerId + " should not be orphaned (no relation, no tags)");
        }
    }

    @Test
    void realworld_fixAll_undoRestoresAll() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plansBefore = MultipolygonAnalyzer.findFixableRelations(ds);
        int countBefore = plansBefore.size();
        long relCountBefore = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();

        MultipolygonFixer.fixRelations(plansBefore);
        UndoRedoHandler.getInstance().undo();

        long relCountAfter = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
        assertEquals(relCountBefore, relCountAfter,
            "Undo should restore all relations");

        List<FixPlan> plansAfter = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(countBefore, plansAfter.size(),
            "Undo should restore same number of fixable relations");
    }

    // ---- Finalboss tests ----
    // Input: 11 relations from real-world data (1 is type=boundary, not multipolygon)
    // Expected: fixRelationsUntilConvergence resolves everything in one call

    @Test
    void finalboss_shouldFindFixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-finalboss.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertFalse(plans.isEmpty(), "Should find fixable relations");
    }

    @Test
    void finalboss_singlePassReducesFixableCount() {
        // A single pass should make progress (reduce the number of fixable relations).
        // Some relations (e.g. splits) may produce new fixable relations, so we don't
        // require convergence in a single pass — the iterative test covers that.
        DataSet ds = JosmTestSetup.loadDataSet("testdata-finalboss.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        int before = plans.size();

        MultipolygonFixer.fixRelations(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertTrue(remaining.size() < before,
            "Single pass should reduce fixable count (before=" + before
            + " after=" + remaining.size() + ")");
    }

    @Test
    void finalboss_iterativeConverges() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-finalboss.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelationsUntilConvergence(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After iterative fix, no relations should be fixable. Remaining: " +
            remaining.stream()
                .map(p -> "rel " + p.getRelation().getUniqueId() + ": " + p.getDescription())
                .toList());
    }

    @Test
    void finalboss_iterative_singleUndo() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-finalboss.osm");
        List<FixPlan> plansBefore = MultipolygonAnalyzer.findFixableRelations(ds);
        int countBefore = plansBefore.size();
        long relCountBefore = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();

        MultipolygonFixer.fixRelationsUntilConvergence(plansBefore);

        // Should be single undo
        assertTrue(UndoRedoHandler.getInstance().hasUndoCommands());
        UndoRedoHandler.getInstance().undo();

        long relCountAfter = ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
        assertEquals(relCountBefore, relCountAfter,
            "Single undo should restore all original relations");

        List<FixPlan> plansAfter = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(countBefore, plansAfter.size(),
            "Single undo should restore same number of fixable relations");
    }

    @Test
    void finalboss_iterative_noOrphanedWays() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-finalboss.osm");
        MultipolygonFixer.fixRelationsUntilConvergence(
            MultipolygonAnalyzer.findFixableRelations(ds));

        List<Way> orphans = ds.getWays().stream()
            .filter(w -> !w.isDeleted()
                && w.getReferrers().stream()
                    .noneMatch(r -> r instanceof Relation && !r.isDeleted())
                && w.getKeys().keySet().stream()
                    .noneMatch(k -> !k.startsWith("_") && !"source".equals(k) && !"created_by".equals(k)))
            .filter(w -> w.getNodesCount() > 2)
            .collect(Collectors.toList());
        assertTrue(orphans.isEmpty(),
            "No ways should be orphaned (standalone + untagged), found " + orphans.size());
    }

    // ---- Big-Meadow real-data tests ----

    @Test
    void bigMeadow_fixAll_relation19084391_outersShouldFormClosedRings() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-big-meadow.osm");
        Relation rel = (Relation) ds.getPrimitiveById(19084391, org.openstreetmap.josm.data.osm.OsmPrimitiveType.RELATION);
        assertNotNull(rel, "Relation 19084391 should exist");

        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        MultipolygonFixer.fixRelations(plans);

        // Relation should survive (it's the big wetland with a too-large-to-consolidate component)
        assertFalse(rel.isDeleted(), "Relation 19084391 should survive");

        // Remaining outer ways must still chain into valid closed rings.
        // Before the shared-way-reuse fix, consolidation of other relations would
        // ChangeCommand shared ways, breaking this relation's outer ring connectivity.
        List<Way> outerWays = rel.getMembers().stream()
            .filter(m -> m.isWay() && "outer".equals(m.getRole()))
            .map(m -> m.getWay())
            .collect(Collectors.toList());
        assertFalse(outerWays.isEmpty(), "Should still have outer ways");

        var chainResult = WayChainBuilder.buildRings(outerWays);
        assertTrue(chainResult.isPresent(),
            "Outer ways should still chain into closed rings after batch fix");
    }

    @Test
    void bigMeadow_fixAll_survivingRelationsShouldHaveValidMembers() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-big-meadow.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertFalse(plans.isEmpty(), "Should find fixable relations");

        MultipolygonFixer.fixRelations(plans);

        // Every surviving (non-deleted) relation's way members must also be non-deleted
        for (Relation r : ds.getRelations()) {
            if (r.isDeleted()) continue;
            for (org.openstreetmap.josm.data.osm.RelationMember m : r.getMembers()) {
                if (m.isWay()) {
                    assertFalse(m.getWay().isDeleted(),
                        "Relation " + r.getUniqueId() + " references deleted way " + m.getWay().getUniqueId());
                }
            }
        }
    }

    @Test
    void bigMeadow_fixAll_survivingWaysShouldHaveValidNodes() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-big-meadow.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        // Every surviving way's nodes must also be non-deleted
        for (Way w : ds.getWays()) {
            if (w.isDeleted()) continue;
            for (org.openstreetmap.josm.data.osm.Node n : w.getNodes()) {
                assertFalse(n.isDeleted(),
                    "Way " + w.getUniqueId() + " references deleted node " + n.getUniqueId());
            }
        }
    }

    @Test
    void bigMeadow_fixAll_noUntaggedOrphanWays() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-big-meadow.osm");
        MultipolygonFixer.fixRelationsUntilConvergence(
            MultipolygonAnalyzer.findFixableRelations(ds));

        List<Way> orphans = ds.getWays().stream()
            .filter(w -> !w.isDeleted()
                && w.getReferrers().stream()
                    .noneMatch(r -> r instanceof Relation && !r.isDeleted())
                && w.getKeys().keySet().stream()
                    .noneMatch(k -> !k.startsWith("_") && !"source".equals(k) && !"created_by".equals(k)))
            .filter(w -> w.getNodesCount() > 2)
            .collect(Collectors.toList());
        assertTrue(orphans.isEmpty(),
            "No ways should be orphaned (standalone + untagged), found " + orphans.size()
            + ": " + orphans.stream().map(w -> String.valueOf(w.getUniqueId())).limit(10).collect(Collectors.joining(", ")));
    }

    @Test
    void bigMeadow_fixAll_sharedWaysNotDeletedWhenStillNeeded() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-big-meadow.osm");

        // Track all relations' way members before fixing
        java.util.Map<Long, Set<Long>> relWaysBefore = new java.util.HashMap<>();
        for (Relation r : ds.getRelations()) {
            if (r.isDeleted()) continue;
            Set<Long> wayIds = r.getMembers().stream()
                .filter(org.openstreetmap.josm.data.osm.RelationMember::isWay)
                .map(m -> m.getWay().getUniqueId())
                .collect(Collectors.toSet());
            relWaysBefore.put(r.getUniqueId(), wayIds);
        }

        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        Set<Long> processedRelIds = plans.stream()
            .map(p -> p.getRelation().getUniqueId())
            .collect(Collectors.toSet());

        MultipolygonFixer.fixRelations(plans);

        // For every non-processed relation, all its original way members should still exist
        for (var entry : relWaysBefore.entrySet()) {
            if (processedRelIds.contains(entry.getKey())) continue;
            Relation r = (Relation) ds.getPrimitiveById(entry.getKey(), org.openstreetmap.josm.data.osm.OsmPrimitiveType.RELATION);
            if (r == null || r.isDeleted()) continue;
            for (long wayId : entry.getValue()) {
                Way w = (Way) ds.getPrimitiveById(wayId, org.openstreetmap.josm.data.osm.OsmPrimitiveType.WAY);
                assertFalse(w != null && w.isDeleted(),
                    "Non-processed relation " + entry.getKey() + "'s way " + wayId + " was deleted");
            }
        }
    }

    // ---- Irrigon OR tests ----
    // Regression: relation 19935959 has a self-intersecting (bowtie) outer way
    // that is already closed. DECOMPOSE should not run on already-closed ways.

    @Test
    void irrigon_analyzeDoesNotCrash() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-irrigon-OR.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertNotNull(plans, "Analysis should complete without crashing");
    }

    @Test
    void irrigon_selfIntersectingClosedOuterIsSkipped() {
        DataSet ds = JosmTestSetup.loadDataSet("regression/real-data-irrigon-OR.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        // Relation 19935959 has a bowtie outer + non-touching inner.
        // Since the outer is already closed, DECOMPOSE should not fire.
        // Without decomposition, no fix is applicable (inner doesn't touch outer),
        // so the relation should be skipped — not present in fix plans.
        boolean hasPlan = plans.stream()
            .anyMatch(p -> p.getRelation().getUniqueId() == 19935959);
        assertFalse(hasPlan,
            "Relation 19935959 should be skipped (bowtie outer is already closed, inner doesn't touch)");
    }
}
