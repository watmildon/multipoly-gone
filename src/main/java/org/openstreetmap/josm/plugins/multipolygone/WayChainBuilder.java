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

        // Sort open ways by ID for deterministic traversal order at junctions
        openWays.sort(Comparator.comparingLong(Way::getUniqueId));

        // Group open ways by connectivity into separate chains
        // Build endpoint graph
        Map<Node, List<WayEndpoint>> endpointMap = new HashMap<>();
        for (Way way : openWays) {
            Node first = way.firstNode();
            Node last = way.lastNode();
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
            ringNodes.addAll(startWay.getNodes());
            Node tail = startWay.lastNode();
            Node startNode = startWay.firstNode();

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
                List<Node> nextNodes = next.way.getNodes();

                if (next.isLastNode) {
                    for (int i = nextNodes.size() - 2; i >= 0; i--) {
                        ringNodes.add(nextNodes.get(i));
                    }
                    tail = next.way.firstNode();
                } else {
                    for (int i = 1; i < nextNodes.size(); i++) {
                        ringNodes.add(nextNodes.get(i));
                    }
                    tail = next.way.lastNode();
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

        // Group open ways into connected components by shared endpoints
        Map<Way, Way> parent = new HashMap<>();
        for (Way w : openWays) {
            parent.put(w, w);
        }

        Map<Node, List<Way>> endpointToWays = new HashMap<>();
        for (Way way : openWays) {
            endpointToWays.computeIfAbsent(way.firstNode(), k -> new ArrayList<>()).add(way);
            endpointToWays.computeIfAbsent(way.lastNode(), k -> new ArrayList<>()).add(way);
        }

        for (List<Way> group : endpointToWays.values()) {
            for (int i = 1; i < group.size(); i++) {
                union(parent, group.get(0), group.get(i));
            }
        }

        Map<Way, List<Way>> componentMap = new HashMap<>();
        for (Way w : openWays) {
            Way root = find(parent, w);
            componentMap.computeIfAbsent(root, k -> new ArrayList<>()).add(w);
        }

        List<Way> leftover = new ArrayList<>();

        // Try building rings from each connected component independently
        for (List<Way> component : componentMap.values()) {
            Optional<List<Ring>> result = buildRings(component);
            if (result.isPresent()) {
                rings.addAll(result.get());
            } else {
                leftover.addAll(component);
            }
        }

        return new PartialRingsResult(rings, leftover);
    }

    private static Way find(Map<Way, Way> parent, Way w) {
        while (!parent.get(w).equals(w)) {
            parent.put(w, parent.get(parent.get(w)));
            w = parent.get(w);
        }
        return w;
    }

    private static void union(Map<Way, Way> parent, Way a, Way b) {
        Way ra = find(parent, a);
        Way rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
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
