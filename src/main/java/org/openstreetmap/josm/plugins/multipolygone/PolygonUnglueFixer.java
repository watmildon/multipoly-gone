package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.tools.Logging;

/**
 * Builds JOSM commands to execute an {@link UngluePlan}.
 * Replaces glued boundary nodes with new offset nodes, preserving the
 * original way's tags and relation memberships.
 */
class PolygonUnglueFixer {

    /**
     * Executes an unglue plan: builds commands, wraps them in an undoable
     * sequence, and submits to JOSM's undo/redo handler.
     */
    static void execute(UngluePlan plan) {
        List<Command> commands = buildCommands(plan);
        if (commands.isEmpty()) {
            Logging.info("Multipoly-Gone: no commands generated for unglue plan");
            return;
        }

        Logging.info("Multipoly-Gone: executing {0} commands for polygon unglue", commands.size());

        for (Command cmd : commands) {
            cmd.executeCommand();
        }
        try {
            Command seq = SequenceCommand.wrapIfNeeded(
                tr("Unglue area from centerline"), commands);
            for (int i = commands.size() - 1; i >= 0; i--) {
                commands.get(i).undoCommand();
            }
            UndoRedoHandler.getInstance().add(seq);
        } catch (RuntimeException e) {
            for (int i = commands.size() - 1; i >= 0; i--) {
                commands.get(i).undoCommand();
            }
            throw e;
        }
    }

    /**
     * Builds the list of JOSM commands for an unglue plan.
     * Creates new nodes at offset positions and changes the way's node list.
     */
    static List<Command> buildCommands(UngluePlan plan) {
        List<Command> commands = new ArrayList<>();
        OsmPrimitive source = plan.getSource();
        DataSet ds = source.getDataSet();
        if (ds == null) return commands;

        // Find the actual way to modify
        Way targetWay;
        if (source instanceof Way way) {
            targetWay = way;
        } else if (source instanceof Relation rel) {
            targetWay = findOuterWay(rel);
        } else {
            return commands;
        }
        if (targetWay == null) return commands;

        // Build the new node list from the result geometry
        List<EastNorth> resultGeom = plan.getResultGeometry();
        List<Node> resultReused = plan.getResultReusedNodes();

        List<Node> newNodes = new ArrayList<>();
        for (int i = 0; i < resultGeom.size(); i++) {
            Node reused = resultReused.get(i);
            if (reused != null) {
                newNodes.add(reused);
            } else {
                // Create a new node at the offset position
                LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(resultGeom.get(i));
                Node node = new Node(ll);
                commands.add(new AddCommand(ds, node));
                newNodes.add(node);
            }
        }

        // Change the way's nodes
        commands.add(new ChangeNodesCommand(targetWay, newNodes));

        return commands;
    }

    private static Way findOuterWay(Relation rel) {
        for (RelationMember m : rel.getMembers()) {
            if (m.isWay() && ("outer".equals(m.getRole()) || m.getRole().isEmpty())) {
                return m.getWay();
            }
        }
        return null;
    }
}
