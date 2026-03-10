package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

public class WayChainBuilder {

    /**
     * Result of grouping ways into rings. Each ring has its chained node list
     * and the original source ways that formed it.
     */
    public static class Ring {
        private final List<Node> nodes;
        private final List<Way> sourceWays;

        Ring(List<Node> nodes, List<Way> sourceWays) {
            this.nodes = nodes;
            this.sourceWays = sourceWays;
        }

        public List<Node> getNodes() {
            return nodes;
        }

        public List<Way> getSourceWays() {
            return sourceWays;
        }

        /** True if this ring is already a single closed way (no chaining needed). */
        public boolean isAlreadyClosed() {
            return sourceWays.size() == 1 && sourceWays.get(0).isClosed();
        }
    }

    /**
     * Groups a mixed set of ways into separate closed rings.
     * Closed ways form their own ring. Open ways are chained together
     * by shared endpoints into closed loops.
     * Returns empty if any open ways can't form valid rings.
     */
    public static Optional<List<Ring>> buildRings(List<Way> ways) {
        if (ways == null || ways.isEmpty()) {
            return Optional.empty();
        }

        List<Ring> rings = new ArrayList<>();
        List<Way> openWays = new ArrayList<>();

        // Closed ways each form their own ring
        for (Way way : ways) {
            if (way.isClosed()) {
                rings.add(new Ring(new ArrayList<>(way.getNodes()), List.of(way)));
            } else {
                openWays.add(way);
            }
        }

        if (openWays.isEmpty()) {
            return Optional.of(rings);
        }

        // Remove open ways that are entirely contained within an existing closed ring.
        // Such ways are redundant sub-paths (e.g., open way [A,B] within closed [A,B,C,D,A]).
        // Absorbed ways are added to the ring's sourceWays for cleanup by the fixer.
        if (!rings.isEmpty()) {
            openWays = absorbRedundantOpenWays(openWays, rings);
            if (openWays.isEmpty()) {
                return Optional.of(rings);
            }
        }

        // Sort open ways by ID for deterministic traversal order at junctions
        openWays.sort(Comparator.comparingLong(Way::getUniqueId));

        // Trim overlapping segments between ways that share interior nodes
        Map<Way, List<Node>> trimmedNodes = trimOverlappingSegments(openWays);

        // Group open ways by connectivity into separate chains
        // Build endpoint graph (using trimmed node lists where applicable)
        Map<Node, List<WayEndpoint>> endpointMap = new HashMap<>();
        for (Way way : openWays) {
            List<Node> nodes = getEffectiveNodes(way, trimmedNodes);
            Node first = nodes.get(0);
            Node last = nodes.get(nodes.size() - 1);
            endpointMap.computeIfAbsent(first, k -> new ArrayList<>()).add(new WayEndpoint(way, false));
            endpointMap.computeIfAbsent(last, k -> new ArrayList<>()).add(new WayEndpoint(way, true));
        }

        // Sort each node's endpoint list by way ID for deterministic junction selection
        for (List<WayEndpoint> eps : endpointMap.values()) {
            eps.sort(Comparator.comparingLong(ep -> ep.way.getUniqueId()));
        }

        // Every endpoint must have even degree for valid closed loops.
        // Degree 2 = simple ring, degree 4+ = junction where rings touch.
        for (List<WayEndpoint> endpoints : endpointMap.values()) {
            if (endpoints.size() % 2 != 0) {
                return Optional.empty();
            }
        }

        // Traverse connected components
        Set<Way> usedWays = new HashSet<>();
        for (Way startWay : openWays) {
            if (usedWays.contains(startWay)) {
                continue;
            }

            List<Node> ringNodes = new ArrayList<>();
            List<Way> ringSourceWays = new ArrayList<>();

            usedWays.add(startWay);
            ringSourceWays.add(startWay);
            List<Node> startNodes = getEffectiveNodes(startWay, trimmedNodes);
            ringNodes.addAll(startNodes);
            Node tail = startNodes.get(startNodes.size() - 1);
            Node startNode = startNodes.get(0);

            while (!tail.equals(startNode)) {
                List<WayEndpoint> endpoints = endpointMap.get(tail);
                if (endpoints == null) {
                    return Optional.empty();
                }

                WayEndpoint next = null;
                for (WayEndpoint ep : endpoints) {
                    if (!usedWays.contains(ep.way)) {
                        next = ep;
                        break;
                    }
                }
                if (next == null) {
                    return Optional.empty();
                }

                usedWays.add(next.way);
                ringSourceWays.add(next.way);
                List<Node> nextNodes = getEffectiveNodes(next.way, trimmedNodes);

                if (next.isLastNode) {
                    for (int i = nextNodes.size() - 2; i >= 0; i--) {
                        ringNodes.add(nextNodes.get(i));
                    }
                    tail = nextNodes.get(0);
                } else {
                    for (int i = 1; i < nextNodes.size(); i++) {
                        ringNodes.add(nextNodes.get(i));
                    }
                    tail = nextNodes.get(nextNodes.size() - 1);
                }
            }

            // Verify closed
            if (!ringNodes.get(ringNodes.size() - 1).equals(ringNodes.get(0))) {
                return Optional.empty();
            }

            // Reject degenerate rings (e.g., two 2-node ways sharing both endpoints: [A, B, A])
            if (ringNodes.size() < 4) {
                return Optional.empty();
            }

            rings.add(new Ring(ringNodes, ringSourceWays));
        }

        // Verify all open ways were used
        if (usedWays.size() != openWays.size()) {
            return Optional.empty();
        }

        return Optional.of(rings);
    }

