package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.AnalysisResult;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.SkipReason;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.SkipResult;

class SkipReasonTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet testData;
    private static AnalysisResult analysisResult;

    @BeforeAll
    static void loadTestData() {
        testData = JosmTestSetup.loadDataSet("testdata.osm");
        analysisResult = MultipolygonAnalyzer.analyzeAll(testData, null);
    }

    // --- Completeness invariant ---

    @Test
    void allRelationsAccountedFor() {
        long totalMPRelations = testData.getRelations().stream()
            .filter(r -> !r.isDeleted() && !r.isIncomplete())
            .filter(r -> "multipolygon".equals(r.get("type")) || "boundary".equals(r.get("type")))
            .count();

        long fixCount = analysisResult.getFixPlans().size();
        long skipCount = analysisResult.getSkipResults().size();

        assertEquals(totalMPRelations, fixCount + skipCount,
            "Every multipolygon/boundary relation should be either fixable or skipped"
            + " (total=" + totalMPRelations + " fix=" + fixCount + " skip=" + skipCount + ")");
    }

    @Test
    void completenessInvariant_megafarmland() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        assertCompletenessInvariant(ds, "megafarmland");
    }

    @Test
    void completenessInvariant_megaheath() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megaheath.osm");
        assertCompletenessInvariant(ds, "megaheath");
    }

    @Test
    void completenessInvariant_megawetland() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megawetland.osm");
        assertCompletenessInvariant(ds, "megawetland");
    }

    @Test
    void completenessInvariant_realworld() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        assertCompletenessInvariant(ds, "realworld");
    }

    @Test
    void completenessInvariant_proposed() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-proposed.osm");
        assertCompletenessInvariant(ds, "proposed");
    }

    private void assertCompletenessInvariant(DataSet ds, String label) {
        AnalysisResult result = MultipolygonAnalyzer.analyzeAll(ds, null);
        long totalMPRelations = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && !r.isIncomplete())
            .filter(r -> "multipolygon".equals(r.get("type")) || "boundary".equals(r.get("type")))
            .count();
        assertEquals(totalMPRelations,
            result.getFixPlans().size() + result.getSkipResults().size(),
            label + ": every relation should be either fixable or skipped");
    }

    // --- Backward compatibility ---

    @Test
    void findFixableRelations_unchangedCount() {
        List<FixPlan> viaOldApi = MultipolygonAnalyzer.findFixableRelations(testData);
        assertEquals(analysisResult.getFixPlans().size(), viaOldApi.size(),
            "findFixableRelations() should return same count as analyzeAll().getFixPlans()");
    }

    @Test
    void findFixableRelations_sameRelations() {
        List<FixPlan> viaOldApi = MultipolygonAnalyzer.findFixableRelations(testData);
        Set<Relation> oldApiRelations = viaOldApi.stream()
            .map(FixPlan::getRelation).collect(Collectors.toSet());
        Set<Relation> newApiRelations = analysisResult.getFixPlans().stream()
            .map(FixPlan::getRelation).collect(Collectors.toSet());
        assertEquals(oldApiRelations, newApiRelations,
            "Both APIs should identify the same fixable relations");
    }

    // --- Skip result sanity ---

    @Test
    void skipResults_haveReasons() {
        for (SkipResult sr : analysisResult.getSkipResults()) {
            assertNotNull(sr.getReason(), "Every skip result must have a reason");
            assertNotNull(sr.getRelation(), "Every skip result must have a relation");
        }
    }

    @Test
    void skipResults_noOverlapWithFixable() {
        Set<Relation> fixable = analysisResult.getFixPlans().stream()
            .map(FixPlan::getRelation).collect(Collectors.toSet());
        for (SkipResult sr : analysisResult.getSkipResults()) {
            assertFalse(fixable.contains(sr.getRelation()),
                "Relation " + sr.getRelation().getUniqueId()
                + " appears in both fixable and skipped lists");
        }
    }

    // --- SkipReason enum sanity ---

    @Test
    void allSkipReasons_haveMessages() {
        for (SkipReason reason : SkipReason.values()) {
            assertNotNull(reason.getMessage(), reason.name() + " must have a message");
            assertFalse(reason.getMessage().isEmpty(), reason.name() + " message must not be empty");
            assertNotNull(reason.getHint(), reason.name() + " must have a hint");
            assertFalse(reason.getHint().isEmpty(), reason.name() + " hint must not be empty");
        }
    }

    // --- Null dataset ---

    @Test
    void nullDataSet_returnsEmptyResult() {
        AnalysisResult result = MultipolygonAnalyzer.analyzeAll(null, null);
        assertTrue(result.getFixPlans().isEmpty());
        assertTrue(result.getSkipResults().isEmpty());
    }

    @Test
    void emptyDataSet_returnsEmptyResult() {
        AnalysisResult result = MultipolygonAnalyzer.analyzeAll(new DataSet(), null);
        assertTrue(result.getFixPlans().isEmpty());
        assertTrue(result.getSkipResults().isEmpty());
    }
}
