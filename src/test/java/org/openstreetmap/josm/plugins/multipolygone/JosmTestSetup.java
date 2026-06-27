package org.openstreetmap.josm.plugins.multipolygone;

import java.io.InputStream;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/**
 * Minimal JOSM subsystem initialization for unit tests.
 *
 * Usage:
 *   @RegisterExtension
 *   static JosmTestSetup josm = new JosmTestSetup();
 */
public class JosmTestSetup implements BeforeAllCallback {

    private static boolean initialized = false;

    @Override
    public void beforeAll(ExtensionContext context) {
        init();
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        // In-memory preferences for Config.getPref() calls
        Config.setPreferencesInstance(new MemoryPreferences());

        // Projection needed by Node.getEastNorth() -> Geometry.nodeInsidePolygon()
        ProjectionRegistry.setProjection(
            Projections.getProjectionByCode("EPSG:4326"));

        initialized = true;
    }

    /**
     * Load a .osm test data file from the classpath into a DataSet.
     *
     * <p>Marks every loaded primitive as having referrers downloaded. Tests assume
     * the file represents a complete world (whatever the test author put in the file
     * is everything that exists). Production code now refuses to delete or mutate
     * primitives whose referrer status is unknown — without this marking, every test
     * would hit the fail-closed path.
     */
    public static DataSet loadDataSet(String resourceName) {
        try (InputStream is = JosmTestSetup.class.getResourceAsStream("/" + resourceName)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Test resource not found on classpath: " + resourceName);
            }
            DataSet ds = OsmReader.parseDataSet(is, null);
            markAllReferrersDownloaded(ds);
            return ds;
        } catch (IllegalDataException | java.io.IOException e) {
            throw new RuntimeException("Failed to load test data: " + resourceName, e);
        }
    }

    /**
     * Marks every primitive in the dataset as having its referrers downloaded.
     * Test fixtures are complete by construction; production code's fail-closed
     * referrer checks would otherwise reject any deletion/mutation under tests.
     */
    public static void markAllReferrersDownloaded(DataSet ds) {
        ds.getNodes().forEach(n -> n.setReferrersDownloaded(true));
        ds.getWays().forEach(w -> w.setReferrersDownloaded(true));
        ds.getRelations().forEach(r -> r.setReferrersDownloaded(true));
    }
}
