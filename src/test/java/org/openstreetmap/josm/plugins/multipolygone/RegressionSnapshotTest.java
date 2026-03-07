package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.io.OsmWriterFactory;


/**
 * Regression snapshot test. Place .osm files in {@code tests/regression/}
 * and run this test. On first run it generates baseline output in
 * {@code tests/regression/expected/} and fails asking you to review and
 * commit. On subsequent runs it compares current output against the
 * committed baseline and reports any geometry or tag differences.
 *
 * <p>Raw JOSM exports are automatically sanitized on first run (metadata
 * attributes stripped). Both the sanitized input and baseline output are
 * committed to version control.
 */
class RegressionSnapshotTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    /** Root of the regression test data directory (on disk, not classpath). */
    private static final Path REGRESSION_DIR = findRegressionDir();
    private static final Path EXPECTED_DIR = REGRESSION_DIR.resolve("expected");

    @TestFactory
    List<DynamicTest> snapshotRegression() {
        List<String> inputFiles = discoverInputFiles();
        if (inputFiles.isEmpty()) {
            return Collections.emptyList();
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (String fileName : inputFiles) {
            tests.add(DynamicTest.dynamicTest(fileName, () -> runSnapshotTest(fileName)));
        }
        return tests;
    }

    private void runSnapshotTest(String inputFileName) throws Exception {
        // Clean undo stack for each test
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }

        Path inputPath = REGRESSION_DIR.resolve(inputFileName);
        Path expectedPath = EXPECTED_DIR.resolve(inputFileName);
        boolean isFirstRun = !Files.exists(expectedPath);

        // On first run, sanitize the input file in-place
        if (isFirstRun) {
            sanitizeOsmFile(inputPath);
        }

        // Load and run the fixer
        DataSet ds = loadDataSetFromDisk(inputPath);
        List<FixPlan> plans = MultipolygonAnalyzer.findFixableRelations(ds);
        if (!plans.isEmpty()) {
            MultipolygonFixer.fixRelationsUntilConvergence(plans);
        }

        DataSetSnapshot actualSnapshot = DataSetSnapshot.capture(ds);

        if (isFirstRun) {
            // Generate baseline
            Files.createDirectories(EXPECTED_DIR);
            writeDataSet(ds, expectedPath);
            sanitizeOsmFile(expectedPath);
            fail("Baseline created for " + inputFileName + " at " + expectedPath + ".\n"
                + "Review the sanitized input and baseline output, then commit both.");
        }

        // Load baseline and compare
        DataSet baselineDs = loadDataSetFromDisk(expectedPath);
        DataSetSnapshot expectedSnapshot = DataSetSnapshot.capture(baselineDs);

        String diff = DataSetSnapshot.diff(expectedSnapshot, actualSnapshot);
        if (!diff.isEmpty()) {
            fail("Regression detected for " + inputFileName + ":\n\n" + diff
                + "\nIf this change is intentional, delete " + expectedPath
                + " and re-run to generate a new baseline.");
        }
    }

    // ===================================================================
    // File discovery
    // ===================================================================

    private static List<String> discoverInputFiles() {
        if (!Files.isDirectory(REGRESSION_DIR)) {
            return Collections.emptyList();
        }
        List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(REGRESSION_DIR, "*.osm")) {
            for (Path path : stream) {
                files.add(path.getFileName().toString());
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(files);
        return files;
    }

    // ===================================================================
    // File I/O helpers
    // ===================================================================

    private static DataSet loadDataSetFromDisk(Path path) throws Exception {
        try (var is = Files.newInputStream(path)) {
            return OsmReader.parseDataSet(is, null);
        }
    }

    /**
     * Writes only the surviving (non-deleted) primitives from a DataSet to an .osm file.
     * Uses OsmWriter but first removes deleted primitives from the dataset.
     * The deletions are reversed afterward so the caller's dataset is unmodified.
     */
    private static void writeDataSet(DataSet ds, Path path) throws IOException {
        // Collect deleted primitives to temporarily purge
        List<org.openstreetmap.josm.data.osm.Relation> deletedRelations = ds.getRelations().stream()
            .filter(org.openstreetmap.josm.data.osm.Relation::isDeleted)
            .collect(Collectors.toList());
        List<org.openstreetmap.josm.data.osm.Way> deletedWays = ds.getWays().stream()
            .filter(org.openstreetmap.josm.data.osm.Way::isDeleted)
            .collect(Collectors.toList());
        List<org.openstreetmap.josm.data.osm.Node> deletedNodes = ds.getNodes().stream()
            .filter(org.openstreetmap.josm.data.osm.Node::isDeleted)
            .collect(Collectors.toList());

        // Temporarily remove deleted primitives (relations first, then ways, then nodes)
        deletedRelations.forEach(ds::removePrimitive);
        deletedWays.forEach(ds::removePrimitive);
        deletedNodes.forEach(ds::removePrimitive);

        try (var fos = Files.newOutputStream(path);
             var pw = new PrintWriter(fos, false, StandardCharsets.UTF_8)) {
            var writer = OsmWriterFactory.createOsmWriter(pw, true, "0.6");
            writer.write(ds);
        } finally {
            // Restore deleted primitives
            deletedNodes.forEach(ds::addPrimitive);
            deletedWays.forEach(ds::addPrimitive);
            deletedRelations.forEach(ds::addPrimitive);
        }
    }

    /**
     * Strips unnecessary metadata attributes from an .osm file in-place.
     * Removes: version, changeset, user, uid, timestamp, visible, action.
     * Mirrors tests/strip-osm-metadata.py but runs automatically in Java.
     */
    static void sanitizeOsmFile(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        // Keep 'version' (OsmReader requires it) and 'visible' (harmless, sometimes needed)
        String[] attrsToStrip = {"changeset", "user", "uid", "timestamp", "action"};
        for (String attr : attrsToStrip) {
            // Match attribute on node/way/relation elements: attr='...' or attr="..."
            content = content.replaceAll(
                "(<(?:node|way|relation)\\b[^>]*?) " + attr + "='[^']*'",
                "$1");
            content = content.replaceAll(
                "(<(?:node|way|relation)\\b[^>]*?) " + attr + "=\"[^\"]*\"",
                "$1");
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ===================================================================
    // Directory resolution
    // ===================================================================

    private static Path findRegressionDir() {
        // Try relative to working directory (typical Gradle run)
        Path candidate = Paths.get("tests", "regression");
        if (Files.isDirectory(candidate)) return candidate;

        // Try relative to project root via system property
        String projectDir = System.getProperty("user.dir");
        if (projectDir != null) {
            candidate = Paths.get(projectDir, "tests", "regression");
            if (Files.isDirectory(candidate)) return candidate;
        }

        // Return the default path even if it doesn't exist yet
        // (discoverInputFiles() will return empty list)
        return Paths.get("tests", "regression");
    }
}
