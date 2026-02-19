package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
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
            .map(FixableRelation::getRelation)
            .collect(Collectors.toSet());

        List<Command> allCommands = new ArrayList<>();

        for (FixableRelation fixable : fixables) {
            Relation relation = fixable.getRelation();
            if (relation.isDeleted()) {
                continue;
            }

            if (fixable.getFixType() == FixType.SEPARATE_CLOSED_WAYS) {
                allCommands.addAll(buildSeparateClosedWaysCommands(relation));
            } else if (fixable.getFixType() == FixType.CHAINABLE_OUTERS) {
                allCommands.addAll(buildChainableOutersCommands(relation, fixable));
            }
        }

        // Cleanup pass: delete unused member ways across the whole batch
        Set<Way> waysAlreadyDeleted = new HashSet<>();
        for (FixableRelation fixable : fixables) {
            if (fixable.getFixType() == FixType.CHAINABLE_OUTERS) {
                for (RelationMember member : fixable.getRelation().getMembers()) {
                    if (member.isWay()) {
                        Way way = member.getWay();
                        if (!waysAlreadyDeleted.contains(way)
                                && isUnusedAfterBatch(way, relationsBeingDeleted, insignificantTags)) {
                            allCommands.add(new DeleteCommand(way));
                            waysAlreadyDeleted.add(way);
                        }
                    }
                }
            }
        }

        if (!allCommands.isEmpty()) {
            Command cmd = SequenceCommand.wrapIfNeeded(
                tr("Dissolve unnecessary multipolygon(s)"), allCommands);
            UndoRedoHandler.getInstance().add(cmd);
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

        // Create new closed way from the chained nodes
        Way newWay = new Way();
        newWay.setNodes(fixable.getChainedNodes());
        commands.add(new AddCommand(ds, newWay));

        // Apply tags to the new way
        Map<String, String> tags = getTransferrableTags(relation);
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            commands.add(new ChangePropertyCommand(newWay, tag.getKey(), tag.getValue()));
        }

        // Delete the relation
        commands.add(new DeleteCommand(relation));

        return commands;
    }

    private static Map<String, String> getTransferrableTags(Relation relation) {
        Map<String, String> tags = new java.util.LinkedHashMap<>(relation.getKeys());
        tags.remove("type");
        // Remove internal/test tags (keys starting with _)
        tags.keySet().removeIf(k -> k.startsWith("_"));
        return tags;
    }

    private static boolean isUnusedAfterBatch(Way way, Set<Relation> relationsBeingDeleted,
            Set<String> insignificantTags) {
        // Check if the way is still referenced by any relation NOT being deleted
        for (OsmPrimitive referrer : way.getReferrers()) {
            if (referrer instanceof Relation r && !relationsBeingDeleted.contains(r)) {
                return false;
            }
        }

        // Check if the way has any significant tags
        for (String key : way.getKeys().keySet()) {
            if (!insignificantTags.contains(key)) {
                return false;
            }
        }

        return true;
    }

    private static Set<String> getInsignificantTags() {
        String pref = Config.getPref().get(PREF_INSIGNIFICANT_TAGS, DEFAULT_INSIGNIFICANT_TAGS);
        Set<String> tags = new HashSet<>();
        for (String tag : pref.split(";")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }
}
