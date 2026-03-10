package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Tests for the handmade break test cases in unit-tests-break-polygon.osm.
 * Each test case is identified by a {@code _test_id} tag on the polygon (Way or Relation)
 * and the intersecting road ways.
 */
class PolygonBreakerHandmadeTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet ds;

    @BeforeAll
    static void loadData() {
        ds = JosmTestSetup.loadDataSet("unit-tests-break-polygon.osm");
        assertNotNull(ds, "Should load unit-tests-break-polygon.osm");
    }

    // -- Helpers --

    private Way findWayByTestId(String testId) {
        // Prefer the way tagged _test_object_to_break=yes, then non-highway
        // closed ways, since highway loops may also be closed ways with the same _test_id.
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
        // Prefer the relation tagged _test_object_to_break=yes
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

    private BreakPlan analyzePrimitive(OsmPrimitive prim) {
        assertNotNull(prim);
        return PolygonBreaker.analyze(prim, ds);
    }

    private void assertPlanPieces(BreakPlan plan, int expectedPieces, String testId) {
        assertNotNull(plan, "Test " + testId + ": should produce a break plan");
        assertEquals(expectedPieces, plan.getResultCoordinates().size(),
            "Test " + testId + ": expected " + expectedPieces + " result pieces");
    }

    private void assertAllClosed(BreakPlan plan, String testId) {
        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            assertTrue(poly.size() >= 4,
                "Test " + testId + " poly " + i + ": needs at least 4 points");
            EastNorth first = poly.get(0);
            EastNorth last = poly.get(poly.size() - 1);
            assertTrue(GeometryUtils.isNear(first, last, 1e-6),
                "Test " + testId + " poly " + i + ": should be closed");
        }
    }

    private void assertAllPositiveArea(BreakPlan plan, String testId) {
        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            double area = computeSignedAreaEN(poly);
            assertTrue(Math.abs(area) > 1e-10,
                "Test " + testId + " poly " + i + ": should have non-trivial area, got " + area);
        }
    }

    private void assertAllCompact(BreakPlan plan, String testId, double maxCompactness) {
        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            double area = Math.abs(computeSignedAreaEN(poly));
            double perimeter = 0;
            for (int pi = 0; pi < poly.size() - 1; pi++) {
                EastNorth a = poly.get(pi);
                EastNorth b = poly.get(pi + 1);
                perimeter += Math.sqrt(
                    Math.pow(b.east() - a.east(), 2) + Math.pow(b.north() - a.north(), 2));
            }
            double compactness = (perimeter * perimeter) / (4 * Math.PI * area);
            assertTrue(compactness < maxCompactness,
                "Test " + testId + " poly " + i + ": compactness=" + compactness
                + " exceeds " + maxCompactness);
        }
    }

    private static double computeSignedAreaEN(List<EastNorth> poly) {
        double area = 0;
        int n = poly.size() - 1;
        for (int i = 0; i < n; i++) {
            EastNorth curr = poly.get(i);
            EastNorth next = poly.get((i + 1) % n);
            area += curr.east() * next.north() - next.east() * curr.north();
        }
        return area / 2.0;
    }

    /**
     * Asserts that no result polygon is self-intersecting.
     * A self-intersecting polygon has non-adjacent edges that properly cross.
     */
    private void assertNotSelfIntersecting(BreakPlan plan, String testId) {
        for (int pi = 0; pi < plan.getResultCoordinates().size(); pi++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(pi);
            int n = poly.size() - 1; // exclude closing point
            for (int i = 0; i < n; i++) {
                EastNorth a1 = poly.get(i);
                EastNorth a2 = poly.get(i + 1);
                for (int j = i + 2; j < n; j++) {
                    if (j == n - 1 && i == 0) continue; // skip adjacent closing edge
                    EastNorth b1 = poly.get(j);
                    EastNorth b2 = poly.get(j + 1);
                    assertFalse(GeometryUtils.segmentsCross(a1, a2, b1, b2),
                        "Test " + testId + " poly " + pi + ": edge " + i
                        + " crosses edge " + j + " — self-intersecting polygon!");
                }
            }
        }
    }

    private void assertNoOverlap(BreakPlan plan, String testId) {
        List<List<EastNorth>> polys = plan.getResultCoordinates();
        for (int i = 0; i < polys.size(); i++) {
            for (int j = i + 1; j < polys.size(); j++) {
                assertNoEdgeCrossings(polys.get(i), polys.get(j), testId, i, j);
            }
        }
    }

    private void assertNoEdgeCrossings(List<EastNorth> polyA, List<EastNorth> polyB,
            String testId, int idxA, int idxB) {
        for (int ai = 0; ai < polyA.size() - 1; ai++) {
            EastNorth a1 = polyA.get(ai);
            EastNorth a2 = polyA.get(ai + 1);
            for (int bi = 0; bi < polyB.size() - 1; bi++) {
                EastNorth b1 = polyB.get(bi);
                EastNorth b2 = polyB.get(bi + 1);
                if (!GeometryUtils.segmentsCross(a1, a2, b1, b2)) continue;
                fail("Test " + testId + ": poly " + idxA + " edge " + ai
                    + " crosses poly " + idxB + " edge " + bi + " — polygons overlap!");
            }
        }
    }

    /**
     * Analyzes with highways reversed, checks same piece count.
     * Direction of highway ways should not change the result.
     */
    private void assertDirectionInvariant(OsmPrimitive target, int expectedPieces, String testId) {
        // Find all highway ways for this test case
        List<Way> roads = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.hasKey("highway") && testId.equals(w.get("_test_id")))
            .collect(Collectors.toList());

        // Reverse all highway ways
        for (Way road : roads) {
            List<Node> nodes = new ArrayList<>(road.getNodes());
            Collections.reverse(nodes);
            road.setNodes(nodes);
        }

        try {
            BreakPlan reversedPlan = PolygonBreaker.analyze(target, ds);
            assertNotNull(reversedPlan,
                "Test " + testId + " (reversed roads): should still produce a break plan");
            assertEquals(expectedPieces, reversedPlan.getResultCoordinates().size(),
                "Test " + testId + " (reversed roads): piece count should be identical");
            assertAllClosed(reversedPlan, testId + " reversed");
            assertAllPositiveArea(reversedPlan, testId + " reversed");
        } finally {
            // Restore original direction
            for (Way road : roads) {
                List<Node> nodes = new ArrayList<>(road.getNodes());
                Collections.reverse(nodes);
                road.setNodes(nodes);
            }
        }
    }

    /**
     * Finds all highway ways tagged with the given _test_id.
     */
    private List<Way> findHighwaysByTestId(String testId) {
        return ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.hasKey("highway") && testId.equals(w.get("_test_id")))
            .collect(Collectors.toList());
    }

    /**
     * Asserts that no result polygon edge properly crosses any interior
     * highway edge. Only road segments where BOTH endpoints are inside the
     * original polygon are checked. Segments that straddle the polygon
     * boundary (one endpoint outside) produce a near-boundary touch, not a
     * visible interior crossing, and are excluded.
     */
    private void assertNoCrossingWithRoads(BreakPlan plan, String testId) {
        List<Way> roads = findHighwaysByTestId(testId);
        List<List<EastNorth>> polys = plan.getResultCoordinates();
        // Build original polygon boundary for containment checks
        List<EastNorth> origPoly = buildSourcePolygonEN(plan);
        for (int pi = 0; pi < polys.size(); pi++) {
            List<EastNorth> poly = polys.get(pi);
            for (Way road : roads) {
                List<Node> roadNodes = road.getNodes();
                for (int pe = 0; pe < poly.size() - 1; pe++) {
                    EastNorth p1 = poly.get(pe);
                    EastNorth p2 = poly.get(pe + 1);
                    for (int re = 0; re < roadNodes.size() - 1; re++) {
                        EastNorth r1 = roadNodes.get(re).getEastNorth();
                        EastNorth r2 = roadNodes.get(re + 1).getEastNorth();
                        if (!GeometryUtils.segmentsCross(p1, p2, r1, r2)) continue;
                        // Segments cross — compute the intersection point.
                        // Only flag it if the intersection is strictly inside
                        // the original polygon (not on the boundary where roads
                        // naturally enter/exit).
                        EastNorth ix = Geometry.getSegmentSegmentIntersection(p1, p2, r1, r2);
                        if (ix == null) continue;
                        if (origPoly != null && isOnPolygonBoundary(ix, origPoly)) continue;
                        // Skip near-endpoint crossings: corridor offsets run alongside
                        // roads and may have tiny numeric crossings near shared endpoints
                        double endpointTol = 2e-4; // ~22m in degrees, covers road width offsets and loop subtraction edges
                        if (isNearAny(ix, endpointTol, p1, p2, r1, r2)) continue;
                        fail("Test " + testId + ": poly " + pi + " edge " + pe
                            + " crosses road edge " + re
                            + " at interior point (" + ix.east() + ", " + ix.north()
                            + ") — result polygon crosses over highway!");
                    }
                }
            }
        }
    }

    /** Checks if a point is on (or very near) the polygon boundary. */
    private boolean isOnPolygonBoundary(EastNorth pt, List<EastNorth> poly) {
        double threshold = 1e-7; // ~1cm in degrees
        for (int i = 0; i < poly.size() - 1; i++) {
            EastNorth a = poly.get(i);
            EastNorth b = poly.get(i + 1);
            double dist = pointToSegmentDist(pt, a, b);
            if (dist < threshold) return true;
        }
        return false;
    }

    /** Returns true if pt is within tol of any of the given points. */
    private boolean isNearAny(EastNorth pt, double tol, EastNorth... points) {
        for (EastNorth p : points) {
            double dx = pt.east() - p.east();
            double dy = pt.north() - p.north();
            if (dx * dx + dy * dy < tol * tol) return true;
        }
        return false;
    }

    /** Distance from point to line segment. */
    private double pointToSegmentDist(EastNorth pt, EastNorth a, EastNorth b) {
        double dx = b.east() - a.east();
        double dy = b.north() - a.north();
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-30) {
            double ex = pt.east() - a.east();
            double ey = pt.north() - a.north();
            return Math.sqrt(ex * ex + ey * ey);
        }
        double t = ((pt.east() - a.east()) * dx + (pt.north() - a.north()) * dy) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double projX = a.east() + t * dx;
        double projY = a.north() + t * dy;
        double ex = pt.east() - projX;
        double ey = pt.north() - projY;
        return Math.sqrt(ex * ex + ey * ey);
    }

    /** Builds the source polygon as EastNorth list from the BreakPlan source primitive. */
    private List<EastNorth> buildSourcePolygonEN(BreakPlan plan) {
        if (plan.getSource() instanceof Way) {
            Way w = (Way) plan.getSource();
            List<EastNorth> pts = new ArrayList<>();
            for (Node n : w.getNodes()) {
                pts.add(n.getEastNorth());
            }
            return pts;
        }
        if (plan.getSource() instanceof Relation) {
            // Use the first outer ring
            Relation r = (Relation) plan.getSource();
            for (org.openstreetmap.josm.data.osm.RelationMember rm : r.getMembers()) {
                if ("outer".equals(rm.getRole()) && rm.isWay() && rm.getWay().isClosed()) {
                    List<EastNorth> pts = new ArrayList<>();
                    for (Node n : rm.getWay().getNodes()) {
                        pts.add(n.getEastNorth());
                    }
                    return pts;
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Cross-cutting invariant tests: no overlap + direction invariance + no road crossing
    // -----------------------------------------------------------------------

    @Test
    void allClosedWayTests_notSelfIntersecting() {
        for (String testId : new String[]{"96", "97", "98", "99", "100", "101", "102", "103", "104", "105", "106"}) {
            Way target = findWayByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find polygon way");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNotSelfIntersecting(plan, testId);
        }
    }

    @Test
    void allMPTests_notSelfIntersecting() {
        for (String testId : new String[]{"98.1", "99.1", "99.2", "100.1", "101.1", "102.1", "105.1", "106.1"}) {
            Relation target = findRelationByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find relation");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNotSelfIntersecting(plan, testId);
        }
    }

    @Test
    void allClosedWayTests_noCrossingWithRoads() {
        for (String testId : new String[]{"96", "97", "98", "99", "100", "101", "102", "103", "104", "105", "106"}) {
            Way target = findWayByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find polygon way");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNoCrossingWithRoads(plan, testId);
        }
    }

    @Test
    void allMPTests_noCrossingWithRoads() {
        for (String testId : new String[]{"98.1", "99.1", "99.2", "100.1", "101.1", "102.1", "105.1"}) {
            Relation target = findRelationByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find relation");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNoCrossingWithRoads(plan, testId);
        }
    }

    @Test
    void allClosedWayTests_noOverlap() {
        for (String testId : new String[]{"96", "97", "98", "99", "100", "101", "102", "103", "104", "105", "106"}) {
            Way target = findWayByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find polygon way");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNoOverlap(plan, testId);
        }
    }

    @Test
    void allMPTests_noOverlap() {
        for (String testId : new String[]{"98.1", "99.1", "99.2", "100.1", "101.1", "102.1", "105.1"}) {
            Relation target = findRelationByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find relation");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNoOverlap(plan, testId);
        }
    }

    @Test
    void closedWayTests_directionInvariant() {
        // Map test ID → expected piece count
        Object[][] cases = {
            {"96", 2}, {"97", 2}, {"98", 2}, {"99", 2}, {"100", 4}, {"101", 6},
            {"102", 4}, {"103", 5}, {"104", 3}, {"105", 3}, {"106", 4}
        };
        for (Object[] c : cases) {
            String testId = (String) c[0];
            int expected = (Integer) c[1];
            Way target = findWayByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find polygon way");
            assertDirectionInvariant(target, expected, testId);
        }
    }

    @Test
    void mpTests_directionInvariant() {
        Object[][] cases = {
            {"98.1", 2}, {"99.1", 2}, {"99.2", 2}, {"100.1", 4}, {"101.1", 6}
        };
        for (Object[] c : cases) {
            String testId = (String) c[0];
            int expected = (Integer) c[1];
            Relation target = findRelationByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find relation");
            assertDirectionInvariant(target, expected, testId);
        }
    }

    @Test
    void allClosedWayTests_noSpuriousAreas() {
        for (String testId : new String[]{"96", "97", "98", "99", "100", "101", "102", "103", "104", "105", "106"}) {
            Way target = findWayByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find polygon way");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNoAreaAtTestNodes(plan, testId);
            assertAreaAtTestNodes(plan, testId);
            assertNodeCounts(plan, testId);
        }
    }

    @Test
    void allMPTests_noSpuriousAreas() {
        for (String testId : new String[]{"98.1", "99.1", "99.2", "100.1", "101.1", "102.1", "105.1"}) {
            Relation target = findRelationByTestId(testId);
            assertNotNull(target, "Test " + testId + ": should find relation");
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": analyzer should offer to break this polygon");
            assertNoAreaAtTestNodes(plan, testId);
            assertAreaAtTestNodes(plan, testId);
            assertNodeCounts(plan, testId);
        }
    }

    // -----------------------------------------------------------------------
    // Test 96: Triangle road inside rectangle → 2 pieces
    // -----------------------------------------------------------------------

    @Test
    void test96_dumpGeometry() {
        Way target = findWayByTestId("96");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan);
        System.out.println("=== Test 96 corridors ===");
        for (int ci = 0; ci < plan.getCorridors().size(); ci++) {
            BreakPlan.RoadCorridor c = plan.getCorridors().get(ci);
            Way primaryWay = c.getPrimaryWay();
            String name = primaryWay != null ? primaryWay.get("name") : "(unknown)";
            System.out.printf("Corridor %d: %d ways, width=%.1fm, name=%s%n",
                ci, c.getSourceWays().size(), c.getWidthMeters(), name);
        }
        System.out.println("=== Test 96 result polygons ===");
        for (int pi = 0; pi < plan.getResultCoordinates().size(); pi++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(pi);
            System.out.println("Poly " + pi + " (" + poly.size() + " pts):");
            for (int i = 0; i < poly.size(); i++) {
                EastNorth p = poly.get(i);
                System.out.println("  [" + i + "] e=" + p.east() + " n=" + p.north());
            }
        }
        System.out.println("=== Test 96 roads ===");
        List<Way> roads = findHighwaysByTestId("96");
        for (Way road : roads) {
            System.out.println("Road " + road.getId() + ":");
            for (int i = 0; i < road.getNodesCount(); i++) {
                EastNorth p = road.getNode(i).getEastNorth();
                System.out.println("  [" + i + "] e=" + p.east() + " n=" + p.north());
            }
        }
    }

    private void dumpGeometry(String testId, BreakPlan plan) {
        System.out.println("=== Test " + testId + " corridors ===");
        for (int ci = 0; ci < plan.getCorridors().size(); ci++) {
            BreakPlan.RoadCorridor c = plan.getCorridors().get(ci);
            Way primaryWay = c.getPrimaryWay();
            String name = primaryWay != null ? primaryWay.get("name") : "(unknown)";
            System.out.printf("Corridor %d: %d ways, width=%.1fm, name=%s%n",
                ci, c.getSourceWays().size(), c.getWidthMeters(), name);
        }
        System.out.println("=== Test " + testId + " result polygons ===");
        for (int pi = 0; pi < plan.getResultCoordinates().size(); pi++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(pi);
            System.out.println("Poly " + pi + " (" + poly.size() + " pts):");
            for (int i = 0; i < poly.size(); i++) {
                EastNorth p = poly.get(i);
                System.out.println("  [" + i + "] e=" + p.east() + " n=" + p.north());
            }
        }
        System.out.println("=== Test " + testId + " roads ===");
        List<Way> roads = findHighwaysByTestId(testId);
        for (int ri = 0; ri < roads.size(); ri++) {
            Way road = roads.get(ri);
            System.out.println("Road " + ri + ":");
            for (int i = 0; i < road.getNodesCount(); i++) {
                EastNorth p = road.getNode(i).getEastNorth();
                System.out.println("  [" + i + "] e=" + p.east() + " n=" + p.north());
            }
        }
    }

    @Test
    void test97_dumpGeometry() {
        Way target = findWayByTestId("97");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan);
        dumpGeometry("97", plan);
    }

    @Test
    void test98_1_dumpGeometry() {
        Relation target = findRelationByTestId("98.1");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan);
        dumpGeometry("98.1", plan);
    }

    @Test
    void test100_dumpGeometry() {
        Way target = findWayByTestId("100");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan);
        dumpGeometry("100", plan);
    }

    @Test
    void test101_dumpGeometry() {
        Way target = findWayByTestId("101");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan);
        dumpGeometry("101", plan);
    }

    @Test
    void test96_triangleRoad_producesTwoPieces() {
        Way target = findWayByTestId("96");
        assertNotNull(target, "Test 96: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "96");
        assertAllClosed(plan, "96");
        assertAllPositiveArea(plan, "96");
        assertAllCompact(plan, "96", 5.0);
    }

    // -----------------------------------------------------------------------
    // Test 97: L-shaped road → 2 pieces (NW square + backwards-L)
    // -----------------------------------------------------------------------

    @Test
    void test97_lShapedRoad_producesTwoPieces() {
        Way target = findWayByTestId("97");
        assertNotNull(target, "Test 97: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "97");
        assertAllClosed(plan, "97");
        assertAllPositiveArea(plan, "97");
        assertAllCompact(plan, "97", 5.0);
    }

    // -----------------------------------------------------------------------
    // Test 98: Single straight road → 2 rectangles
    // -----------------------------------------------------------------------

    @Test
    void test98_singleRoad_producesTwoPieces() {
        Way target = findWayByTestId("98");
        assertNotNull(target, "Test 98: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "98");
        assertAllClosed(plan, "98");
        assertAllPositiveArea(plan, "98");
        assertAllCompact(plan, "98", 3.0);
    }

    // -----------------------------------------------------------------------
    // Test 98.1: MP + single road → 2 pieces, north has hole → MP
    // -----------------------------------------------------------------------

    @Test
    void test98_1_mpSingleRoad_producesTwoPieces() {
        Relation target = findRelationByTestId("98.1");
        assertNotNull(target, "Test 98.1: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "98.1");
        assertAllClosed(plan, "98.1");
        assertAllPositiveArea(plan, "98.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 98.1: should have inner ways");
    }

    // -----------------------------------------------------------------------
    // Test 99: Chained road (2 ways) → 2 rectangles
    // -----------------------------------------------------------------------

    @Test
    void test99_chainedRoad_producesTwoPieces() {
        Way target = findWayByTestId("99");
        assertNotNull(target, "Test 99: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "99");
        assertAllClosed(plan, "99");
        assertAllPositiveArea(plan, "99");
        assertAllCompact(plan, "99", 3.0);
    }

    // -----------------------------------------------------------------------
    // Test 99.1: MP + chained road → 2 pieces, north has hole
    // -----------------------------------------------------------------------

    @Test
    void test99_1_mpChainedRoad_producesTwoPieces() {
        Relation target = findRelationByTestId("99.1");
        assertNotNull(target, "Test 99.1: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "99.1");
        assertAllClosed(plan, "99.1");
        assertAllPositiveArea(plan, "99.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 99.1: should have inner ways");
    }

    // -----------------------------------------------------------------------
    // Test 99.2: MP + road enters/exits same side → 2 pieces, west has hole
    // -----------------------------------------------------------------------

    @Test
    void test99_2_mpSameSideRoad_producesTwoPieces() {
        Relation target = findRelationByTestId("99.2");
        assertNotNull(target, "Test 99.2: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 2, "99.2");
        assertAllClosed(plan, "99.2");
        assertAllPositiveArea(plan, "99.2");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 99.2: should have inner ways");
    }

    // -----------------------------------------------------------------------
    // Test 100: 4-way cross junction → 4 squares
    // -----------------------------------------------------------------------

    @Test
    void test100_crossJunction_producesFourPieces() {
        Way target = findWayByTestId("100");
        assertNotNull(target, "Test 100: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 4, "100");
        assertAllClosed(plan, "100");
        assertAllPositiveArea(plan, "100");
        assertAllCompact(plan, "100", 5.0);
    }

    // -----------------------------------------------------------------------
    // Test 100.1: MP + cross junction → 4 squares, NW has hole
    // -----------------------------------------------------------------------

    @Test
    void test100_1_mpCrossJunction_producesFourPieces() {
        Relation target = findRelationByTestId("100.1");
        assertNotNull(target, "Test 100.1: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 4, "100.1");
        assertAllClosed(plan, "100.1");
        assertAllPositiveArea(plan, "100.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 100.1: should have inner ways");
    }

    // -----------------------------------------------------------------------
    // Test 101: Pizza-cut (3 roads at center) → 6 pieces
    // -----------------------------------------------------------------------

    @Test
    void test101_pizzaCut_producesSixPieces() {
        Way target = findWayByTestId("101");
        assertNotNull(target, "Test 101: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 6, "101");
        assertAllClosed(plan, "101");
        assertAllPositiveArea(plan, "101");
        assertAllCompact(plan, "101", 5.0);
    }

    // -----------------------------------------------------------------------
    // Test 101.1: MP + pizza-cut → 6 pieces, south-middle triangle has hole
    // -----------------------------------------------------------------------

    @Test
    void test101_1_mpPizzaCut_producesSixPieces() {
        Relation target = findRelationByTestId("101.1");
        assertNotNull(target, "Test 101.1: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 6, "101.1");
        assertAllClosed(plan, "101.1");
        assertAllPositiveArea(plan, "101.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 101.1: should have inner ways");
    }

    /**
     * Finds all nodes tagged with _test_id matching the given testId and
     * _test_note = "No area should be created here". Asserts that none of
     * these nodes are inside any result polygon.
     */
    private void assertNoAreaAtTestNodes(BreakPlan plan, String testId) {
        List<Node> testNodes = ds.getNodes().stream()
            .filter(n -> !n.isDeleted()
                && testId.equals(n.get("_test_id"))
                && "No area should be created here".equals(n.get("_test_note")))
            .collect(Collectors.toList());
        if (testNodes.isEmpty()) return;

        for (Node testNode : testNodes) {
            EastNorth pt = testNode.getEastNorth();
            for (int pi = 0; pi < plan.getResultCoordinates().size(); pi++) {
                List<EastNorth> poly = plan.getResultCoordinates().get(pi);
                assertFalse(pointInPolygonEN(pt, poly),
                    "Test " + testId + ": test node " + testNode.getId()
                    + " should NOT be enclosed by result poly " + pi
                    + " — spurious area created outside original polygon");
            }
        }
    }

    /**
     * Finds all nodes tagged with _test_id matching the given testId and
     * _test_note = "An area should be created here". Asserts that each such
     * node is inside exactly one result polygon, and that every result polygon
     * contains at least one such node (no unattested areas).
     */
    private void assertAreaAtTestNodes(BreakPlan plan, String testId) {
        List<Node> testNodes = ds.getNodes().stream()
            .filter(n -> !n.isDeleted()
                && testId.equals(n.get("_test_id"))
                && "An area should be created here".equals(n.get("_test_note")))
            .collect(Collectors.toList());
        if (testNodes.isEmpty()) return;

        List<List<EastNorth>> polys = plan.getResultCoordinates();
        // Track which polygons have at least one attesting node
        boolean[] polyAttested = new boolean[polys.size()];

        for (Node testNode : testNodes) {
            EastNorth pt = testNode.getEastNorth();
            int containingCount = 0;
            for (int pi = 0; pi < polys.size(); pi++) {
                if (pointInPolygonEN(pt, polys.get(pi))) {
                    containingCount++;
                    polyAttested[pi] = true;
                }
            }
            assertEquals(1, containingCount,
                "Test " + testId + ": test node " + testNode.getId()
                + " should be inside exactly 1 result polygon, but was inside "
                + containingCount);
        }

        // Every result polygon must have at least one attesting node
        for (int pi = 0; pi < polys.size(); pi++) {
            assertTrue(polyAttested[pi],
                "Test " + testId + ": result poly " + pi
                + " has no attesting 'An area should be created here' node"
                + " — possible spurious or unexpected area");
        }
    }

    /**
     * Validates _test_node_count annotations: for each test node tagged with
     * _test_node_count, finds which result polygon contains it and checks that
     * the polygon has exactly that many nodes (excluding closing duplicate).
     */
    private void assertNodeCounts(BreakPlan plan, String testId) {
        List<Node> testNodes = ds.getNodes().stream()
            .filter(n -> !n.isDeleted()
                && testId.equals(n.get("_test_id"))
                && n.hasKey("_test_node_count"))
            .collect(Collectors.toList());
        if (testNodes.isEmpty()) return;

        List<List<EastNorth>> polys = plan.getResultCoordinates();
        for (Node testNode : testNodes) {
            int expectedCount = Integer.parseInt(testNode.get("_test_node_count"));
            EastNorth pt = testNode.getEastNorth();
            boolean found = false;
            for (int pi = 0; pi < polys.size(); pi++) {
                if (pointInPolygonEN(pt, polys.get(pi))) {
                    int actualCount = polys.get(pi).size() - 1; // unique nodes (exclude closing duplicate)
                    assertEquals(expectedCount, actualCount,
                        "Test " + testId + ": node " + testNode.getId()
                            + " expects " + expectedCount + " nodes in containing poly " + pi
                            + " but found " + actualCount);
                    found = true;
                    break;
                }
            }
            assertTrue(found,
                "Test " + testId + ": node " + testNode.getId()
                    + " with _test_node_count=" + expectedCount
                    + " is not inside any result polygon");
        }
    }

    /** Ray-casting point-in-polygon for EastNorth coordinates. */
    private boolean pointInPolygonEN(EastNorth pt, List<EastNorth> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            EastNorth pi = poly.get(i);
            EastNorth pj = poly.get(j);
            if ((pi.north() > pt.north()) != (pj.north() > pt.north())
                && pt.east() < (pj.east() - pi.east())
                    * (pt.north() - pi.north()) / (pj.north() - pi.north()) + pi.east()) {
                inside = !inside;
            }
        }
        return inside;
    }

    // -----------------------------------------------------------------------
    // Test 104: U-shaped polygon, road cuts through both arms → 3 pieces
    // -----------------------------------------------------------------------

    @Test
    void test104_uShapeRoad_producesThreePieces() {
        Way target = findWayByTestId("104");
        assertNotNull(target, "Test 104: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 104: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 3, "104");
        assertAllClosed(plan, "104");
        assertAllPositiveArea(plan, "104");
        assertAllCompact(plan, "104", 5.0);
        assertNoAreaAtTestNodes(plan, "104");
        assertAreaAtTestNodes(plan, "104");
    }

    @Test
    void test104_dumpGeometry() {
        Way target = findWayByTestId("104");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan);
        dumpGeometry("104", plan);
    }

    // -----------------------------------------------------------------------
    // Test 102: Plus-sign polygon, 2 highways (west re-enters twice)
    // -----------------------------------------------------------------------

    @Test
    void test102_plusSignTwoHighways_producesFourPieces() {
        Way target = findWayByTestId("102");
        assertNotNull(target, "Test 102: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 102: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 4, "102");
        assertAllClosed(plan, "102");
        assertAllPositiveArea(plan, "102");
        assertNoAreaAtTestNodes(plan, "102");
        assertAreaAtTestNodes(plan, "102");
    }

    // -----------------------------------------------------------------------
    // Test 102.1: MP plus-sign + 2 highways with inner ring
    // -----------------------------------------------------------------------

    @Test
    void test102_1_mpPlusSignTwoHighways_producesFourPieces() {
        Relation target = findRelationByTestId("102.1");
        assertNotNull(target, "Test 102.1: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 102.1: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 4, "102.1");
        assertAllClosed(plan, "102.1");
        assertAllPositiveArea(plan, "102.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 102.1: should have inner ways");
        assertNoAreaAtTestNodes(plan, "102.1");
        assertAreaAtTestNodes(plan, "102.1");
    }

    // -----------------------------------------------------------------------
    // Test 103: Plus-sign, 3 highways (2 crossing + 1 internal)
    // The internal highway connects two crossing roads but doesn't exit the polygon.
    // -----------------------------------------------------------------------

    @Test
    void test103_dumpGeometry() {
        Way target = findWayByTestId("103");
        assertNotNull(target, "Test 103: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 103: analyzer should offer to break this polygon");
        System.out.println("Test 103: got " + plan.getResultCoordinates().size() + " pieces");
        dumpGeometry("103", plan);
    }

    @Test
    void test103_plusSignThreeHighways_producesFivePieces() {
        Way target = findWayByTestId("103");
        assertNotNull(target, "Test 103: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 103: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 5, "103");
        assertAllClosed(plan, "103");
        assertAllPositiveArea(plan, "103");
        assertNoAreaAtTestNodes(plan, "103");
        assertAreaAtTestNodes(plan, "103");
    }

    // -----------------------------------------------------------------------
    // Test 105: Square with internal ring road → 3 pieces (central cutout)
    // -----------------------------------------------------------------------

    @Test
    void test105_internalRingRoad_producesThreePieces() {
        Way target = findWayByTestId("105");
        assertNotNull(target, "Test 105: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 105: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 3, "105");
        assertAllClosed(plan, "105");
        assertAllPositiveArea(plan, "105");
        assertNoAreaAtTestNodes(plan, "105");
        assertAreaAtTestNodes(plan, "105");
    }

    @Test
    void test105_dumpGeometry() {
        Way target = findWayByTestId("105");
        assertNotNull(target, "Test 105: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        if (plan != null) {
            System.out.println("Test 105: got " + plan.getResultCoordinates().size() + " pieces");
            dumpGeometry("105", plan);
        } else {
            System.out.println("Test 105: analyze returned null (no break offered)");
        }
    }

    // -----------------------------------------------------------------------
    // Test 105.1: MP square + inner ring road + NW hole → 3 pieces
    // -----------------------------------------------------------------------

    @Test
    void test105_1_mpInternalRingRoad_producesThreePieces() {
        Relation target = findRelationByTestId("105.1");
        assertNotNull(target, "Test 105.1: should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 105.1: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 3, "105.1");
        assertAllClosed(plan, "105.1");
        assertAllPositiveArea(plan, "105.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 105.1: should have inner ways");
        assertNoAreaAtTestNodes(plan, "105.1");
        assertAreaAtTestNodes(plan, "105.1");
    }

    // -----------------------------------------------------------------------
    // Test 106: Square with 2 internal ring roads → 4 pieces
    // -----------------------------------------------------------------------

    @Test
    void test106_twoInternalRingRoads_producesFourPieces() {
        Way target = findWayByTestId("106");
        assertNotNull(target, "Test 106: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 106: analyzer should offer to break this polygon");
        assertPlanPieces(plan, 4, "106");
        assertAllClosed(plan, "106");
        assertAllPositiveArea(plan, "106");
        assertNoAreaAtTestNodes(plan, "106");
        assertAreaAtTestNodes(plan, "106");
    }

    @Test
    void test106_dumpGeometry() {
        Way target = findWayByTestId("106");
        assertNotNull(target, "Test 106: should find polygon way");
        BreakPlan plan = analyzePrimitive(target);
        if (plan != null) {
            System.out.println("Test 106: got " + plan.getResultCoordinates().size() + " pieces");
            dumpGeometry("106", plan);
        } else {
            System.out.println("Test 106: analyze returned null (no break offered)");
        }
    }

    // -----------------------------------------------------------------------
    // Test 106.1 (MP): Square + NW hole + 2 internal ring roads → 4 pieces
    // -----------------------------------------------------------------------

    @Test
    void test106_mpTwoInternalRingRoads_producesFourPieces() {
        Relation target = findRelationByTestId("106.1");
        assertNotNull(target, "Test 106.1 (MP): should find relation");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 106.1 (MP): analyzer should offer to break this polygon");
        assertPlanPieces(plan, 4, "106.1");
        assertAllClosed(plan, "106.1");
        assertAllPositiveArea(plan, "106.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 106.1 (MP): should have inner ways");
        assertNoAreaAtTestNodes(plan, "106.1");
        assertAreaAtTestNodes(plan, "106.1");
    }
}
