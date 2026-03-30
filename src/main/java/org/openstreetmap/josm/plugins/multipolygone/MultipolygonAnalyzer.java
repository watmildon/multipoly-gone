package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.tools.Logging;

public class MultipolygonAnalyzer {

    private static final int MAX_WAY_NODES = 2000;

    /**
     * Returns true if the debug mode preference is enabled.
     */
    static boolean isDebugMode() {
        return Config.getPref().getBoolean(MultipolyGonePreferences.PREF_DEBUG_MODE, false);
    }

    /**
     * Default tag key prefixes/patterns that indicate a relation has a unified
     * real-world identity and should not be dissolved into separate independent ways.
     * Semicolon-delimited. Supports prefix matching with trailing '*' (e.g. "name:*"
     * matches name:en, name:fr, etc.).
     */
    static final String DEFAULT_IDENTITY_TAGS =
        // Naming and external references
        "name;name:*;alt_name;old_name;loc_name;short_name;official_name;reg_name;nat_name;int_name;"
        + "wikidata;wikipedia;ref;ref:*;"
        // Administrative and organizational
        + "place;boundary;admin_level;protect_class;"
        + "operator;brand;owner;capacity;"
        // Features that typically represent a specific named place, often mapped
        // without a name in hopes someone will add it later
        + "tourism=artwork;tourism=museum;tourism=attraction;"
        + "leisure=park;leisure=nature_reserve;leisure=golf_course;leisure=sports_centre;leisure=stadium;"
        + "amenity=school;amenity=university;amenity=hospital;amenity=place_of_worship;"
        + "landuse=cemetery;landuse=military;"
        + "man_made=geoglyph;"
        + "historic=monument;historic=memorial;historic=castle;historic=archaeological_site;historic=ruins;"
        + "aeroway=aerodrome;"
        // Tags implying a countable/specific installation
        + "generator:solar:modules;student:count;personnel:count;"
        // Mapper notes — signal that someone is tracking a specific feature;
        // a mapper can remove these then break apart the relation
        + "note;description;fixme";

