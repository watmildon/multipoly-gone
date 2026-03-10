package org.openstreetmap.josm.plugins.multipolygone;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;

import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.io.OsmWriter;
import org.openstreetmap.josm.io.OsmWriterFactory;

/**
 * Utility that analyzes all break test cases, updates _test_node_count on
 * existing test nodes, and creates new test nodes at centroids of uncovered
 * result polygons. Writes the updated OSM file back to disk.
 *
 * Run: ./gradlew test --tests "BreakRegressionUpdater.updateTestData"
 */
class BreakRegressionUpdater {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet ds;

    @BeforeAll
    static void loadData() {
        ds = JosmTestSetup.loadDataSet("unit-tests-break-polygon.osm");
    }

    @Test
    void updateTestData() throws Exception {
        Set<String> closedWayIds = new LinkedHashSet<>();
        Set<String> relationIds = new LinkedHashSet<>();

        for (Way w : ds.getWays()) {
            if (!w.isDeleted() && w.isClosed() && w.hasKey("_test_id")
                && "yes".equals(w.get("_test_object_to_break"))) {
                closedWayIds.add(w.get("_test_id"));
            }
        }
        for (Relation r : ds.getRelations()) {
            if (!r.isDeleted() && r.hasKey("_test_id")
                && "yes".equals(r.get("_test_object_to_break"))) {
                relationIds.add(r.get("_test_id"));
            }
        }

        int updates = 0, adds = 0, newNodes = 0;

        // Process closed way tests
        for (String testId : closedWayIds) {
            Way way = findWayByTestId(testId);
            if (way == null) continue;
            BreakPlan plan = PolygonBreaker.analyze(way, ds);
            if (plan == null) continue;
            int[] counts = processTestCase(testId, plan);
            updates += counts[0];
            adds += counts[1];
            newNodes += counts[2];
        }

        // Process relation tests
        for (String testId : relationIds) {
            Relation rel = findRelationByTestId(testId);
            if (rel == null) continue;
            BreakPlan plan = PolygonBreaker.analyze(rel, ds);
            if (plan == null) continue;
            int[] counts = processTestCase(testId, plan);
            updates += counts[0];
            adds += counts[1];
            newNodes += counts[2];
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.printf("Updated: %d, Added count to existing: %d, New nodes: %d%n",
            updates, adds, newNodes);

        // Write the modified dataset back
        Path outPath = Paths.get("tests", "unit-tests-break-polygon.osm").toAbsolutePath();
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outPath.toFile()),
                    StandardCharsets.UTF_8))) {
            OsmWriter writer = OsmWriterFactory.createOsmWriter(pw, false, "0.6");
            writer.write(ds);
        }
        System.out.println("Wrote: " + outPath);
    }

    /**
     * Processes one test case: updates/adds _test_node_count on existing nodes,
     * creates new nodes for uncovered polygons.
     * @return {updates, adds, newNodes}
     */
    private int[] processTestCase(String testId, BreakPlan plan) {
        List<List<EastNorth>> polys = plan.getResultCoordinates();
        int updates = 0, adds = 0, created = 0;

        // Find existing "area" test nodes for this test ID
        List<Node> areaNodes = ds.getNodes().stream()
            .filter(n -> !n.isDeleted()
                && testId.equals(n.get("_test_id"))
                && n.hasKey("_test_note")
                && n.get("_test_note").contains("area should be created"))
            .collect(Collectors.toList());

        boolean[] polyHasNode = new boolean[polys.size()];

        for (Node testNode : areaNodes) {
            EastNorth pt = testNode.getEastNorth();
            for (int pi = 0; pi < polys.size(); pi++) {
                if (pointInPolygonEN(pt, polys.get(pi))) {
                    polyHasNode[pi] = true;
                    int nodeCount = polys.get(pi).size() - 1;
                    String existing = testNode.get("_test_node_count");
                    if (existing != null) {
                        int oldCount = Integer.parseInt(existing);
                        if (oldCount != nodeCount) {
                            testNode.put("_test_node_count", String.valueOf(nodeCount));
                            System.out.printf("  UPDATE test %s node %d: %d -> %d%n",
                                testId, testNode.getId(), oldCount, nodeCount);
                            updates++;
                        }
                    } else {
                        testNode.put("_test_node_count", String.valueOf(nodeCount));
                        System.out.printf("  ADD test %s node %d: _test_node_count=%d%n",
                            testId, testNode.getId(), nodeCount);
                        adds++;
                    }
                    break;
                }
            }
        }

        // Create new test nodes for uncovered polygons
        for (int pi = 0; pi < polys.size(); pi++) {
            if (!polyHasNode[pi]) {
                List<EastNorth> poly = polys.get(pi);
                EastNorth centroid = computeCentroid(poly);
                int nodeCount = poly.size() - 1;

                // Convert EastNorth to LatLon
                LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(centroid);

                Node newNode = new Node(ll);
                newNode.put("_test_id", testId);
                newNode.put("_test_node_count", String.valueOf(nodeCount));
                newNode.put("_test_note", "An area should be created here");
                ds.addPrimitive(newNode);

                System.out.printf("  NEW test %s node %d at (%.8f, %.8f): _test_node_count=%d%n",
                    testId, newNode.getId(), ll.lat(), ll.lon(), nodeCount);
                created++;
            }
        }

        return new int[]{updates, adds, created};
    }

    private EastNorth computeCentroid(List<EastNorth> poly) {
        int n = poly.size() - 1; // exclude closing duplicate
        double area = 0;
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            EastNorth curr = poly.get(i);
            EastNorth next = poly.get((i + 1) % n);
            double cross = curr.east() * next.north() - next.east() * curr.north();
            area += cross;
            cx += (curr.east() + next.east()) * cross;
            cy += (curr.north() + next.north()) * cross;
        }
        area /= 2.0;
        if (Math.abs(area) < 1e-15) {
            double ex = 0, ey = 0;
            for (int i = 0; i < n; i++) {
                ex += poly.get(i).east();
                ey += poly.get(i).north();
            }
            return new EastNorth(ex / n, ey / n);
        }
        cx /= (6.0 * area);
        cy /= (6.0 * area);
        return new EastNorth(cx, cy);
    }

    private boolean pointInPolygonEN(EastNorth pt, List<EastNorth> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            EastNorth pi = poly.get(i);
            EastNorth pj = poly.get(j);
            if ((pi.north() > pt.north()) != (pj.north() > pt.north())
                && pt.east() < (pj.east() - pi.east()) * (pt.north() - pi.north())
                    / (pj.north() - pi.north()) + pi.east()) {
                inside = !inside;
            }
        }
        return inside;
    }

    private Way findWayByTestId(String testId) {
        return ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.isClosed() && testId.equals(w.get("_test_id")))
            .sorted((a, b) -> {
                boolean aBreak = "yes".equals(a.get("_test_object_to_break"));
                boolean bBreak = "yes".equals(b.get("_test_object_to_break"));
                if (aBreak != bBreak) return aBreak ? -1 : 1;
                boolean aHw = a.hasKey("highway");
                boolean bHw = b.hasKey("highway");
                if (aHw != bHw) return aHw ? 1 : -1;
                return 0;
            })
            .findFirst().orElse(null);
    }

    private Relation findRelationByTestId(String testId) {
        return ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && testId.equals(r.get("_test_id")))
            .sorted((a, b) -> {
                boolean aBreak = "yes".equals(a.get("_test_object_to_break"));
                boolean bBreak = "yes".equals(b.get("_test_object_to_break"));
                if (aBreak != bBreak) return aBreak ? -1 : 1;
                return 0;
            })
            .findFirst().orElse(null);
    }
}
