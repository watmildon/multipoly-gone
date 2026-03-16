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
import org.openstreetmap.josm.data.projection.Projection;

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
     * Executes multiple unglue plans as a single atomic undo operation.
     * Commands are built and executed sequentially (so later plans can
     * reuse nodes created by earlier ones), then wrapped in one SequenceCommand.
     */
    static void executeAll(List<UngluePlan> plans) {
        List<Command> allCommands = new ArrayList<>();

        for (UngluePlan plan : plans) {
            List<Command> commands = buildCommands(plan);
            for (Command cmd : commands) {
                cmd.executeCommand();
            }
            allCommands.addAll(commands);
        }

        if (allCommands.isEmpty()) return;

        Logging.info("Multipoly-Gone: executed {0} commands for {1} unglue plans",
            allCommands.size(), plans.size());

        try {
            Command seq = SequenceCommand.wrapIfNeeded(
                tr("Unglue all areas from centerlines"), allCommands);
            for (int i = allCommands.size() - 1; i >= 0; i--) {
                allCommands.get(i).undoCommand();
            }
            UndoRedoHandler.getInstance().add(seq);
        } catch (RuntimeException e) {
            for (int i = allCommands.size() - 1; i >= 0; i--) {
                allCommands.get(i).undoCommand();
            }
            throw e;
        }
    }

    /**
     * Tolerance in meters for matching an offset position to an existing node.
     * With JTS buffer/difference, adjacent areas may produce slightly different
     * vertex positions along the corridor edge, so we use a generous tolerance.
     */
    private static final double REUSE_TOLERANCE_METERS = 0.5;

    /**
     * Builds the list of JOSM commands for an unglue plan.
     * Creates new nodes at offset positions and changes the way's node list.
     * When an existing node in the DataSet already occupies the target offset
     * position (e.g., from a previously executed unglue on an adjacent area),
     * that node is reused to maintain connectivity.
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

        Projection proj = ProjectionRegistry.getProjection();
        double toleranceEN = REUSE_TOLERANCE_METERS / proj.getMetersPerUnit();

        // Build the new node list from the result geometry
        List<EastNorth> resultGeom = plan.getResultGeometry();
        List<Node> resultReused = plan.getResultReusedNodes();

        // Track nodes created within this call so we can reuse them too
        List<Node> createdNodes = new ArrayList<>();

        List<Node> newNodes = new ArrayList<>();
        for (int i = 0; i < resultGeom.size(); i++) {
            Node reused = resultReused.get(i);
            if (reused != null) {
                newNodes.add(reused);
            } else {
                EastNorth targetEN = resultGeom.get(i);
                // Try to find an existing node at this position
                Node existing = findNearbyNode(ds, createdNodes, targetEN, toleranceEN);
                if (existing != null) {
                    newNodes.add(existing);
                } else {
                    LatLon ll = proj.eastNorth2latlon(targetEN);
                    Node node = new Node(ll);
                    commands.add(new AddCommand(ds, node));
                    createdNodes.add(node);
                    newNodes.add(node);
                }
            }
        }

        // Change the way's nodes
        commands.add(new ChangeNodesCommand(targetWay, newNodes));

        return commands;
    }

    /**
     * Searches for an existing node near the target position. Checks both
     * the DataSet (for nodes from previous unglue operations) and the list
     * of nodes created in the current call.
     */
    private static Node findNearbyNode(DataSet ds, List<Node> createdNodes,
                                        EastNorth target, double toleranceEN) {
        // Check nodes created in this batch first (fast, small list)
        for (Node n : createdNodes) {
            EastNorth en = n.getEastNorth();
            if (en != null && en.distance(target) <= toleranceEN) {
                return n;
            }
        }
        // Search the DataSet for a nearby node
        for (Node n : ds.getNodes()) {
            if (n.isDeleted() || n.isIncomplete()) continue;
            EastNorth en = n.getEastNorth();
            if (en != null && en.distance(target) <= toleranceEN) {
                return n;
            }
        }
        return null;
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