    public static Optional<List<Node>> buildChain(List<Way> ways) {
        if (ways == null || ways.isEmpty()) {
            return Optional.empty();
        }

        // Single closed way — just return its nodes
        if (ways.size() == 1) {
            Way way = ways.get(0);
            if (way.isClosed()) {
                return Optional.of(new ArrayList<>(way.getNodes()));
            }
            return Optional.empty();
        }

        // Build endpoint graph: map each endpoint node to the ways that touch it
        Map<Node, List<WayEndpoint>> endpointMap = new HashMap<>();
        for (Way way : ways) {
            Node first = way.firstNode();
            Node last = way.lastNode();

            // A closed way in a chain context doesn't make sense for chaining
            if (first.equals(last)) {
                return Optional.empty();
            }

            endpointMap.computeIfAbsent(first, k -> new ArrayList<>()).add(new WayEndpoint(way, false));
            endpointMap.computeIfAbsent(last, k -> new ArrayList<>()).add(new WayEndpoint(way, true));
        }

        // Every endpoint node must appear exactly twice (degree-2 constraint for a simple loop)
        for (Map.Entry<Node, List<WayEndpoint>> entry : endpointMap.entrySet()) {
            if (entry.getValue().size() != 2) {
                return Optional.empty();
            }
        }

        // Traverse the chain starting from the first way in natural direction
        List<Node> result = new ArrayList<>();
        Set<Way> usedWays = new HashSet<>();

        Way currentWay = ways.get(0);
        usedWays.add(currentWay);

        // Add all nodes of the first way
        result.addAll(currentWay.getNodes());
        Node tail = currentWay.lastNode();
        Node startNode = currentWay.firstNode();

        while (!tail.equals(startNode)) {
            List<WayEndpoint> endpoints = endpointMap.get(tail);
            if (endpoints == null) {
                return Optional.empty();
            }

            // Find the next way (not already used) that connects at this node
            WayEndpoint next = null;
            for (WayEndpoint ep : endpoints) {
                if (!usedWays.contains(ep.way)) {
                    next = ep;
                    break;
                }
            }
            if (next == null) {
                return Optional.empty();
            }

            usedWays.add(next.way);
            List<Node> nextNodes = next.way.getNodes();

            if (next.isLastNode) {
                // The tail matches this way's last node — traverse in reverse
                for (int i = nextNodes.size() - 2; i >= 0; i--) {
                    result.add(nextNodes.get(i));
                }
                tail = next.way.firstNode();
            } else {
                // The tail matches this way's first node — traverse naturally
                for (int i = 1; i < nextNodes.size(); i++) {
                    result.add(nextNodes.get(i));
                }
                tail = next.way.lastNode();
            }
        }

        // Verify all ways were used
        if (usedWays.size() != ways.size()) {
            return Optional.empty();
        }

        // Verify the loop is closed
        if (!result.get(result.size() - 1).equals(result.get(0))) {
            return Optional.empty();
        }

        return Optional.of(result);
    }

    /**
     * Result of partial ring building: rings that could be formed, plus leftover ways that couldn't.
     */
    public static class PartialRingsResult {
        private final List<Ring> rings;
        private final List<Way> leftoverWays;

        PartialRingsResult(List<Ring> rings, List<Way> leftoverWays) {
            this.rings = rings;
            this.leftoverWays = leftoverWays;
        }

        public List<Ring> getRings() {
            return rings;
        }

        public List<Way> getLeftoverWays() {
            return leftoverWays;
        }
    }

