package org.openstreetmap.josm.plugins.multipolygone;

import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

/**
 * A plan to unglue an area feature (closed way or multipolygon) from
 * centerline features (roads, waterways, etc.) by clipping the area
 * boundary away from centerline corridors using JTS buffer/difference.
 */
class UngluePlan {

    private final OsmPrimitive source;
    private final List<CenterlineCorridor> corridors;
    private final List<EastNorth> resultGeometry;
    private final List<Node> resultReusedNodes;
    private final String description;

    UngluePlan(OsmPrimitive source, List<CenterlineCorridor> corridors,
               List<EastNorth> resultGeometry, List<Node> resultReusedNodes,
               String description) {
        this.source = source;
        this.corridors = Collections.unmodifiableList(corridors);
        this.resultGeometry = Collections.unmodifiableList(resultGeometry);
        this.resultReusedNodes = Collections.unmodifiableList(resultReusedNodes);
        this.description = description;
    }

    OsmPrimitive getSource() {
        return source;
    }

    List<CenterlineCorridor> getCorridors() {
        return corridors;
    }

    /** Closed ring of the corrected area geometry (first == last). */
    List<EastNorth> getResultGeometry() {
        return resultGeometry;
    }

    /**
     * Parallel to {@link #getResultGeometry()}. Non-null entries are existing nodes
     * that should be reused; null entries require new nodes.
     */
    List<Node> getResultReusedNodes() {
        return resultReusedNodes;
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
     * A centerline way that the area boundary is glued to, along with
     * the determined width used for the corridor buffer.
     */
    static class CenterlineCorridor {
        private final Way centerlineWay;
        private final double widthMeters;

        CenterlineCorridor(Way centerlineWay, double widthMeters) {
            this.centerlineWay = centerlineWay;
            this.widthMeters = widthMeters;
        }

        Way getCenterlineWay() { return centerlineWay; }
        double getWidthMeters() { return widthMeters; }
    }
}
