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
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.AbstractPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixType;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixableRelation;
import org.openstreetmap.josm.spi.preferences.Config;

public class MultipolygonFixer {

    private static final String PREF_INSIGNIFICANT_TAGS = "multipolygone.insignificantTags";
    private static final String DEFAULT_INSIGNIFICANT_TAGS = "source;created_by";

    public static void fixRelations(List<FixableRelation> fixables) {
        if (fixables.isEmpty()) {
            return;
        }

        Set<String> insignificantTags = getInsignificantTags();
        Set<Relation> relationsBeingDeleted = fixables.stream()
            .filter(f -> f.getFixType() != FixType.EXTRACTABLE_OUTERS)
            .map(FixableRelation::getRelation)
            .collect(Collectors.toSet());

        List<Command> allCommands = new ArrayList<>();
        // Track ways whose source segments should be cleaned up
        Set<Way> waysToCleanup = new HashSet<>();

        for (FixableRelation fixable : fixables) {
            Relation relation = fixable.getRelation();
            if (relation.isDeleted()) {
                continue;
            }

            if (fixable.getFixType() == FixType.SEPARATE_CLOSED_WAYS) {
                allCommands.addAll(buildSeparateClosedWaysCommands(relation));
            } else if (fixable.getFixType() == FixType.CHAINABLE_OUTERS) {
                allCommands.addAll(buildChainableOutersCommands(relation, fixable));
                collectSourceWays(fixable.getRelation(), waysToCleanup);
            } else if (fixable.getFixType() == FixType.DISJOINT_OUTERS) {
                allCommands.addAll(buildDisjointOutersCommands(relation, fixable));
                collectChainedSourceWays(fixable.getExtractableRings(), waysToCleanup);
            } else if (fixable.getFixType() == FixType.EXTRACTABLE_OUTERS) {
                allCommands.addAll(buildExtractableOutersCommands(relation, fixable));
                collectChainedSourceWays(fixable.getExtractableRings(), waysToCleanup);
                collectChainedSourceWays(fixable.getRetainedRings(), waysToCleanup);
            }
        }

        // Cleanup pass: delete unused source ways
        Set<Way> waysAlreadyDeleted = new HashSet<>();
        for (Way way : waysToCleanup) {
            if (!waysAlreadyDeleted.contains(way)
                    && isUnusedAfterBatch(way, relationsBeingDeleted, insignificantTags)) {
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

    private static void collectSourceWays(Relation relation, Set<Way> target) {
        for (RelationMember member : relation.getMembers()) {
            if (member.isWay()) {
                target.add(member.getWay());
            }
        }
    }

    private static void collectChainedSourceWays(List<WayChainBuilder.Ring> rings, Set<Way> target) {
        if (rings == null) return;
        for (WayChainBuilder.Ring ring : rings) {
            if (!ring.isAlreadyClosed()) {
                target.addAll(ring.getSourceWays());
            }
        }
    }

    private static List<Command> buildSeparateClosedWaysCommands(Relation relation) {
        List<Command> commands = new ArrayList<>();
        Map<String, String> tags = getTransferrableTags(relation);

        for (RelationMember member : relation.getMembers()) {
            if (member.isWay()) {
                Way way = member.getWay();
                for (Map.Entry<String, String> tag : tags.entrySet()) {
                    commands.add(new ChangePropertyCommand(way, tag.getKey(), tag.getValue()));
                }
            }
        }

        commands.add(new DeleteCommand(relation));
        return commands;
    }

    private static List<Command> buildChainableOutersCommands(Relation relation, FixableRelation fixable) {
        List<Command> commands = new ArrayList<>();
        DataSet ds = relation.getDataSet();

        Way newWay = new Way();
        newWay.setNodes(fixable.getChainedNodes());
        Map<String, String> tags = getTransferrableTags(relation);
        newWay.setKeys(tags);
        commands.add(new AddCommand(ds, newWay));

        commands.add(new DeleteCommand(relation));
        return commands;
    }

    private static List<Command> buildDisjointOutersCommands(Relation relation, FixableRelation fixable) {
        List<Command> commands = new ArrayList<>();
        DataSet ds = relation.getDataSet();
        Map<String, String> tags = getTransferrableTags(relation);

        for (WayChainBuilder.Ring ring : fixable.getExtractableRings()) {
            if (ring.isAlreadyClosed()) {
                // Tag the existing closed way
                Way way = ring.getSourceWays().get(0);
                for (Map.Entry<String, String> tag : tags.entrySet()) {
                    commands.add(new ChangePropertyCommand(way, tag.getKey(), tag.getValue()));
                }
            } else {
                // Create a new closed way from the chained nodes
                Way newWay = new Way();
                newWay.setNodes(ring.getNodes());
                newWay.setKeys(tags);
                commands.add(new AddCommand(ds, newWay));
            }
        }

        commands.add(new DeleteCommand(relation));
        return commands;
    }

    private static List<Command> buildExtractableOutersCommands(Relation relation, FixableRelation fixable) {
        List<Command> commands = new ArrayList<>();
        DataSet ds = relation.getDataSet();
        Map<String, String> tags = getTransferrableTags(relation);

        // Collect all source ways from extractable rings for removal from relation
        Set<Way> waysToRemove = new HashSet<>();

        for (WayChainBuilder.Ring ring : fixable.getExtractableRings()) {
            waysToRemove.addAll(ring.getSourceWays());

            if (ring.isAlreadyClosed()) {
                Way way = ring.getSourceWays().get(0);
                for (Map.Entry<String, String> tag : tags.entrySet()) {
                    commands.add(new ChangePropertyCommand(way, tag.getKey(), tag.getValue()));
                }
            } else {
                Way newWay = new Way();
                newWay.setNodes(ring.getNodes());
                newWay.setKeys(tags);
                commands.add(new AddCommand(ds, newWay));
            }
        }

        // Also consolidate retained rings that need chaining into new closed ways
        // Maps old source ways to the new way that replaces them in the relation
        Map<Way, Way> replacements = new HashMap<>();
        if (fixable.getRetainedRings() != null) {
            for (WayChainBuilder.Ring ring : fixable.getRetainedRings()) {
                if (!ring.isAlreadyClosed()) {
                    Way newWay = new Way();
                    newWay.setNodes(ring.getNodes());
                    commands.add(new AddCommand(ds, newWay));
                    // All source ways in this ring get replaced by the new way
                    for (Way sourceWay : ring.getSourceWays()) {
                        replacements.put(sourceWay, newWay);
                    }
                }
            }
        }

        // Build new member list: remove extracted ways, replace chained retained ways
        Relation modified = new Relation(relation);
        List<RelationMember> remainingMembers = new ArrayList<>();
        Set<Way> alreadyAdded = new HashSet<>();
        for (RelationMember member : relation.getMembers()) {
            if (member.isWay() && waysToRemove.contains(member.getWay())) {
                // Extracted — skip
                continue;
            }
            if (member.isWay() && replacements.containsKey(member.getWay())) {
                // This source way is part of a retained ring that was consolidated
                Way newWay = replacements.get(member.getWay());
                if (!alreadyAdded.contains(newWay)) {
                    remainingMembers.add(new RelationMember(member.getRole(), newWay));
                    alreadyAdded.add(newWay);
                }
                // Mark the old source way for cleanup
                waysToRemove.add(member.getWay());
                continue;
            }
            remainingMembers.add(member);
        }
        modified.setMembers(remainingMembers);
        commands.add(new ChangeCommand(relation, modified));

        return commands;
    }

    private static Map<String, String> getTransferrableTags(Relation relation) {
        Map<String, String> tags = new java.util.LinkedHashMap<>(relation.getKeys());
        tags.remove("type");
        tags.keySet().removeIf(k -> k.startsWith("_"));
        return tags;
    }

    private static boolean isUnusedAfterBatch(Way way, Set<Relation> relationsBeingDeleted,
            Set<String> insignificantTags) {
        for (OsmPrimitive referrer : way.getReferrers()) {
            if (referrer instanceof Relation r && !relationsBeingDeleted.contains(r)) {
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
