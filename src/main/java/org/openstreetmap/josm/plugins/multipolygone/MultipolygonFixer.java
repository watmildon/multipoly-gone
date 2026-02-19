package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.AbstractPrimitive;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOp;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOpType;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;
import org.openstreetmap.josm.spi.preferences.Config;

public class MultipolygonFixer {

    private static final String PREF_INSIGNIFICANT_TAGS = "multipolygone.insignificantTags";
    private static final String DEFAULT_INSIGNIFICANT_TAGS = "source;created_by";

    public static void fixRelations(List<FixPlan> plans) {
        if (plans.isEmpty()) {
            return;
        }

        Set<String> insignificantTags = getInsignificantTags();

        // All processed relations should be ignored for cleanup referrer checks,
        // whether they are deleted or just modified
        Set<Relation> processedRelations = plans.stream()
            .map(FixPlan::getRelation)
            .collect(Collectors.toSet());

        List<Command> allCommands = new ArrayList<>();
        Set<Way> waysToCleanup = new HashSet<>();

        for (FixPlan plan : plans) {
            Relation relation = plan.getRelation();
            if (relation.isDeleted()) {
                continue;
            }
            allCommands.addAll(buildCommandsForPlan(plan, waysToCleanup));
        }

        // Cleanup pass: delete unused source ways
        Set<Way> waysAlreadyDeleted = new HashSet<>();
        for (Way way : waysToCleanup) {
            if (!waysAlreadyDeleted.contains(way)
                    && isUnusedAfterBatch(way, processedRelations, insignificantTags)) {
                allCommands.add(new DeleteCommand(way));
                waysAlreadyDeleted.add(way);
            }
        }

        if (!allCommands.isEmpty()) {
            Command cmd = SequenceCommand.wrapIfNeeded(
                tr("Dissolve unnecessary multipolygon(s)"), allCommands);
            UndoRedoHandler.getInstance().add(cmd);
        }
    }

    private static List<Command> buildCommandsForPlan(FixPlan plan, Set<Way> waysToCleanup) {
        List<Command> commands = new ArrayList<>();
        Relation relation = plan.getRelation();
        DataSet ds = relation.getDataSet();
        Map<String, String> tags = getTransferrableTags(relation);

        // Track Ring -> Way mapping for cross-stage references
        Map<WayChainBuilder.Ring, Way> ringToWay = new HashMap<>();

        // Track relation member modifications
        Set<Way> membersToRemove = new HashSet<>();
        Map<Way, Way> memberReplacements = new HashMap<>();
        boolean relationDeleted = false;

        for (FixOp op : plan.getOperations()) {
            switch (op.getType()) {
                case CONSOLIDATE_RINGS -> {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        Way newWay = new Way();
                        newWay.setNodes(ring.getNodes());
                        commands.add(new AddCommand(ds, newWay));
                        ringToWay.put(ring, newWay);

                        for (Way src : ring.getSourceWays()) {
                            memberReplacements.put(src, newWay);
                        }
                        waysToCleanup.addAll(ring.getSourceWays());
                    }
                }

                case EXTRACT_OUTERS -> {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        Way targetWay = ringToWay.get(ring);
                        if (targetWay != null) {
                            // Was consolidated in a prior step — tag the new way
                            targetWay.setKeys(tags);
                        } else if (ring.isAlreadyClosed()) {
                            // Tag existing way in place
                            targetWay = ring.getSourceWays().get(0);
                            for (Map.Entry<String, String> tag : tags.entrySet()) {
                                commands.add(new ChangePropertyCommand(targetWay, tag.getKey(), tag.getValue()));
                            }
                        } else {
                            // Create new way from ring nodes
                            Way newWay = new Way();
                            newWay.setNodes(ring.getNodes());
                            newWay.setKeys(tags);
                            commands.add(new AddCommand(ds, newWay));
                            waysToCleanup.addAll(ring.getSourceWays());
                        }
                        // Mark source ways for removal from relation
                        membersToRemove.addAll(ring.getSourceWays());
                    }
                }

                case DISSOLVE -> {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        Way targetWay = ringToWay.get(ring);
                        if (targetWay != null) {
                            // Consolidated way — set tags before it's added
                            targetWay.setKeys(tags);
                        } else if (ring.isAlreadyClosed()) {
                            targetWay = ring.getSourceWays().get(0);
                            for (Map.Entry<String, String> tag : tags.entrySet()) {
                                commands.add(new ChangePropertyCommand(targetWay, tag.getKey(), tag.getValue()));
                            }
                        }
                    }
                    commands.add(new DeleteCommand(relation));
                    relationDeleted = true;
                }

                case TOUCHING_INNER_MERGE -> {
                    for (List<Node> wayNodes : op.getMergedWays()) {
                        Way newWay = new Way();
                        newWay.setNodes(wayNodes);
                        newWay.setKeys(tags);
                        commands.add(new AddCommand(ds, newWay));
                    }
                    // Collect all relation member ways for cleanup
                    for (RelationMember member : relation.getMembers()) {
                        if (member.isWay()) {
                            waysToCleanup.add(member.getWay());
                        }
                    }
                    commands.add(new DeleteCommand(relation));
                    relationDeleted = true;
                }
            }
        }

        // If the relation survives (partial ops), apply accumulated member changes
        if (!relationDeleted && (!membersToRemove.isEmpty() || !memberReplacements.isEmpty())) {
            Relation modified = new Relation(relation);
            List<RelationMember> newMembers = new ArrayList<>();
            Set<Way> alreadyReplaced = new HashSet<>();

            for (RelationMember member : relation.getMembers()) {
                if (!member.isWay()) {
                    newMembers.add(member);
                    continue;
                }
                Way way = member.getWay();

                // Was this way replaced by a consolidated ring?
                if (memberReplacements.containsKey(way)) {
                    Way replacement = memberReplacements.get(way);
                    if (!alreadyReplaced.contains(replacement)) {
                        newMembers.add(new RelationMember(member.getRole(), replacement));
                        alreadyReplaced.add(replacement);
                    }
                    continue;
                }

                // Was this way extracted?
                if (membersToRemove.contains(way)) {
                    continue;
                }

                newMembers.add(member);
            }
            modified.setMembers(newMembers);
            commands.add(new ChangeCommand(relation, modified));
        }

        return commands;
    }

    private static Map<String, String> getTransferrableTags(Relation relation) {
        Map<String, String> tags = new java.util.LinkedHashMap<>(relation.getKeys());
        tags.remove("type");
        tags.keySet().removeIf(k -> k.startsWith("_"));
        return tags;
    }

    private static boolean isUnusedAfterBatch(Way way, Set<Relation> processedRelations,
            Set<String> insignificantTags) {
        for (OsmPrimitive referrer : way.getReferrers()) {
            if (referrer instanceof Relation r && !processedRelations.contains(r)) {
                return false;
            }
        }

        for (String key : way.getKeys().keySet()) {
            if (!insignificantTags.contains(key)) {
                return false;
            }
        }

        return true;
    }

    private static Set<String> getInsignificantTags() {
        Set<String> tags = new HashSet<>();

        String pref = Config.getPref().get(PREF_INSIGNIFICANT_TAGS, DEFAULT_INSIGNIFICANT_TAGS);
        for (String tag : pref.split(";")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }

        if (Config.getPref().getBoolean(MultipolyGonePreferences.PREF_USE_DISCARDABLE_KEYS, true)) {
            tags.addAll(AbstractPrimitive.getDiscardableKeys());
        }

        return tags;
    }
}
