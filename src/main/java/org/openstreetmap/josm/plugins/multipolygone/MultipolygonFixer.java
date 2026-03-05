package org.openstreetmap.josm.plugins.multipolygone;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Comparator;
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
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.ComponentResult;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixOp;
import org.openstreetmap.josm.plugins.multipolygone.MultipolygonAnalyzer.FixPlan;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

public class MultipolygonFixer {

    private static final int MAX_ITERATIONS = 10;

    public static void fixRelations(List<FixPlan> plans) {
        if (plans.isEmpty()) {
            return;
        }

        List<Command> allCommands = buildAllCommands(plans);
        if (allCommands.isEmpty()) {
            return;
        }

        Logging.info("Multipoly-Gone: executing {0} commands for {1} plan(s)", allCommands.size(), plans.size());
        Command cmd = SequenceCommand.wrapIfNeeded(
            tr("Fix multipolygon/boundary relation(s)"), allCommands);
        UndoRedoHandler.getInstance().add(cmd);
    }

    /**
     * Fixes all given plans, then re-analyzes the DataSet for newly fixable relations
     * and repeats until convergence. All changes are wrapped in a single SequenceCommand
     * so that Ctrl+Z undoes the entire operation.
     */
    public static void fixRelationsUntilConvergence(List<FixPlan> initialPlans) {
        if (initialPlans.isEmpty()) {
            return;
        }

        DataSet ds = initialPlans.get(0).getRelation().getDataSet();
        List<Command> allCommands = new ArrayList<>();
        List<FixPlan> currentPlans = initialPlans;

        for (int iteration = 0; iteration < MAX_ITERATIONS && !currentPlans.isEmpty(); iteration++) {
            List<Command> passCommands = buildAllCommands(currentPlans);
            if (passCommands.isEmpty()) {
                break;
            }

            // Execute commands directly to mutate the DataSet (without UndoRedoHandler)
            for (Command cmd : passCommands) {
                cmd.executeCommand();
            }
            allCommands.addAll(passCommands);

            // Re-analyze for newly fixable relations
            List<FixPlan> newPlans = MultipolygonAnalyzer.findFixableRelations(ds);
            if (newPlans.size() >= currentPlans.size()) {
                Logging.warn("Multipoly-Gone: iteration did not reduce fixable count ({0} -> {1}), stopping",
                    currentPlans.size(), newPlans.size());
                break;
            }
            currentPlans = newPlans;
        }

        if (allCommands.isEmpty()) {
            return;
        }

        // Undo all commands to restore original DataSet state
        for (int i = allCommands.size() - 1; i >= 0; i--) {
            allCommands.get(i).undoCommand();
        }

        // Wrap everything and add to UndoRedoHandler (which replays as a single undoable unit)
        Command cmd = SequenceCommand.wrapIfNeeded(
            tr("Fix multipolygon/boundary relation(s)"), allCommands);
        UndoRedoHandler.getInstance().add(cmd);
    }

    private static List<Command> buildAllCommands(List<FixPlan> plans) {
        Set<String> mpInsignificantTags = getInsignificantTagsForMultipolygon();
        Set<String> boundaryInsignificantTags = getInsignificantTagsForBoundary();

        // Sort plans by relation ID for deterministic processing order
        List<FixPlan> sortedPlans = new ArrayList<>(plans);
        sortedPlans.sort(Comparator.comparingLong(p -> p.getRelation().getUniqueId()));

        // All processed relations should be ignored for cleanup referrer checks,
        // whether they are deleted or just modified
        Set<Relation> processedRelations = sortedPlans.stream()
            .map(FixPlan::getRelation)
            .collect(Collectors.toSet());

        List<Command> allCommands = new ArrayList<>();
        Set<Way> waysToCleanup = new HashSet<>();
        Set<Way> waysRetainedByPlans = new HashSet<>();
        Set<Node> nodesToCleanup = new HashSet<>();
        // Track {source-way-set} → consolidated-way across all plans so that when
        // multiple relations reference the exact same set of ways, consolidation is shared
        Map<Set<Way>, Way> globalConsolidations = new HashMap<>();

        for (FixPlan plan : sortedPlans) {
            Relation relation = plan.getRelation();
            if (relation.isDeleted()) {
                continue;
            }

            Set<String> insignificantTags = plan.isBoundary()
                ? boundaryInsignificantTags : mpInsignificantTags;
            allCommands.addAll(buildCommandsForPlan(plan, waysToCleanup, waysRetainedByPlans,
                nodesToCleanup, globalConsolidations, insignificantTags));
        }

        // Cleanup pass: delete unused source ways first, then unused nodes.
        // Order matters for undo: on undo (reverse), nodes are restored before ways,
        // so ways can safely reference those nodes during Way.load().
        // Use the union of both tag sets — ways only enter waysToCleanup if the plan
        // that added them already deemed them cleanup-worthy using the correct per-type set.
        Set<String> unionInsignificantTags = new HashSet<>();
        unionInsignificantTags.addAll(mpInsignificantTags);
        unionInsignificantTags.addAll(boundaryInsignificantTags);

        Set<Way> waysAlreadyDeleted = new HashSet<>();
        for (Way way : waysToCleanup) {
            if (!waysAlreadyDeleted.contains(way)
                    && !waysRetainedByPlans.contains(way)
                    && isUnusedAfterBatch(way, processedRelations, unionInsignificantTags)) {
                allCommands.add(new DeleteCommand(way));
                waysAlreadyDeleted.add(way);
            }
        }

        // Delete orphaned nodes after ways, so undo restores nodes before ways.
        // Re-check each node: only delete if all its referrer ways are being deleted.
        // The earlier check (during plan building) may have used stale cross-plan state.
        for (Node node : nodesToCleanup) {
            if (!node.isDeleted()) {
                boolean safeToDelete = node.getReferrers().stream()
                    .filter(Way.class::isInstance)
                    .map(Way.class::cast)
                    .allMatch(waysAlreadyDeleted::contains);
                if (safeToDelete) {
                    allCommands.add(new DeleteCommand(node));
                }
            }
        }

        return allCommands;
    }