    /**
     * Returns true if the relation has any tag key that matches the configured
     * identity tag patterns. Relations with identity tags represent a unified
     * real-world feature and should not be dissolved into separate ways.
     */
    static boolean hasIdentityTags(Relation relation) {
        Set<String> patterns = getIdentityTagPatterns();
        for (Map.Entry<String, String> entry : relation.getKeys().entrySet()) {
            if (matchesAnyPattern(entry.getKey(), entry.getValue(), patterns)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the set of identity tag patterns from preferences, falling back to defaults.
     */
    static Set<String> getIdentityTagPatterns() {
        Set<String> patterns = new HashSet<>();
        String pref = Config.getPref().get(
            MultipolyGonePreferences.PREF_IDENTITY_TAGS, DEFAULT_IDENTITY_TAGS);
        for (String tag : pref.split(";")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    /**
     * Returns true if the given key (and value) matches any of the patterns.
     * Supported pattern forms:
     * - "key" — matches any tag with that key
     * - "key:*" — prefix match on the key (e.g. "name:*" matches "name:en")
     * - "key=value" — matches only when both key and value match
     */
    private static boolean matchesAnyPattern(String key, String value, Set<String> patterns) {
        for (String pattern : patterns) {
            int eqIdx = pattern.indexOf('=');
            if (eqIdx >= 0) {
                // key=value pattern
                String patKey = pattern.substring(0, eqIdx);
                String patVal = pattern.substring(eqIdx + 1);
                if (patKey.equals(key) && patVal.equals(value)) {
                    return true;
                }
            } else if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (key.startsWith(prefix)) {
                    return true;
                }
            } else if (pattern.equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static List<FixPlan> findFixableRelations(DataSet dataSet) {
        return analyzeAll(dataSet, null).getFixPlans();
    }

    /**
     * Finds fixable relations in the dataset, optionally shuffling member order
     * to perturb HashMap/HashSet iteration order for non-determinism detection.
     * @param dataSet the dataset to analyze
     * @param rng if non-null, shuffles way lists before analysis to expose order-dependent bugs
     */
    public static List<FixPlan> findFixableRelations(DataSet dataSet, Random rng) {
        return analyzeAll(dataSet, rng).getFixPlans();
    }

    /**
     * Analyzes all multipolygon/boundary relations in the dataset, returning both
     * fixable plans and skip reasons for relations that cannot be fixed.
     */
    public static AnalysisResult analyzeAll(DataSet dataSet, Random rng) {
        boolean debug = isDebugMode();
        List<FixPlan> fixPlans = new ArrayList<>();
        List<SkipResult> skipResults = new ArrayList<>();
        if (dataSet == null) {
            return new AnalysisResult(fixPlans, skipResults);
        }

        // Pre-pass: detect cross-relation duplicate ways
        // Index by relation so we can merge into per-relation plans below
        Map<Relation, FixPlan> dedupByRelation = new HashMap<>();
        for (FixPlan dp : findDuplicateWays(dataSet)) {
            dedupByRelation.put(dp.getRelation(), dp);
        }

        List<Relation> relations = new ArrayList<>(dataSet.getRelations());
        if (rng != null) {
            Collections.shuffle(relations, rng);
        }

        for (Relation relation : relations) {
            if (relation.isDeleted() || relation.isIncomplete()) {
                continue;
            }
            String relType = relation.get("type");
            if (!"multipolygon".equals(relType) && !"boundary".equals(relType)) {
                continue;
            }

            AnalyzeOutcome outcome = analyze(relation, rng);
            FixPlan dedupPlan = dedupByRelation.get(relation);

            if (outcome.plan != null) {
                if (dedupPlan != null) {
                    // Merge: prepend dedup ops to the per-relation plan
                    List<FixOp> merged = new ArrayList<>(dedupPlan.getOperations());
                    merged.addAll(outcome.plan.getOperations());
                    String desc = dedupPlan.getDescription() + " + " + outcome.plan.getDescription();
                    fixPlans.add(new FixPlan(relation, merged, desc, outcome.plan.isBoundary()));
                } else {
                    fixPlans.add(outcome.plan);
                }
                if (debug) {
                    Logging.info("Multipoly-Gone DEBUG: plan for relation {0}: {1}",
                        relation.getUniqueId(),
                        fixPlans.get(fixPlans.size() - 1).fingerprint());
                }
            } else if (dedupPlan != null) {
                // Relation has no per-relation fix, but has dedup → standalone dedup plan
                fixPlans.add(dedupPlan);
            } else if (outcome.skip != null) {
                skipResults.add(outcome.skip);
            }
        }
        if (debug) {
            Logging.info("Multipoly-Gone DEBUG: found {0} fixable relations, {1} skipped",
                fixPlans.size(), skipResults.size());
        }
        return new AnalysisResult(fixPlans, skipResults);
    }

    /**
     * Finds closed ways with identical geometry that are members of different
     * multipolygon/boundary relations. For each group of duplicates, picks a
     * survivor and emits a DEDUPLICATE_WAYS plan for each affected relation.
     *
     * Survivor selection: if exactly one way has own tags, it survives.
     * If none have own tags, the way with the lowest ID survives.
     * If multiple have own tags, the group is skipped (can't consolidate).
     */
    static List<FixPlan> findDuplicateWays(DataSet dataSet) {
        // Collect all closed ways that are members of MP/boundary relations
        // Map: way -> set of relations it belongs to
        Map<Way, Set<Relation>> wayToRelations = new HashMap<>();
        for (Relation relation : dataSet.getRelations()) {
            if (relation.isDeleted() || relation.isIncomplete()) continue;
            String relType = relation.get("type");
            if (!"multipolygon".equals(relType) && !"boundary".equals(relType)) continue;

            for (RelationMember member : relation.getMembers()) {
                if (!member.isWay()) continue;
                Way way = member.getWay();
                if (way.isDeleted() || way.isIncomplete() || !way.isClosed()) continue;
                wayToRelations.computeIfAbsent(way, k -> new HashSet<>()).add(relation);
            }
        }

        // Group ways by canonical geometry key.
        // Key: sorted set of node IDs (order-independent since cyclic rings can start anywhere
        // and go in either direction — two rings with the same node set are identical).
        Map<Set<Long>, List<Way>> geometryGroups = new HashMap<>();
        for (Way way : wayToRelations.keySet()) {
            Set<Long> nodeIdSet = new HashSet<>();
            // Exclude closing duplicate node (first == last for closed ways)
            List<Node> nodes = way.getNodes();
            for (int i = 0; i < nodes.size() - 1; i++) {
                nodeIdSet.add(nodes.get(i).getUniqueId());
            }
            geometryGroups.computeIfAbsent(nodeIdSet, k -> new ArrayList<>()).add(way);
        }

        // For each group of 2+ ways, verify actual cyclic match and pick survivor
        List<FixPlan> plans = new ArrayList<>();
        for (List<Way> group : geometryGroups.values()) {
            if (group.size() < 2) continue;

            // Verify geometry match pairwise against the first way (node-set match
            // is necessary but not sufficient — node order matters for ring identity)
            List<Way> verified = new ArrayList<>();
            verified.add(group.get(0));
            List<Node> refNodes = group.get(0).getNodes();
            for (int i = 1; i < group.size(); i++) {
                if (MultipolygonFixer.ringsMatch(refNodes, group.get(i).getNodes())) {
                    verified.add(group.get(i));
                }
            }
            if (verified.size() < 2) continue;

            // Pick survivor: prefer tagged way, else lowest ID
            Way tagged = null;
            boolean multipleTagged = false;
            for (Way w : verified) {
                if (wayHasOwnTags(w)) {
                    if (tagged != null) {
                        multipleTagged = true;
                        break;
                    }
                    tagged = w;
                }
            }
            if (multipleTagged) continue; // can't consolidate

            Way survivor = tagged != null ? tagged : verified.stream()
                .min(java.util.Comparator.comparingLong(Way::getUniqueId)).orElseThrow();

            // Emit a plan for each relation that has a duplicate (non-survivor) way
            for (Way duplicate : verified) {
                if (duplicate == survivor) continue;
                for (Relation relation : wayToRelations.get(duplicate)) {
                    List<DuplicateWayReplacement> replacements = new ArrayList<>();
                    replacements.add(new DuplicateWayReplacement(duplicate, survivor));
                    FixOp op = new FixOp(FixOpType.DEDUPLICATE_WAYS, replacements, true);
                    String desc = "Deduplicate: replace way " + duplicate.getUniqueId()
                        + " with " + survivor.getUniqueId();
                    plans.add(new FixPlan(relation, List.of(op), desc,
                        "boundary".equals(relation.get("type"))));
                }
            }
        }
        return plans;
    }

    /** Internal union type: either a FixPlan (fixable) or a SkipResult (not fixable). */
    private static class AnalyzeOutcome {
        final FixPlan plan;
        final SkipResult skip;

        private AnalyzeOutcome(FixPlan plan, SkipResult skip) {
            this.plan = plan;
            this.skip = skip;
        }

        static AnalyzeOutcome fix(FixPlan plan) {
            return new AnalyzeOutcome(plan, null);
        }

        static AnalyzeOutcome skip(Relation relation, SkipReason reason, String detail) {
            return new AnalyzeOutcome(null, new SkipResult(relation, reason, detail));
        }
    }

    /**
     * Re-analyzes a single relation with current geometry.
     * Used at fix time to ensure plans reflect the latest dataset state (issue #14).
     * @return a fresh FixPlan, or null if the relation is no longer fixable
     */
    public static FixPlan reanalyze(Relation relation) {
        AnalyzeOutcome outcome = analyze(relation, null);
        return outcome.plan;
    }

    private static AnalyzeOutcome analyze(Relation relation, Random rng) {
        boolean isBoundary = "boundary".equals(relation.get("type"));

        // Skip relations with incomplete members — we can't see all geometry
        if (relation.hasIncompleteMembers()) {
            return AnalyzeOutcome.skip(relation, SkipReason.INCOMPLETE_MEMBERS, null);
        }

        List<Way> outerWays = new ArrayList<>();
        List<Way> innerWays = new ArrayList<>();
        Set<Way> innerWaySet = new HashSet<>();

        for (RelationMember member : relation.getMembers()) {
            if (!member.isWay()) {
                if (isBoundary) {
                    continue; // skip non-Way members (label nodes, admin_centre, etc.)
                }
                return AnalyzeOutcome.skip(relation, SkipReason.NON_WAY_MEMBER,
                    "member type=" + member.getType() + " role=" + member.getRole());
            }
            Way way = member.getWay();
            if (way.isDeleted() || way.isIncomplete() || way.getNodesCount() < 2) {
                return AnalyzeOutcome.skip(relation, SkipReason.DELETED_OR_INCOMPLETE_WAY,
                    "way " + way.getUniqueId());
            }
            String role = member.getRole();
            if (!"outer".equals(role) && !"inner".equals(role) && !"".equals(role)) {
                return AnalyzeOutcome.skip(relation, SkipReason.UNEXPECTED_WAY_ROLE,
                    "way " + way.getUniqueId() + " role=" + role);
            }
            if ("inner".equals(role)) {
                innerWays.add(way);
                innerWaySet.add(way);
            } else {
                outerWays.add(way);
            }
        }

        if (outerWays.isEmpty()) {
            return AnalyzeOutcome.skip(relation, SkipReason.NO_OUTER_WAYS, null);
        }

        // Shuffle way lists to perturb downstream HashMap/HashSet iteration order
        if (rng != null) {
            Collections.shuffle(outerWays, rng);
            Collections.shuffle(innerWays, rng);
        }

        boolean identityProtected = hasIdentityTags(relation);
        // Boundaries are always identity-protected (never dissolved or split)
        if (isBoundary) {
            identityProtected = true;
        }
        // Relations that are members of other relations cannot be safely dissolved
        // or split — doing so would break the parent relation's member references.
        boolean parentProtected = relation.getReferrers().stream()
            .anyMatch(r -> r instanceof Relation && !r.isDeleted());

        // Check if open outer ways form multiple disconnected ring groups.
        // If so, split into sub-relations up front so we reach the simplest
        // form in one pass. Without this, buildRings() succeeds globally on
        // disconnected ring groups, producing a consolidated relation that
        // still needs splitting on a second pass.
        // Already-closed outers are handled fine by the single-relation path
        // (EXTRACT_OUTERS / DISSOLVE), so we only check open ways.
        // Identity-protected relations and boundaries are never split.
        if (!identityProtected && !parentProtected) {
            List<Way> openOuterWays = new ArrayList<>();
            for (Way w : outerWays) {
                if (!w.isClosed()) {
                    openOuterWays.add(w);
                }
            }

            if (!openOuterWays.isEmpty()) {
                List<Set<Way>> openComponents = findConnectedComponents(openOuterWays);

                if (openComponents.size() > 1) {
                    // Open outers form multiple disconnected ring groups.
                    // Rebuild full component sets including all ways.
                    List<Way> allWays = new ArrayList<>(outerWays);
                    allWays.addAll(innerWays);
                    if (rng != null) {
                        Collections.shuffle(allWays, rng);
                    }
                    List<Set<Way>> components = findConnectedComponents(allWays);

                    if (rng != null) {
                        Collections.shuffle(components, rng);
                    }
                    // Multiple components: analyze each independently
                    return analyzeMultiComponent(relation, components, innerWaySet);
                }
            }
        }

        // Single connected outer group (or identity-protected/boundary):
        // use the single-relation path.
        SkipReason[] singleSkip = new SkipReason[1];
        ComponentResult singleComp = analyzeComponent(outerWays, innerWays,
            identityProtected, isBoundary, parentProtected, singleSkip);
        if (singleComp != null) {
            // Post-consolidation split check: after consolidation closes open ways
            // into rings, previously-connected outers may become disconnected components.
            // If so, split into sub-relations now instead of requiring a second pass.
            if (!singleComp.dissolvesCompletely() && !identityProtected && !parentProtected
                    && singleComp.getRetainedOuterRings() != null
                    && singleComp.getRetainedOuterRings().size() > 1) {
                AnalyzeOutcome splitOutcome = tryPostConsolidationSplit(
                    relation, singleComp, innerWaySet, rng);
                if (splitOutcome != null) {
                    return splitOutcome;
                }
            }

            // No split needed — wrap as a single-relation plan
            String primaryTag = getPrimaryTag(relation);
            String desc = singleComp.getDescription().isEmpty()
                ? primaryTag
                : singleComp.getDescription() + " (" + primaryTag + ")";
            FixPlan singleResult = new FixPlan(relation, singleComp.getOperations(), desc, isBoundary);
            singleResult.validate();
            return AnalyzeOutcome.fix(singleResult);
        }

        // Single-relation path failed (e.g., multiple closed outers with inners).
        // Try splitting into disconnected components as a fallback.
        // Don't split if the failure was NESTED_OUTER_RINGS — that's a structural error
        // that splitting would mask by putting each outer in its own component.
        if (!identityProtected && !parentProtected && singleSkip[0] != SkipReason.NESTED_OUTER_RINGS) {
            List<Way> allWays = new ArrayList<>(outerWays);
            allWays.addAll(innerWays);
            if (rng != null) {
                Collections.shuffle(allWays, rng);
            }
            List<Set<Way>> components = findConnectedComponents(allWays);

            if (components.size() > 1) {
                if (rng != null) {
                    Collections.shuffle(components, rng);
                }
                return analyzeMultiComponent(relation, components, innerWaySet);
            }
        }

        // Not splittable — report the skip reason.
        // Prefer the specific reason from component analysis; fall back to structural reasons.
        SkipReason reason;
        if (singleSkip[0] == SkipReason.HAS_PARENT_RELATION || parentProtected) {
            reason = SkipReason.HAS_PARENT_RELATION;
        } else if (singleSkip[0] != null) {
            reason = singleSkip[0];
        } else if (identityProtected) {
            reason = SkipReason.IDENTITY_PROTECTED_MULTI_COMPONENT;
        } else {
            reason = SkipReason.SINGLE_COMPONENT_NOT_FIXABLE;
        }
        return AnalyzeOutcome.skip(relation, reason, null);
    }

    /**
     * Original single-component analysis path. Unchanged from before SPLIT_RELATION.
     * @param skipOut if non-null and the component is not fixable, skipOut[0] is set to the reason
     */
    private static FixPlan analyzeSingleRelation(Relation relation, List<Way> outerWays,
            List<Way> innerWays, boolean identityProtected, boolean isBoundary,
            boolean parentProtected, SkipReason[] skipOut) {
        ComponentResult result = analyzeComponent(outerWays, innerWays, identityProtected, isBoundary,
            parentProtected, skipOut);
        if (result == null) {
            return null;
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = result.getDescription().isEmpty()
            ? primaryTag
            : result.getDescription() + " (" + primaryTag + ")";

        FixPlan plan = new FixPlan(relation, result.getOperations(), desc, isBoundary);
        plan.validate();
        return plan;
    }

    /**
     * Analyze a relation with multiple disconnected components.
     * Inner-only components (no outers) are assigned to the outer component
     * that spatially contains them.
     */
    private static AnalyzeOutcome analyzeMultiComponent(Relation relation, List<Set<Way>> components, Set<Way> innerWaySet) {
        boolean debug = isDebugMode();
        // Separate components into outer-bearing and inner-only
        List<List<Way>> outerComponents = new ArrayList<>();
        List<List<Way>> outerCompInners = new ArrayList<>();
        List<List<Way>> innerOnlyComponents = new ArrayList<>();

        for (Set<Way> component : components) {
            List<Way> compOuters = new ArrayList<>();
            List<Way> compInners = new ArrayList<>();
            for (Way w : component) {
                if (innerWaySet.contains(w)) {
                    compInners.add(w);
                } else {
                    compOuters.add(w);
                }
            }
            if (compOuters.isEmpty()) {
                innerOnlyComponents.add(compInners);
            } else {
                outerComponents.add(compOuters);
                outerCompInners.add(compInners);
            }
        }

        if (debug) {
            Logging.info("Multipoly-Gone DEBUG: relation {0} split into {1} outer components, {2} inner-only components",
                relation.getUniqueId(), outerComponents.size(), innerOnlyComponents.size());
            for (int c = 0; c < outerComponents.size(); c++) {
                TreeSet<Long> outerIds = new TreeSet<>();
                for (Way w : outerComponents.get(c)) outerIds.add(w.getUniqueId());
                TreeSet<Long> innerIds = new TreeSet<>();
                for (Way w : outerCompInners.get(c)) innerIds.add(w.getUniqueId());
                Logging.info("  component {0}: outers={1} inners={2}", c, outerIds, innerIds);
            }
        }

        if (outerComponents.size() <= 1 && innerOnlyComponents.isEmpty()) {
            // Degenerate: only one real component, shouldn't normally happen
            List<Way> allOuters = outerComponents.isEmpty() ? new ArrayList<>() : outerComponents.get(0);
            List<Way> allInners = outerCompInners.isEmpty() ? new ArrayList<>() : outerCompInners.get(0);
            SkipReason[] skipOut = new SkipReason[1];
            FixPlan plan = analyzeSingleRelation(relation, allOuters, allInners, false, false, false, skipOut);
            if (plan != null) return AnalyzeOutcome.fix(plan);
            return AnalyzeOutcome.skip(relation,
                skipOut[0] != null ? skipOut[0] : SkipReason.SINGLE_COMPONENT_NOT_FIXABLE, null);
        }

        // Build outer rings for each outer component so we can do spatial containment
        List<List<WayChainBuilder.Ring>> outerRingsPerComp = new ArrayList<>();
        for (int c = 0; c < outerComponents.size(); c++) {
            List<Way> outers = outerComponents.get(c);
            List<Way> inners = outerCompInners.get(c);
            Optional<List<WayChainBuilder.Ring>> ringsOpt = WayChainBuilder.buildRings(outers);
            if (ringsOpt.isEmpty() && !inners.isEmpty()) {
                List<Way> adjOuters = new ArrayList<>(outers);
                List<Way> adjInners = new ArrayList<>(inners);
                if (reclassifyBridgingInners(adjOuters, adjInners)) {
                    ringsOpt = WayChainBuilder.buildRings(adjOuters);
                }
            }
            outerRingsPerComp.add(ringsOpt.orElse(null));
        }

        // Assign inner-only components to outer components via spatial containment
        // Build a set of all outer ring nodes so we can skip boundary nodes
        Set<Node> allOuterNodes = new HashSet<>();
        for (List<WayChainBuilder.Ring> rings : outerRingsPerComp) {
            if (rings == null) continue;
            for (WayChainBuilder.Ring ring : rings) {
                allOuterNodes.addAll(ring.getNodes());
            }
        }

        for (List<Way> innerComp : innerOnlyComponents) {
            int bestComp = -1;

            // Find a test node from the inner component that is NOT on any outer boundary
            Node testNode = null;
            for (Way w : innerComp) {
                for (Node n : w.getNodes()) {
                    if (!allOuterNodes.contains(n)) {
                        testNode = n;
                        break;
                    }
                }
                if (testNode != null) break;
            }

            if (testNode != null) {
                for (int c = 0; c < outerComponents.size(); c++) {
                    List<WayChainBuilder.Ring> rings = outerRingsPerComp.get(c);
                    if (rings == null) continue;
                    for (WayChainBuilder.Ring ring : rings) {
                        if (nodeInsidePolygon(testNode, ring.getNodes())) {
                            bestComp = c;
                            break;
                        }
                    }
                    if (bestComp >= 0) break;
                }
            }

            if (bestComp >= 0) {
                outerCompInners.get(bestComp).addAll(innerComp);
                if (debug) {
                    TreeSet<Long> innerIds = new TreeSet<>();
                    for (Way w : innerComp) innerIds.add(w.getUniqueId());
                    Logging.info("  inner-only component {0} assigned to outer component {1}",
                        innerIds, bestComp);
                }
            } else if (debug) {
                TreeSet<Long> innerIds = new TreeSet<>();
                for (Way w : innerComp) innerIds.add(w.getUniqueId());
                Logging.info("  inner-only component {0} orphaned (no containing outer found)", innerIds);
            }
            // If no containing outer found, the inner-only component is orphaned — skip it
        }

        // If only 1 outer component after inner assignment, use the simple single-relation path
        if (outerComponents.size() == 1) {
            SkipReason[] skipOut = new SkipReason[1];
            FixPlan plan = analyzeSingleRelation(relation, outerComponents.get(0), outerCompInners.get(0),
                false, false, false, skipOut);
            if (plan != null) return AnalyzeOutcome.fix(plan);
            return AnalyzeOutcome.skip(relation,
                skipOut[0] != null ? skipOut[0] : SkipReason.SINGLE_COMPONENT_NOT_FIXABLE, null);
        }

        // Now analyze each outer component with its assigned inners
        List<ComponentResult> results = new ArrayList<>();
        int dissolvedCount = 0;
        int retainedCount = 0;

        for (int c = 0; c < outerComponents.size(); c++) {
            List<Way> compOuters = outerComponents.get(c);
            List<Way> compInners = outerCompInners.get(c);
            ComponentResult cr = analyzeComponent(compOuters, compInners, false, false, false, null);
            if (cr == null && !compInners.isEmpty()) {
                // Component can't be simplified internally, but in a multi-component split
                // it still needs to become its own sub-relation. Create a no-op result.
                cr = new ComponentResult(compOuters, compInners, new ArrayList<>(), false,
                    null, null, "retained as sub-relation");
            }
            results.add(cr);
            if (cr != null) {
                if (debug) {
                    Logging.info("  component {0} result: {1} — {2}",
                        c, cr.dissolvesCompletely() ? "dissolves" : "retained", cr.getDescription());
                }
                if (cr.dissolvesCompletely()) {
                    dissolvedCount++;
                } else {
                    retainedCount++;
                }
            } else if (debug) {
                Logging.info("  component {0} result: null (not fixable)", c);
            }
        }

        // A split is only worthwhile if there are multiple outer components
        // and at least one component is actually fixable
        if (outerComponents.size() < 2) {
            return AnalyzeOutcome.skip(relation, SkipReason.MULTI_COMPONENT_SPLIT_NOT_WORTHWHILE, null);
        }
        boolean anyNonNull = results.stream().anyMatch(r -> r != null);
        if (!anyNonNull) {
            return AnalyzeOutcome.skip(relation, SkipReason.MULTI_COMPONENT_SPLIT_NOT_WORTHWHILE, null);
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = String.format("split %d components (%d extracted, %d retained) (%s)",
            outerComponents.size(), dissolvedCount, retainedCount, primaryTag);

        List<FixOp> ops = new ArrayList<>();
        ops.add(new FixOp(FixOpType.SPLIT_RELATION, results));
        FixPlan plan = new FixPlan(relation, ops, desc);
        plan.validate();
        return AnalyzeOutcome.fix(plan);
    }

    /**
     * After analyzeComponent consolidates open ways into closed rings, the resulting
     * outer rings may form disconnected groups — each with its own inners. This method
     * detects that case and re-routes to a SPLIT_RELATION plan so the split happens
     * in one click instead of requiring a second pass.
     *
     * @return a SPLIT_RELATION outcome, or null if no split is possible/worthwhile
     */
    private static AnalyzeOutcome tryPostConsolidationSplit(
            Relation relation, ComponentResult singleComp, Set<Way> innerWaySet, Random rng) {
        List<WayChainBuilder.Ring> outerRings = singleComp.getRetainedOuterRings();

        // Check if the outer rings form disconnected node groups
        // Build a Union-Find on rings based on shared nodes
        Map<Node, List<WayChainBuilder.Ring>> nodeToRings = new HashMap<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            for (Node node : ring.getNodes()) {
                nodeToRings.computeIfAbsent(node, k -> new ArrayList<>()).add(ring);
            }
        }

        UnionFind<WayChainBuilder.Ring> uf = new UnionFind<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            uf.makeSet(ring);
        }
        for (List<WayChainBuilder.Ring> group : nodeToRings.values()) {
            for (int i = 1; i < group.size(); i++) {
                uf.union(group.get(0), group.get(i));
            }
        }

        List<Set<WayChainBuilder.Ring>> ringComponents = uf.components();
        if (ringComponents.size() <= 1) {
            return null; // all outers are connected — no split possible
        }

        // Outer rings form multiple disconnected groups.
        // Assign inner rings to their containing outer ring component.
        List<WayChainBuilder.Ring> innerRings = singleComp.getRetainedInnerRings();
        if (innerRings == null) {
            innerRings = new ArrayList<>();
        }

        // Build component index per outer ring
        Map<WayChainBuilder.Ring, Integer> ringToCompIndex = new HashMap<>();
        for (int i = 0; i < ringComponents.size(); i++) {
            for (WayChainBuilder.Ring ring : ringComponents.get(i)) {
                ringToCompIndex.put(ring, i);
            }
        }

        // Map each inner to its containing outer's component
        List<List<WayChainBuilder.Ring>> innersPerComp = new ArrayList<>();
        for (int i = 0; i < ringComponents.size(); i++) {
            innersPerComp.add(new ArrayList<>());
        }
        for (WayChainBuilder.Ring innerRing : innerRings) {
            WayChainBuilder.Ring containing = findContainingRing(innerRing, outerRings);
            if (containing != null) {
                Integer compIdx = ringToCompIndex.get(containing);
                if (compIdx != null) {
                    innersPerComp.get(compIdx).add(innerRing);
                }
            }
        }

        // Build sub-ComponentResults for each ring component.
        // Each sub-component is re-analyzed independently through the full pipeline,
        // so consolidation ops are re-discovered from the original source ways.
        List<ComponentResult> subResults = new ArrayList<>();
        int dissolvedCount = 0;
        int retainedCount = 0;

        for (int i = 0; i < ringComponents.size(); i++) {
            Set<WayChainBuilder.Ring> compRings = ringComponents.get(i);
            List<WayChainBuilder.Ring> compInners = innersPerComp.get(i);

            // Collect source ways for this ring component's outers
            List<Way> compOuterWays = new ArrayList<>();
            for (WayChainBuilder.Ring ring : compRings) {
                compOuterWays.addAll(ring.getSourceWays());
            }
            List<Way> compInnerWays = new ArrayList<>();
            for (WayChainBuilder.Ring ring : compInners) {
                compInnerWays.addAll(ring.getSourceWays());
            }

            // Analyze each sub-component independently through the full pipeline
            ComponentResult cr = analyzeComponent(compOuterWays, compInnerWays,
                false, false, false, null);
            if (cr == null && !compInnerWays.isEmpty()) {
                // Not simplifiable but still needs its own sub-relation
                cr = new ComponentResult(compOuterWays, compInnerWays, new ArrayList<>(), false,
                    null, null, "retained as sub-relation");
            }
            subResults.add(cr);
            if (cr != null) {
                if (cr.dissolvesCompletely()) {
                    dissolvedCount++;
                } else {
                    retainedCount++;
                }
            }
        }

        String primaryTag = getPrimaryTag(relation);
        String desc = String.format("split %d components (%d extracted, %d retained) (%s)",
            ringComponents.size(), dissolvedCount, retainedCount, primaryTag);

        List<FixOp> ops = new ArrayList<>();
        ops.add(new FixOp(FixOpType.SPLIT_RELATION, subResults));
        FixPlan plan = new FixPlan(relation, ops, desc);
        plan.validate();
        return AnalyzeOutcome.fix(plan);
    }

    /**
     * Handles the "no inners remain" case: dissolve or retain the relation depending
     * on protection modes. Returns a ComponentResult, or null if no ops apply.
     */
    private static ComponentResult tryDissolveOrRetain(
            List<Way> outerWays, List<Way> innerWays,
            List<FixOp> ops, List<String> descParts,
            List<WayChainBuilder.Ring> outerRings,
            boolean partialMode, boolean parentProtected, boolean identityProtected,
            SkipReason[] skipOut) {
        if (partialMode) {
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
                return null;
            }
            descParts.add("partial improvement");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, new ArrayList<>(), String.join(", ", descParts));
        }
        if (parentProtected) {
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.HAS_PARENT_RELATION;
                return null;
            }
            descParts.add("parent-protected, kept as relation");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, new ArrayList<>(), String.join(", ", descParts));
        }
        if (identityProtected && outerRings.size() > 1) {
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.IDENTITY_PROTECTED_NO_OPS;
                return null;
            }
            descParts.add("identity-protected, kept as relation");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, new ArrayList<>(), String.join(", ", descParts));
        }
        ops.add(new FixOp(FixOpType.DISSOLVE, outerRings, null));
        int count = outerRings.size();
        descParts.add(count == 1
            ? "dissolved"
            : count + " outers dissolved");
        return new ComponentResult(outerWays, innerWays, ops, true,
            null, null, String.join(", ", descParts));
    }

    /**
     * Removes oversized rings (exceeding MAX_WAY_NODES) from the list.
     * @return true if any rings were removed (caller should set partialMode)
     */
    private static boolean removeOversizedRings(List<WayChainBuilder.Ring> rings) {
        List<WayChainBuilder.Ring> oversized = new ArrayList<>();
        for (WayChainBuilder.Ring ring : rings) {
            if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 > MAX_WAY_NODES) {
                oversized.add(ring);
            }
        }
        if (!oversized.isEmpty()) {
            rings.removeAll(oversized);
            return true;
        }
        return false;
    }

