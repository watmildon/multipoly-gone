package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
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
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.Logging;

/**
 * Builds JOSM commands to execute a {@link BreakPlan}.
 * Creates new nodes at offset points, new ways for each sub-polygon,
 * and deletes the original polygon.
 */
class PolygonBreakFixer {

    /**
     * Executes a break plan: builds commands, wraps them in an undoable
     * sequence, and submits to JOSM's undo/redo handler.
     */
    static void execute(BreakPlan plan) {
        List<Command> commands = buildCommands(plan);
        if (commands.isEmpty()) {
            Logging.info("Multipoly-Gone: no commands generated for break plan");
            return;
        }

        Logging.info("Multipoly-Gone: executing {0} commands for polygon break", commands.size());

        // Execute all commands to populate the DataSet
        for (Command cmd : commands) {
            cmd.executeCommand();
        }

        // Undo them (JOSM's UndoRedoHandler.add will replay)
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).undoCommand();
        }

        Command cmd = SequenceCommand.wrapIfNeeded(
            tr("Break polygon along roads"), commands);
        UndoRedoHandler.getInstance().add(cmd);
    }

    /**
     * Builds the list of JOSM commands for a break plan.
     */
    static List<Command> buildCommands(BreakPlan plan) {
        List<Command> commands = new ArrayList<>();
        OsmPrimitive source = plan.getSource();
        DataSet ds = source.getDataSet();
        if (ds == null) return commands;

        // Collect the tags from the original polygon
        // For relations, use the relation's tags (not the outer way's tags)
        Map<String, String> originalTags = source.getKeys();
        // Remove type=multipolygon from tags — only added to relations that need it
        originalTags.remove("type");

        List<Way> innerWays = plan.getInnerWays();

        // Create new outer ways for each sub-polygon
        List<Way> newOuterWays = new ArrayList<>();
        for (BreakPlan.ResultPolygon resultPoly : plan.getResultPolygons()) {
            List<EastNorth> subPoly = resultPoly.coordinates;
            List<Node> reusedNodes = resultPoly.reusedNodes;
            if (subPoly.size() < 4) continue;

            List<Node> wayNodes = new ArrayList<>();
            for (int i = 0; i < subPoly.size(); i++) {
                if (i == subPoly.size() - 1 && !wayNodes.isEmpty()) {
                    // Closure point: reuse the first node so JOSM sees a closed way
                    wayNodes.add(wayNodes.get(0));
                } else if (reusedNodes.get(i) != null) {
                    // Reuse existing node from original polygon
                    wayNodes.add(reusedNodes.get(i));
                } else {
                    LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(subPoly.get(i));
                    Node node = new Node(ll);
                    commands.add(new AddCommand(ds, node));
                    wayNodes.add(node);
                }
            }

            Way newWay = new Way();
            newWay.setNodes(wayNodes);
            commands.add(new AddCommand(ds, newWay));
            newOuterWays.add(newWay);
        }

        if (innerWays.isEmpty()) {
            // Simple case: no inners, just tag each outer way directly
            for (Way outer : newOuterWays) {
                outer.setKeys(originalTags);
            }
        } else {
            // Assign each inner to the sub-polygon that contains it
            // Execute pending AddCommands so nodes exist for containment checks
            for (Command c : commands) {
                c.executeCommand();
            }

            List<List<Way>> innersPerOuter = assignInners(newOuterWays, innerWays);

            // Undo the temporary execution
            for (int i = commands.size() - 1; i >= 0; i--) {
                commands.get(i).undoCommand();
            }

            for (int oi = 0; oi < newOuterWays.size(); oi++) {
                Way outer = newOuterWays.get(oi);
                List<Way> assignedInners = innersPerOuter.get(oi);

                if (assignedInners.isEmpty()) {
                    // No inners in this piece — simple closed way with tags
                    outer.setKeys(originalTags);
                } else {
                    // Has inners — create a multipolygon relation
                    Relation rel = new Relation();
                    rel.put("type", "multipolygon");
                    for (Map.Entry<String, String> tag : originalTags.entrySet()) {
                        rel.put(tag.getKey(), tag.getValue());
                    }
                    rel.addMember(new RelationMember("outer", outer));
                    for (Way inner : assignedInners) {
                        rel.addMember(new RelationMember("inner", inner));
                    }
                    commands.add(new AddCommand(ds, rel));
                }
            }
        }

        // Delete the original source (way or relation)
        // For relations, also delete the outer way if it was a member
        if (source instanceof Relation rel) {
            // Delete relation first, then the outer way
            commands.add(new DeleteCommand(rel));
            for (RelationMember m : rel.getMembers()) {
                if (m.getMember() instanceof Way way
                    && ("outer".equals(m.getRole()) || m.getRole().isEmpty())
                    && !way.isDeleted()) {
                    commands.add(new DeleteCommand(way));
                }
            }
        } else {
            commands.add(new DeleteCommand(source));
        }

        return commands;
    }

    /**
     * Assigns inner ways to the outer sub-polygon that contains them.
     * Uses the centroid of each inner ring for containment testing.
     * Returns a list parallel to {@code outers}, each entry listing the
     * inner ways contained by that outer.
     */
    private static List<List<Way>> assignInners(List<Way> outers, List<Way> inners) {
        List<List<Way>> result = new ArrayList<>();
        for (int i = 0; i < outers.size(); i++) {
            result.add(new ArrayList<>());
        }

        for (Way inner : inners) {
            if (!inner.isClosed() || inner.getNodesCount() < 4) continue;

            // Use centroid of inner for containment check
            Node testNode = findInteriorPoint(inner);

            for (int oi = 0; oi < outers.size(); oi++) {
                Way outer = outers.get(oi);
                if (Geometry.nodeInsidePolygon(testNode, outer.getNodes())) {
                    result.get(oi).add(inner);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Finds a point guaranteed to be inside the polygon by using the
     * centroid of the first triangle. Falls back to the first node.
     */
    private static Node findInteriorPoint(Way polygon) {
        List<Node> nodes = polygon.getNodes();
        if (nodes.size() < 4) return nodes.get(0);

        // Use centroid of polygon (average of all non-closure nodes)
        double latSum = 0, lonSum = 0;
        int count = nodes.size() - 1; // exclude closure node
        for (int i = 0; i < count; i++) {
            latSum += nodes.get(i).lat();
            lonSum += nodes.get(i).lon();
        }
        Node centroid = new Node(new LatLon(latSum / count, lonSum / count));
        return centroid;
    }
}
