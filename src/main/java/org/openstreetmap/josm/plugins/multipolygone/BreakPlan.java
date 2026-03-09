package org.openstreetmap.josm.plugins.multipolygone;

import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

/**
 * A plan to split a polygon (closed way or multipolygon relation) along intersecting roads.
 */
class BreakPlan {

    private final OsmPrimitive source;
    private final List<RoadCorridor> corridors;
    private final List<List<EastNorth>> resultPolygons;
    private final List<Way> innerWays;
    private final String description;

    BreakPlan(OsmPrimitive source, List<RoadCorridor> corridors,
              List<List<EastNorth>> resultPolygons, List<Way> innerWays,
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

    List<List<EastNorth>> getResultPolygons() {
        return resultPolygons;
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
     * A road that crosses through the polygon, with its corridor offset polylines.
     */
    static class RoadCorridor {
        private final List<Way> sourceWays;
        private final double widthMeters;
        private final List<CrossingPair> crossingPairs;

        RoadCorridor(List<Way> sourceWays, double widthMeters, List<CrossingPair> crossingPairs) {
            this.sourceWays = Collections.unmodifiableList(sourceWays);
            this.widthMeters = widthMeters;
            this.crossingPairs = Collections.unmodifiableList(crossingPairs);
        }

        /** @deprecated Use {@link #getSourceWays()} instead. */
        @Deprecated
        Way getRoad() { return sourceWays.isEmpty() ? null : sourceWays.get(0); }
        List<Way> getSourceWays() { return sourceWays; }
        double getWidthMeters() { return widthMeters; }
        List<CrossingPair> getCrossingPairs() { return crossingPairs; }
    }

    /**
     * An entry/exit pair where a road crosses a polygon boundary.
     * Each pair has two offset polylines (left/right) that follow the road
     * between the entry and exit points.
     */
    static class CrossingPair {
        /** Position of the entry crossing on the polygon boundary. */
        BoundaryPosition entry;
        /** Position of the exit crossing on the polygon boundary. */
        BoundaryPosition exit;
        /** Offset polyline on the left side of the road (entry→exit direction). */
        final List<EastNorth> leftOffsets;
        /** Offset polyline on the right side of the road (entry→exit direction). */
        final List<EastNorth> rightOffsets;

        CrossingPair(BoundaryPosition entry, BoundaryPosition exit,
                     List<EastNorth> leftOffsets, List<EastNorth> rightOffsets) {
            this.entry = entry;
            this.exit = exit;
            this.leftOffsets = Collections.unmodifiableList(leftOffsets);
            this.rightOffsets = Collections.unmodifiableList(rightOffsets);
        }
    }

    /**
     * A position on the polygon boundary, identified by segment index and
     * parametric t along that segment.
     */
    static class BoundaryPosition implements Comparable<BoundaryPosition> {
        /** Index of the polygon segment (edge from node[segIdx] to node[segIdx+1]). */
        final int segmentIndex;
        /** Parametric position along the segment, 0.0 = start, 1.0 = end. */
        final double t;
        /** The exact crossing point in EastNorth coordinates. */
        final EastNorth point;

        BoundaryPosition(int segmentIndex, double t, EastNorth point) {
            this.segmentIndex = segmentIndex;
            this.t = t;
            this.point = point;
        }

        @Override
        public int compareTo(BoundaryPosition o) {
            int cmp = Integer.compare(this.segmentIndex, o.segmentIndex);
            return cmp != 0 ? cmp : Double.compare(this.t, o.t);
        }
    }
}
