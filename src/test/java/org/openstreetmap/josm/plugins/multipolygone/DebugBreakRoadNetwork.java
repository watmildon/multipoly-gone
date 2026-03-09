package org.openstreetmap.josm.plugins.multipolygone;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

class DebugBreakRoadNetwork {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void analyzeRoadNetwork() {
        System.out.println("=== ROAD NETWORK ===");
        DataSet ds = JosmTestSetup.loadDataSet("testdata-break-roadnetwork.osm");

        Relation rel = ds.getRelations().stream()
            .filter(r -> !r.isDeleted() && "forestRN1".equals(r.get("_test_break")))
            .findFirst().orElse(null);
        System.out.println("Relation: " + rel);

        List<Way> roads = ds.getWays().stream()
            .filter(w -> !w.isDeleted() && w.hasKey("highway"))
            .collect(Collectors.toList());
        for (Way road : roads) {
            String name = road.get("name");
            System.out.printf("Road: %s nodes=%d name=%s%n", road, road.getNodesCount(),
                name != null ? name : "(unnamed)");
        }

        BreakPlan plan = PolygonBreaker.analyze(rel, ds);
        if (plan == null) {
            System.out.println("RESULT: null plan");
            return;
        }

        System.out.println("\nCorridors: " + plan.getCorridors().size());
        for (int c = 0; c < plan.getCorridors().size(); c++) {
            BreakPlan.RoadCorridor cor = plan.getCorridors().get(c);
            System.out.printf("  Corridor %d: %d ways, width=%.1fm%n",
                c, cor.getSourceWays().size(), cor.getWidthMeters());
            for (Way w : cor.getSourceWays()) {
                String name = w.get("name");
                System.out.printf("    Way: %s name=%s%n", w,
                    name != null ? name : "(unnamed)");
            }
        }

        System.out.println("\nResult polygons: " + plan.getResultCoordinates().size());
        for (int i = 0; i < plan.getResultCoordinates().size(); i++) {
            List<EastNorth> poly = plan.getResultCoordinates().get(i);
            double area = computeSignedArea(poly);
            EastNorth first = poly.get(0);
            EastNorth last = poly.get(poly.size() - 1);
            boolean closed = GeometryUtils.isNear(first, last, 1e-6);
            System.out.printf("  Polygon %d: %d pts, area=%.10f, closed=%s%n",
                i, poly.size(), area, closed);
            for (int pi = 0; pi < poly.size(); pi++) {
                EastNorth pt = poly.get(pi);
                System.out.printf("    [%d] (%.8f, %.8f)%n", pi, pt.east(), pt.north());
            }

            // Compactness check: ratio of area to convex hull area, or perimeter²/area
            double perimeter = 0;
            for (int pi = 0; pi < poly.size() - 1; pi++) {
                EastNorth a = poly.get(pi);
                EastNorth b = poly.get(pi + 1);
                perimeter += Math.sqrt(
                    Math.pow(b.east() - a.east(), 2) + Math.pow(b.north() - a.north(), 2));
            }
            double compactness = (perimeter * perimeter) / (4 * Math.PI * Math.abs(area));
            System.out.printf("    perimeter=%.8f compactness=%.2f (1.0=circle, higher=spikier)%n",
                perimeter, compactness);

            // Check road midpoints
            for (Way road : roads) {
                for (int ri = 0; ri < road.getNodesCount() - 1; ri++) {
                    EastNorth n1 = road.getNode(ri).getEastNorth();
                    EastNorth n2 = road.getNode(ri + 1).getEastNorth();
                    EastNorth mid = new EastNorth(
                        (n1.east() + n2.east()) / 2,
                        (n1.north() + n2.north()) / 2);
                    if (GeometryUtils.pointInsideOrOnPolygon(mid, poly)) {
                        String name = road.get("name");
                        System.out.printf("    !! Road '%s' seg %d mid INSIDE this polygon%n",
                            name != null ? name : "(unnamed)", ri);
                    }
                }
            }
        }
    }

    private static double computeSignedArea(List<EastNorth> poly) {
        double area = 0;
        int n = poly.size() - 1;
        for (int i = 0; i < n; i++) {
            EastNorth curr = poly.get(i);
            EastNorth next = poly.get((i + 1) % n);
            area += curr.east() * next.north() - next.east() * curr.north();
        }
        return area / 2.0;
    }
}
