package org.openstreetmap.josm.plugins.multipolygone;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Analyzes a selected polygon and finds intersecting roads that can split it.
 * Produces a {@link BreakPlan} describing the split.
 *
 * <p>TODO: This class is a stub awaiting a full rewrite of the break algorithm.</p>
 */
class PolygonBreaker {

    /**
     * Analyzes whether the given primitive (closed way or multipolygon relation)
     * can be split by intersecting roads.
     *
     * @return a BreakPlan, or null if no splitting is possible
     */
    static BreakPlan analyze(OsmPrimitive selected, DataSet ds) {
        if (selected instanceof Way way) {
            if (!way.isClosed() || way.getNodesCount() < 4) return null;
        } else if (selected instanceof Relation rel) {
            String type = rel.get("type");
            if (!"multipolygon".equals(type) && !"boundary".equals(type)) return null;
        } else {
            return null;
        }

        // TODO: implement break algorithm
        return null;
    }
}
