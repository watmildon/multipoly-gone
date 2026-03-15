package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
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
    void adjacentAreas_shareOffsetNodes() {
        // Build a synthetic DataSet: two adjacent closed ways sharing 2 nodes with a road
        //
        //   A---B---C        road (highway=secondary): A-B-C
        //   |   |   |
        //   D---E---F        area1 (grass): A-B-E-D-A    (A,B glued to road)
        //                    area2 (wood):  B-C-F-E-B    (B,C glued to road)
        //
        // After ungluing, both areas need offset nodes for B.
        // Those offset nodes should be the same Node object.

        DataSet ds = new DataSet();
        Node a = newNode(ds, 45.0, -93.0);
        Node b = newNode(ds, 45.0, -93.001);
        Node c = newNode(ds, 45.0, -93.002);
        Node d = newNode(ds, 44.999, -93.0);
        Node e = newNode(ds, 44.999, -93.001);
        Node f = newNode(ds, 44.999, -93.002);

        Way road = new Way();
        road.setNodes(List.of(a, b, c));
        road.put("highway", "secondary");
        ds.addPrimitive(road);

        Way area1 = new Way();
        area1.setNodes(List.of(a, b, e, d, a));
        area1.put("landuse", "grass");
        ds.addPrimitive(area1);

        Way area2 = new Way();
        area2.setNodes(List.of(b, c, f, e, b));
        area2.put("natural", "wood");
        ds.addPrimitive(area2);

        // Analyze both
        UngluePlan plan1 = PolygonUngluer.analyze(area1, ds);
        UngluePlan plan2 = PolygonUngluer.analyze(area2, ds);
        assertNotNull(plan1, "area1 should have glued segments");
        assertNotNull(plan2, "area2 should have glued segments");

        // Execute plan1, then build commands for plan2
        List<Command> cmds1 = PolygonUnglueFixer.buildCommands(plan1);
        for (Command cmd : cmds1) cmd.executeCommand();

        // Now build plan2 — it should reuse nodes from plan1
        // Re-analyze since the DataSet changed
        UngluePlan plan2fresh = PolygonUngluer.analyze(area2, ds);
        assertNotNull(plan2fresh, "area2 should still have glued segments");

        List<Command> cmds2 = PolygonUnglueFixer.buildCommands(plan2fresh);
        for (Command cmd : cmds2) cmd.executeCommand();

        // After both execute, area1 and area2 should share exactly 2 nodes
        // (the offset of B and the original E)
        Set<Node> nodes1 = new HashSet<>(area1.getNodes());
        Set<Node> nodes2 = new HashSet<>(area2.getNodes());
        nodes1.retainAll(nodes2);
        assertTrue(nodes1.size() >= 2,
            "Adjacent areas should share at least 2 nodes (offset of B + original E), got " + nodes1.size()
            + "\narea1 nodes: " + area1.getNodes()
            + "\narea2 nodes: " + area2.getNodes());
    }

    @Test
    void adjacentAreas_shareOffsetNodes_batchAnalyzeThenExecute() {
        // Same setup as adjacentAreas_shareOffsetNodes, but mimics unglueAll():
        // analyze ALL plans first, THEN execute them sequentially.

        DataSet ds = new DataSet();
        Node a = newNode(ds, 45.0, -93.0);
        Node b = newNode(ds, 45.0, -93.001);
        Node c = newNode(ds, 45.0, -93.002);
        Node d = newNode(ds, 44.999, -93.0);
        Node e = newNode(ds, 44.999, -93.001);
        Node f = newNode(ds, 44.999, -93.002);

        Way road = new Way();
        road.setNodes(List.of(a, b, c));
        road.put("highway", "secondary");
        ds.addPrimitive(road);

        Way area1 = new Way();
        area1.setNodes(List.of(a, b, e, d, a));
        area1.put("landuse", "grass");
        ds.addPrimitive(area1);

        Way area2 = new Way();
        area2.setNodes(List.of(b, c, f, e, b));
        area2.put("natural", "wood");
        ds.addPrimitive(area2);

        // Analyze both BEFORE executing either (mimics unglueAll)
        UngluePlan plan1 = PolygonUngluer.analyze(area1, ds);
        UngluePlan plan2 = PolygonUngluer.analyze(area2, ds);
        assertNotNull(plan1);
        assertNotNull(plan2);

        // Execute both sequentially (mimics unglueAll loop)
        List<Command> cmds1 = PolygonUnglueFixer.buildCommands(plan1);
        for (Command cmd : cmds1) cmd.executeCommand();

        List<Command> cmds2 = PolygonUnglueFixer.buildCommands(plan2);
        for (Command cmd : cmds2) cmd.executeCommand();

        // After both execute, area1 and area2 should share the offset node for B and original E
        Set<Node> nodes1 = new HashSet<>(area1.getNodes());
        Set<Node> nodes2 = new HashSet<>(area2.getNodes());
        nodes1.retainAll(nodes2);
        assertTrue(nodes1.size() >= 2,
            "Adjacent areas should share at least 2 nodes (offset of B + original E), got " + nodes1.size()
            + "\narea1 nodes: " + area1.getNodes()
            + "\narea2 nodes: " + area2.getNodes());
    }

    @Test
    void adjacentAreas_realData_offsetNodesReused() {
        // Load the real test data from scratch file and mimic unglueAll flow
        DataSet ds = JosmTestSetup.loadDataSet("testdata-unglue-adjacent.osm");

        // Collect all unglue plans (mimic unglueAll)
        List<UngluePlan> plans = new ArrayList<>();
        for (Way w : ds.getWays()) {
            if (w.isDeleted() || w.isIncomplete()) continue;
            if (!w.isClosed() || w.getNodesCount() < 4) continue;
            UngluePlan plan = PolygonUngluer.analyze(w, ds);
            if (plan != null) plans.add(plan);
        }
        assertFalse(plans.isEmpty(), "Should have unglue plans");

        // Execute all sequentially (mimic unglueAll)
        for (UngluePlan plan : plans) {
            List<Command> cmds = PolygonUnglueFixer.buildCommands(plan);
            for (Command cmd : cmds) cmd.executeCommand();
        }

        // Test 1001: grass and wood ways should share offset nodes
        List<Way> test1001Ways = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "1001".equals(w.get("_test_id")))
            .collect(Collectors.toList());
        assertEquals(2, test1001Ways.size(), "Should have 2 ways with _test_id=1001");

        // Detailed diagnostics: print node positions for both ways
        StringBuilder diag = new StringBuilder();
        for (int wi = 0; wi < test1001Ways.size(); wi++) {
            Way w = test1001Ways.get(wi);
            diag.append("\nway").append(wi).append(" (").append(w.get("natural") != null ? w.get("natural") : w.get("landuse")).append("):");
            for (Node n : w.getNodes()) {
                diag.append("\n  node ").append(n.getUniqueId())
                    .append(" at ").append(n.getCoor())
                    .append(" en=").append(n.getEastNorth());
            }
        }
        // Also check for near-duplicate nodes
        List<Node> allNodes1001 = new ArrayList<>();
        for (Way w : test1001Ways) allNodes1001.addAll(w.getNodes());
        for (int a = 0; a < allNodes1001.size(); a++) {
            for (int b = a + 1; b < allNodes1001.size(); b++) {
                Node na = allNodes1001.get(a);
                Node nb = allNodes1001.get(b);
                if (na == nb) continue;
                EastNorth ea = na.getEastNorth();
                EastNorth eb = nb.getEastNorth();
                if (ea != null && eb != null) {
                    double dist = ea.distance(eb);
                    if (dist < 0.001) {
                        diag.append("\n  NEAR-DUPLICATE: node ").append(na.getUniqueId())
                            .append(" and ").append(nb.getUniqueId())
                            .append(" dist=").append(dist);
                    }
                }
            }
        }

        Set<Node> nodes0 = new HashSet<>(test1001Ways.get(0).getNodes());
        Set<Node> nodes1 = new HashSet<>(test1001Ways.get(1).getNodes());
        Set<Node> shared1001 = new HashSet<>(nodes0);
        shared1001.retainAll(nodes1);
        assertTrue(shared1001.size() >= 2,
            "Test 1001: adjacent areas should share at least 2 nodes, got " + shared1001.size()
            + diag.toString());

        // Test 1002: grass and wood ways should share offset nodes
        List<Way> test1002Ways = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "1002".equals(w.get("_test_id")))
            .collect(Collectors.toList());
        assertEquals(2, test1002Ways.size(), "Should have 2 ways with _test_id=1002");
        Set<Node> n2a = new HashSet<>(test1002Ways.get(0).getNodes());
        Set<Node> n2b = new HashSet<>(test1002Ways.get(1).getNodes());
        Set<Node> shared1002 = new HashSet<>(n2a);
        shared1002.retainAll(n2b);
        // Both share node -38223 (not on road). Both offset -38221 from same centerline.
        assertTrue(shared1002.size() >= 2,
            "Test 1002: adjacent areas should share at least 2 nodes, got " + shared1002.size()
            + "\nway0: " + test1002Ways.get(0).getNodes()
            + "\nway1: " + test1002Ways.get(1).getNodes());
    }

    private static Node newNode(DataSet ds, double lat, double lon) {
        Node n = new Node(new org.openstreetmap.josm.data.coor.LatLon(lat, lon));
        ds.addPrimitive(n);
        return n;
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
