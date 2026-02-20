package org.openstreetmap.josm.plugins.multipolygone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
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
}