    private static List<Command> buildCommandsForPlan(FixPlan plan, Set<Way> waysToCleanup,
            Set<Way> waysRetainedByPlans, Set<Node> nodesToCleanup,
            Map<Set<Way>, Way> globalConsolidations, Set<String> insignificantTags) {
        List<Command> commands = new ArrayList<>();
        Relation relation = plan.getRelation();
        DataSet ds = relation.getDataSet();
        Map<String, String> tags = plan.isBoundary()
            ? java.util.Collections.emptyMap()
            : getTransferrableTags(relation);

        // Track Ring -> Way mapping for cross-stage references
        Map<WayChainBuilder.Ring, Way> ringToWay = new HashMap<>();

        // Track relation member modifications
        Set<Way> membersToRemove = new HashSet<>();
        Map<Way, Way> memberReplacements = new HashMap<>();
        // Extra members to add (e.g., additional decomposed sub-ring ways for boundaries)
        List<RelationMember> extraMembers = new ArrayList<>();
        boolean relationDeleted = false;
        boolean splitHandled = false;

        for (FixOp op : plan.getOperations()) {
            switch (op.getType()) {
                case CONSOLIDATE_RINGS -> {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        Set<Way> sourceSet = new HashSet<>(ring.getSourceWays());
                        // Check if these exact source ways were already consolidated by a prior plan
                        Way existing = globalConsolidations.get(sourceSet);
                        if (existing != null) {
                            ringToWay.put(ring, existing);
                            for (Way src : ring.getSourceWays()) {
                                memberReplacements.put(src, existing);
                            }
                        } else {
                            // Check if an existing closed way in the DataSet already
                            // has this ring's geometry — reuse it instead of creating a duplicate
                            Way existingMatch = findMatchingWayInDataSet(ring.getNodes(), sourceSet);

                            Way targetWay;
                            if (existingMatch != null) {
                                targetWay = existingMatch;
                                waysToCleanup.addAll(ring.getSourceWays());
                            } else {
                                // Check if any source way has significant tags (e.g., highway=residential).
                                // If so, reuse an untagged source way instead of creating a new one,
                                // so that the tagged way remains untouched.
                                // The reusable way must NOT be shared with another relation,
                                // because ChangeCommand would alter its geometry and break the other relation.
                                boolean anyTagged = false;
                                Way reusableWay = null;
                                for (Way src : ring.getSourceWays()) {
                                    if (hasSignificantTags(src, insignificantTags)) {
                                        anyTagged = true;
                                    } else if (reusableWay == null && !isSharedWithOtherRelation(src, relation)) {
                                        reusableWay = src;
                                    }
                                }

                                if (anyTagged && reusableWay != null) {
                                    // Reuse the untagged way by changing its nodes to form the closed ring
                                    Way modified = new Way(reusableWay);
                                    modified.setNodes(ring.getNodes());
                                    commands.add(new ChangeCommand(reusableWay, modified));
                                    targetWay = reusableWay;

                                    // Only add other untagged source ways to cleanup.
                                    // Tagged source ways must NOT be touched.
                                    for (Way src : ring.getSourceWays()) {
                                        if (src != reusableWay && !hasSignificantTags(src, insignificantTags)) {
                                            waysToCleanup.add(src);
                                        }
                                    }
                                } else {
                                    // Default: create a new way (either no tagged ways, or all are tagged)
                                    targetWay = new Way();
                                    targetWay.setNodes(ring.getNodes());
                                    commands.add(new AddCommand(ds, targetWay));
                                    waysToCleanup.addAll(ring.getSourceWays());
                                }
                            }

                            ringToWay.put(ring, targetWay);
                            for (Way src : ring.getSourceWays()) {
                                memberReplacements.put(src, targetWay);
                            }
                            globalConsolidations.put(sourceSet, targetWay);
                        }
                    }
                }

                case DECOMPOSE_SELF_INTERSECTIONS -> {
                    for (MultipolygonAnalyzer.DecomposedRing decomp : op.getDecomposedRings()) {
                        // Remove the AddCommand for the consolidated way created in CONSOLIDATE_RINGS,
                        // since it will be replaced by the decomposed sub-rings
                        Way consolidatedWay = ringToWay.get(decomp.getOriginalRing());
                        if (consolidatedWay != null) {
                            commands.removeIf(cmd -> cmd instanceof AddCommand
                                && ((AddCommand) cmd).getParticipatingPrimitives().contains(consolidatedWay));
                        }
                        // Add new intersection nodes to the DataSet
                        for (Node node : decomp.getNewIntersectionNodes()) {
                            commands.add(new AddCommand(ds, node));
                        }
                        // Create a Way for each sub-ring
                        for (WayChainBuilder.Ring subRing : decomp.getSubRings()) {
                            Way newWay = new Way();
                            newWay.setNodes(subRing.getNodes());
                            commands.add(new AddCommand(ds, newWay));
                            ringToWay.put(subRing, newWay);
                        }
                        // Queue original source ways for cleanup
                        waysToCleanup.addAll(decomp.getOriginalRing().getSourceWays());
                        // Map source ways to the first sub-ring way for member replacement
                        Way firstSubWay = ringToWay.get(decomp.getSubRings().get(0));
                        for (Way src : decomp.getOriginalRing().getSourceWays()) {
                            memberReplacements.put(src, firstSubWay);
                        }
                        // For boundary plans (relation survives), add extra sub-ring ways as members
                        if (plan.isBoundary() && decomp.getSubRings().size() > 1) {
                            for (int i = 1; i < decomp.getSubRings().size(); i++) {
                                Way subWay = ringToWay.get(decomp.getSubRings().get(i));
                                if (subWay != null) {
                                    extraMembers.add(new RelationMember("outer", subWay));
                                }
                            }
                        }
                    }
                }

                case CONSOLIDATE_INNERS -> {
                    for (MultipolygonAnalyzer.ConsolidatedInnerGroup group :
                            op.getConsolidatedInnerGroups()) {
                        WayChainBuilder.Ring mergedRing = group.getMergedRing();
                        List<Way> allSourceWays = new ArrayList<>();
                        for (WayChainBuilder.Ring srcRing : group.getSourceRings()) {
                            allSourceWays.addAll(srcRing.getSourceWays());
                            // If this source ring was created by a prior CONSOLIDATE_RINGS,
                            // remove its AddCommand since it's being superseded
                            Way priorWay = ringToWay.get(srcRing);
                            if (priorWay != null) {
                                commands.removeIf(cmd -> cmd instanceof AddCommand
                                    && ((AddCommand) cmd).getParticipatingPrimitives()
                                        .contains(priorWay));
                            }
                        }

                        boolean anyTagged = false;
                        Way reusableWay = null;
                        for (Way src : allSourceWays) {
                            if (hasSignificantTags(src, insignificantTags)) {
                                anyTagged = true;
                            } else if (reusableWay == null
                                    && !isSharedWithOtherRelation(src, relation)) {
                                reusableWay = src;
                            }
                        }

                        Way targetWay;
                        if (anyTagged && reusableWay != null) {
                            Way modified = new Way(reusableWay);
                            modified.setNodes(mergedRing.getNodes());
                            commands.add(new ChangeCommand(reusableWay, modified));
                            targetWay = reusableWay;
                            for (Way src : allSourceWays) {
                                if (src != reusableWay
                                        && !hasSignificantTags(src, insignificantTags)) {
                                    waysToCleanup.add(src);
                                }
                            }
                        } else {
                            targetWay = new Way();
                            targetWay.setNodes(mergedRing.getNodes());
                            commands.add(new AddCommand(ds, targetWay));
                            waysToCleanup.addAll(allSourceWays);
                        }

                        ringToWay.put(mergedRing, targetWay);
                        for (Way src : allSourceWays) {
                            memberReplacements.put(src, targetWay);
                        }
                    }
                }

                case EXTRACT_OUTERS -> {
                    for (WayChainBuilder.Ring ring : op.getRings()) {
                        Way targetWay = ringToWay.get(ring);
                        if (targetWay != null) {
                            // Was consolidated in a prior step — tag the way
                            if (targetWay.getDataSet() != null) {
                                // Reused existing way — must use commands
                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                    commands.add(new ChangePropertyCommand(targetWay, tag.getKey(), tag.getValue()));
                                }
                            } else {
                                targetWay.setKeys(tags);
                            }
                            // Also mark the consolidated way for removal from the relation,
                            // since source ways were already replaced by it
                            membersToRemove.add(targetWay);
                        } else if (ring.isAlreadyClosed()) {
                            Way sourceWay = ring.getSourceWays().get(0);
                            if (hasSignificantTags(sourceWay, insignificantTags)) {
                                // Source way has its own tags — create a new way instead
                                Way newWay = new Way();
                                newWay.setNodes(ring.getNodes());
                                newWay.setKeys(tags);
                                commands.add(new AddCommand(ds, newWay));
                            } else {
                                // Tag existing way in place
                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                    commands.add(new ChangePropertyCommand(sourceWay, tag.getKey(), tag.getValue()));
                                }
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
                            if (targetWay.getDataSet() != null) {
                                // Reused existing way — must use commands
                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                    commands.add(new ChangePropertyCommand(targetWay, tag.getKey(), tag.getValue()));
                                }
                            } else {
                                // New way not yet in DataSet — direct mutation is fine
                                targetWay.setKeys(tags);
                            }
                        } else if (ring.isAlreadyClosed()) {
                            Way sourceWay = ring.getSourceWays().get(0);
                            if (hasSignificantTags(sourceWay, insignificantTags)) {
                                // Source way has its own tags (e.g., natural=coastline) —
                                // create a new way with the same geometry instead of clobbering.
                                Way newWay = new Way();
                                newWay.setNodes(ring.getNodes());
                                newWay.setKeys(tags);
                                commands.add(new AddCommand(ds, newWay));
                            } else {
                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                    commands.add(new ChangePropertyCommand(sourceWay, tag.getKey(), tag.getValue()));
                                }
                            }
                        }
                    }
                    commands.add(new DeleteCommand(relation));
                    relationDeleted = true;
                }

                case TOUCHING_INNER_MERGE -> {
                    // Add any new intersection nodes (e.g., from self-intersecting inner merges)
                    if (op.getNewNodes() != null) {
                        for (Node newNode : op.getNewNodes()) {
                            commands.add(new AddCommand(ds, newNode));
                        }
                    }
                    // Collect all nodes referenced by the new merged ways
                    Set<Node> mergedNodes = new HashSet<>();
                    for (List<Node> wayNodes : op.getMergedWays()) {
                        Way newWay = new Way();
                        newWay.setNodes(wayNodes);
                        newWay.setKeys(tags);
                        commands.add(new AddCommand(ds, newWay));
                        mergedNodes.addAll(wayNodes);
                    }
                    // Collect nodes from source ways and mark unused ones for deferred deletion
                    Set<Node> sourceNodes = new HashSet<>();
                    for (RelationMember member : relation.getMembers()) {
                        if (member.isWay() && !membersToRemove.contains(member.getWay())) {
                            waysToCleanup.add(member.getWay());
                            sourceNodes.addAll(member.getWay().getNodes());
                        }
                    }
                    // Defer node deletion to cleanup pass (after way deletion) so undo restores
                    // nodes before ways, preventing "deleted node referenced" errors
                    for (Node node : sourceNodes) {
                        if (!mergedNodes.contains(node)) {
                            boolean usedElsewhere = node.getReferrers().stream()
                                .anyMatch(r -> r instanceof Way w && !waysToCleanup.contains(w));
                            if (!usedElsewhere) {
                                nodesToCleanup.add(node);
                            }
                        }
                    }
                    commands.add(new DeleteCommand(relation));
                    relationDeleted = true;
                }

                case SPLIT_RELATION -> {
                    Set<Way> waysToRemoveFromRelation = new HashSet<>();
                    Map<Way, Way> splitReplacements = new HashMap<>();
                    // Non-dissolving components that need to become sub-relations
                    List<List<RelationMember>> subRelationMemberLists = new ArrayList<>();

                    for (ComponentResult comp : op.getComponents()) {
                        if (comp == null) continue;

                        Map<WayChainBuilder.Ring, Way> compRingToWay = new HashMap<>();
                        // Track replacements local to this component for sub-relation building
                        Map<Way, Way> compReplacements = new HashMap<>();

                        for (FixOp subOp : comp.getOperations()) {
                            switch (subOp.getType()) {
                                case CONSOLIDATE_RINGS -> {
                                    for (WayChainBuilder.Ring ring : subOp.getRings()) {
                                        Set<Way> sourceSet = new HashSet<>(ring.getSourceWays());
                                        Way existingMatch = findMatchingWayInDataSet(ring.getNodes(), sourceSet);

                                        Way targetWay;
                                        if (existingMatch != null) {
                                            targetWay = existingMatch;
                                            waysToCleanup.addAll(ring.getSourceWays());
                                        } else {
                                            boolean anyTagged = false;
                                            Way reusableWay = null;
                                            for (Way src : ring.getSourceWays()) {
                                                if (hasSignificantTags(src, insignificantTags)) {
                                                    anyTagged = true;
                                                } else if (reusableWay == null && !isSharedWithOtherRelation(src, relation)) {
                                                    reusableWay = src;
                                                }
                                            }

                                            if (anyTagged && reusableWay != null) {
                                                Way modified = new Way(reusableWay);
                                                modified.setNodes(ring.getNodes());
                                                commands.add(new ChangeCommand(reusableWay, modified));
                                                targetWay = reusableWay;
                                                for (Way src : ring.getSourceWays()) {
                                                    if (src != reusableWay && !hasSignificantTags(src, insignificantTags)) {
                                                        waysToCleanup.add(src);
                                                    }
                                                }
                                            } else {
                                                targetWay = new Way();
                                                targetWay.setNodes(ring.getNodes());
                                                commands.add(new AddCommand(ds, targetWay));
                                                waysToCleanup.addAll(ring.getSourceWays());
                                            }
                                        }

                                        compRingToWay.put(ring, targetWay);
                                        for (Way src : ring.getSourceWays()) {
                                            splitReplacements.put(src, targetWay);
                                            compReplacements.put(src, targetWay);
                                        }
                                    }
                                }
                                case CONSOLIDATE_INNERS -> {
                                    for (MultipolygonAnalyzer.ConsolidatedInnerGroup group :
                                            subOp.getConsolidatedInnerGroups()) {
                                        WayChainBuilder.Ring mergedRing = group.getMergedRing();
                                        List<Way> allSrcWays = new ArrayList<>();
                                        for (WayChainBuilder.Ring srcRing : group.getSourceRings()) {
                                            allSrcWays.addAll(srcRing.getSourceWays());
                                            Way priorWay = compRingToWay.get(srcRing);
                                            if (priorWay != null) {
                                                commands.removeIf(cmd -> cmd instanceof AddCommand
                                                    && ((AddCommand) cmd).getParticipatingPrimitives()
                                                        .contains(priorWay));
                                            }
                                        }

                                        boolean anyTaggedSplit = false;
                                        Way reusableWaySplit = null;
                                        for (Way src : allSrcWays) {
                                            if (hasSignificantTags(src, insignificantTags)) {
                                                anyTaggedSplit = true;
                                            } else if (reusableWaySplit == null
                                                    && !isSharedWithOtherRelation(src, relation)) {
                                                reusableWaySplit = src;
                                            }
                                        }

                                        Way targetWay;
                                        if (anyTaggedSplit && reusableWaySplit != null) {
                                            Way modified = new Way(reusableWaySplit);
                                            modified.setNodes(mergedRing.getNodes());
                                            commands.add(new ChangeCommand(reusableWaySplit, modified));
                                            targetWay = reusableWaySplit;
                                            for (Way src : allSrcWays) {
                                                if (src != reusableWaySplit
                                                        && !hasSignificantTags(src, insignificantTags)) {
                                                    waysToCleanup.add(src);
                                                }
                                            }
                                        } else {
                                            targetWay = new Way();
                                            targetWay.setNodes(mergedRing.getNodes());
                                            commands.add(new AddCommand(ds, targetWay));
                                            waysToCleanup.addAll(allSrcWays);
                                        }

                                        compRingToWay.put(mergedRing, targetWay);
                                        for (Way src : allSrcWays) {
                                            splitReplacements.put(src, targetWay);
                                            compReplacements.put(src, targetWay);
                                        }
                                    }
                                }
                                case DECOMPOSE_SELF_INTERSECTIONS -> {
                                    for (MultipolygonAnalyzer.DecomposedRing decomp : subOp.getDecomposedRings()) {
                                        // Remove the AddCommand for the consolidated way
                                        Way consolidatedWay = compRingToWay.get(decomp.getOriginalRing());
                                        if (consolidatedWay != null) {
                                            commands.removeIf(cmd -> cmd instanceof AddCommand
                                                && ((AddCommand) cmd).getParticipatingPrimitives().contains(consolidatedWay));
                                        }
                                        for (Node node : decomp.getNewIntersectionNodes()) {
                                            commands.add(new AddCommand(ds, node));
                                        }
                                        for (WayChainBuilder.Ring subRing : decomp.getSubRings()) {
                                            Way newWay = new Way();
                                            newWay.setNodes(subRing.getNodes());
                                            commands.add(new AddCommand(ds, newWay));
                                            compRingToWay.put(subRing, newWay);
                                        }
                                        waysToCleanup.addAll(decomp.getOriginalRing().getSourceWays());
                                        Way firstSubWay = compRingToWay.get(decomp.getSubRings().get(0));
                                        for (Way src : decomp.getOriginalRing().getSourceWays()) {
                                            splitReplacements.put(src, firstSubWay);
                                            compReplacements.put(src, firstSubWay);
                                        }
                                    }
                                }
                                case EXTRACT_OUTERS -> {
                                    for (WayChainBuilder.Ring ring : subOp.getRings()) {
                                        Way targetWay = compRingToWay.get(ring);
                                        if (targetWay != null) {
                                            if (targetWay.getDataSet() != null) {
                                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                                    commands.add(new ChangePropertyCommand(targetWay, tag.getKey(), tag.getValue()));
                                                }
                                            } else {
                                                targetWay.setKeys(tags);
                                            }
                                        } else if (ring.isAlreadyClosed()) {
                                            Way sourceWay = ring.getSourceWays().get(0);
                                            if (hasSignificantTags(sourceWay, insignificantTags)) {
                                                Way newWay = new Way();
                                                newWay.setNodes(ring.getNodes());
                                                newWay.setKeys(tags);
                                                commands.add(new AddCommand(ds, newWay));
                                            } else {
                                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                                    commands.add(new ChangePropertyCommand(sourceWay, tag.getKey(), tag.getValue()));
                                                }
                                            }
                                        }
                                        waysToRemoveFromRelation.addAll(ring.getSourceWays());
                                    }
                                }
                                case DISSOLVE -> {
                                    for (WayChainBuilder.Ring ring : subOp.getRings()) {
                                        Way targetWay = compRingToWay.get(ring);
                                        if (targetWay != null) {
                                            if (targetWay.getDataSet() != null) {
                                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                                    commands.add(new ChangePropertyCommand(targetWay, tag.getKey(), tag.getValue()));
                                                }
                                            } else {
                                                targetWay.setKeys(tags);
                                            }
                                        } else if (ring.isAlreadyClosed()) {
                                            Way sourceWay = ring.getSourceWays().get(0);
                                            if (hasSignificantTags(sourceWay, insignificantTags)) {
                                                Way newWay = new Way();
                                                newWay.setNodes(ring.getNodes());
                                                newWay.setKeys(tags);
                                                commands.add(new AddCommand(ds, newWay));
                                            } else {
                                                for (Map.Entry<String, String> tag : tags.entrySet()) {
                                                    commands.add(new ChangePropertyCommand(sourceWay, tag.getKey(), tag.getValue()));
                                                }
                                            }
                                        }
                                    }
                                    waysToRemoveFromRelation.addAll(comp.getOuterWays());
                                    waysToRemoveFromRelation.addAll(comp.getInnerWays());
                                }
                                case TOUCHING_INNER_MERGE -> {
                                    if (subOp.getNewNodes() != null) {
                                        for (Node newNode : subOp.getNewNodes()) {
                                            commands.add(new AddCommand(ds, newNode));
                                        }
                                    }
                                    Set<Node> mergedNodesComp = new HashSet<>();
                                    for (List<Node> wayNodes : subOp.getMergedWays()) {
                                        Way newWay = new Way();
                                        newWay.setNodes(wayNodes);
                                        newWay.setKeys(tags);
                                        commands.add(new AddCommand(ds, newWay));
                                        mergedNodesComp.addAll(wayNodes);
                                    }
                                    waysToRemoveFromRelation.addAll(comp.getOuterWays());
                                    waysToRemoveFromRelation.addAll(comp.getInnerWays());
                                    waysToCleanup.addAll(comp.getOuterWays());
                                    waysToCleanup.addAll(comp.getInnerWays());
                                    // Defer node deletion to cleanup pass (after way deletion)
                                    Set<Node> sourceNodesComp = new HashSet<>();
                                    for (Way w : comp.getOuterWays()) sourceNodesComp.addAll(w.getNodes());
                                    for (Way w : comp.getInnerWays()) sourceNodesComp.addAll(w.getNodes());
                                    for (Node node : sourceNodesComp) {
                                        if (!mergedNodesComp.contains(node)) {
                                            boolean usedElsewhere = node.getReferrers().stream()
                                                .anyMatch(r -> r instanceof Way w && !waysToCleanup.contains(w));
                                            if (!usedElsewhere) {
                                                nodesToCleanup.add(node);
                                            }
                                        }
                                    }
                                }
                                default -> { }
                            }
                        }

                        // Non-dissolving component: build member list for a sub-relation
                        if (!comp.dissolvesCompletely()) {
                            Set<Way> compWaySet = new HashSet<>();
                            compWaySet.addAll(comp.getOuterWays());
                            compWaySet.addAll(comp.getInnerWays());
                            List<RelationMember> subMembers = new ArrayList<>();
                            Set<Way> alreadyReplacedInComp = new HashSet<>();
                            Set<Way> alreadyAddedInComp = new HashSet<>();

                            for (RelationMember member : relation.getMembers()) {
                                if (!member.isWay()) continue;
                                Way way = member.getWay();
                                if (!compWaySet.contains(way)) continue;

                                if (compReplacements.containsKey(way)) {
                                    Way replacement = compReplacements.get(way);
                                    if (!alreadyReplacedInComp.contains(replacement)) {
                                        subMembers.add(new RelationMember(member.getRole(), replacement));
                                        alreadyReplacedInComp.add(replacement);
                                    }
                                } else if (alreadyAddedInComp.add(way)) {
                                    subMembers.add(member);
                                }
                            }
                            if (!subMembers.isEmpty()) {
                                subRelationMemberLists.add(subMembers);
                            }
                            // Mark these ways for removal from the original relation
                            waysToRemoveFromRelation.addAll(comp.getOuterWays());
                            waysToRemoveFromRelation.addAll(comp.getInnerWays());
                        }
                    }

                    // Create sub-relations: keep the largest in the original relation,
                    // create new relations for the rest
                    if (!subRelationMemberLists.isEmpty()) {
                        // Find the largest sub-relation to keep in the original
                        int largestIdx = 0;
                        for (int i = 1; i < subRelationMemberLists.size(); i++) {
                            if (subRelationMemberLists.get(i).size() > subRelationMemberLists.get(largestIdx).size()) {
                                largestIdx = i;
                            }
                        }

                        // Create new sub-relations for all except the largest
                        Map<String, String> relTags = new java.util.LinkedHashMap<>(relation.getKeys());
                        for (int i = 0; i < subRelationMemberLists.size(); i++) {
                            if (i == largestIdx) continue;
                            Relation subRel = new Relation();
                            subRel.setKeys(relTags);
                            subRel.setMembers(subRelationMemberLists.get(i));
                            commands.add(new AddCommand(ds, subRel));
                            // Record retained ways in sub-relations
                            for (RelationMember m : subRelationMemberLists.get(i)) {
                                if (m.isWay()) {
                                    waysRetainedByPlans.add(m.getWay());
                                }
                            }
                        }

                        // Un-mark the largest component's ways from removal —
                        // they stay in the original relation.
                        // Un-mark both the final member ways (replacements + inners)
                        // AND their source ways so that source ways can be found
                        // by splitReplacements during the member update loop.
                        List<RelationMember> keptMembers = subRelationMemberLists.get(largestIdx);
                        Set<Way> keptWays = new HashSet<>();
                        for (RelationMember m : keptMembers) {
                            if (m.isWay()) {
                                keptWays.add(m.getWay());
                                waysToRemoveFromRelation.remove(m.getWay());
                            }
                        }
                        // Also un-mark source ways that map to kept replacement ways
                        for (Map.Entry<Way, Way> entry : splitReplacements.entrySet()) {
                            if (keptWays.contains(entry.getValue())) {
                                waysToRemoveFromRelation.remove(entry.getKey());
                            }
                        }
                    }

                    // Modify the original relation: remove consumed/split-out members,
                    // replace consolidated ones
                    Relation modified = new Relation(relation);
                    List<RelationMember> splitMembers = new ArrayList<>();
                    Set<Way> alreadyReplacedInSplit = new HashSet<>();
                    Set<Way> alreadyAddedInSplit = new HashSet<>();

                    for (RelationMember member : relation.getMembers()) {
                        if (!member.isWay()) {
                            splitMembers.add(member);
                            continue;
                        }
                        Way way = member.getWay();

                        if (waysToRemoveFromRelation.contains(way)) {
                            continue;
                        }

                        if (splitReplacements.containsKey(way)) {
                            Way replacement = splitReplacements.get(way);
                            if (!alreadyReplacedInSplit.contains(replacement)) {
                                splitMembers.add(new RelationMember(member.getRole(), replacement));
                                alreadyReplacedInSplit.add(replacement);
                            }
                            continue;
                        }

                        if (!alreadyAddedInSplit.add(way)) {
                            continue;
                        }
                        splitMembers.add(member);
                    }

                    if (splitMembers.isEmpty()) {
                        commands.add(new DeleteCommand(relation));
                        relationDeleted = true;
                    } else {
                        modified.setMembers(splitMembers);
                        commands.add(new ChangeCommand(relation, modified));
                        // Record retained ways in the surviving original relation
                        for (RelationMember m : splitMembers) {
                            if (m.isWay()) {
                                waysRetainedByPlans.add(m.getWay());
                            }
                        }
                    }
                    splitHandled = true;
                }
            }
        }

        // If the relation survives (partial ops), apply accumulated member changes
        if (!relationDeleted && (!membersToRemove.isEmpty() || !memberReplacements.isEmpty() || !extraMembers.isEmpty())) {
            Relation modified = new Relation(relation);
            List<RelationMember> newMembers = new ArrayList<>();
            Set<Way> alreadyReplaced = new HashSet<>();
            Set<Way> alreadyAdded = new HashSet<>();

            for (RelationMember member : relation.getMembers()) {
                if (!member.isWay()) {
                    newMembers.add(member);
                    continue;
                }
                Way way = member.getWay();

                // Was this way replaced by a consolidated ring?
                if (memberReplacements.containsKey(way)) {
                    Way replacement = memberReplacements.get(way);
                    // Skip if the replacement was itself extracted (e.g., consolidated then extracted)
                    if (!membersToRemove.contains(replacement)
                            && !alreadyReplaced.contains(replacement)) {
                        newMembers.add(new RelationMember(member.getRole(), replacement));
                        alreadyReplaced.add(replacement);
                    }
                    continue;
                }

                // Was this way extracted?
                if (membersToRemove.contains(way)) {
                    continue;
                }

                // Deduplicate: skip if this way was already added (handles input relations
                // with the same way listed as a member multiple times)
                if (!alreadyAdded.add(way)) {
                    continue;
                }

                newMembers.add(member);
            }
            // Add any extra members (e.g., additional decomposed sub-ring ways)
            newMembers.addAll(extraMembers);
            modified.setMembers(newMembers);
            commands.add(new ChangeCommand(relation, modified));

            // Record retained way members so the cross-plan cleanup pass
            // does not delete ways still needed by this surviving relation
            for (RelationMember m : newMembers) {
                if (m.isWay()) {
                    waysRetainedByPlans.add(m.getWay());
                }
            }
        } else if (!relationDeleted && !splitHandled) {
            // Relation survives without member changes — all current members are retained
            for (RelationMember m : relation.getMembers()) {
                if (m.isWay()) {
                    waysRetainedByPlans.add(m.getWay());
                }
            }
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

    /**
     * Returns true if the given way is referenced by any relation other than {@code currentRelation}.
     * A shared way must not be reused (geometry-changed) because other relations depend on its nodes.
     */
    private static boolean isSharedWithOtherRelation(Way way, Relation currentRelation) {
        for (OsmPrimitive referrer : way.getReferrers()) {
            if (referrer instanceof Relation r && r != currentRelation) {
                return true;
            }
        }
        return false;
    }

    static boolean hasSignificantTags(Way way, Set<String> insignificantTags) {
        for (String key : way.getKeys().keySet()) {
            if (!insignificantTags.contains(key)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> getInsignificantTagsForMultipolygon() {
        return loadInsignificantTags(MultipolyGonePreferences.PREF_INSIGNIFICANT_TAGS_MP);
    }

    static Set<String> getInsignificantTagsForBoundary() {
        return loadInsignificantTags(MultipolyGonePreferences.PREF_INSIGNIFICANT_TAGS_BOUNDARY);
    }

    private static Set<String> loadInsignificantTags(String prefKey) {
        Set<String> tags = new HashSet<>();

        String pref = Config.getPref().get(prefKey,
            MultipolyGonePreferences.DEFAULT_INSIGNIFICANT_TAGS);
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

    /**
     * Searches the DataSet for a closed way whose node sequence represents
     * the same cyclic ring as {@code ringNodes}, considering rotations and
     * reversals. Candidate ways that are source ways of this ring are excluded.
     *
     * @param ringNodes  the closed node list [A,B,C,...,A] (first == last)
     * @param sourceWays ways being consumed to form this ring (excluded from search)
     * @return matching Way if found, or null
     */
    static Way findMatchingWayInDataSet(List<Node> ringNodes, Set<Way> sourceWays) {
        int ringSize = ringNodes.size();
        Node anchor = ringNodes.get(0);
        for (OsmPrimitive referrer : anchor.getReferrers()) {
            if (!(referrer instanceof Way candidate)) continue;
            if (candidate.isDeleted() || candidate.isIncomplete()) continue;
            if (sourceWays.contains(candidate)) continue;
            if (!candidate.isClosed()) continue;
            if (candidate.getNodesCount() != ringSize) continue;
            if (ringsMatch(ringNodes, candidate.getNodes())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns true if {@code candidateNodes} represents the same cyclic ring
     * as {@code ringNodes} (same nodes in same cyclic order, forward or reverse).
     * Both lists must have the same length and represent closed ways (first == last).
     */
    static boolean ringsMatch(List<Node> ringNodes, List<Node> candidateNodes) {
        int n = ringNodes.size() - 1; // unique node count (exclude closing duplicate)
        Node startNode = ringNodes.get(0);
        for (int startIdx = 0; startIdx < n; startIdx++) {
            if (!candidateNodes.get(startIdx).equals(startNode)) continue;
            // Try forward direction
            boolean forward = true;
            for (int i = 0; i < n && forward; i++) {
                if (!candidateNodes.get((startIdx + i) % n).equals(ringNodes.get(i))) {
                    forward = false;
                }
            }
            if (forward) return true;
            // Try reverse direction
            boolean reverse = true;
            for (int i = 0; i < n && reverse; i++) {
                if (!candidateNodes.get((startIdx - i + n) % n).equals(ringNodes.get(i))) {
                    reverse = false;
                }
            }
            if (reverse) return true;
        }
        return false;
    }
}