    /**
     * Analyze a single connected component of ways through the full pipeline.
     * Returns a ComponentResult, or null if the component is not fixable.
     * @param parentProtected true if the relation has a parent relation and must not be deleted
     * @param skipOut if non-null and the component is not fixable, skipOut[0] is set to the reason
     */
    private static ComponentResult analyzeComponent(List<Way> outerWays, List<Way> innerWays,
            boolean identityProtected, boolean boundaryMode, boolean parentProtected,
            SkipReason[] skipOut) {
        // Partial mode: when some steps fail but others succeed, accumulate
        // whatever consolidation ops are possible and keep the relation alive.
        boolean partialMode = false;

        // Try building rings from outers; if that fails, try reclassifying bridging inners
        Optional<List<WayChainBuilder.Ring>> ringsOpt = WayChainBuilder.buildRings(outerWays);

        if (ringsOpt.isEmpty() && !innerWays.isEmpty()) {
            List<Way> adjustedOuters = new ArrayList<>(outerWays);
            List<Way> adjustedInners = new ArrayList<>(innerWays);
            if (reclassifyBridgingInners(adjustedOuters, adjustedInners)) {
                ringsOpt = WayChainBuilder.buildRings(adjustedOuters);
                if (ringsOpt.isPresent()) {
                    outerWays = adjustedOuters;
                    innerWays = adjustedInners;
                }
            }
        }

        List<WayChainBuilder.Ring> outerRings;
        if (ringsOpt.isEmpty()) {
            // Full ring building failed — try partial: form rings from subsets that chain
            WayChainBuilder.PartialRingsResult partial = WayChainBuilder.buildRingsPartial(outerWays);
            if (partial.getRings().isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.OUTERS_CANT_FORM_RINGS;
                return null;
            }
            outerRings = partial.getRings();
            partialMode = true;
        } else {
            outerRings = ringsOpt.get();
        }

        // Check node limits on rings that need chaining — skip oversized rings
        if (removeOversizedRings(outerRings)) {
            partialMode = true;
        }

        // Skip if any outer ring is nested inside another with no inners to justify
        // the nesting. When inners exist, defer the check until inner rings are built
        // so we can validate the island-within-a-hole pattern (outer > inner > outer).
        // In partial mode, skip this check: the incomplete ring set could give false positives.
        if (!partialMode && innerWays.isEmpty() && hasNestedOuters(outerRings)) {
            if (skipOut != null) skipOut[0] = SkipReason.NESTED_OUTER_RINGS;
            return null;
        }

        List<FixOp> ops = new ArrayList<>();
        List<String> descParts = new ArrayList<>();

        // Record consolidation if any rings need chaining
        List<WayChainBuilder.Ring> needsChaining = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            if (!ring.isAlreadyClosed()) {
                needsChaining.add(ring);
            }
        }
        if (!needsChaining.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, needsChaining, null));
            descParts.add(needsChaining.size() == 1
                ? "1 ring chained"
                : needsChaining.size() + " rings chained");
        }

        // Check all outer rings for self-intersections and decompose
        List<DecomposedRing> decompositions = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            DecomposedRing decomp = SelfIntersectionDecomposer.decomposeIfSelfIntersecting(ring);
            if (decomp != null) {
                // Check node limits on sub-rings
                boolean withinLimits = true;
                for (WayChainBuilder.Ring sub : decomp.getSubRings()) {
                    if (sub.getNodes().size() - 1 > MAX_WAY_NODES) {
                        withinLimits = false;
                        break;
                    }
                }
                if (withinLimits) {
                    decompositions.add(decomp);
                }
            }
        }
        if (!decompositions.isEmpty()) {
            ops.add(new FixOp(FixOpType.DECOMPOSE_SELF_INTERSECTIONS, decompositions, null));
            // Replace decomposed rings with their sub-rings in outerRings
            for (DecomposedRing d : decompositions) {
                outerRings.remove(d.getOriginalRing());
                outerRings.addAll(d.getSubRings());
            }
            int totalSubs = decompositions.stream()
                .mapToInt(d -> d.getSubRings().size())
                .sum();
            descParts.add(totalSubs + " sub-rings from decomposition");
        }

        // Boundary mode: only CONSOLIDATE + DECOMPOSE for outers, plus inner consolidation.
        // The relation always survives — no dissolve, extract, merge, or split.
        if (boundaryMode) {
            // Also consolidate inner rings if present
            if (!innerWays.isEmpty()) {
                Optional<List<WayChainBuilder.Ring>> innerRingsOpt = WayChainBuilder.buildRings(innerWays);
                if (innerRingsOpt.isEmpty()) {
                    innerRingsOpt = buildRingsWithAbsorption(innerWays);
                }
                List<WayChainBuilder.Ring> innerRings;
                if (innerRingsOpt.isPresent()) {
                    innerRings = innerRingsOpt.get();
                } else {
                    // Try partial inner ring building
                    WayChainBuilder.PartialRingsResult partialInner =
                        WayChainBuilder.buildRingsPartial(innerWays);
                    innerRings = partialInner.getRings();
                }
                if (!innerRings.isEmpty()) {
                    List<WayChainBuilder.Ring> innerNeedsChaining = new ArrayList<>();
                    for (WayChainBuilder.Ring ring : innerRings) {
                        if (!ring.isAlreadyClosed() && ring.getNodes().size() - 1 <= MAX_WAY_NODES) {
                            innerNeedsChaining.add(ring);
                        }
                    }
                    if (!innerNeedsChaining.isEmpty()) {
                        ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, innerNeedsChaining, null));
                        descParts.add(innerNeedsChaining.size() == 1
                            ? "1 inner ring chained"
                            : innerNeedsChaining.size() + " inner rings chained");
                    }
                    // Replace untagged already-closed inner rings with tagged duplicate ways
                    List<WayChainBuilder.Ring> bndInnerReusable =
                        findReusableInnerRings(innerRings, outerWays, innerWays);
                    if (!bndInnerReusable.isEmpty()) {
                        ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, bndInnerReusable, null));
                        descParts.add(bndInnerReusable.size() == 1
                            ? "1 inner reused"
                            : bndInnerReusable.size() + " inners reused");
                    }
                    // Merge abutting closed inner rings
                    List<ConsolidatedInnerGroup> bndInnerConsolidations =
                        mergeAdjoiningInnerRings(innerRings);
                    if (!bndInnerConsolidations.isEmpty()) {
                        ops.add(new FixOp(FixOpType.CONSOLIDATE_INNERS, bndInnerConsolidations, 0));
                        int totalSrc = bndInnerConsolidations.stream()
                            .mapToInt(g -> g.getSourceRings().size()).sum();
                        int resCnt = bndInnerConsolidations.size();
                        descParts.add(totalSrc + " inners merged into " + resCnt);
                    }
                }
            }
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.BOUNDARY_NO_OPS;
                return null; // nothing to fix
            }
            descParts.add("boundary updated");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, new ArrayList<>(), String.join(", ", descParts));
        }

        // No inners: dissolve everything (unless partial or identity-protected)
        if (innerWays.isEmpty()) {
            return tryDissolveOrRetain(outerWays, innerWays, ops, descParts,
                outerRings, partialMode, parentProtected, identityProtected, skipOut);
        }

        // Build rings from inner ways
        Optional<List<WayChainBuilder.Ring>> innerRingsOpt = WayChainBuilder.buildRings(innerWays);
        if (innerRingsOpt.isEmpty()) {
            // Try absorbing open inner ways into closed inner rings
            innerRingsOpt = buildRingsWithAbsorption(innerWays);
        }
        List<WayChainBuilder.Ring> innerRings;
        if (innerRingsOpt.isEmpty()) {
            // Full inner ring building failed — try partial
            WayChainBuilder.PartialRingsResult partialInner = WayChainBuilder.buildRingsPartial(innerWays);
            if (!partialInner.getRings().isEmpty()) {
                innerRings = partialInner.getRings();
                partialMode = true;
            } else {
                // No inner rings could be formed at all — return whatever outer ops we have
                if (!ops.isEmpty()) {
                    descParts.add("partial improvement");
                    return new ComponentResult(outerWays, innerWays, ops, false,
                        outerRings, new ArrayList<>(), String.join(", ", descParts));
                }
                if (skipOut != null) skipOut[0] = SkipReason.INNER_WAYS_CANT_FORM_RINGS;
                return null;
            }
        } else {
            innerRings = innerRingsOpt.get();
        }

        // Check node limits on inner rings that need chaining — skip oversized
        if (removeOversizedRings(innerRings)) {
            partialMode = true;
        }

        // Deferred nested outer check: now that inner rings are built, validate
        // that any nested outers are justified by the island-within-a-hole pattern
        // (outer > inner > outer). In partial mode, skip: incomplete ring sets
        // may give false positives.
        if (!partialMode && hasInvalidNestedOuters(outerRings, innerRings)) {
            if (skipOut != null) skipOut[0] = SkipReason.NESTED_OUTER_RINGS;
            return null;
        }

        // Record inner consolidation if any inner rings need chaining
        List<WayChainBuilder.Ring> innerNeedsChaining = new ArrayList<>();
        for (WayChainBuilder.Ring ring : innerRings) {
            if (!ring.isAlreadyClosed()) {
                innerNeedsChaining.add(ring);
            }
        }
        if (!innerNeedsChaining.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, innerNeedsChaining, null));
            int total = needsChaining.size() + innerNeedsChaining.size();
            if (!needsChaining.isEmpty()) {
                descParts.remove(descParts.size() - 1);
            }
            descParts.add(total == 1
                ? "1 ring chained"
                : total + " rings chained");
        }

        // Replace untagged already-closed inner rings with tagged duplicate ways from the DataSet
        List<WayChainBuilder.Ring> innerReusable = findReusableInnerRings(innerRings, outerWays, innerWays);
        if (!innerReusable.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_RINGS, innerReusable, null));
            descParts.add(innerReusable.size() == 1
                ? "1 inner reused"
                : innerReusable.size() + " inners reused");
        }

        // Merge abutting closed inner rings that share edges
        List<ConsolidatedInnerGroup> innerConsolidations = mergeAdjoiningInnerRings(innerRings);
        if (!innerConsolidations.isEmpty()) {
            ops.add(new FixOp(FixOpType.CONSOLIDATE_INNERS, innerConsolidations, 0));
            int totalSources = innerConsolidations.stream()
                .mapToInt(g -> g.getSourceRings().size()).sum();
            int resultCount = innerConsolidations.size();
            descParts.add(totalSources + " inners merged into " + resultCount);
        }

        // Extract tagged inner rings that share a contiguous edge with any outer ring
        List<WayChainBuilder.Ring> edgeSharingInners = findEdgeSharingInners(outerRings, innerRings);
        if (!edgeSharingInners.isEmpty()) {
            ops.add(new FixOp(FixOpType.EXTRACT_INNERS, edgeSharingInners, null));
            innerRings.removeAll(edgeSharingInners);
            for (WayChainBuilder.Ring r : edgeSharingInners) {
                innerWays.removeAll(r.getSourceWays());
            }
            descParts.add(edgeSharingInners.size() == 1
                ? "1 edge-sharing inner extracted"
                : edgeSharingInners.size() + " edge-sharing inners extracted");
        }

        // If all inners were extracted, dissolve (same logic as no-inners case)
        if (innerRings.isEmpty()) {
            return tryDissolveOrRetain(outerWays, innerWays, ops, descParts,
                outerRings, partialMode, parentProtected, identityProtected, skipOut);
        }

        // In partial mode, skip dissolution/extraction/merge — just return consolidation ops
        if (partialMode) {
            if (ops.isEmpty()) {
                if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
                return null;
            }
            descParts.add("partial improvement");
            return new ComponentResult(outerWays, innerWays, ops, false,
                outerRings, innerRings, String.join(", ", descParts));
        }

        // Map inner rings to containing outer rings
        Map<WayChainBuilder.Ring, List<WayChainBuilder.Ring>> ringToInners = new HashMap<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            ringToInners.put(ring, new ArrayList<>());
        }
        for (WayChainBuilder.Ring innerRing : innerRings) {
            WayChainBuilder.Ring containing = findContainingRing(innerRing, outerRings);
            if (containing == null) {
                // In non-partial mode, a single unmapped inner is a hard failure
                if (skipOut != null) skipOut[0] = SkipReason.INNER_NOT_CONTAINED_IN_OUTER;
                return null;
            }
            ringToInners.get(containing).add(innerRing);
        }

        List<WayChainBuilder.Ring> extractable = new ArrayList<>();
        List<WayChainBuilder.Ring> retained = new ArrayList<>();
        List<WayChainBuilder.Ring> retainedInners = new ArrayList<>();
        for (WayChainBuilder.Ring ring : outerRings) {
            if (ringToInners.get(ring).isEmpty()) {
                extractable.add(ring);
            } else {
                retained.add(ring);
                retainedInners.addAll(ringToInners.get(ring));
            }
        }

        // Extract standalone outers if any exist — but not if identity-protected,
        // since all outers should stay in the relation as one feature
        if (!extractable.isEmpty() && !identityProtected) {
            ops.add(new FixOp(FixOpType.EXTRACT_OUTERS, extractable, null));
            descParts.add(extractable.size() == 1
                ? "1 outer extracted"
                : extractable.size() + " outers extracted");
        } else if (!extractable.isEmpty() && identityProtected) {
            // Keep extractable outers in the relation
            retained.addAll(extractable);
            extractable.clear();
        }

        // Analyze what remains after extraction
        if (retained.size() == 1 && retainedInners.size() == 1 && !parentProtected) {
            TouchingInnerMerger.MergeResult mergeResult = TouchingInnerMerger.tryTouchingInnerMerge(retained.get(0), retainedInners.get(0).getNodes());
            if (mergeResult != null) {
                for (List<Node> way : mergeResult.mergedWays) {
                    if (way.size() - 1 > MAX_WAY_NODES) {
                        if (ops.isEmpty()) {
                            if (skipOut != null) skipOut[0] = SkipReason.CONSOLIDATED_RING_TOO_LARGE;
                            return null;
                        }
                        return new ComponentResult(outerWays, innerWays, ops, false,
                            retained, retainedInners, String.join(", ", descParts));
                    }
                }
                ops.add(new FixOp(FixOpType.TOUCHING_INNER_MERGE, null, mergeResult.mergedWays, mergeResult.newNodes));
                if (mergeResult.mergedWays.size() == 1) {
                    descParts.add("touching inner merged");
                } else {
                    descParts.add("inner split into " + mergeResult.mergedWays.size() + " ways");
                }
                return new ComponentResult(outerWays, innerWays, ops, true,
                    null, null, String.join(", ", descParts));
            }

            // Inner doesn't touch outer — relation must be kept with this outer+inner pair
        }

        // Component survives with remaining members
        if (ops.isEmpty()) {
            if (skipOut != null) skipOut[0] = SkipReason.NO_OPERATIONS_APPLICABLE;
            return null;
        }
        return new ComponentResult(outerWays, innerWays, ops, false,
            retained, retainedInners, String.join(", ", descParts));
    }

    // ---- Connected component detection (Union-Find) ----

    /**
     * Partitions ways into connected components based on shared endpoint nodes.
     */
    private static List<Set<Way>> findConnectedComponents(List<Way> allWays) {
        UnionFind<Way> uf = new UnionFind<>();
        for (Way w : allWays) {
            uf.makeSet(w);
        }

        // Map each node (endpoint or interior) to all ways that contain it.
        // This ensures overlapping ways that share interior nodes (issue #9)
        // are grouped into the same component.
        Map<Node, List<Way>> nodeToWays = new HashMap<>();
        for (Way way : allWays) {
            for (Node node : way.getNodes()) {
                nodeToWays.computeIfAbsent(node, k -> new ArrayList<>()).add(way);
            }
        }

        // Union all ways sharing any node
        for (List<Way> group : nodeToWays.values()) {
            for (int i = 1; i < group.size(); i++) {
                uf.union(group.get(0), group.get(i));
            }
        }

        return uf.components();
    }

    // ---- Bridging inner reclassification ----

    /**
     * Attempts to fix degree violations in the outer endpoint graph by
     * reclassifying inner ways that bridge dangling outer endpoints.
     * Modifies the lists in place. Returns true if any reclassification happened.
     */
    private static boolean reclassifyBridgingInners(List<Way> outers, List<Way> inners) {
        Map<Node, Integer> degree = new HashMap<>();
        for (Way w : outers) {
            if (!w.isClosed()) {
                degree.merge(w.firstNode(), 1, Integer::sum);
                degree.merge(w.lastNode(), 1, Integer::sum);
            }
        }

        Set<Node> dangling = new HashSet<>();
        for (Map.Entry<Node, Integer> e : degree.entrySet()) {
            if (e.getValue() != 2) {
                dangling.add(e.getKey());
            }
        }

        if (dangling.isEmpty()) return false;

        boolean changed = false;
        Iterator<Way> it = inners.iterator();
        while (it.hasNext()) {
            Way inner = it.next();
            Node first = inner.firstNode();
            Node last = inner.lastNode();
            if (dangling.contains(first) && dangling.contains(last)) {
                it.remove();
                outers.add(inner);
                degree.merge(first, 1, Integer::sum);
                degree.merge(last, 1, Integer::sum);
                if (degree.getOrDefault(first, 0) == 2) dangling.remove(first);
                if (degree.getOrDefault(last, 0) == 2) dangling.remove(last);
                changed = true;
            }
        }

        return changed;
    }

    private static String buildDescription(List<String> parts, String primaryTag) {
        return String.join(", ", parts) + " (" + primaryTag + ")";
    }

    // ---- Adjoining inner ring consolidation ----
    //
    // Uses "twin edge elimination" (Schurer et al., Computers & Graphics 2000):
    // when two rings share a contiguous edge, remove the shared edge and trace
    // the remaining boundary. O(n), preserves exact Node identity.
    //
    // Current limitation: only handles a single contiguous shared run between
    // two rings. If two rings ever share multiple disjoint edge segments, the
    // merge returns null. A planar-graph face-finding algorithm (build directed
    // half-edges, remove twins, sort by polar angle at each vertex, trace outer
    // face) would handle that case — still O(n log n) and Node-identity-safe.
    // Not justified yet since multi-segment shared edges are extremely rare in
    // real OSM data.

    /**
     * Iteratively merges abutting closed inner rings that share consecutive edges.
     * Modifies the innerRings list in-place: merged sources are removed, merged ring inserted.
     * Returns the list of consolidation groups (empty if no merges happened).
     */
    private static List<ConsolidatedInnerGroup> mergeAdjoiningInnerRings(
            List<WayChainBuilder.Ring> innerRings) {
        List<ConsolidatedInnerGroup> groups = new ArrayList<>();
        // Map from merged ring to its group (for iterative aggregation)
        Map<WayChainBuilder.Ring, ConsolidatedInnerGroup> ringToGroup = new HashMap<>();

        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < innerRings.size() && !merged; i++) {
                for (int j = i + 1; j < innerRings.size() && !merged; j++) {
                    WayChainBuilder.Ring ringA = innerRings.get(i);
                    WayChainBuilder.Ring ringB = innerRings.get(j);
                    WayChainBuilder.Ring result = tryMergeAdjoiningRings(ringA, ringB);
                    if (result != null && result.getNodes().size() - 1 <= MAX_WAY_NODES) {
                        // Collect all original source rings (handle iterative merges)
                        List<WayChainBuilder.Ring> sources = new ArrayList<>();
                        ConsolidatedInnerGroup groupA = ringToGroup.get(ringA);
                        ConsolidatedInnerGroup groupB = ringToGroup.get(ringB);
                        if (groupA != null) {
                            sources.addAll(groupA.getSourceRings());
                            groups.remove(groupA);
                            ringToGroup.remove(ringA);
                        } else {
                            sources.add(ringA);
                        }
                        if (groupB != null) {
                            sources.addAll(groupB.getSourceRings());
                            groups.remove(groupB);
                            ringToGroup.remove(ringB);
                        } else {
                            sources.add(ringB);
                        }

                        ConsolidatedInnerGroup group = new ConsolidatedInnerGroup(sources, result);
                        groups.add(group);
                        ringToGroup.put(result, group);

                        innerRings.remove(j);
                        innerRings.remove(i);
                        innerRings.add(i, result);
                        merged = true;
                    }
                }
            }
        }
        return groups;
    }

    /**
     * Tries to merge two closed inner rings that share a consecutive edge.
     * Returns the merged ring, or null if they don't share a valid consecutive edge.
     */
    private static WayChainBuilder.Ring tryMergeAdjoiningRings(
            WayChainBuilder.Ring ringA, WayChainBuilder.Ring ringB) {
        List<Node> nodesA = ringA.getNodes();
        List<Node> nodesB = ringB.getNodes();
        int sizeA = nodesA.size() - 1; // exclude closing node
        int sizeB = nodesB.size() - 1;

        // Find shared nodes
        Set<Node> nodesSetB = new HashSet<>();
        for (int i = 0; i < sizeB; i++) {
            nodesSetB.add(nodesB.get(i));
        }
        Set<Node> sharedSet = new HashSet<>();
        for (int i = 0; i < sizeA; i++) {
            if (nodesSetB.contains(nodesA.get(i))) {
                sharedSet.add(nodesA.get(i));
            }
        }

        if (sharedSet.size() < 2) {
            return null; // need at least 2 shared nodes for an edge
        }

        // Find the contiguous shared run in ring A
        int runStart = findSharedRunStart(nodesA, sizeA, sharedSet);
        if (runStart < 0) return null;

        int runLength = 0;
        for (int k = 0; k < sizeA; k++) {
            if (sharedSet.contains(nodesA.get((runStart + k) % sizeA))) {
                runLength++;
            } else {
                break;
            }
        }
        if (runLength != sharedSet.size()) {
            return null; // shared nodes are not contiguous in A
        }

        // Find matching run in B (may be forward or reversed)
        Node runFirstA = nodesA.get(runStart % sizeA);
        Node runLastA = nodesA.get((runStart + runLength - 1) % sizeA);

        int startInB = -1;
        for (int i = 0; i < sizeB; i++) {
            if (nodesB.get(i).equals(runFirstA)) {
                startInB = i;
                break;
            }
        }
        if (startInB < 0) return null;

        // Check forward match in B
        boolean forwardMatch = true;
        for (int k = 0; k < runLength; k++) {
            if (!nodesB.get((startInB + k) % sizeB).equals(nodesA.get((runStart + k) % sizeA))) {
                forwardMatch = false;
                break;
            }
        }

        // Check reverse match in B
        boolean reverseMatch = false;
        int reverseStartInB = -1;
        if (!forwardMatch) {
            for (int i = 0; i < sizeB; i++) {
                if (nodesB.get(i).equals(runLastA)) {
                    reverseStartInB = i;
                    break;
                }
            }
            if (reverseStartInB >= 0) {
                reverseMatch = true;
                for (int k = 0; k < runLength; k++) {
                    Node expectedA = nodesA.get((runStart + k) % sizeA);
                    Node actualB = nodesB.get((reverseStartInB + runLength - 1 - k) % sizeB);
                    if (!actualB.equals(expectedA)) {
                        reverseMatch = false;
                        break;
                    }
                }
            }
        }

        if (!forwardMatch && !reverseMatch) {
            return null;
        }

        // Build merged ring: junction1 → A non-shared → junction2 → B non-shared → junction1
        // junction1 = A[runStart] (first shared node in A's run)
        // junction2 = A[(runStart + runLength - 1) % sizeA] (last shared node in A's run)
        List<Node> merged = new ArrayList<>();

        // Start at junction2 (last shared), walk A's non-shared, arrive at junction1 (first shared)
        int aStart = (runStart + runLength - 1) % sizeA; // junction2 position in A
        int aNonShared = sizeA - runLength;
        for (int k = 0; k <= aNonShared + 1; k++) { // junction2, non-shared, junction1
            merged.add(nodesA.get((aStart + k) % sizeA));
        }
        // merged now ends at junction1

        // Walk B's non-shared nodes (excluding both junctions which are already in merged)
        int bNonShared = sizeB - runLength;
        if (forwardMatch) {
            // Forward match: shared run goes same direction in both rings.
            // Walk B's non-shared arc in reverse (from startInB-1 backwards)
            // to trace the outer perimeter rather than cutting through.
            for (int k = 0; k < bNonShared; k++) {
                merged.add(nodesB.get((startInB - 1 - k + sizeB) % sizeB));
            }
        } else {
            // Reverse: A's first shared node (runFirstA) maps to
            // B[(reverseStartInB + runLength - 1) % sizeB].
            // B's non-shared starts right after that position.
            int bStart = (reverseStartInB + runLength) % sizeB;
            for (int k = 0; k < bNonShared; k++) {
                merged.add(nodesB.get((bStart + k) % sizeB));
            }
        }

        // Close the ring
        merged.add(merged.get(0));

        if (merged.size() < 4) {
            return null; // degenerate
        }

        // Combine source ways
        List<Way> sourceWays = new ArrayList<>(ringA.getSourceWays());
        sourceWays.addAll(ringB.getSourceWays());

        return new WayChainBuilder.Ring(merged, sourceWays);
    }

    /**
     * Finds the index in a ring's node list where the contiguous shared run begins.
     * Handles the wrap-around case (run spanning the closing point).
     */
    private static int findSharedRunStart(List<Node> nodes, int size, Set<Node> sharedSet) {
        // Find a transition from non-shared to shared
        for (int i = 0; i < size; i++) {
            boolean curr = sharedSet.contains(nodes.get(i));
            boolean prev = sharedSet.contains(nodes.get((i - 1 + size) % size));
            if (curr && !prev) {
                return i;
            }
        }
        // All nodes are shared (shouldn't happen for valid distinct rings)
        return -1;
    }

    /**
     * Finds the index of the ring that contains both endpoints of the open way.
     * Returns -1 if no such ring exists.
     */
    private static int findTargetRing(Way openWay, List<WayChainBuilder.Ring> rings) {
        Node p = openWay.firstNode();
        Node q = openWay.lastNode();
        for (int i = 0; i < rings.size(); i++) {
            List<Node> ringNodes = rings.get(i).getNodes();
            int size = ringNodes.size() - 1;
            boolean hasP = false, hasQ = false;
            for (int j = 0; j < size; j++) {
                if (ringNodes.get(j).equals(p)) hasP = true;
                if (ringNodes.get(j).equals(q)) hasQ = true;
                if (hasP && hasQ) return i;
            }
        }
        return -1;
    }

    /**
     * Absorbs an open way into a closed ring by replacing one arc between
     * the open way's endpoints with the open way's path. Picks the candidate
     * that produces the larger enclosed area (expanding the inner).
     *
     * @return the enlarged Ring, or null if absorption is not possible
     */
    private static WayChainBuilder.Ring absorbOpenWayIntoRing(Way openWay,
            WayChainBuilder.Ring ring) {
        Node p = openWay.firstNode();
        Node q = openWay.lastNode();
        if (p.equals(q)) return null;

        List<Node> ringNodes = ring.getNodes();
        int size = ringNodes.size() - 1; // exclude closing node

        // Find positions of P and Q in the ring
        int posP = -1, posQ = -1;
        for (int i = 0; i < size; i++) {
            if (ringNodes.get(i).equals(p)) posP = i;
            if (ringNodes.get(i).equals(q)) posQ = i;
        }
        if (posP < 0 || posQ < 0 || posP == posQ) return null;

        List<Node> openNodes = openWay.getNodes();

        // Candidate 1: arc Q→...→P (going forward from Q around ring) + open way P→...→Q
        List<Node> candidate1 = new ArrayList<>();
        for (int k = 0; k <= size; k++) {
            int idx = (posQ + k) % size;
            candidate1.add(ringNodes.get(idx));
            if (idx == posP && k > 0) break;
        }
        for (int k = 1; k < openNodes.size(); k++) {
            candidate1.add(openNodes.get(k));
        }

        // Candidate 2: arc P→...→Q (going forward from P around ring) + reversed open way Q→...→P
        List<Node> candidate2 = new ArrayList<>();
        for (int k = 0; k <= size; k++) {
            int idx = (posP + k) % size;
            candidate2.add(ringNodes.get(idx));
            if (idx == posQ && k > 0) break;
        }
        for (int k = openNodes.size() - 2; k >= 0; k--) {
            candidate2.add(openNodes.get(k));
        }

        // Validate
        if (candidate1.size() < 4 || candidate2.size() < 4) return null;
        if (!candidate1.get(0).equals(candidate1.get(candidate1.size() - 1))) return null;
        if (!candidate2.get(0).equals(candidate2.get(candidate2.size() - 1))) return null;

        // Pick the candidate with larger absolute area
        double area1 = Math.abs(GeometryUtils.computeSignedArea(candidate1));
        double area2 = Math.abs(GeometryUtils.computeSignedArea(candidate2));
        List<Node> chosen = (area1 >= area2) ? candidate1 : candidate2;

        List<Way> sourceWays = new ArrayList<>(ring.getSourceWays());
        sourceWays.add(openWay);
        return new WayChainBuilder.Ring(chosen, sourceWays);
    }

    /**
     * Fallback when buildRings(innerWays) fails: separates closed and open ways,
     * builds rings from closed ways, then absorbs open ways whose endpoints lie
     * on a closed ring. Leftover open ways are chained separately.
     */
    private static Optional<List<WayChainBuilder.Ring>> buildRingsWithAbsorption(
            List<Way> innerWays) {
        List<Way> closedWays = new ArrayList<>();
        List<Way> openWays = new ArrayList<>();
        for (Way w : innerWays) {
            if (w.isClosed()) {
                closedWays.add(w);
            } else {
                openWays.add(w);
            }
        }
        if (openWays.isEmpty() || closedWays.isEmpty()) {
            return Optional.empty();
        }

        Optional<List<WayChainBuilder.Ring>> closedRingsOpt =
            WayChainBuilder.buildRings(closedWays);
        if (closedRingsOpt.isEmpty()) {
            return Optional.empty();
        }
        List<WayChainBuilder.Ring> rings = new ArrayList<>(closedRingsOpt.get());

        // Iteratively absorb open ways into rings
        List<Way> remainingOpen = new ArrayList<>(openWays);
        boolean progress = true;
        while (progress && !remainingOpen.isEmpty()) {
            progress = false;
            Iterator<Way> it = remainingOpen.iterator();
            while (it.hasNext()) {
                Way openWay = it.next();
                int targetIdx = findTargetRing(openWay, rings);
                if (targetIdx < 0) continue;

                WayChainBuilder.Ring enlarged = absorbOpenWayIntoRing(
                    openWay, rings.get(targetIdx));
                if (enlarged != null && enlarged.getNodes().size() - 1 <= MAX_WAY_NODES) {
                    rings.set(targetIdx, enlarged);
                    it.remove();
                    progress = true;
                }
            }
        }

        if (!remainingOpen.isEmpty()) {
            // Try chaining leftover open ways
            Optional<List<WayChainBuilder.Ring>> chainedOpt =
                WayChainBuilder.buildRings(remainingOpen);
            if (chainedOpt.isEmpty()) {
                return Optional.empty();
            }
            rings.addAll(chainedOpt.get());
        }

        return Optional.of(rings);
    }

    /**
     * Finds already-closed inner rings whose source way has no own tags but where an
     * identical-geometry way with own tags exists in the DataSet (not already a relation
     * member). Such inners can be replaced by the tagged duplicate via CONSOLIDATE_RINGS.
     */
    private static List<WayChainBuilder.Ring> findReusableInnerRings(
            List<WayChainBuilder.Ring> innerRings, List<Way> outerWays, List<Way> innerWays) {
        Set<Way> relationWays = new HashSet<>(outerWays);
        relationWays.addAll(innerWays);
        List<WayChainBuilder.Ring> result = new ArrayList<>();
        for (WayChainBuilder.Ring inner : innerRings) {
            if (!inner.isAlreadyClosed()) continue;
            Way sourceWay = inner.getSourceWays().get(0);
            if (wayHasOwnTags(sourceWay)) continue;
            Way match = MultipolygonFixer.findMatchingWayInDataSet(
                inner.getNodes(), new HashSet<>(inner.getSourceWays()));
            if (match != null && !relationWays.contains(match) && wayHasOwnTags(match)) {
                result.add(inner);
            }
        }
        return result;
    }

    /**
     * Finds inner rings that share a contiguous edge (>= 2 consecutive nodes) with any
     * outer ring and have their own significant tags. Such inners can be extracted from
     * the relation as standalone ways.
     */
    private static List<WayChainBuilder.Ring> findEdgeSharingInners(
            List<WayChainBuilder.Ring> outerRings, List<WayChainBuilder.Ring> innerRings) {
        List<WayChainBuilder.Ring> result = new ArrayList<>();
        for (WayChainBuilder.Ring inner : innerRings) {
            if (!inner.isAlreadyClosed()) continue;
            Way sourceWay = inner.getSourceWays().get(0);
            if (!wayHasOwnTags(sourceWay)) continue;
            for (WayChainBuilder.Ring outer : outerRings) {
                if (sharesContiguousEdge(inner, outer)) {
                    result.add(inner);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Returns true if the way has any tags other than test annotations (keys starting with '_').
     */
    private static boolean wayHasOwnTags(Way way) {
        for (String key : way.keySet()) {
            if (!key.startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the inner ring shares a contiguous run of >= 2 consecutive nodes
     * with the outer ring. Handles wrap-around at ring closure.
     */
    private static boolean sharesContiguousEdge(WayChainBuilder.Ring inner, WayChainBuilder.Ring outer) {
        List<Node> outerNodes = outer.getNodes();
        int outerSize = outerNodes.size() - 1; // exclude closing duplicate

        Set<Node> innerNodeSet = new HashSet<>();
        List<Node> innerNodes = inner.getNodes();
        int innerSize = innerNodes.size() - 1;
        for (int i = 0; i < innerSize; i++) {
            innerNodeSet.add(innerNodes.get(i));
        }

        // Walk the outer ring counting the longest contiguous run of shared nodes.
        // To handle wrap-around, walk 2*outerSize positions but cap maxRun at outerSize.
        int maxRun = 0;
        int currentRun = 0;
        for (int i = 0; i < 2 * outerSize; i++) {
            if (innerNodeSet.contains(outerNodes.get(i % outerSize))) {
                currentRun++;
                if (currentRun > maxRun) {
                    maxRun = currentRun;
                }
            } else {
                currentRun = 0;
            }
            if (maxRun >= 2) return true;
        }
        return false;
    }

    private static WayChainBuilder.Ring findContainingRing(WayChainBuilder.Ring inner, List<WayChainBuilder.Ring> outerRings) {
        for (WayChainBuilder.Ring ring : outerRings) {
            Set<Node> ringNodeSet = new HashSet<>(ring.getNodes());
            // Try each inner node, skipping any that lie on the outer boundary
            for (Node testNode : inner.getNodes()) {
                if (ringNodeSet.contains(testNode)) {
                    continue;
                }
                if (nodeInsidePolygon(testNode, ring.getNodes())) {
                    return ring;
                }
                break; // Not inside this ring
            }
        }
        return null;
    }

    /**
     * Returns true if any outer ring is geometrically contained within another outer ring.
     * This indicates an ambiguous mapping error (likely a missing inner role).
     */
    private static boolean hasNestedOuters(List<WayChainBuilder.Ring> outerRings) {
        if (outerRings.size() < 2) {
            return false;
        }
        for (int i = 0; i < outerRings.size(); i++) {
            WayChainBuilder.Ring candidate = outerRings.get(i);
            for (int j = 0; j < outerRings.size(); j++) {
                if (i == j) continue;
                WayChainBuilder.Ring container = outerRings.get(j);
                Set<Node> containerNodes = new HashSet<>(container.getNodes());
                for (Node testNode : candidate.getNodes()) {
                    if (containerNodes.contains(testNode)) {
                        continue;
                    }
                    if (nodeInsidePolygon(testNode, container.getNodes())) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    /**
     * Returns true if any outer ring is geometrically inside another outer ring
     * without a mediating inner ring justifying the nesting (island-within-a-hole).
     *
     * A nested outer O_candidate inside O_container is VALID if there exists an inner ring I
     * such that O_candidate is inside I and I is inside O_container. This represents
     * the standard OSM "island in a lake" pattern.
     */
    private static boolean hasInvalidNestedOuters(List<WayChainBuilder.Ring> outerRings,
            List<WayChainBuilder.Ring> innerRings) {
        if (outerRings.size() < 2) {
            return false;
        }
        for (int i = 0; i < outerRings.size(); i++) {
            WayChainBuilder.Ring candidate = outerRings.get(i);
            for (int j = 0; j < outerRings.size(); j++) {
                if (i == j) continue;
                WayChainBuilder.Ring container = outerRings.get(j);

                if (!isRingInsideRing(candidate, container)) {
                    continue;
                }

                // Candidate IS inside container. Check if any inner ring mediates:
                // valid if candidate is inside some inner I, and I is inside container.
                boolean justified = false;
                for (WayChainBuilder.Ring inner : innerRings) {
                    if (isRingInsideRing(candidate, inner)
                            && isRingInsideRing(inner, container)) {
                        justified = true;
                        break;
                    }
                }

                if (!justified) {
                    return true; // Invalid nesting — no mediating inner
                }
            }
        }
        return false;
    }

    /**
     * Returns true if ring A is geometrically inside ring B.
     * Tests using the first node of A that is not on B's boundary.
     */
    private static boolean isRingInsideRing(WayChainBuilder.Ring a, WayChainBuilder.Ring b) {
        Set<Node> bNodes = new HashSet<>(b.getNodes());
        for (Node testNode : a.getNodes()) {
            if (bNodes.contains(testNode)) {
                continue;
            }
            return nodeInsidePolygon(testNode, b.getNodes());
        }
        // All nodes of A are shared with B — treat as not inside
        return false;
    }

    private static final GeometryFactory JTS_GF = new GeometryFactory();

    /**
     * JTS-based point-in-polygon test. Replacement for JOSM's Geometry.nodeInsidePolygon.
     */
    private static boolean nodeInsidePolygon(Node testNode, List<Node> polygonNodes) {
        EastNorth en = testNode.getEastNorth();
        if (en == null) return false;
        org.locationtech.jts.geom.Point point = JTS_GF.createPoint(
                new Coordinate(en.east(), en.north()));
        org.locationtech.jts.geom.Polygon polygon = GeometryUtils.nodesToJtsPolygon(polygonNodes);
        return polygon.contains(point);
    }

    static String getPrimaryTag(Relation relation) {
        for (String key : relation.getKeys().keySet()) {
            if (!"type".equals(key) && !key.startsWith("_")) {
                return key + "=" + relation.get(key);
            }
        }
        String type = relation.get("type");
        return type != null ? type : "relation";
    }
}
