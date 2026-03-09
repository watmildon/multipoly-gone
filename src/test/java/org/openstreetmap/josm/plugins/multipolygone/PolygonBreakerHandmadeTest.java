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
 * Tests for the handmade break test cases in testdata-break-handmade.osm.
 * Each test case is identified by a {@code _test_id} tag on the polygon (Way or Relation)
 * and the intersecting road ways.
 */
class PolygonBreakerHandmadeTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet ds;

    @BeforeAll
    static void loadData() {
        ds = JosmTestSetup.loadDataSet("testdata-break-handmade.osm");
        assertNotNull(ds, "Should load testdata-break-handmade.osm");
    }

    // -- Helpers --

    private Way findWayByTestId(String testId) {
        return ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.isClosed() && testId.equals(w.get("_test_id")))
            .findFirst().orElse(null);
    }

    private Relation findRelationByTestId(String testId) {
        return ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && testId.equals(r.get("_test_id")))
            .findFirst().orElse(null);
    }

    private BreakPlan analyzePrimitive(OsmPrimitive prim) {
        assertNotNull(prim);
        return PolygonBreaker.analyze(prim, ds);
    }

    private void assertPlanPieces(BreakPlan plan, int expectedPieces, String testId) {
        assertNotNull(plan, "Test " + testId + ": should produce a break plan");
        assertEquals(expectedPieces, plan.getResultPolygons().size(),
            "Test " + testId + ": expected " + expectedPieces + " result pieces");
    }

    private void assertAllClosed(BreakPlan plan, String testId) {
        for (int i = 0; i < plan.getResultPolygons().size(); i++) {
            List<EastNorth> poly = plan.getResultPolygons().get(i);
            assertTrue(poly.size() >= 4,
                "Test " + testId + " poly " + i + ": needs at least 4 points");
            EastNorth first = poly.get(0);
            EastNorth last = poly.get(poly.size() - 1);
            assertTrue(GeometryUtils.isNear(first, last, 1e-6),
                "Test " + testId + " poly " + i + ": should be closed");
        }
    }

    private void assertAllPositiveArea(BreakPlan plan, String testId) {
        for (int i = 0; i < plan.getResultPolygons().size(); i++) {
            List<EastNorth> poly = plan.getResultPolygons().get(i);
            double area = computeSignedAreaEN(poly);
            assertTrue(Math.abs(area) > 1e-10,
                "Test " + testId + " poly " + i + ": should have non-trivial area, got " + area);
        }
    }

    private void assertAllCompact(BreakPlan plan, String testId, double maxCompactness) {
        for (int i = 0; i < plan.getResultPolygons().size(); i++) {
            List<EastNorth> poly = plan.getResultPolygons().get(i);
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
     * Asserts that no two result polygons overlap.
     * Tests by checking that no edge of one polygon properly crosses an edge of another.
     * Shared boundary segments (collinear or touching at endpoints) are allowed,
     * but proper crossings indicate overlap.
     */
    private void assertNoOverlap(BreakPlan plan, String testId) {
        List<List<EastNorth>> polys = plan.getResultPolygons();
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
            assertEquals(expectedPieces, reversedPlan.getResultPolygons().size(),
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
        List<List<EastNorth>> polys = plan.getResultPolygons();
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
    void allClosedWayTests_noCrossingWithRoads() {
        for (String testId : new String[]{"96", "97", "98", "99", "100", "101"}) {
            Way target = findWayByTestId(testId);
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": should produce a plan");
            assertNoCrossingWithRoads(plan, testId);
        }
    }

    @Test
    void allMPTests_noCrossingWithRoads() {
        for (String testId : new String[]{"98.1", "99.1", "99.2", "100.1", "101.1"}) {
            Relation target = findRelationByTestId(testId);
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": should produce a plan");
            assertNoCrossingWithRoads(plan, testId);
        }
    }

    @Test
    void allClosedWayTests_noOverlap() {
        for (String testId : new String[]{"96", "97", "98", "99", "100", "101"}) {
            Way target = findWayByTestId(testId);
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": should produce a plan");
            assertNoOverlap(plan, testId);
        }
    }


    @Test
    void allMPTests_noOverlap() {
        for (String testId : new String[]{"98.1", "99.1", "99.2", "100.1", "101.1"}) {
            Relation target = findRelationByTestId(testId);
            BreakPlan plan = analyzePrimitive(target);
            assertNotNull(plan, "Test " + testId + ": should produce a plan");
            assertNoOverlap(plan, testId);
        }
    }

    @Test
    void closedWayTests_directionInvariant() {
        // Map test ID → expected piece count
        Object[][] cases = {
            {"96", 2}, {"97", 2}, {"98", 2}, {"99", 2}, {"100", 4}, {"101", 6}
        };
        for (Object[] c : cases) {
            String testId = (String) c[0];
            int expected = (Integer) c[1];
            Way target = findWayByTestId(testId);
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
            assertDirectionInvariant(target, expected, testId);
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
            for (int pi = 0; pi < c.getCrossingPairs().size(); pi++) {
                BreakPlan.CrossingPair cp = c.getCrossingPairs().get(pi);
                System.out.println("Corridor " + ci + " pair " + pi);
                System.out.println("  Left offsets:");
                for (int i = 0; i < cp.leftOffsets.size(); i++) {
                    EastNorth p = cp.leftOffsets.get(i);
                    System.out.println("    [" + i + "] e=" + p.east() + " n=" + p.north());
                }
                System.out.println("  Right offsets:");
                for (int i = 0; i < cp.rightOffsets.size(); i++) {
                    EastNorth p = cp.rightOffsets.get(i);
                    System.out.println("    [" + i + "] e=" + p.east() + " n=" + p.north());
                }
            }
        }
        System.out.println("=== Test 96 result polygons ===");
        for (int pi = 0; pi < plan.getResultPolygons().size(); pi++) {
            List<EastNorth> poly = plan.getResultPolygons().get(pi);
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
            for (int pi = 0; pi < c.getCrossingPairs().size(); pi++) {
                BreakPlan.CrossingPair cp = c.getCrossingPairs().get(pi);
                System.out.println("Corridor " + ci + " pair " + pi);
                System.out.println("  Left offsets:");
                for (int i = 0; i < cp.leftOffsets.size(); i++) {
                    EastNorth p = cp.leftOffsets.get(i);
                    System.out.println("    [" + i + "] e=" + p.east() + " n=" + p.north());
                }
                System.out.println("  Right offsets:");
                for (int i = 0; i < cp.rightOffsets.size(); i++) {
                    EastNorth p = cp.rightOffsets.get(i);
                    System.out.println("    [" + i + "] e=" + p.east() + " n=" + p.north());
                }
            }
        }
        System.out.println("=== Test " + testId + " result polygons ===");
        for (int pi = 0; pi < plan.getResultPolygons().size(); pi++) {
            List<EastNorth> poly = plan.getResultPolygons().get(pi);
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
        BreakPlan plan = analyzePrimitive(target);
        assertPlanPieces(plan, 6, "101.1");
        assertAllClosed(plan, "101.1");
        assertAllPositiveArea(plan, "101.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 101.1: should have inner ways");
    }

    // -----------------------------------------------------------------------
    // Test 102: Plus-sign polygon, 2 highways (west re-enters twice)
    // -----------------------------------------------------------------------

    @Test
    void test102_plusSignTwoHighways_producesMultiplePieces() {
        Way target = findWayByTestId("102");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 102: should produce a break plan");
        assertTrue(plan.getResultPolygons().size() >= 3,
            "Test 102: plus sign with re-entering highway should produce >= 3 pieces, got "
            + plan.getResultPolygons().size());
        assertAllClosed(plan, "102");
        assertAllPositiveArea(plan, "102");
    }

    // -----------------------------------------------------------------------
    // Test 102.1: MP plus-sign + 2 highways with inner ring
    // -----------------------------------------------------------------------

    @Test
    void test102_1_mpPlusSignTwoHighways() {
        Relation target = findRelationByTestId("102.1");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 102.1: should produce a break plan");
        assertTrue(plan.getResultPolygons().size() >= 3,
            "Test 102.1: should produce >= 3 pieces, got " + plan.getResultPolygons().size());
        assertAllClosed(plan, "102.1");
        assertAllPositiveArea(plan, "102.1");
        assertFalse(plan.getInnerWays().isEmpty(), "Test 102.1: should have inner ways");
    }

    // -----------------------------------------------------------------------
    // Test 103: Plus-sign, 3 highways (2 crossing + 1 internal)
    // The internal highway connects two crossing roads but doesn't exit the polygon.
    // -----------------------------------------------------------------------

    @Test
    void test103_plusSignThreeHighways_producesMultiplePieces() {
        Way target = findWayByTestId("103");
        BreakPlan plan = analyzePrimitive(target);
        assertNotNull(plan, "Test 103: should produce a break plan");
        // Internal road should still contribute to splitting via graph connectivity
        assertTrue(plan.getResultPolygons().size() >= 3,
            "Test 103: plus sign with internal connecting road should produce >= 3 pieces, got "
            + plan.getResultPolygons().size());
        assertAllClosed(plan, "103");
        assertAllPositiveArea(plan, "103");
    }
}