    /**
     * Best-effort ring building: forms rings from subsets of ways that chain successfully,
     * returning any unchainable ways as leftovers. Closed ways always succeed.
     * Open ways are grouped by shared endpoints; each group is tried independently.
     */
    public static PartialRingsResult buildRingsPartial(List<Way> ways) {
        if (ways == null || ways.isEmpty()) {
            return new PartialRingsResult(new ArrayList<>(), new ArrayList<>());
        }

        List<Ring> rings = new ArrayList<>();
        List<Way> openWays = new ArrayList<>();

        // Closed ways always form their own ring
        for (Way way : ways) {
            if (way.isClosed()) {
                rings.add(new Ring(new ArrayList<>(way.getNodes()), List.of(way)));
            } else {
                openWays.add(way);
            }
        }

        if (openWays.isEmpty()) {
            return new PartialRingsResult(rings, new ArrayList<>());
        }

        // Group open ways into connected components by shared nodes
        // (including interior nodes, to handle overlapping ways per issue #9)
        UnionFind<Way> uf = new UnionFind<>();
        for (Way w : openWays) {
            uf.makeSet(w);
        }

        Map<Node, List<Way>> nodeToWays = new HashMap<>();
        for (Way way : openWays) {
            for (Node node : way.getNodes()) {
                nodeToWays.computeIfAbsent(node, k -> new ArrayList<>()).add(way);
            }
        }

        for (List<Way> group : nodeToWays.values()) {
            for (int i = 1; i < group.size(); i++) {
                uf.union(group.get(0), group.get(i));
            }
        }

        List<Way> leftover = new ArrayList<>();

        // Try building rings from each connected component independently
        for (List<Way> component : uf.componentLists().values()) {
            Optional<List<Ring>> result = buildRings(component);
            if (result.isPresent()) {
                rings.addAll(result.get());
            } else {
                leftover.addAll(component);
            }
        }

        return new PartialRingsResult(rings, leftover);
    }

    /**
     * Removes open ways whose nodes are entirely contained as a contiguous
     * sub-sequence within an existing closed ring. Such ways are redundant
     * and are absorbed into the ring's sourceWays for cleanup.
     *
     * Returns the remaining open ways (those not absorbed).
     */
    private static List<Way> absorbRedundantOpenWays(List<Way> openWays, List<Ring> rings) {
        List<Way> remaining = new ArrayList<>();
        for (Way openWay : openWays) {
            Ring absorbingRing = null;
            List<Node> openNodes = openWay.getNodes();
            for (Ring ring : rings) {
                if (isContiguousSubsequence(openNodes, ring.getNodes())) {
                    absorbingRing = ring;
                    break;
                }
            }
            if (absorbingRing != null) {
                // Add the redundant way to the ring's source ways for cleanup.
                // sourceWays may be immutable (List.of), so replace the ring.
                List<Way> newSources = new ArrayList<>(absorbingRing.sourceWays);
                newSources.add(openWay);
                int idx = rings.indexOf(absorbingRing);
                Ring newRing = new Ring(absorbingRing.nodes, newSources);
                rings.set(idx, newRing);
                absorbingRing = newRing;
            } else {
                remaining.add(openWay);
            }
        }
        return remaining;
    }

    /**
     * Checks if 'sub' appears as a contiguous sub-sequence within 'seq'.
     * Also checks the reversed sub-sequence. For closed rings (where
     * seq[0] == seq[last]), handles wrap-around at the closure point.
     */
    private static boolean isContiguousSubsequence(List<Node> sub, List<Node> seq) {
        if (sub.size() > seq.size()) return false;
        if (sub.isEmpty()) return true;

        // For closed rings, the logical ring is seq[0..n-2] with wrap-around
        boolean isClosed = seq.size() >= 2 && seq.get(0).equals(seq.get(seq.size() - 1));
        int ringLen = isClosed ? seq.size() - 1 : seq.size();

        // Check forward and reversed
        return isSubsequenceInRing(sub, seq, ringLen)
                || isSubsequenceInRing(reversed(sub), seq, ringLen);
    }

    private static boolean isSubsequenceInRing(List<Node> sub, List<Node> seq, int ringLen) {
        for (int start = 0; start < ringLen; start++) {
            if (seq.get(start).equals(sub.get(0))) {
                boolean match = true;
                for (int j = 1; j < sub.size(); j++) {
                    if (!seq.get((start + j) % ringLen).equals(sub.get(j))) {
                        match = false;
                        break;
                    }
                }
                if (match) return true;
            }
        }
        return false;
    }

