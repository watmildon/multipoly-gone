package org.openstreetmap.josm.plugins.multipolygone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

public class WayChainBuilder {

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

    private static class WayEndpoint {
        final Way way;
        final boolean isLastNode;

        WayEndpoint(Way way, boolean isLastNode) {
            this.way = way;
            this.isLastNode = isLastNode;
        }
    }
}
