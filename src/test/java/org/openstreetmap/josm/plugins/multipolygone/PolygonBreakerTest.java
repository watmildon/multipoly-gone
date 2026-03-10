package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

class PolygonBreakerTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeEach
    void clearUndoStack() {
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }
    }

    private Way findBreakTarget(DataSet ds, String testId) {
        return ds.getWays().stream()
            .filter(w -> !w.isDeleted() && testId.equals(w.get("_test_break")))
            .findFirst()
            .orElse(null);
    }

    @Test
    void forest1_twoRoadsCrossing_producesThreePieces() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break.osm");
        Way forest = findBreakTarget(ds, "forest1");
        assertNotNull(forest, "Should find forest1 test case");
        assertTrue(forest.isClosed(), "Forest should be a closed way");

        BreakPlan plan = PolygonBreaker.analyze(forest, ds);
        assertNotNull(plan, "Should produce a break plan");
        assertEquals(2, plan.getCorridors().size(), "Should find 2 intersecting roads");
        assertEquals(3, plan.getResultCoordinates().size(),
            "Two roads should split the polygon into 3 pieces");
    }

    @Test
    void forest1_resultPolygonsAreClosed() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break.osm");
        Way forest = findBreakTarget(ds, "forest1");
        BreakPlan plan = PolygonBreaker.analyze(forest, ds);
        assertNotNull(plan);

        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            assertTrue(poly.size() >= 4,
                "Sub-polygon " + i + " should have at least 4 points (triangle + closure)");
            EastNorth first = poly.get(0);
            EastNorth last = poly.get(poly.size() - 1);
            assertTrue(GeometryUtils.isNear(first, last, 1e-6),
                "Sub-polygon " + i + " should be closed (first == last)");
        }
    }

    @Test
    void forest1_resultPolygonsHavePositiveArea() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break.osm");
        Way forest = findBreakTarget(ds, "forest1");
        BreakPlan plan = PolygonBreaker.analyze(forest, ds);
        assertNotNull(plan);

        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            double area = computeSignedAreaEN(poly);
            assertTrue(Math.abs(area) > 1e-6,
                "Sub-polygon " + i + " should have non-trivial area, got " + area);
        }
    }

    @Test
    void forest1_roadsNotEnclosedByResultPolygons() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break.osm");
        Way forest = findBreakTarget(ds, "forest1");
        BreakPlan plan = PolygonBreaker.analyze(forest, ds);
        assertNotNull(plan);

        // Get road nodes
        List<Way> roads = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.hasKey("highway"))
            .collect(Collectors.toList());

        for (Way road : roads) {
            // Check midpoint of each road segment
            for (int i = 0; i < road.getNodesCount() - 1; i++) {
                EastNorth n1 = road.getNode(i).getEastNorth();
                EastNorth n2 = road.getNode(i + 1).getEastNorth();
                EastNorth mid = new EastNorth(
                    (n1.east() + n2.east()) / 2,
                    (n1.north() + n2.north()) / 2);

                for (int pi = 0; pi < plan.getResultCoordinates().size(); pi++) {
                    List<EastNorth> poly = plan.getResultCoordinates().get(pi);
                    assertFalse(GeometryUtils.pointInsideOrOnPolygon(mid, poly),
                        "Road midpoint should NOT be inside sub-polygon " + pi
                        + "; road centerline must be excluded by offset");
                }
            }
        }
    }

    @Test
    void forest1_executeProducesValidWays() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break.osm");
        Way forest = findBreakTarget(ds, "forest1");
        BreakPlan plan = PolygonBreaker.analyze(forest, ds);
        assertNotNull(plan);

        PolygonBreakFixer.execute(plan);

        // Original forest should be deleted
        assertTrue(forest.isDeleted(), "Original forest way should be deleted");

        // Should have 3 new wood ways
        List<Way> woodWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "wood".equals(w.get("natural")))
            .collect(Collectors.toList());
        assertEquals(3, woodWays.size(),
            "Should produce 3 new natural=wood ways");

        for (Way w : woodWays) {
            assertTrue(w.isClosed(), "Each new way should be closed");
            assertTrue(w.getNodesCount() >= 4, "Each new way should have enough nodes");
        }

        // Undo should restore original
        UndoRedoHandler.getInstance().undo();
        assertFalse(forest.isDeleted(), "Undo should restore original forest");
    }

    // -----------------------------------------------------------------------
    // Multipolygon tests (forest with inner ring)
    // -----------------------------------------------------------------------

    private Relation findBreakRelation(DataSet ds, String testId) {
        return ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && testId.equals(r.get("_test_break")))
            .findFirst()
            .orElse(null);
    }

    @Test
    void forestMP1_producesThreePieces() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-mp.osm");
        Relation rel = findBreakRelation(ds, "forestMP1");
        assertNotNull(rel, "Should find forestMP1 relation");

        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan, "Should produce a break plan for MP");
        assertEquals(2, plan.getCorridors().size(), "Should find 2 intersecting roads");
        assertEquals(3, plan.getResultCoordinates().size(),
            "Two roads should split the polygon into 3 pieces");
        assertFalse(plan.getInnerWays().isEmpty(), "Plan should carry inner ways");
    }

    @Test
    void forestMP1_executeProducesRelationWithInner() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-mp.osm");
        Relation rel = findBreakRelation(ds, "forestMP1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        // Remember the inner way
        Way innerWay = plan.getInnerWays().get(0);
        assertNotNull(innerWay);

        PolygonBreakFixer.execute(plan);

        // Original relation should be deleted
        assertTrue(rel.isDeleted(), "Original relation should be deleted");

        // The inner ring sits in the middle piece. We should get:
        // - 2 simple natural=wood ways (no inners)
        // - 1 multipolygon relation with 1 outer + 1 inner
        List<Way> woodWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "wood".equals(w.get("natural")))
            .collect(Collectors.toList());
        List<Relation> woodRels = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "multipolygon".equals(r.get("type"))
                && "wood".equals(r.get("natural")))
            .collect(Collectors.toList());

        assertEquals(2, woodWays.size(),
            "Should produce 2 simple natural=wood ways (pieces without inners)");
        assertEquals(1, woodRels.size(),
            "Should produce 1 multipolygon relation (piece with inner)");

        // The relation should have 1 outer + 1 inner
        Relation newRel = woodRels.get(0);
        long outerCount = newRel.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole())).count();
        long innerCount = newRel.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole())).count();
        assertEquals(1, outerCount, "New relation should have 1 outer");
        assertEquals(1, innerCount, "New relation should have 1 inner");

        // The inner member should be the original inner way (preserved, not recreated)
        Way relInner = newRel.getMembers().stream()
            .filter(m -> "inner".equals(m.getRole()))
            .map(m -> (Way) m.getMember())
            .findFirst().orElse(null);
        assertSame(innerWay, relInner,
            "Inner way should be the original inner (not a copy)");

        // All outer ways (standalone + in relation) should be closed
        Way relOuter = newRel.getMembers().stream()
            .filter(m -> "outer".equals(m.getRole()))
            .map(m -> (Way) m.getMember())
            .findFirst().orElse(null);
        assertNotNull(relOuter);
        assertTrue(relOuter.isClosed(), "Relation outer should be closed");
        for (Way w : woodWays) {
            assertTrue(w.isClosed(), "Standalone wood way should be closed");
        }

        // Undo should restore original
        UndoRedoHandler.getInstance().undo();
        assertFalse(rel.isDeleted(), "Undo should restore original relation");
    }

    // -----------------------------------------------------------------------
    // Road network tests (chained roads + T-junction)
    // -----------------------------------------------------------------------

    @Test
    void forestRN1_producesFourPieces() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        assertNotNull(rel, "Should find forestRN1 relation");

        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan, "Should produce a break plan");
        assertEquals(3, plan.getCorridors().size(),
            "Should find 3 corridors (connected road ways chained together)");
        assertEquals(4, plan.getResultCoordinates().size(),
            "Road network should split the polygon into 4 pieces");
    }

    @Test
    void forestRN1_resultPolygonsAreClosed() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            assertTrue(poly.size() >= 4,
                "Sub-polygon " + i + " should have at least 4 points");
            EastNorth first = poly.get(0);
            EastNorth last = poly.get(poly.size() - 1);
            assertTrue(GeometryUtils.isNear(first, last, 1e-6),
                "Sub-polygon " + i + " should be closed");
        }
    }

    @Test
    void forestRN1_resultPolygonsHavePositiveArea() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            double area = computeSignedAreaEN(poly);
            assertTrue(Math.abs(area) > 1e-6,
                "Sub-polygon " + i + " should have non-trivial area, got " + area);
        }
    }

    @Test
    void forestRN1_innerRingPreserved() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        assertFalse(plan.getInnerWays().isEmpty(), "Plan should carry inner ways");
    }

    @Test
    void forestRN1_executeProducesFourPieces() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        PolygonBreakFixer.execute(plan);

        // Original relation should be deleted
        assertTrue(rel.isDeleted(), "Original relation should be deleted");

        // Count all natural=wood primitives
        List<Way> woodWays = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && "wood".equals(w.get("natural")))
            .collect(Collectors.toList());
        List<Relation> woodRels = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "multipolygon".equals(r.get("type"))
                && "wood".equals(r.get("natural")))
            .collect(Collectors.toList());

        int totalPieces = woodWays.size() + woodRels.size();
        assertEquals(4, totalPieces,
            "Should produce 4 total pieces (ways + relations)");

        // All ways should be closed
        for (Way w : woodWays) {
            assertTrue(w.isClosed(), "Each wood way should be closed");
        }

        // Undo should restore original
        UndoRedoHandler.getInstance().undo();
        assertFalse(rel.isDeleted(), "Undo should restore original relation");
    }

    @Test
    void forestRN1_resultPolygonsAreCompact() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            double area = Math.abs(computeSignedAreaEN(poly));
            double perimeter = 0;
            for (int pi = 0; pi < poly.size() - 1; pi++) {
                EastNorth a = poly.get(pi);
                EastNorth b = poly.get(pi + 1);
                perimeter += Math.sqrt(
                    Math.pow(b.east() - a.east(), 2)
                    + Math.pow(b.north() - a.north(), 2));
            }
            double compactness = (perimeter * perimeter) / (4 * Math.PI * area);
            assertTrue(compactness < 5.0,
                "Sub-polygon " + i + " should be reasonably compact (no spikes), "
                + "got compactness=" + compactness + " (1.0=circle, higher=spikier)");
        }
    }

    @org.junit.jupiter.api.Disabled("Known issue: 'North Highway Offshoot' offset needs investigation")
    @Test
    void forestRN1_roadsNotEnclosedByResultPolygons() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        List<Way> roads = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.hasKey("highway"))
            .collect(Collectors.toList());

        for (Way road : roads) {
            for (int i = 0; i < road.getNodesCount() - 1; i++) {
                EastNorth n1 = road.getNode(i).getEastNorth();
                EastNorth n2 = road.getNode(i + 1).getEastNorth();
                EastNorth mid = new EastNorth(
                    (n1.east() + n2.east()) / 2,
                    (n1.north() + n2.north()) / 2);

                for (int pi = 0; pi < plan.getResultCoordinates().size(); pi++) {
                    List<EastNorth> poly = plan.getResultCoordinates().get(pi);
                    String name = road.get("name");
                    assertFalse(GeometryUtils.pointInsideOrOnPolygon(mid, poly),
                        "Road '" + (name != null ? name : "(unnamed)")
                        + "' seg " + i + " midpoint should NOT be inside sub-polygon "
                        + pi + "; road centerline must be excluded by offset");
                }
            }
        }
    }

    @Test
    void forestRN1_afterBreak_analyzerDoesNotCrash() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");
        Relation rel = findBreakRelation(ds, "forestRN1");
        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        assertNotNull(plan);

        PolygonBreakFixer.execute(plan);

        // The analyzer should gracefully handle newly created relations
        assertDoesNotThrow(() -> MultipolygonAnalyzer.analyzeAll(ds, null),
            "Analyzer should not crash on relations created by PolygonBreakFixer");
    }

    // -----------------------------------------------------------------------
    // Width accuracy tests (issue #19)
    // -----------------------------------------------------------------------

    /**
     * Verifies that a road with width=40 produces a corridor gap that is
     * approximately 40m wide, not 20m (the reported bug in issue #19).
     *
     * The test polygon is at lat ~56.66 (Latvia), matching the bug report.
     * At this latitude, Mercator distortion is significant: cos(56.66°) ≈ 0.55,
     * so if the code doesn't account for projection distortion, the gap will
     * be roughly half the expected width.
     */
    @Test
    void width40Road_corridorGapShouldBe40Meters() {
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-width.osm");
        Way polygon = findBreakTarget(ds, "width40");
        assertNotNull(polygon, "Should find width40 test case");

        BreakPlan plan = PolygonBreaker.analyze(polygon, ds);
        assertNotNull(plan, "Should produce a break plan");
        assertEquals(1, plan.getCorridors().size(), "Should find 1 corridor");
        assertEquals(40.0, plan.getCorridors().get(0).getWidthMeters(), 1e-6,
            "Corridor should report 40m width");
        assertEquals(2, plan.getResultCoordinates().size(),
            "One road should split the polygon into 2 pieces");

        // The road runs horizontally at lat 56.660.
        // The polygon extends from lat 56.658 to 56.662.
        // With a 40m gap, each piece should have its edge ~20m from the road centerline.
        //
        // Measure the gap: find the closest points between the two result polygons
        // along the latitude axis (perpendicular to the E-W road).

        List<EastNorth> piece1 = plan.getResultCoordinates().get(0);
        List<EastNorth> piece2 = plan.getResultCoordinates().get(1);

        // Find the northernmost point of the southern piece and
        // the southernmost point of the northern piece.
        // In EPSG:4326, north = lat = EastNorth.north().
        // Road centerline is at lat 56.660.

        // Separate pieces into north and south of the road
        List<EastNorth> southPiece, northPiece;
        double centroid1N = piece1.stream().mapToDouble(EastNorth::north).average().orElse(0);
        double centroid2N = piece2.stream().mapToDouble(EastNorth::north).average().orElse(0);
        if (centroid1N < centroid2N) {
            southPiece = piece1;
            northPiece = piece2;
        } else {
            southPiece = piece2;
            northPiece = piece1;
        }

        // Find the edge of each piece closest to the road centerline.
        // The south piece's max latitude (closest edge to road):
        double southEdge = southPiece.stream()
            .mapToDouble(EastNorth::north).max().orElse(0);
        // The north piece's min latitude (closest edge to road):
        double northEdge = northPiece.stream()
            .mapToDouble(EastNorth::north).min().orElse(0);

        // Gap in projection units (degrees for EPSG:4326)
        double gapDegrees = northEdge - southEdge;
        assertTrue(gapDegrees > 0, "North edge should be north of south edge");

        // Convert gap to meters.
        // At EPSG:4326: 1 degree latitude ≈ 111,320 meters
        double metersPerDegree = org.openstreetmap.josm.data.projection.ProjectionRegistry
            .getProjection().getMetersPerUnit();
        double gapMeters = gapDegrees * metersPerDegree;

        // The gap should be approximately 40m (within 20% tolerance for
        // buffer geometry approximation and curved endcaps)
        String msg = String.format(
            "Gap should be ~40m, got %.1fm (%.6f degrees). "
            + "If ~20m, the width/2 bug is present (issue #19).",
            gapMeters, gapDegrees);
        assertTrue(gapMeters > 32.0, msg);  // At least 80% of 40m
        assertTrue(gapMeters < 48.0, msg);  // At most 120% of 40m
    }

    /**
     * Reproduces issue #19: under EPSG:3857 (Web Mercator, JOSM's default),
     * metersToProjectionUnits returns meters/1.0 = meters, but Mercator
     * projection units are only equal to meters at the equator. At lat 56.66°,
     * 1 Mercator unit in the E-W direction ≈ cos(56.66°) ≈ 0.55 real meters.
     *
     * For a N-S gap (latitude direction), Mercator stretches coordinates by
     * 1/cos(lat), so the gap in projection units needs to be larger than the
     * real-world meters to produce the correct physical distance.
     *
     * This test switches to EPSG:3857, runs the same analysis, and checks
     * the gap. It should fail until the bug is fixed.
     */
    @Test
    void width40Road_corridorGapShouldBe40Meters_mercator() {
        // Switch to EPSG:3857 (Web Mercator) — JOSM's default projection
        org.openstreetmap.josm.data.projection.Projection oldProj =
            ProjectionRegistry.getProjection();
        try {
            ProjectionRegistry.setProjection(
                org.openstreetmap.josm.data.projection.Projections
                    .getProjectionByCode("EPSG:3857"));

            DataSet ds = JosmTestSetup.loadDataSet("testdata-break-width.osm");
            Way polygon = findBreakTarget(ds, "width40");
            assertNotNull(polygon, "Should find width40 test case");

            BreakPlan plan = PolygonBreaker.analyze(polygon, ds);
            assertNotNull(plan, "Should produce a break plan");
            assertEquals(2, plan.getResultCoordinates().size(),
                "One road should split the polygon into 2 pieces");

            List<EastNorth> piece1 = plan.getResultCoordinates().get(0);
            List<EastNorth> piece2 = plan.getResultCoordinates().get(1);

            // Separate pieces into north and south
            List<EastNorth> southPiece, northPiece;
            double centroid1N = piece1.stream().mapToDouble(EastNorth::north).average().orElse(0);
            double centroid2N = piece2.stream().mapToDouble(EastNorth::north).average().orElse(0);
            if (centroid1N < centroid2N) {
                southPiece = piece1;
                northPiece = piece2;
            } else {
                southPiece = piece2;
                northPiece = piece1;
            }

            double southEdge = southPiece.stream()
                .mapToDouble(EastNorth::north).max().orElse(0);
            double northEdge = northPiece.stream()
                .mapToDouble(EastNorth::north).min().orElse(0);

            // In EPSG:3857, EastNorth is in meters (at equator scale).
            // To get real-world distance from Mercator northing difference,
            // we need: realMeters = mercatorDiff * cos(lat)
            // where lat is the latitude at the measurement point.
            double gapMercator = northEdge - southEdge;
            assertTrue(gapMercator > 0, "North edge should be north of south edge");

            // Convert Mercator northing gap to real-world meters.
            // The derivative of Mercator northing w.r.t. latitude is R/cos(lat),
            // so: delta_lat (radians) = delta_y_mercator * cos(lat) / R
            // and: delta_meters = delta_lat * R = delta_y_mercator * cos(lat)
            double latRadians = Math.toRadians(56.660);
            double gapMeters = gapMercator * Math.cos(latRadians);

            String msg = String.format(
                "Gap should be ~40m, got %.1fm (%.1f Mercator units). "
                + "If ~20m, the Mercator distortion bug is present (issue #19).",
                gapMeters, gapMercator);
            assertTrue(gapMeters > 32.0, msg);  // At least 80% of 40m
            assertTrue(gapMeters < 48.0, msg);  // At most 120% of 40m
        } finally {
            // Restore original projection
            ProjectionRegistry.setProjection(oldProj);
        }
    }

    // -----------------------------------------------------------------------
    // parseWidthTag unit tests
    // -----------------------------------------------------------------------

    @Test
    void parseWidthTag_plainMeters() {
        assertEquals(5.0, PolygonBreaker.parseWidthTag("5"), 1e-6);
        assertEquals(3.5, PolygonBreaker.parseWidthTag("3.5"), 1e-6);
        assertEquals(12.0, PolygonBreaker.parseWidthTag("12"), 1e-6);
    }

    @Test
    void parseWidthTag_withMSuffix() {
        assertEquals(5.0, PolygonBreaker.parseWidthTag("5 m"), 1e-6);
        assertEquals(3.5, PolygonBreaker.parseWidthTag("3.5m"), 1e-6);
    }

    @Test
    void parseWidthTag_feet() {
        assertEquals(10 * 0.3048, PolygonBreaker.parseWidthTag("10 ft"), 1e-6);
        assertEquals(20 * 0.3048, PolygonBreaker.parseWidthTag("20ft"), 1e-6);
    }

    @Test
    void parseWidthTag_feetAndInches() {
        // 12'6" = 12.5 feet
        assertEquals(12.5 * 0.3048, PolygonBreaker.parseWidthTag("12'6\""), 1e-6);
        // 10' (no inches)
        assertEquals(10 * 0.3048, PolygonBreaker.parseWidthTag("10'"), 1e-6);
    }

    @Test
    void parseWidthTag_nullAndInvalid() {
        assertEquals(0, PolygonBreaker.parseWidthTag(null), 1e-6);
        assertEquals(0, PolygonBreaker.parseWidthTag(""), 1e-6);
        assertEquals(0, PolygonBreaker.parseWidthTag("abc"), 1e-6);
    }

    // -----------------------------------------------------------------------
    // estimateWidthFromLanes unit tests
    // -----------------------------------------------------------------------

    private Way wayWithTags(String... keyValues) {
        Way w = new Way();
        for (int i = 0; i < keyValues.length; i += 2) {
            w.put(keyValues[i], keyValues[i + 1]);
        }
        return w;
    }

    @Test
    void estimateWidthFromLanes_simpleLanesTag() {
        assertEquals(2 * 3.5, PolygonBreaker.estimateWidthFromLanes(
            wayWithTags("lanes", "2")), 1e-6);
        assertEquals(4 * 3.5, PolygonBreaker.estimateWidthFromLanes(
            wayWithTags("lanes", "4")), 1e-6);
    }

    @Test
    void estimateWidthFromLanes_directionalTags() {
        // lanes:forward=2 + lanes:backward=1 = 3 lanes
        assertEquals(3 * 3.5, PolygonBreaker.estimateWidthFromLanes(
            wayWithTags("lanes:forward", "2", "lanes:backward", "1")), 1e-6);
    }

    @Test
    void estimateWidthFromLanes_withBothWays() {
        // lanes:forward=1 + lanes:backward=1 + lanes:both_ways=1 = 3 lanes
        assertEquals(3 * 3.5, PolygonBreaker.estimateWidthFromLanes(
            wayWithTags("lanes:forward", "1", "lanes:backward", "1",
                        "lanes:both_ways", "1")), 1e-6);
    }

    @Test
    void estimateWidthFromLanes_totalLanesTakesPrecedence() {
        // "lanes" tag present — directional tags are ignored
        Way w = wayWithTags("lanes", "2", "lanes:forward", "3", "lanes:backward", "3");
        assertEquals(2 * 3.5, PolygonBreaker.estimateWidthFromLanes(w), 1e-6);
    }

    @Test
    void estimateWidthFromLanes_noLaneTags() {
        assertEquals(0, PolygonBreaker.estimateWidthFromLanes(
            wayWithTags("highway", "residential")), 1e-6);
    }

    @Test
    void estimateWidthFromLanes_invalidValue() {
        assertEquals(0, PolygonBreaker.estimateWidthFromLanes(
            wayWithTags("lanes", "abc")), 1e-6);
    }

    /**
     * Computes signed area from EastNorth coordinates using shoelace formula.
     */
    private static double computeSignedAreaEN(List<EastNorth> poly) {
        double area = 0;
        int n = poly.size() - 1; // exclude closure point
        for (int i = 0; i < n; i++) {
            EastNorth curr = poly.get(i);
            EastNorth next = poly.get((i + 1) % n);
            area += curr.east() * next.north() - next.east() * curr.north();
        }
        return area / 2.0;
    }
}
