package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

class WayChainBuilderTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static Node node(DataSet ds, double lat, double lon) {
        Node n = new Node(new LatLon(lat, lon));
        ds.addPrimitive(n);
        return n;
    }

    private static Way way(DataSet ds, Node... nodes) {
        Way w = new Way();
        w.setNodes(List.of(nodes));
        ds.addPrimitive(w);
        return w;
    }

    // --- buildRings ---

    @Test
    void buildRings_singleClosedWay_returnsOneRing() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0), c = node(ds, 1, 1);
        Way w = way(ds, a, b, c, a);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(w));

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertTrue(result.get().get(0).isAlreadyClosed());
        assertEquals(4, result.get().get(0).getNodes().size());
    }

    @Test
    void buildRings_twoOpenWaysFormingRing_returnsOneChainedRing() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0), c = node(ds, 1, 1);
        Way w1 = way(ds, a, b);
        Way w2 = way(ds, b, c, a);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(w1, w2));

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertFalse(result.get().get(0).isAlreadyClosed());
        List<Node> nodes = result.get().get(0).getNodes();
        assertEquals(nodes.get(0), nodes.get(nodes.size() - 1), "Ring should be closed");
    }

    @Test
    void buildRings_fourOpenWaysFormingRing_returnsOneChainedRing() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0);
        Node c = node(ds, 1, 1), d = node(ds, 0, 1);
        Way w1 = way(ds, a, b);
        Way w2 = way(ds, b, c);
        Way w3 = way(ds, c, d);
        Way w4 = way(ds, d, a);

        Optional<List<WayChainBuilder.Ring>> result =
            WayChainBuilder.buildRings(List.of(w1, w2, w3, w4));

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals(4, result.get().get(0).getSourceWays().size());
    }

    @Test
    void buildRings_disconnectedOpenWays_returnsEmpty() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0);
        Node c = node(ds, 2, 0), d = node(ds, 3, 0);
        Way w1 = way(ds, a, b);
        Way w2 = way(ds, c, d);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(w1, w2));

        assertTrue(result.isEmpty());
    }

    @Test
    void buildRings_mixedClosedAndOpenWays_separatesCorrectly() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0), c = node(ds, 1, 1);
        Way closed = way(ds, a, b, c, a);

        Node d = node(ds, 2, 0), e = node(ds, 3, 0), f = node(ds, 3, 1);
        Way open1 = way(ds, d, e);
        Way open2 = way(ds, e, f, d);

        Optional<List<WayChainBuilder.Ring>> result =
            WayChainBuilder.buildRings(List.of(closed, open1, open2));

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
    }

    @Test
    void buildRings_twoSeparateClosedWays_returnsTwoRings() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0), c = node(ds, 1, 1);
        Way w1 = way(ds, a, b, c, a);

        Node d = node(ds, 2, 0), e = node(ds, 3, 0), f = node(ds, 3, 1);
        Way w2 = way(ds, d, e, f, d);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(w1, w2));

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().get(0).isAlreadyClosed());
        assertTrue(result.get().get(1).isAlreadyClosed());
    }

    @Test
    void buildRings_nullInput_returnsEmpty() {
        assertTrue(WayChainBuilder.buildRings(null).isEmpty());
    }

    @Test
    void buildRings_emptyInput_returnsEmpty() {
        assertTrue(WayChainBuilder.buildRings(new ArrayList<>()).isEmpty());
    }

    // --- buildChain ---

    @Test
    void buildChain_singleClosedWay_returnsNodes() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0), c = node(ds, 1, 1);
        Way w = way(ds, a, b, c, a);

        Optional<List<Node>> result = WayChainBuilder.buildChain(List.of(w));

        assertTrue(result.isPresent());
        assertEquals(4, result.get().size());
    }

    @Test
    void buildChain_twoOpenWaysFormingLoop_returnsClosedChain() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0), c = node(ds, 1, 1);
        Way w1 = way(ds, a, b);
        Way w2 = way(ds, b, c, a);

        Optional<List<Node>> result = WayChainBuilder.buildChain(List.of(w1, w2));

        assertTrue(result.isPresent());
        List<Node> nodes = result.get();
        assertEquals(nodes.get(0), nodes.get(nodes.size() - 1), "Chain should be closed");
    }

    @Test
    void buildChain_nullInput_returnsEmpty() {
        assertTrue(WayChainBuilder.buildChain(null).isEmpty());
    }

    @Test
    void buildChain_emptyInput_returnsEmpty() {
        assertTrue(WayChainBuilder.buildChain(new ArrayList<>()).isEmpty());
    }

    @Test
    void buildChain_singleOpenWay_returnsEmpty() {
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0);
        Way w = way(ds, a, b);

        Optional<List<Node>> result = WayChainBuilder.buildChain(List.of(w));

        assertTrue(result.isEmpty());
    }

    // --- trimOverlappingSegments ---

    @Test
    void trimOverlapping_forwardOverlap_trimsBPrefix() {
        // A = [1,2,3,4]  B = [3,4,5,6,1]  → B trimmed to [4,5,6,1]
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0), n3 = node(ds, 2, 0);
        Node n4 = node(ds, 3, 0), n5 = node(ds, 4, 0), n6 = node(ds, 5, 0);
        Way a = way(ds, n1, n2, n3, n4);
        Way b = way(ds, n3, n4, n5, n6, n1);

        Map<Way, List<Node>> trimmed = WayChainBuilder.trimOverlappingSegments(List.of(a, b));

        assertFalse(trimmed.containsKey(a), "A should not be trimmed");
        assertTrue(trimmed.containsKey(b), "B should be trimmed");
        assertEquals(List.of(n4, n5, n6, n1), trimmed.get(b));
    }

    @Test
    void trimOverlapping_reversedOverlap_trimsBSuffix() {
        // A = [1,2,3,4]  B = [1,6,5,4,3]  → B trimmed to [1,6,5,4]
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0), n3 = node(ds, 2, 0);
        Node n4 = node(ds, 3, 0), n5 = node(ds, 4, 0), n6 = node(ds, 5, 0);
        Way a = way(ds, n1, n2, n3, n4);
        Way b = way(ds, n1, n6, n5, n4, n3);

        Map<Way, List<Node>> trimmed = WayChainBuilder.trimOverlappingSegments(List.of(a, b));

        assertTrue(trimmed.containsKey(b), "B should be trimmed");
        assertEquals(List.of(n1, n6, n5, n4), trimmed.get(b));
    }

    @Test
    void trimOverlapping_noOverlap_returnsEmpty() {
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0);
        Node n3 = node(ds, 2, 0), n4 = node(ds, 3, 0);
        Way a = way(ds, n1, n2);
        Way b = way(ds, n2, n3, n4, n1);

        Map<Way, List<Node>> trimmed = WayChainBuilder.trimOverlappingSegments(List.of(a, b));

        assertTrue(trimmed.isEmpty(), "No overlap to trim");
    }

    @Test
    void buildRings_overlappingForwardWays_chainsCorrectly() {
        // A = [1,2,3,4,5]  B = [4,5,6,7,8,1]  → ring [1,2,3,4,5,6,7,8,1]
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0), n3 = node(ds, 2, 0);
        Node n4 = node(ds, 3, 0), n5 = node(ds, 4, 0), n6 = node(ds, 5, 0);
        Node n7 = node(ds, 5, 1), n8 = node(ds, 0, 1);
        Way a = way(ds, n1, n2, n3, n4, n5);
        Way b = way(ds, n4, n5, n6, n7, n8, n1);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(a, b));

        assertTrue(result.isPresent(), "Should chain with overlap trimming");
        assertEquals(1, result.get().size());
        List<Node> ringNodes = result.get().get(0).getNodes();
        assertEquals(ringNodes.get(0), ringNodes.get(ringNodes.size() - 1), "Ring should be closed");
        // Should have 8 unique nodes + closure = 9
        assertEquals(9, ringNodes.size(), "Ring should have 8 unique nodes + closure");
    }

    @Test
    void trimOverlapping_doubleEndedOverlap_trimsBothEnds() {
        // A = [1,2,3,4,5,6]  B = [4,5,6,1,2]  → B trimmed to [6,1]
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0), n3 = node(ds, 2, 0);
        Node n4 = node(ds, 3, 0), n5 = node(ds, 4, 0), n6 = node(ds, 5, 0);
        Way a = way(ds, n1, n2, n3, n4, n5, n6);
        Way b = way(ds, n4, n5, n6, n1, n2);

        Map<Way, List<Node>> trimmed = WayChainBuilder.trimOverlappingSegments(List.of(a, b));

        assertTrue(trimmed.containsKey(b), "B should be trimmed");
        assertEquals(List.of(n6, n1), trimmed.get(b));
    }

    @Test
    void buildRings_doubleEndedOverlap_chainsCorrectly() {
        // A = [1,2,3,4,5,6]  B = [4,5,6,1,2]  → ring [1,2,3,4,5,6,1]
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0), n3 = node(ds, 2, 0);
        Node n4 = node(ds, 3, 0), n5 = node(ds, 4, 0), n6 = node(ds, 5, 0);
        Way a = way(ds, n1, n2, n3, n4, n5, n6);
        Way b = way(ds, n4, n5, n6, n1, n2);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(a, b));

        assertTrue(result.isPresent(), "Should chain with double-ended overlap trimming");
        assertEquals(1, result.get().size());
        List<Node> ringNodes = result.get().get(0).getNodes();
        assertEquals(ringNodes.get(0), ringNodes.get(ringNodes.size() - 1), "Ring should be closed");
        // 6 unique nodes + closure = 7
        assertEquals(7, ringNodes.size(), "Ring should have 6 unique nodes + closure");
    }

    @Test
    void buildRings_overlappingReversedWays_chainsCorrectly() {
        // A = [1,2,3,4,5]  B = [1,8,7,6,5,4]  → trimmed B = [1,8,7,6,5], ring closes
        DataSet ds = new DataSet();
        Node n1 = node(ds, 0, 0), n2 = node(ds, 1, 0), n3 = node(ds, 2, 0);
        Node n4 = node(ds, 3, 0), n5 = node(ds, 4, 0), n6 = node(ds, 5, 0);
        Node n7 = node(ds, 5, 1), n8 = node(ds, 0, 1);
        Way a = way(ds, n1, n2, n3, n4, n5);
        Way b = way(ds, n1, n8, n7, n6, n5, n4);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(a, b));

        assertTrue(result.isPresent(), "Should chain with reversed overlap trimming");
        assertEquals(1, result.get().size());
        List<Node> ringNodes = result.get().get(0).getNodes();
        assertEquals(ringNodes.get(0), ringNodes.get(ringNodes.size() - 1), "Ring should be closed");
        assertEquals(9, ringNodes.size(), "Ring should have 8 unique nodes + closure");
    }

    // --- absorbRedundantOpenWays ---

    @Test
    void buildRings_redundantOpenWayInClosedRing_absorbed() {
        // Closed way [A,B,C,D,A] + open way [A,B] → open way absorbed, 1 ring
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0);
        Node c = node(ds, 1, 1), d = node(ds, 0, 1);
        Way closed = way(ds, a, b, c, d, a);
        Way sub = way(ds, a, b);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(closed, sub));

        assertTrue(result.isPresent(), "Should absorb redundant open way");
        assertEquals(1, result.get().size());
        WayChainBuilder.Ring ring = result.get().get(0);
        assertEquals(5, ring.getNodes().size(), "Ring nodes should be the closed way's nodes");
        assertEquals(2, ring.getSourceWays().size(), "Ring should track both source ways");
    }

    @Test
    void buildRings_redundantOpenWayReversed_absorbed() {
        // Closed way [A,B,C,D,A] + open way [B,A] → absorbed (reversed sub-path)
        DataSet ds = new DataSet();
        Node a = node(ds, 0, 0), b = node(ds, 1, 0);
        Node c = node(ds, 1, 1), d = node(ds, 0, 1);
        Way closed = way(ds, a, b, c, d, a);
        Way sub = way(ds, b, a);

        Optional<List<WayChainBuilder.Ring>> result = WayChainBuilder.buildRings(List.of(closed, sub));

        assertTrue(result.isPresent(), "Should absorb reversed redundant open way");
        assertEquals(1, result.get().size());
        assertEquals(2, result.get().get(0).getSourceWays().size());
    }
}
