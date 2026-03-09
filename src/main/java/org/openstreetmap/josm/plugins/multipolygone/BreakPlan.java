package org.openstreetmap.josm.plugins.multipolygone;

import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

/**
 * A plan to split a polygon (closed way or multipolygon relation) along intersecting roads.
 */
class BreakPlan {

    private final OsmPrimitive source;
    private final List<RoadCorridor> corridors;
    private final List<ResultPolygon> resultPolygons;
    private final List<Way> innerWays;
    private final String description;

    BreakPlan(OsmPrimitive source, List<RoadCorridor> corridors,
              List<ResultPolygon> resultPolygons, List<Way> innerWays,
              String description) {
        this.source = source;
        this.corridors = Collections.unmodifiableList(corridors);
        this.resultPolygons = Collections.unmodifiableList(resultPolygons);
        this.innerWays = innerWays != null
            ? Collections.unmodifiableList(innerWays) : Collections.emptyList();
        this.description = description;
    }

    OsmPrimitive getSource() {
        return source;
    }

    List<RoadCorridor> getCorridors() {
        return corridors;
    }

    List<ResultPolygon> getResultPolygons() {
        return resultPolygons;
    }

    /** Convenience: extracts just the coordinate lists from result polygons. */
    List<List<EastNorth>> getResultCoordinates() {
        return resultPolygons.stream()
            .map(rp -> rp.coordinates)
            .collect(java.util.stream.Collectors.toList());
    }

    /** Inner ways from the original multipolygon, empty for simple closed ways. */
    List<Way> getInnerWays() {
        return innerWays;
    }

    String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }

    // -----------------------------------------------------------------------

    /**
     * A road that crosses through the polygon.
     */
    static class RoadCorridor {
        private final List<Way> sourceWays;
        private final double widthMeters;

        RoadCorridor(List<Way> sourceWays, double widthMeters) {
            this.sourceWays = Collections.unmodifiableList(sourceWays);
            this.widthMeters = widthMeters;
        }

        List<Way> getSourceWays() { return sourceWays; }
        double getWidthMeters() { return widthMeters; }

        /** Returns the first source way (convenience for display). */
        Way getPrimaryWay() { return sourceWays.isEmpty() ? null : sourceWays.get(0); }
    }

    /**
     * One result polygon from the split operation.
     * Contains coordinates and optional references to reused original nodes.
     */
    static class ResultPolygon {
        /** Closed ring of coordinates (first == last). */
        final List<EastNorth> coordinates;
        /**
         * Parallel to {@link #coordinates}. Non-null entries are existing nodes
         * from the original polygon that should be reused instead of creating new ones.
         */
        final List<Node> reusedNodes;

        ResultPolygon(List<EastNorth> coordinates, List<Node> reusedNodes) {
            this.coordinates = Collections.unmodifiableList(coordinates);
            this.reusedNodes = Collections.unmodifiableList(reusedNodes);
        }
    }
}
