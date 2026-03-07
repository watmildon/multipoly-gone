package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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


/**
 * Runs all 100 geometry test cases (IDs 200-299) through the analyzer and fixer,
 * checking for common problems:
 *   1. Exceptions during analysis or fixing
 *   2. Orphaned nodes (nodes not referenced by any surviving way)
 *   3. Unclosed ways that should be closed
 *   4. Orphan untagged new ways (intermediate artifacts left behind)
 *   5. Undo/redo consistency
 */
class GeometryTestCaseRunner {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeEach
    void clearUndoStack() {
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }
    }

    static class TestResult {
        final String testId;
        final String testNotes;
        final String expectedAction;
        boolean analyzable;
        String analyzeError;
        boolean fixable;
        String fixError;
        boolean hasOrphanedNodes;
        List<Long> orphanedNodeIds = new ArrayList<>();
        boolean hasOrphanNewWays;
        int orphanNewWayCount;
        boolean hasUnclosedTaggedWays;
        List<Long> unclosedWayIds = new ArrayList<>();
        boolean undoFailed;
        String undoError;
        boolean redoFailed;
        String redoError;
        int waysCreated;
        int waysDeleted;
        boolean relationDeleted;
        String planDescription;

        TestResult(String testId, String testNotes, String expectedAction) {
            this.testId = testId;
            this.testNotes = testNotes;
            this.expectedAction = expectedAction;
        }

        boolean hasProblems() {
            return analyzeError != null || fixError != null || hasOrphanedNodes
                || hasOrphanNewWays || hasUnclosedTaggedWays || undoFailed || redoFailed;
        }
    }

    @Test
    void runAllGeometryTestCases() {
        DataSet dsTemplate = JosmTestSetup.loadDataSet("testdata-geometry.osm");

        // Collect all test IDs from relations
        Map<String, Relation> testRelations = new HashMap<>();
        for (Relation r : dsTemplate.getRelations()) {
            String testId = r.get("_test_id");
            if (testId != null) {
                testRelations.put(testId, r);
            }
        }

        List<TestResult> results = new ArrayList<>();
        List<TestResult> problems = new ArrayList<>();

        for (int id = 200; id <= 299; id++) {
            String testId = String.valueOf(id);
            Relation templateRel = testRelations.get(testId);
            if (templateRel == null) continue;

            String notes = templateRel.get("_test_notes");
            String expected = templateRel.get("_test_expected");
            TestResult result = new TestResult(testId, notes != null ? notes : "", expected != null ? expected : "");

            // Load fresh dataset for each test
            DataSet ds;
            try {
                ds = JosmTestSetup.loadDataSet("testdata-geometry.osm");
            } catch (Exception e) {
                result.analyzeError = "Failed to load dataset: " + e.getMessage();
                results.add(result);
                problems.add(result);
                continue;
            }

            // Run analyzer
            List<FixPlan> plans;
            try {
                plans = MultipolygonAnalyzer.findFixableRelations(ds);
            } catch (Exception e) {
                result.analyzeError = "EXCEPTION during analysis: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                results.add(result);
                problems.add(result);
                continue;
            }

            FixPlan plan = plans.stream()
                .filter(p -> testId.equals(p.getRelation().get("_test_id")))
                .findFirst()
                .orElse(null);

            if (plan == null) {
                result.analyzable = false;
                results.add(result);
                // Not necessarily a problem — some cases are expected to be skipped
                if (expected != null && !expected.startsWith("SKIP")) {
                    // Expected to be fixable but wasn't
                    result.analyzeError = "Expected " + expected + " but analyzer returned null (not fixable)";
                    problems.add(result);
                }
                continue;
            }

            result.analyzable = true;
            result.planDescription = plan.getDescription();

            // Snapshot state before fix
            Set<Way> waysBefore = new HashSet<>(ds.getWays());
            Set<Node> nodesBefore = new HashSet<>(ds.getNodes());
            Relation rel = plan.getRelation();

            // Collect nodes used by the relation's member ways
            Set<Node> relatedNodes = new HashSet<>();
            for (var member : rel.getMembers()) {
                if (member.isWay()) {
                    relatedNodes.addAll(member.getWay().getNodes());
                }
            }

            int relsBefore = (int) ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
            int wayCountBefore = (int) ds.getWays().stream().filter(w -> !w.isDeleted()).count();

            // Run fixer
            try {
                MultipolygonFixer.fixRelations(List.of(plan));
                result.fixable = true;
            } catch (Exception e) {
                result.fixError = "EXCEPTION during fix: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                results.add(result);
                problems.add(result);
                continue;
            }

            result.relationDeleted = rel.isDeleted();

            int wayCountAfter = (int) ds.getWays().stream().filter(w -> !w.isDeleted()).count();
            result.waysCreated = (int) ds.getWays().stream().filter(w -> !w.isDeleted() && !waysBefore.contains(w)).count();
            result.waysDeleted = (int) waysBefore.stream().filter(Way::isDeleted).count();

            // Check 1: Orphaned nodes — nodes from the relation that are no longer
            // referenced by any non-deleted way
            for (Node node : relatedNodes) {
                if (node.isDeleted()) continue;
                boolean referencedByWay = node.getReferrers().stream()
                    .anyMatch(r -> r instanceof Way && !r.isDeleted());
                if (!referencedByWay) {
                    result.hasOrphanedNodes = true;
                    result.orphanedNodeIds.add(node.getId());
                }
            }

            // Check 2: Orphan untagged new ways (artifacts from consolidation/decomposition)
            List<Way> orphanNewWays = ds.getWays().stream()
                .filter(w -> !w.isDeleted() && !waysBefore.contains(w) && w.getKeys().isEmpty())
                .collect(Collectors.toList());
            if (!orphanNewWays.isEmpty()) {
                result.hasOrphanNewWays = true;
                result.orphanNewWayCount = orphanNewWays.size();
            }

            // Check 3: Unclosed tagged ways created by the fixer
            List<Way> unclosedTaggedWays = ds.getWays().stream()
                .filter(w -> !w.isDeleted() && !waysBefore.contains(w)
                    && !w.getKeys().isEmpty() && !w.isClosed())
                .collect(Collectors.toList());
            if (!unclosedTaggedWays.isEmpty()) {
                result.hasUnclosedTaggedWays = true;
                for (Way w : unclosedTaggedWays) {
                    result.unclosedWayIds.add(w.getId());
                }
            }

            // Check 4: Undo
            try {
                if (UndoRedoHandler.getInstance().hasUndoCommands()) {
                    UndoRedoHandler.getInstance().undo();
                    // After undo, relation should be restored
                    int relsAfterUndo = (int) ds.getRelations().stream().filter(r -> !r.isDeleted()).count();
                    if (relsAfterUndo != relsBefore) {
                        result.undoFailed = true;
                        result.undoError = "Relation count mismatch after undo: expected " + relsBefore + " got " + relsAfterUndo;
                    }
                }
            } catch (Exception e) {
                result.undoFailed = true;
                result.undoError = "EXCEPTION during undo: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            // Check 5: Redo
            try {
                if (UndoRedoHandler.getInstance().hasRedoCommands()) {
                    UndoRedoHandler.getInstance().redo();
                }
            } catch (Exception e) {
                result.redoFailed = true;
                result.redoError = "EXCEPTION during redo: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            results.add(result);
            if (result.hasProblems()) {
                problems.add(result);
            }

            // Clean undo stack for next test
            while (UndoRedoHandler.getInstance().hasUndoCommands()) {
                UndoRedoHandler.getInstance().undo();
            }
        }

        // Print summary
        System.out.println("\n========================================");
        System.out.println("GEOMETRY TEST CASE RESULTS");
        System.out.println("========================================\n");

        int analyzed = 0, fixed = 0, skipped = 0;
        for (TestResult r : results) {
            if (!r.analyzable && r.analyzeError == null) skipped++;
            else if (r.analyzable) analyzed++;
            if (r.fixable) fixed++;
        }

        System.out.printf("Total: %d cases | Analyzed: %d | Fixed: %d | Skipped: %d | Problems: %d%n%n",
            results.size(), analyzed, fixed, skipped, problems.size());

        if (!problems.isEmpty()) {
            System.out.println("======= PROBLEMS =======\n");
            for (TestResult r : problems) {
                System.out.printf("--- test_id=%s: %s ---%n", r.testId, r.testNotes);
                System.out.printf("    Expected: %s%n", r.expectedAction);
                if (r.planDescription != null) {
                    System.out.printf("    Plan: %s%n", r.planDescription);
                }
                if (r.analyzeError != null) {
                    System.out.printf("    [ANALYZE ERROR] %s%n", r.analyzeError);
                }
                if (r.fixError != null) {
                    System.out.printf("    [FIX ERROR] %s%n", r.fixError);
                }
                if (r.hasOrphanedNodes) {
                    System.out.printf("    [ORPHANED NODES] %d nodes: %s%n",
                        r.orphanedNodeIds.size(), r.orphanedNodeIds);
                }
                if (r.hasOrphanNewWays) {
                    System.out.printf("    [ORPHAN NEW WAYS] %d untagged new ways left behind%n",
                        r.orphanNewWayCount);
                }
                if (r.hasUnclosedTaggedWays) {
                    System.out.printf("    [UNCLOSED WAYS] %d tagged ways not closed: %s%n",
                        r.unclosedWayIds.size(), r.unclosedWayIds);
                }
                if (r.undoFailed) {
                    System.out.printf("    [UNDO FAILED] %s%n", r.undoError);
                }
                if (r.redoFailed) {
                    System.out.printf("    [REDO FAILED] %s%n", r.redoError);
                }
                System.out.println();
            }
        }

        // Also print full table for reference
        System.out.println("======= FULL RESULTS =======\n");
        System.out.printf("%-6s %-8s %-10s %-50s%n", "ID", "Status", "Plan", "Notes");
        System.out.println("-".repeat(80));
        for (TestResult r : results) {
            String status;
            if (r.analyzeError != null) status = "ERR-A";
            else if (r.fixError != null) status = "ERR-F";
            else if (r.hasProblems()) status = "WARN";
            else if (!r.analyzable) status = "SKIP";
            else status = "OK";

            String planDesc = r.planDescription != null ? r.planDescription : "-";
            if (planDesc.length() > 10) planDesc = planDesc.substring(0, 10);

            System.out.printf("%-6s %-8s %-10s %-50s%n",
                r.testId, status, planDesc, r.testNotes);
        }

        // Also run the "All Gone" path to check for convergence issues
        System.out.println("\n======= ALL GONE TEST =======\n");
        try {
            DataSet dsAll = JosmTestSetup.loadDataSet("testdata-geometry.osm");
            List<FixPlan> allPlans = MultipolygonAnalyzer.findFixableRelations(dsAll);
            System.out.printf("Found %d fixable relations%n", allPlans.size());

            MultipolygonFixer.fixRelations(allPlans);

            // Check for remaining fixable after batch
            List<FixPlan> remaining = MultipolygonAnalyzer.findFixableRelations(dsAll);
            System.out.printf("After batch fix: %d fixable remain%n", remaining.size());
            for (FixPlan p : remaining) {
                System.out.printf("  Remaining: test_id=%s desc=%s%n",
                    p.getRelation().get("_test_id"), p.getDescription());
            }

            // Check orphaned nodes across all
            int orphanNodeCount = 0;
            for (Node n : dsAll.getNodes()) {
                if (!n.isDeleted() && n.getReferrers().stream().noneMatch(r -> r instanceof Way && !r.isDeleted())) {
                    orphanNodeCount++;
                }
            }
            System.out.printf("Orphaned nodes after batch: %d%n", orphanNodeCount);

            // Check untagged new ways
            long untaggedNewWays = dsAll.getWays().stream()
                .filter(w -> !w.isDeleted() && w.getKeys().isEmpty()
                    && w.getReferrers().stream().noneMatch(r -> r instanceof Relation && !r.isDeleted()))
                .count();
            System.out.printf("Untagged unreferenced ways after batch: %d%n", untaggedNewWays);

            // Undo all
            UndoRedoHandler.getInstance().undo();
            List<FixPlan> afterUndo = MultipolygonAnalyzer.findFixableRelations(dsAll);
            System.out.printf("After undo: %d fixable (should match original %d)%n",
                afterUndo.size(), allPlans.size());

            System.out.println("ALL GONE test completed successfully");
        } catch (Exception e) {
            System.out.printf("ALL GONE test FAILED: %s: %s%n",
                e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}
