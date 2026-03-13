package org.openstreetmap.josm.plugins.multipolygone;

import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

/**
 * A plan to unglue an area feature (closed way or multipolygon) from
 * centerline features (roads, waterways, etc.) by offsetting shared segments.
 */
class UngluePlan {

    private final OsmPrimitive source;
    private final List<GluedRun> gluedRuns;
    private final List<EastNorth> resultGeometry;
    private final List<Node> resultReusedNodes;
    private final String description;

    UngluePlan(OsmPrimitive source, List<GluedRun> gluedRuns,
               List<EastNorth> resultGeometry, List<Node> resultReusedNodes,
               String description) {
        this.source = source;
        this.gluedRuns = Collections.unmodifiableList(gluedRuns);
        this.resultGeometry = Collections.unmodifiableList(resultGeometry);
        this.resultReusedNodes = Collections.unmodifiableList(resultReusedNodes);
        this.description = description;
    }

    OsmPrimitive getSource() {
        return source;
    }

    List<GluedRun> getGluedRuns() {
        return gluedRuns;
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
     * A contiguous run of shared nodes between the area boundary and a
     * centerline way. The area boundary along this run should be offset
     * away from the centerline.
     */
    static class GluedRun {
        private final Way centerlineWay;
        private final List<Node> sharedNodes;
        private final double widthMeters;
        private final List<EastNorth> offsetPoints;

        GluedRun(Way centerlineWay, List<Node> sharedNodes, double widthMeters,
                 List<EastNorth> offsetPoints) {
            this.centerlineWay = centerlineWay;
            this.sharedNodes = Collections.unmodifiableList(sharedNodes);
            this.widthMeters = widthMeters;
            this.offsetPoints = Collections.unmodifiableList(offsetPoints);
        }

        Way getCenterlineWay() { return centerlineWay; }
        List<Node> getSharedNodes() { return sharedNodes; }
        double getWidthMeters() { return widthMeters; }

        /** Offset points replacing the shared nodes in the area boundary. */
        List<EastNorth> getOffsetPoints() { return offsetPoints; }
    }
}