    /**
     * Detects and trims overlapping node runs between pairs of open ways.
     * When two open ways share a contiguous run of nodes at their boundaries
     * (e.g., way A = [1,2,3,4] and way B = [3,4,5,6,1]), the shared segment
     * is trimmed from one way so they connect at a single shared endpoint.
     *
     * Returns a map from Way to trimmed node list. Ways that don't need trimming
     * are not included in the map.
     */
    static Map<Way, List<Node>> trimOverlappingSegments(List<Way> openWays) {
        Map<Way, List<Node>> trimmed = new HashMap<>();

        // Find pairs of ways that share overlapping boundary segments
        Set<Way> processed = new HashSet<>();
        for (Way wayA : openWays) {
            List<Node> nodesA = getEffectiveNodes(wayA, trimmed);
            for (Way wayB : openWays) {
                if (wayA == wayB || processed.contains(wayB)) continue;

                List<Node> nodesB = getEffectiveNodes(wayB, trimmed);
                trimPairOverlap(wayA, nodesA, wayB, nodesB, trimmed);
            }
            processed.add(wayA);
        }

        return trimmed;
    }

    private static List<Node> getEffectiveNodes(Way way, Map<Way, List<Node>> trimmed) {
        List<Node> t = trimmed.get(way);
        return t != null ? t : way.getNodes();
    }

    /**
     * Checks if two ways share a contiguous run of nodes at their boundaries
     * and trims the overlap from one of them.
     *
     * Handles four overlap patterns:
     * - A's suffix overlaps B's prefix (same direction)
     * - A's suffix overlaps B's suffix (reversed)
     * - A's prefix overlaps B's prefix (reversed)
     * - A's prefix overlaps B's suffix (same direction)
     */
    /**
     * Checks if two ways share contiguous node runs at their boundaries
     * and trims the overlap from B. Handles double-ended overlaps where
     * both ends of B overlap with A simultaneously.
     */
    private static void trimPairOverlap(Way wayA, List<Node> nodesA,
            Way wayB, List<Node> nodesB, Map<Way, List<Node>> trimmed) {

        List<Node> nodesARev = reversed(nodesA);

        // Determine how many nodes to trim from B's prefix and suffix.
        // There are two independent overlap sites: A's suffix vs B's start,
        // and A's prefix vs B's end. Check both and apply together.
        int trimFromStart = 0;
        int trimFromEnd = 0;

        // A's suffix overlaps B's prefix (forward): A=[..X,Y,Z] B=[X,Y,Z,..]
        int overlap = suffixPrefixOverlap(nodesA, nodesB);
        if (overlap >= 2) {
            trimFromStart = overlap - 1;
        }

        // A's prefix overlaps B's suffix: check via reversed(A) suffix vs reversed(B) prefix
        List<Node> nodesBRev = reversed(nodesB);
        overlap = suffixPrefixOverlap(nodesARev, nodesBRev);
        if (overlap >= 2) {
            trimFromEnd = overlap - 1;
        }

        // If no forward overlaps found, try the two reversed patterns
        if (trimFromStart == 0 && trimFromEnd == 0) {
            // A's suffix overlaps B's suffix (B reversed): A=[..X,Y,Z] B=[..,Z,Y,X]
            overlap = suffixPrefixOverlap(nodesA, nodesBRev);
            if (overlap >= 2) {
                trimFromEnd = overlap - 1;
            }

            // A's prefix overlaps B's prefix (B reversed): A=[X,Y,Z,..] B=[Z,Y,X,..]
            overlap = suffixPrefixOverlap(nodesARev, nodesB);
            if (overlap >= 2) {
                trimFromStart = overlap - 1;
            }
        }

        if (trimFromStart == 0 && trimFromEnd == 0) {
            return;
        }

        // Ensure we don't trim B down to fewer than 2 nodes
        int remaining = nodesB.size() - trimFromStart - trimFromEnd;
        if (remaining < 2) {
            return;
        }

        trimmed.put(wayB, new ArrayList<>(
                nodesB.subList(trimFromStart, nodesB.size() - trimFromEnd)));
    }

    /**
     * Returns the length of the longest suffix of 'a' that equals a prefix of 'b'.
     * E.g., a=[1,2,3,4], b=[3,4,5,6] → returns 2 (the shared [3,4]).
     */
    private static int suffixPrefixOverlap(List<Node> a, List<Node> b) {
        int maxOverlap = Math.min(a.size(), b.size());
        for (int len = maxOverlap; len >= 2; len--) {
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (!a.get(a.size() - len + i).equals(b.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) return len;
        }
        return 0;
    }

    private static List<Node> reversed(List<Node> nodes) {
        List<Node> rev = new ArrayList<>(nodes.size());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            rev.add(nodes.get(i));
        }
        return rev;
    }

    private static class WayEndpoint {
        final Way way;
        final boolean isLastNode;

        WayEndpoint(Way way, boolean isLastNode) {
            this.way = way;
            this.isLastNode = isLastNode;
        }
    }
}
