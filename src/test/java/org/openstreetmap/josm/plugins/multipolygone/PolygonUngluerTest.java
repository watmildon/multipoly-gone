package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;

class PolygonUngluerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private Way findWayByTag(DataSet ds, String key, String value) {
        return ds.getWays().stream()
            .filter(w -> !w.isDeleted() && value.equals(w.get(key)))
            .findFirst()
            .orElse(null);
    }

    @Test
    void gluedPark_detected() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue.osm");
        // The glued park shares nodes with the highway
        Way gluedPark = findWayByTag(ds, "_test_note",
            "This is incorrectly glued to this roadway");
        assertNotNull(gluedPark, "Should find the glued park");
        assertTrue(gluedPark.isClosed(), "Park should be closed");

        UngluePlan plan = PolygonUngluer.analyze(gluedPark, ds);
        assertNotNull(plan, "Should detect glued segments");
        assertFalse(plan.getGluedRuns().isEmpty(), "Should have at least one glued run");
    }

    @Test
    void gluedPark_runCoversSharedNodes() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue.osm");
        Way gluedPark = findWayByTag(ds, "_test_note",
            "This is incorrectly glued to this roadway");
        assertNotNull(gluedPark);

        UngluePlan plan = PolygonUngluer.analyze(gluedPark, ds);
        assertNotNull(plan);

        // The park shares 2 nodes with the highway (-38132 and -38135)
        UngluePlan.GluedRun run = plan.getGluedRuns().get(0);
        assertEquals(2, run.getSharedNodes().size(),
            "Should have 2 shared nodes with the highway");

        Way centerline = run.getCenterlineWay();
        assertNotNull(centerline);
        assertTrue(centerline.hasKey("highway"),
            "Centerline should be a highway");
    }

    @Test
    void gluedPark_resultGeometryIsClosed() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue.osm");
        Way gluedPark = findWayByTag(ds, "_test_note",
            "This is incorrectly glued to this roadway");

        UngluePlan plan = PolygonUngluer.analyze(gluedPark, ds);
        assertNotNull(plan);

        List<EastNorth> result = plan.getResultGeometry();
        assertTrue(result.size() >= 4, "Result should have at least 4 points");
        EastNorth first = result.get(0);
        EastNorth last = result.get(result.size() - 1);
        assertTrue(GeometryUtils.isNear(first, last, 1e-6),
            "Result geometry should be closed");
    }

    @Test
    void gluedPark_offsetPointsAreNotOnCenterline() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue.osm");
        Way gluedPark = findWayByTag(ds, "_test_note",
            "This is incorrectly glued to this roadway");

        UngluePlan plan = PolygonUngluer.analyze(gluedPark, ds);
        assertNotNull(plan);

        UngluePlan.GluedRun run = plan.getGluedRuns().get(0);

        for (int i = 0; i < run.getOffsetPoints().size(); i++) {
            EastNorth offsetPt = run.getOffsetPoints().get(i);
            EastNorth sharedPt = run.getSharedNodes().get(i).getEastNorth();
            double dist = Math.sqrt(
                Math.pow(offsetPt.east() - sharedPt.east(), 2) +
                Math.pow(offsetPt.north() - sharedPt.north(), 2));
            assertFalse(GeometryUtils.isNear(offsetPt, sharedPt, 1e-8),
                "Offset point " + i + " should not coincide with original shared node"
                + " (dist=" + dist + ", offset=" + offsetPt + ", shared=" + sharedPt + ")");
        }
    }

    @Test
    void correctPark_noGluedSegments() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue.osm");
        // The correctly offset park shares no nodes with the highway
        Way correctPark = findWayByTag(ds, "_test_note",
            "This is correctly offset from the roadway");
        assertNotNull(correctPark, "Should find the correct park");

        UngluePlan plan = PolygonUngluer.analyze(correctPark, ds);
        assertNull(plan, "Correctly offset park should have no glued segments");
    }

    @Test
    void gluedPark_resultHasMoreOrEqualNodes() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue.osm");
        Way gluedPark = findWayByTag(ds, "_test_note",
            "This is incorrectly glued to this roadway");

        UngluePlan plan = PolygonUngluer.analyze(gluedPark, ds);
        assertNotNull(plan);

        // Result should have at least as many points as the original
        // (shared nodes are replaced with offset nodes, non-shared kept)
        assertTrue(plan.getResultGeometry().size() >= gluedPark.getNodesCount(),
            "Result geometry should have at least as many nodes as original");
    }
}
