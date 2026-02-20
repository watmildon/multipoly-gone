package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
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
        assertTrue(plans.size() >= 9, "Should find fixable relations initially");

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

    @Test
    void megafarmland_shouldFindFixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertFalse(plans.isEmpty(),
            "Megafarmland test data should have fixable relations");
    }

    @Test
    void megafarmland_fixAll_multiPass_eventuallyConverges() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-megafarmland.osm");

        // SPLIT_RELATION can create sub-relations that are themselves fixable,
        // so multiple passes may be needed to fully simplify.
        int maxPasses = 5;
        int pass = 0;
        List<FixPlan> plans;
        while (pass < maxPasses) {
            plans = MultipolygonAnalyzer.findFixableRelations(ds);
            if (plans.isEmpty()) break;
            MultipolygonFixer.fixRelations(plans);
            pass++;
        }

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After " + pass + " passes, megafarmland should have no fixable relations");
    }

    @Test
    void realworld_shouldFindFixableRelations() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        assertFalse(plans.isEmpty(),
            "Real-world test data should have fixable relations");
    }

    @Test
    void realworld_fixAll_thenReanalyze() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-realworld.osm");
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);

        MultipolygonFixer.fixRelations(plans);

        List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(ds);
        assertEquals(0, remaining.size(),
            "After fixing real-world data, no relations should be fixable");
    }
}
