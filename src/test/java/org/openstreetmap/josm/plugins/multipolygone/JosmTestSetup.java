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
     */
    public static DataSet loadDataSet(String resourceName) {
        try (InputStream is = JosmTestSetup.class.getResourceAsStream("/" + resourceName)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Test resource not found on classpath: " + resourceName);
            }
            return OsmReader.parseDataSet(is, null);
        } catch (IllegalDataException | java.io.IOException e) {
            throw new RuntimeException("Failed to load test data: " + resourceName, e);
        }
    }
}
