#!/usr/bin/env python3
"""
Generate testdata-geometry.osm with 100 test cases focused on unusual geometries
and intermingled multipolygons. De-emphasizes tag variety.

Test IDs start at 200 to avoid conflicts with testdata-proposed.osm.
"""

import math

# ===== Global counters =====
_next_node_id = -300000
_next_way_id = -300000
_next_rel_id = -300000

nodes = []
ways = []
relations = []

def next_node_id():
    global _next_node_id
    _next_node_id -= 1
    return _next_node_id + 1

def next_way_id():
    global _next_way_id
    _next_way_id -= 1
    return _next_way_id + 1

def next_rel_id():
    global _next_rel_id
    _next_rel_id -= 1
    return _next_rel_id + 1


def add_node(lat, lon, tags=None):
    nid = next_node_id()
    nodes.append((nid, lat, lon, tags or {}))
    return nid

def add_way(node_ids, tags=None):
    wid = next_way_id()
    ways.append((wid, node_ids, tags or {}))
    return wid

def add_relation(members, tags):
    rid = next_rel_id()
    relations.append((rid, members, tags))
    return rid


def polygon_nodes(center_lat, center_lon, radius=0.001, n=6, start_angle=0):
    """Create nodes for a regular polygon, return list of node IDs (not closed)."""
    nids = []
    for i in range(n):
        angle = start_angle + 2 * math.pi * i / n
        lat = center_lat + radius * math.sin(angle)
        lon = center_lon + radius * math.cos(angle)
        nids.append(add_node(lat, lon))
    return nids

def closed_polygon(center_lat, center_lon, radius=0.001, n=6, tags=None, start_angle=0):
    """Create a closed way polygon."""
    nids = polygon_nodes(center_lat, center_lon, radius, n, start_angle)
    nids.append(nids[0])
    return add_way(nids, tags)

def rect_nodes(center_lat, center_lon, w=0.002, h=0.001):
    """Create 4 nodes for a rectangle, return list (not closed)."""
    hw, hh = w/2, h/2
    return [
        add_node(center_lat + hh, center_lon - hw),
        add_node(center_lat + hh, center_lon + hw),
        add_node(center_lat - hh, center_lon + hw),
        add_node(center_lat - hh, center_lon - hw),
    ]

def closed_rect(center_lat, center_lon, w=0.002, h=0.001, tags=None):
    nids = rect_nodes(center_lat, center_lon, w, h)
    nids.append(nids[0])
    return add_way(nids, tags)


def grid_pos(test_num, cols=10):
    row = test_num // cols
    col = test_num % cols
    base_lat = 47.6
    base_lon = -122.5
    spacing = 0.006
    return base_lat + row * spacing, base_lon + col * spacing


test_num = 199  # will increment to 200 for first test

def make_test(notes, expected, extra_tags=None):
    global test_num
    test_num += 1
    tags = {"type": "multipolygon", "natural": "water"}
    if extra_tags:
        tags.update(extra_tags)
    tags["_test_id"] = str(test_num)
    tags["_test_notes"] = notes
    tags["_test_expected"] = expected
    return str(test_num), tags


# ============================================================
# CATEGORY A: Self-intersecting geometries (DECOMPOSE)
# ============================================================

def gen_200():
    """Closed bowtie: 2 triangles crossing in center (4 nodes)."""
    _, tags = make_test(
        "closed bowtie outer (4 nodes, X shape)",
        "DECOMPOSE + DISSOLVE: split into 2 triangles")
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat - 0.001, lon + 0.001)
    n3 = add_node(lat + 0.001, lon + 0.001)
    n4 = add_node(lat - 0.001, lon - 0.001)
    w = add_way([n1, n2, n3, n4, n1])
    add_relation([("way", w, "outer")], tags)

def gen_201():
    """Two open ways that form a bowtie when chained."""
    _, tags = make_test(
        "2 open ways forming bowtie when chained",
        "CONSOLIDATE + DECOMPOSE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat - 0.001, lon + 0.001)
    n3 = add_node(lat + 0.001, lon + 0.001)
    n4 = add_node(lat - 0.001, lon - 0.001)
    n5 = add_node(lat, lon)  # shared endpoint
    w1 = add_way([n1, n5, n2])
    w2 = add_way([n2, n3, n5, n4, n1])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_202():
    """Three open ways forming a bowtie."""
    _, tags = make_test(
        "3 open ways forming bowtie when chained",
        "CONSOLIDATE + DECOMPOSE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon - 0.001)
    nm = add_node(lat, lon)
    n2 = add_node(lat - 0.001, lon + 0.001)
    n3 = add_node(lat + 0.001, lon + 0.001)
    n4 = add_node(lat - 0.001, lon - 0.001)
    w1 = add_way([n1, nm])
    w2 = add_way([nm, n2, n3])
    w3 = add_way([n3, nm, n4, n1])
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_203():
    """Closed figure-8: large polygon crossing itself (6 nodes)."""
    _, tags = make_test(
        "closed figure-8 (6 nodes, 2 loops)",
        "DECOMPOSE + DISSOLVE: split into 2 closed ways")
    lat, lon = grid_pos(test_num)
    # top-left, bottom-right, bottom-left, top-right creates an 8
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat + 0.0005, lon - 0.0005)
    n3 = add_node(lat - 0.001, lon + 0.001)
    n4 = add_node(lat - 0.0005, lon + 0.0005)
    n5 = add_node(lat - 0.001, lon - 0.001)
    n6 = add_node(lat + 0.001, lon + 0.001)
    w = add_way([n1, n2, n3, n4, n5, n2, n6, n4, n1])
    add_relation([("way", w, "outer")], tags)

def gen_204():
    """Closed way with double crossing (pretzel shape)."""
    _, tags = make_test(
        "closed way with 2 self-crossings",
        "DECOMPOSE + DISSOLVE: split into 3+ closed ways")
    lat, lon = grid_pos(test_num)
    # Create a shape that crosses itself twice
    n = [
        add_node(lat, lon - 0.002),          # 0: far left
        add_node(lat + 0.001, lon - 0.0005), # 1: upper mid-left
        add_node(lat - 0.001, lon + 0.0005), # 2: lower mid-right (crosses 0-1)
        add_node(lat, lon + 0.002),           # 3: far right
        add_node(lat - 0.001, lon - 0.0005), # 4: lower mid-left
        add_node(lat + 0.001, lon + 0.0005), # 5: upper mid-right (crosses 3-4)
    ]
    w = add_way([n[0], n[1], n[2], n[3], n[4], n[5], n[0]])
    add_relation([("way", w, "outer")], tags)

def gen_205():
    """2 open ways forming figure-8 with start/end at crossing point."""
    _, tags = make_test(
        "2 open ways, start nodes meet, forming figure-8",
        "CONSOLIDATE + DECOMPOSE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    center = add_node(lat, lon)
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat + 0.001, lon + 0.001)
    n3 = add_node(lat - 0.001, lon + 0.001)
    n4 = add_node(lat - 0.001, lon - 0.001)
    w1 = add_way([center, n1, n2, center])
    w2 = add_way([center, n3, n4, center])
    # These two closed ways share endpoint = center, chain to figure-8
    # Actually these are already closed individually. Let me make them open.
    # Redo: split figure-8 into 2 open halves
    pass  # skip, covered by 201

def gen_205_real():
    """Closed hourglass: thin middle."""
    _, tags = make_test(
        "closed hourglass shape (near-crossing at narrow point)",
        "DECOMPOSE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    n = [
        add_node(lat + 0.001, lon - 0.001),   # top-left
        add_node(lat + 0.001, lon + 0.001),    # top-right
        add_node(lat + 0.0001, lon + 0.0001),  # pinch right
        add_node(lat - 0.001, lon + 0.001),    # bottom-right
        add_node(lat - 0.001, lon - 0.001),    # bottom-left
        add_node(lat - 0.0001, lon - 0.0001),  # pinch left
    ]
    # This crosses between segments 1-2 and 4-5
    w = add_way([n[0], n[1], n[2], n[3], n[4], n[5], n[0]])
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY B: Touching inner with various shared node counts
# ============================================================

def gen_206():
    """Outer + inner sharing 5 consecutive nodes."""
    _, tags = make_test(
        "outer + inner sharing 5 consecutive nodes",
        "TOUCHING_INNER_MERGE: merge into 2 closed ways")
    lat, lon = grid_pos(test_num)
    # Outer: 10-node circle
    outer_nids = polygon_nodes(lat, lon, 0.002, 10)
    # Inner shares first 5 nodes, diverges inward for the rest
    inner_nids = list(outer_nids[:5])
    for i in range(3):
        angle = 2 * math.pi * (4 - i) / 8
        inner_nids.append(add_node(lat + 0.001 * math.sin(angle), lon + 0.001 * math.cos(angle)))
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_207():
    """Outer + inner sharing 6 consecutive nodes (half the outer)."""
    _, tags = make_test(
        "outer + inner sharing 6 of 12 nodes (half the ring)",
        "TOUCHING_INNER_MERGE: merge into 2 closed ways")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 12)
    inner_nids = list(outer_nids[:7])  # share first 7 nodes (indices 0-6)
    # Close inner via a shorter path
    inner_nids.append(add_node(lat, lon))  # center node
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_208():
    """Outer + inner sharing 2 non-consecutive nodes (opposite sides)."""
    _, tags = make_test(
        "outer + inner sharing 2 non-adjacent nodes (opposite sides of ring)",
        "TOUCHING_INNER_MERGE: merge into 2 closed ways")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 8)
    # Share nodes 0 and 4 (opposite sides)
    inner_nids = [
        outer_nids[0],
        add_node(lat + 0.0005, lon),
        outer_nids[4],
        add_node(lat - 0.0005, lon),
    ]
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_209():
    """Outer + inner sharing 3 nodes in two separate runs (1+2)."""
    _, tags = make_test(
        "outer + inner sharing 3 nodes in 2 runs (1 node + 2 consecutive)",
        "TOUCHING_INNER_MERGE: merge into 3 closed ways")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 8)
    # Share node 0 alone and nodes 4,5 consecutive
    inner_nids = [
        outer_nids[0],
        add_node(lat + 0.0005, lon + 0.0005),
        outer_nids[4],
        outer_nids[5],
        add_node(lat - 0.0005, lon - 0.0005),
    ]
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_210():
    """Outer + inner sharing all but 1 node."""
    _, tags = make_test(
        "outer + inner sharing all but 1 outer node (inner is nearly the outer)",
        "TOUCHING_INNER_MERGE: merge into 2 closed ways")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 6)
    # Inner uses 5 of 6 outer nodes + 1 inner node
    inner_nids = list(outer_nids[:5])
    inner_nids.append(add_node(lat - 0.001, lon))
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)


# ============================================================
# CATEGORY C: Intermingled / cross-referenced multipolygons
# ============================================================

def gen_211():
    """Two relations sharing the same outer way."""
    _, tags1 = make_test(
        "relation 1: shares outer way with another relation",
        "DISSOLVE: tag way, delete relation (way kept for other rel)")
    lat, lon = grid_pos(test_num)
    shared_outer = closed_polygon(lat, lon, 0.0015, 6)
    inner1 = closed_polygon(lat + 0.0004, lon, 0.0003, 4)
    add_relation([("way", shared_outer, "outer"), ("way", inner1, "inner")], tags1)

    _, tags2 = make_test(
        "relation 2: shares outer way with another relation",
        "EXTRACT_OUTERS: extract outer, keep relation for inner")
    inner2 = closed_polygon(lat - 0.0004, lon, 0.0003, 4)
    add_relation([("way", shared_outer, "outer"), ("way", inner2, "inner")], tags2)

def gen_213():
    """Three relations forming a chain: rel1 outer = rel2 inner's container."""
    _, tags1 = make_test(
        "chain: large outer, no inners (contains rel2 spatially)",
        "DISSOLVE: tag and delete")
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.002, 8)
    add_relation([("way", w1, "outer")], tags1)

    _, tags2 = make_test(
        "chain: medium outer inside rel1, no inners (contains rel3 spatially)",
        "DISSOLVE: tag and delete")
    w2 = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w2, "outer")], tags2)

    _, tags3 = make_test(
        "chain: small outer inside rel2",
        "DISSOLVE: tag and delete")
    w3 = closed_polygon(lat, lon, 0.0004, 4)
    add_relation([("way", w3, "outer")], tags3)

def gen_216():
    """Two relations sharing an inner way."""
    _, tags1 = make_test(
        "relation A: has its own outer + shared inner",
        "EXTRACT_OUTERS or complex fix")
    lat, lon = grid_pos(test_num)
    outer1 = closed_polygon(lat, lon - 0.001, 0.0015, 6)
    shared_inner = closed_polygon(lat, lon, 0.0004, 4)
    add_relation([("way", outer1, "outer"), ("way", shared_inner, "inner")], tags1)

    _, tags2 = make_test(
        "relation B: has its own outer + shared inner",
        "EXTRACT_OUTERS or complex fix")
    outer2 = closed_polygon(lat, lon + 0.001, 0.0015, 6)
    add_relation([("way", outer2, "outer"), ("way", shared_inner, "inner")], tags2)

def gen_218():
    """Two relations whose outers share a boundary edge (2 nodes)."""
    _, tags1 = make_test(
        "adjacent relation 1: shares 2-node edge with neighbor",
        "DISSOLVE: tag and delete")
    lat, lon = grid_pos(test_num)
    # Shared edge nodes
    s1 = add_node(lat + 0.001, lon)
    s2 = add_node(lat - 0.001, lon)
    # Left polygon
    l1 = add_node(lat + 0.001, lon - 0.002)
    l2 = add_node(lat - 0.001, lon - 0.002)
    w1 = add_way([s1, l1, l2, s2, s1])
    add_relation([("way", w1, "outer")], tags1)

    _, tags2 = make_test(
        "adjacent relation 2: shares 2-node edge with neighbor",
        "DISSOLVE: tag and delete")
    r1 = add_node(lat + 0.001, lon + 0.002)
    r2 = add_node(lat - 0.001, lon + 0.002)
    w2 = add_way([s1, s2, r2, r1, s1])
    add_relation([("way", w2, "outer")], tags2)

def gen_220():
    """Three adjacent relations forming a horizontal strip (shared edges)."""
    _, tags1 = make_test(
        "strip left: shares right edge with middle",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    # 4 vertical edge lines
    e0 = [add_node(lat + 0.001, lon - 0.003), add_node(lat - 0.001, lon - 0.003)]
    e1 = [add_node(lat + 0.001, lon - 0.001), add_node(lat - 0.001, lon - 0.001)]
    e2 = [add_node(lat + 0.001, lon + 0.001), add_node(lat - 0.001, lon + 0.001)]
    e3 = [add_node(lat + 0.001, lon + 0.003), add_node(lat - 0.001, lon + 0.003)]
    w1 = add_way([e0[0], e1[0], e1[1], e0[1], e0[0]])
    add_relation([("way", w1, "outer")], tags1)

    _, tags2 = make_test(
        "strip middle: shares edges with left and right",
        "DISSOLVE")
    w2 = add_way([e1[0], e2[0], e2[1], e1[1], e1[0]])
    add_relation([("way", w2, "outer")], tags2)

    _, tags3 = make_test(
        "strip right: shares left edge with middle",
        "DISSOLVE")
    w3 = add_way([e2[0], e3[0], e3[1], e2[1], e2[0]])
    add_relation([("way", w3, "outer")], tags3)


# ============================================================
# CATEGORY D: Complex consolidation patterns
# ============================================================

def gen_223():
    """Ring made of 2 ways that meet at the same 2 endpoints but in reverse order."""
    _, tags = make_test(
        "2 open ways, both reversed (end nodes match start nodes)",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    s1 = add_node(lat + 0.001, lon)
    s2 = add_node(lat - 0.001, lon)
    n1 = add_node(lat + 0.0005, lon - 0.001)
    n2 = add_node(lat - 0.0005, lon - 0.001)
    n3 = add_node(lat + 0.0005, lon + 0.001)
    n4 = add_node(lat - 0.0005, lon + 0.001)
    w1 = add_way([s2, n2, n1, s1])  # reversed
    w2 = add_way([s2, n4, n3, s1])  # reversed
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_224():
    """Ring made of 3 ways where middle way is reversed."""
    _, tags = make_test(
        "3 open ways, middle one reversed",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    n = [add_node(lat + 0.001 * math.sin(a), lon + 0.001 * math.cos(a))
         for a in [0, math.pi/3, 2*math.pi/3, math.pi, 4*math.pi/3, 5*math.pi/3]]
    w1 = add_way([n[0], n[1]])          # normal
    w2 = add_way([n[3], n[2], n[1]])    # reversed
    w3 = add_way([n[3], n[4], n[5], n[0]])  # normal
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_225():
    """2 open ways that form ring, but with 50+ nodes each (stress test)."""
    _, tags = make_test(
        "2 open ways with 50 nodes each forming ring",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    n_total = 100
    all_nids = polygon_nodes(lat, lon, 0.002, n_total)
    half = n_total // 2
    w1 = add_way(all_nids[:half+1])
    w2 = add_way(all_nids[half:] + [all_nids[0]])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_226():
    """4 open ways forming a ring, each meeting only at endpoints."""
    _, tags = make_test(
        "4 open ways forming quadrants of a ring",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = polygon_nodes(lat, lon, 0.0015, 12)
    w1 = add_way(nids[0:4])
    w2 = add_way(nids[3:7])
    w3 = add_way(nids[6:10])
    w4 = add_way(nids[9:] + [nids[0]])
    add_relation([("way", w1, "outer"), ("way", w2, "outer"),
                  ("way", w3, "outer"), ("way", w4, "outer")], tags)

def gen_227():
    """2 open ways forming ring + 1 standalone closed outer (mixed)."""
    _, tags = make_test(
        "2 open ways forming 1 ring + 1 closed outer (3 outers total)",
        "CONSOLIDATE + DISSOLVE: chain the 2, tag all 3, delete relation")
    lat, lon = grid_pos(test_num)
    nids = polygon_nodes(lat, lon - 0.001, 0.001, 6)
    w1 = add_way(nids[:4])
    w2 = add_way(nids[3:] + [nids[0]])
    w3 = closed_polygon(lat, lon + 0.001, 0.0008, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_228():
    """2 open ways forming ring with inner that doesn't touch."""
    _, tags = make_test(
        "2 open outer ways + 1 closed non-touching inner",
        "CONSOLIDATE + DISSOLVE remainder or CONSOLIDATE + EXTRACT")
    lat, lon = grid_pos(test_num)
    nids = polygon_nodes(lat, lon, 0.002, 8)
    w1 = add_way(nids[:5])
    w2 = add_way(nids[4:] + [nids[0]])
    inner = closed_polygon(lat, lon, 0.0005, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", inner, "inner")], tags)


# ============================================================
# CATEGORY E: Split relation edge cases
# ============================================================

def gen_229():
    """3 completely disconnected closed outers."""
    _, tags = make_test(
        "3 disconnected closed outers (split into 3)",
        "SPLIT_RELATION or DISSOLVE: each becomes standalone")
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat + 0.001, lon - 0.001, 0.0006, 5)
    w2 = closed_polygon(lat + 0.001, lon + 0.001, 0.0006, 5)
    w3 = closed_polygon(lat - 0.001, lon, 0.0006, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_230():
    """2 disconnected outers, each with its own inner."""
    _, tags = make_test(
        "2 disconnected outers each with inner",
        "SPLIT_RELATION: split into 2 sub-relations")
    lat, lon = grid_pos(test_num)
    o1 = closed_polygon(lat, lon - 0.0015, 0.001, 6)
    i1 = closed_polygon(lat, lon - 0.0015, 0.0004, 4)
    o2 = closed_polygon(lat, lon + 0.0015, 0.001, 6)
    i2 = closed_polygon(lat, lon + 0.0015, 0.0004, 4)
    add_relation([("way", o1, "outer"), ("way", i1, "inner"),
                  ("way", o2, "outer"), ("way", i2, "inner")], tags)

def gen_231():
    """2 disconnected groups: one outer-only, one outer+inner."""
    _, tags = make_test(
        "2 disconnected groups: 1 outer-only + 1 outer+inner",
        "SPLIT_RELATION: standalone dissolves, other keeps as sub-relation")
    lat, lon = grid_pos(test_num)
    o1 = closed_polygon(lat, lon - 0.0015, 0.0008, 5)
    o2 = closed_polygon(lat, lon + 0.0015, 0.001, 6)
    i2 = closed_polygon(lat, lon + 0.0015, 0.0004, 4)
    add_relation([("way", o1, "outer"), ("way", o2, "outer"), ("way", i2, "inner")], tags)

def gen_232():
    """4 disconnected outers, 2 with inners, 2 without."""
    _, tags = make_test(
        "4 disconnected outers, 2 with inners",
        "SPLIT_RELATION: complex split")
    lat, lon = grid_pos(test_num)
    o1 = closed_polygon(lat + 0.001, lon - 0.001, 0.0006, 5)
    o2 = closed_polygon(lat + 0.001, lon + 0.001, 0.0008, 6)
    i2 = closed_polygon(lat + 0.001, lon + 0.001, 0.0003, 4)
    o3 = closed_polygon(lat - 0.001, lon - 0.001, 0.0006, 5)
    i3 = closed_polygon(lat - 0.001, lon - 0.001, 0.0003, 4)
    o4 = closed_polygon(lat - 0.001, lon + 0.001, 0.0006, 5)
    add_relation([
        ("way", o1, "outer"), ("way", o2, "outer"), ("way", i2, "inner"),
        ("way", o3, "outer"), ("way", i3, "inner"), ("way", o4, "outer")
    ], tags)

def gen_233():
    """Split where one component needs consolidation."""
    _, tags = make_test(
        "2 disconnected groups: 1 closed outer + 1 open-way chain outer",
        "SPLIT_RELATION: one dissolves, one consolidates+dissolves")
    lat, lon = grid_pos(test_num)
    o1 = closed_polygon(lat, lon - 0.0015, 0.0008, 5)
    nids = polygon_nodes(lat, lon + 0.0015, 0.001, 8)
    w1 = add_way(nids[:5])
    w2 = add_way(nids[4:] + [nids[0]])
    add_relation([("way", o1, "outer"), ("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY F: Extract outers edge cases
# ============================================================

def gen_234():
    """5 outers, 3 of which have inners — extract the 2 standalone."""
    _, tags = make_test(
        "5 outers, 3 with inners (extract 2 standalone outers)",
        "EXTRACT_OUTERS")
    lat, lon = grid_pos(test_num)
    members = []
    # 3 outer+inner pairs
    for i in range(3):
        offset = (i - 1) * 0.002
        o = closed_polygon(lat + 0.001, lon + offset, 0.0007, 6)
        inn = closed_polygon(lat + 0.001, lon + offset, 0.0003, 4)
        members.extend([("way", o, "outer"), ("way", inn, "inner")])
    # 2 standalone outers
    s1 = closed_polygon(lat - 0.001, lon - 0.001, 0.0006, 5)
    s2 = closed_polygon(lat - 0.001, lon + 0.001, 0.0006, 5)
    members.extend([("way", s1, "outer"), ("way", s2, "outer")])
    add_relation(members, tags)

def gen_235():
    """Extract where the standalone outer is consolidated from open ways."""
    _, tags = make_test(
        "1 closed outer+inner + 1 consolidated outer (2 open ways)",
        "CONSOLIDATE + EXTRACT_OUTERS")
    lat, lon = grid_pos(test_num)
    o1 = closed_polygon(lat, lon - 0.0015, 0.001, 6)
    i1 = closed_polygon(lat, lon - 0.0015, 0.0004, 4)
    nids = polygon_nodes(lat, lon + 0.0015, 0.001, 8)
    w1 = add_way(nids[:5])
    w2 = add_way(nids[4:] + [nids[0]])
    add_relation([("way", o1, "outer"), ("way", i1, "inner"),
                  ("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY G: Unusual polygon shapes
# ============================================================

def gen_236():
    """Very thin, elongated rectangle (1:20 aspect ratio)."""
    _, tags = make_test(
        "very thin elongated rectangle outer",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    w = closed_rect(lat, lon, 0.004, 0.0002)
    add_relation([("way", w, "outer")], tags)

def gen_237():
    """L-shaped polygon (concave)."""
    _, tags = make_test(
        "L-shaped concave outer polygon",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    n = [
        add_node(lat + 0.001, lon - 0.001),
        add_node(lat + 0.001, lon + 0.001),
        add_node(lat, lon + 0.001),
        add_node(lat, lon),
        add_node(lat - 0.001, lon),
        add_node(lat - 0.001, lon - 0.001),
    ]
    w = add_way([n[0], n[1], n[2], n[3], n[4], n[5], n[0]])
    add_relation([("way", w, "outer")], tags)

def gen_238():
    """U-shaped polygon (deep concavity)."""
    _, tags = make_test(
        "U-shaped deep concave outer polygon",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    n = [
        add_node(lat + 0.001, lon - 0.001),
        add_node(lat + 0.001, lon + 0.001),
        add_node(lat + 0.0005, lon + 0.001),
        add_node(lat + 0.0005, lon - 0.0005),
        add_node(lat - 0.0005, lon - 0.0005),
        add_node(lat - 0.0005, lon + 0.001),
        add_node(lat - 0.001, lon + 0.001),
        add_node(lat - 0.001, lon - 0.001),
    ]
    w = add_way([n[i] for i in range(8)] + [n[0]])
    add_relation([("way", w, "outer")], tags)

def gen_239():
    """Star shape (5-pointed, concave)."""
    _, tags = make_test(
        "5-pointed star (concave outer)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = []
    for i in range(10):
        angle = math.pi/2 + 2 * math.pi * i / 10
        r = 0.002 if i % 2 == 0 else 0.0008
        nids.append(add_node(lat + r * math.sin(angle), lon + r * math.cos(angle)))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)

def gen_240():
    """Spiral-like polygon (very concave, nearly self-intersecting)."""
    _, tags = make_test(
        "spiral-like polygon approaching self-intersection",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = []
    for i in range(20):
        angle = 2 * math.pi * i / 20
        r = 0.001 + 0.0005 * math.sin(3 * angle)
        nids.append(add_node(lat + r * math.sin(angle), lon + r * math.cos(angle)))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)

def gen_241():
    """Outer is a perfect square, inner is a diamond (rotated 45 degrees)."""
    _, tags = make_test(
        "square outer + rotated diamond inner (non-touching)",
        "DISSOLVE or EXTRACT")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.0015, 4, start_angle=math.pi/4)
    inner = closed_polygon(lat, lon, 0.0006, 4, start_angle=0)
    add_relation([("way", outer, "outer"), ("way", inner, "inner")], tags)

def gen_242():
    """Inner touches outer at exactly 1 node (figure-8 merge)."""
    _, tags = make_test(
        "outer + inner touching at exactly 1 node",
        "TOUCHING_INNER_MERGE: figure-8 closed way")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.0015, 6)
    # Inner shares only node 0
    inner_nids = [
        outer_nids[0],
        add_node(lat + 0.0005, lon),
        add_node(lat, lon + 0.0005),
    ]
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_243():
    """Crescent: outer circle with large offset inner circle."""
    _, tags = make_test(
        "crescent: outer circle with offset non-touching inner",
        "EXTRACT_OUTERS or DISSOLVE")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.002, 16)
    inner = closed_polygon(lat + 0.0005, lon + 0.0005, 0.0012, 12)
    add_relation([("way", outer, "outer"), ("way", inner, "inner")], tags)


# ============================================================
# CATEGORY H: Multiple relations interacting
# ============================================================

def gen_244():
    """Two relations: one's inner is the other's outer."""
    _, tags1 = make_test(
        "rel1: large outer + inner (inner is rel2's outer)",
        "EXTRACT_OUTERS")
    lat, lon = grid_pos(test_num)
    big = closed_polygon(lat, lon, 0.002, 8)
    shared = closed_polygon(lat, lon, 0.001, 6)
    small = closed_polygon(lat, lon, 0.0004, 4)
    add_relation([("way", big, "outer"), ("way", shared, "inner")], tags1)

    _, tags2 = make_test(
        "rel2: outer is rel1's inner, has its own inner",
        "EXTRACT_OUTERS")
    add_relation([("way", shared, "outer"), ("way", small, "inner")], tags2)

def gen_246():
    """Two relations sharing 2 outer ways (both use same pair of ways)."""
    _, tags1 = make_test(
        "rel1: shares both outer ways with rel2 (different tags)",
        "DISSOLVE",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.001, 0.0008, 5)
    w2 = closed_polygon(lat, lon + 0.001, 0.0008, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags1)

    _, tags2 = make_test(
        "rel2: shares both outer ways with rel1 (different tags)",
        "DISSOLVE",
        {"landuse": "forest"})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags2)

def gen_248():
    """4 relations in a 2x2 grid sharing edges."""
    _, tags1 = make_test(
        "grid 2x2 top-left",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    # Create shared corner/edge nodes
    c = [[add_node(lat + 0.001 - i*0.001, lon - 0.001 + j*0.001)
          for j in range(3)] for i in range(3)]
    w1 = add_way([c[0][0], c[0][1], c[1][1], c[1][0], c[0][0]])
    add_relation([("way", w1, "outer")], tags1)

    _, tags2 = make_test("grid 2x2 top-right", "DISSOLVE")
    w2 = add_way([c[0][1], c[0][2], c[1][2], c[1][1], c[0][1]])
    add_relation([("way", w2, "outer")], tags2)

    _, tags3 = make_test("grid 2x2 bottom-left", "DISSOLVE")
    w3 = add_way([c[1][0], c[1][1], c[2][1], c[2][0], c[1][0]])
    add_relation([("way", w3, "outer")], tags3)

    _, tags4 = make_test("grid 2x2 bottom-right", "DISSOLVE")
    w4 = add_way([c[1][1], c[1][2], c[2][2], c[2][1], c[1][1]])
    add_relation([("way", w4, "outer")], tags4)


# ============================================================
# CATEGORY I: Consolidation that creates self-intersections
# ============================================================

def gen_252():
    """2 open ways forming ring that self-intersects when chained in wrong order."""
    _, tags = make_test(
        "2 open ways forming self-intersecting ring when chained",
        "CONSOLIDATE + DECOMPOSE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    # Way1: top-left to bottom-right
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat - 0.001, lon + 0.001)
    # Way2: top-right to bottom-left (crossing way1)
    n3 = add_node(lat + 0.001, lon + 0.001)
    n4 = add_node(lat - 0.001, lon - 0.001)
    w1 = add_way([n1, n2])
    w2 = add_way([n2, n3, n1, n4, n2])
    # This forms: n1->n2->n3->n1->n4->n2 which self-intersects
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_253():
    """3 open ways that chain into a figure-8."""
    _, tags = make_test(
        "3 open ways chaining into figure-8 (crosses at center)",
        "CONSOLIDATE + DECOMPOSE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    # Center node
    top = add_node(lat + 0.001, lon)
    right = add_node(lat, lon + 0.001)
    bottom = add_node(lat - 0.001, lon)
    left = add_node(lat, lon - 0.001)
    tr = add_node(lat + 0.0007, lon + 0.0007)
    bl = add_node(lat - 0.0007, lon - 0.0007)
    w1 = add_way([top, tr, right])
    w2 = add_way([right, bottom, bl, left])
    w3 = add_way([left, top])
    # Chain: top->tr->right->bottom->bl->left->top
    # Segments top-tr-right go NE, then bottom-bl-left go SW, creates crossing
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)


# ============================================================
# CATEGORY J: Degenerate and edge cases
# ============================================================

def gen_254():
    """Outer with 3 nodes (minimal triangle)."""
    _, tags = make_test(
        "minimal triangle outer (3 nodes + closing)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 3)
    add_relation([("way", w, "outer")], tags)

def gen_255():
    """Outer way is a very thin sliver (nearly degenerate)."""
    _, tags = make_test(
        "very thin sliver outer (near-degenerate geometry)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat, lon - 0.002)
    n2 = add_node(lat + 0.00001, lon)
    n3 = add_node(lat, lon + 0.002)
    n4 = add_node(lat - 0.00001, lon)
    w = add_way([n1, n2, n3, n4, n1])
    add_relation([("way", w, "outer")], tags)

def gen_256():
    """Outer where two adjacent nodes are at the same location."""
    _, tags = make_test(
        "outer with two coincident nodes",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon)
    n2 = add_node(lat, lon + 0.001)
    n3 = add_node(lat, lon + 0.001)  # same coords as n2
    n4 = add_node(lat - 0.001, lon)
    n5 = add_node(lat, lon - 0.001)
    w = add_way([n1, n2, n3, n4, n5, n1])
    add_relation([("way", w, "outer")], tags)

def gen_257():
    """Inner is larger than outer (geometrically invalid but structurally valid)."""
    _, tags = make_test(
        "inner larger than outer (invalid geometry, skip expected)",
        "SKIP or EXTRACT")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.0005, 4)
    inner = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", outer, "outer"), ("way", inner, "inner")], tags)

def gen_258():
    """Outer polygon with 100 nodes (high node count)."""
    _, tags = make_test(
        "outer with 100 nodes (high node count)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.002, 100)
    add_relation([("way", w, "outer")], tags)

def gen_259():
    """Two outers: one tiny inside a large one (different connected components)."""
    _, tags = make_test(
        "large outer + tiny outer inside it (not connected by nodes)",
        "DISSOLVE: both tagged")
    lat, lon = grid_pos(test_num)
    big = closed_polygon(lat, lon, 0.002, 8)
    tiny = closed_polygon(lat, lon, 0.0002, 4)
    add_relation([("way", big, "outer"), ("way", tiny, "outer")], tags)


# ============================================================
# CATEGORY K: Touching inner merge edge cases
# ============================================================

def gen_260():
    """Inner touches outer at 2 non-adjacent shared nodes, inner passes through center."""
    _, tags = make_test(
        "inner bridges across outer (2 shared nodes on opposite sides)",
        "TOUCHING_INNER_MERGE: splits outer into 2 ways")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 8)
    inner_nids = [
        outer_nids[0],
        add_node(lat + 0.0008, lon + 0.0003),
        add_node(lat, lon + 0.0005),
        add_node(lat - 0.0008, lon + 0.0003),
        outer_nids[4],
        add_node(lat - 0.0008, lon - 0.0003),
        add_node(lat, lon - 0.0005),
        add_node(lat + 0.0008, lon - 0.0003),
    ]
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_261():
    """Inner + outer share every other node (alternating shared/private)."""
    _, tags = make_test(
        "inner shares alternating nodes with outer (every other node)",
        "TOUCHING_INNER_MERGE")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 8)
    # Inner shares nodes 0, 2, 4, 6
    inner_nids = []
    for i in range(4):
        inner_nids.append(outer_nids[i * 2])
        inner_nids.append(add_node(
            lat + 0.001 * math.sin(2 * math.pi * (i * 2 + 1) / 8),
            lon + 0.001 * math.cos(2 * math.pi * (i * 2 + 1) / 8)))
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_262():
    """Extract 2 standalone outers + touching inner merge for remaining."""
    _, tags = make_test(
        "3 outers + 1 inner: 2 standalone outers extracted, 1 outer+inner merged",
        "EXTRACT + TOUCHING_INNER_MERGE")
    lat, lon = grid_pos(test_num)
    # Main outer + inner sharing 2 nodes
    outer_nids = polygon_nodes(lat, lon, 0.0012, 8)
    shared1 = outer_nids[0]
    shared2 = outer_nids[4]
    inner_nids = [shared1, add_node(lat + 0.0004, lon), shared2, add_node(lat - 0.0004, lon)]
    main_outer = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    # 2 standalone outers
    s1 = closed_polygon(lat, lon - 0.003, 0.0006, 5)
    s2 = closed_polygon(lat, lon + 0.003, 0.0006, 5)
    add_relation([("way", main_outer, "outer"), ("way", inner_w, "inner"),
                  ("way", s1, "outer"), ("way", s2, "outer")], tags)


# ============================================================
# CATEGORY L: Multiple intermingled relations with shared ways
# ============================================================

def gen_263():
    """3 relations forming a chain: each shares 1 way with the next."""
    _, tags1 = make_test(
        "chain rel1: uses way A and way B",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    wA = closed_polygon(lat, lon - 0.002, 0.0007, 5)
    wB = closed_polygon(lat, lon, 0.0007, 5)
    wC = closed_polygon(lat, lon + 0.002, 0.0007, 5)
    add_relation([("way", wA, "outer"), ("way", wB, "outer")], tags1)

    _, tags2 = make_test(
        "chain rel2: uses way B and way C",
        "DISSOLVE")
    add_relation([("way", wB, "outer"), ("way", wC, "outer")], tags2)

    _, tags3 = make_test(
        "chain rel3: uses only way C",
        "DISSOLVE")
    add_relation([("way", wC, "outer")], tags3)

def gen_266():
    """Two relations with overlapping consolidation: same 2 open ways in both."""
    _, tags1 = make_test(
        "rel1: 2 shared open ways + 1 own closed outer",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = polygon_nodes(lat, lon, 0.001, 8)
    shared_w1 = add_way(nids[:5])
    shared_w2 = add_way(nids[4:] + [nids[0]])
    own1 = closed_polygon(lat, lon - 0.003, 0.0006, 5)
    add_relation([("way", shared_w1, "outer"), ("way", shared_w2, "outer"),
                  ("way", own1, "outer")], tags1)

    _, tags2 = make_test(
        "rel2: same 2 shared open ways + 1 own closed outer",
        "CONSOLIDATE + DISSOLVE (shares consolidation)")
    own2 = closed_polygon(lat, lon + 0.003, 0.0006, 5)
    add_relation([("way", shared_w1, "outer"), ("way", shared_w2, "outer"),
                  ("way", own2, "outer")], tags2)


# ============================================================
# CATEGORY M: Open inner rings (consolidation needed)
# ============================================================

def gen_268():
    """Closed outer + inner made of 2 open ways."""
    _, tags = make_test(
        "closed outer + inner from 2 open ways (inner consolidation)",
        "CONSOLIDATE inner or complex")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.002, 8)
    inner_nids = polygon_nodes(lat, lon, 0.0008, 6)
    iw1 = add_way(inner_nids[:4])
    iw2 = add_way(inner_nids[3:] + [inner_nids[0]])
    add_relation([("way", outer, "outer"), ("way", iw1, "inner"), ("way", iw2, "inner")], tags)

def gen_269():
    """Closed outer + inner made of 3 open ways."""
    _, tags = make_test(
        "closed outer + inner from 3 open ways",
        "CONSOLIDATE inner or complex")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.002, 8)
    inner_nids = polygon_nodes(lat, lon, 0.0008, 9)
    iw1 = add_way(inner_nids[:4])
    iw2 = add_way(inner_nids[3:7])
    iw3 = add_way(inner_nids[6:] + [inner_nids[0]])
    add_relation([("way", outer, "outer"),
                  ("way", iw1, "inner"), ("way", iw2, "inner"), ("way", iw3, "inner")], tags)


# ============================================================
# CATEGORY N: Complex real-world-like patterns
# ============================================================

def gen_270():
    """Forest with 3 clearings (1 outer + 3 non-touching inners)."""
    _, tags = make_test(
        "forest with 3 clearings (1 outer + 3 inners)",
        "SKIP: multiple inners that don't touch, keep relation",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.002, 12)
    i1 = closed_polygon(lat + 0.0005, lon - 0.0005, 0.0004, 4)
    i2 = closed_polygon(lat + 0.0005, lon + 0.0005, 0.0004, 4)
    i3 = closed_polygon(lat - 0.0005, lon, 0.0004, 4)
    add_relation([("way", outer, "outer"),
                  ("way", i1, "inner"), ("way", i2, "inner"), ("way", i3, "inner")], tags)

def gen_271():
    """Lake with island that has a pond (nested multipolygons)."""
    _, tags1 = make_test(
        "lake (outer) with island (inner)",
        "SKIP: 1 outer + 1 non-touching inner")
    lat, lon = grid_pos(test_num)
    lake = closed_polygon(lat, lon, 0.002, 12)
    island = closed_polygon(lat, lon, 0.001, 8)
    add_relation([("way", lake, "outer"), ("way", island, "inner")], tags1)

    _, tags2 = make_test(
        "pond on island (island is outer, pond is inner)",
        "SKIP: nested inner")
    pond = closed_polygon(lat, lon, 0.0004, 6)
    add_relation([("way", island, "outer"), ("way", pond, "inner")], tags2)

def gen_273():
    """Farmland split by road: 2 open ways share road nodes."""
    _, tags = make_test(
        "2 open outer ways sharing endpoints (road split farmland)",
        "CONSOLIDATE + DISSOLVE",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    # Road endpoints
    r1 = add_node(lat + 0.001, lon)
    r2 = add_node(lat - 0.001, lon)
    # Left field
    l1 = add_node(lat + 0.0008, lon - 0.001)
    l2 = add_node(lat - 0.0008, lon - 0.001)
    # Right field
    rr1 = add_node(lat + 0.0008, lon + 0.001)
    rr2 = add_node(lat - 0.0008, lon + 0.001)
    w1 = add_way([r1, l1, l2, r2])
    w2 = add_way([r2, rr2, rr1, r1])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_274():
    """Residential area with 5 building-shaped inners."""
    _, tags = make_test(
        "1 outer + 5 small rectangular non-touching inners",
        "SKIP: too many inners, keep relation",
        {"landuse": "residential"})
    lat, lon = grid_pos(test_num)
    outer = closed_rect(lat, lon, 0.004, 0.003)
    members = [("way", outer, "outer")]
    for i in range(5):
        ix = (i % 3 - 1) * 0.0012
        iy = (i // 3 - 0.5) * 0.001
        b = closed_rect(lat + iy, lon + ix, 0.0006, 0.0004)
        members.append(("way", b, "inner"))
    add_relation(members, tags)

def gen_275():
    """Wetland with meandering boundary (many nodes, irregular shape)."""
    _, tags = make_test(
        "irregular wetland boundary (30 nodes, meandering)",
        "DISSOLVE",
        {"natural": "wetland"})
    lat, lon = grid_pos(test_num)
    nids = []
    for i in range(30):
        angle = 2 * math.pi * i / 30
        r = 0.0015 + 0.0005 * math.sin(5 * angle) + 0.0003 * math.cos(7 * angle)
        nids.append(add_node(lat + r * math.sin(angle), lon + r * math.cos(angle)))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY O: More SPLIT_RELATION patterns
# ============================================================

def gen_276():
    """5 disconnected closed outers, no inners."""
    _, tags = make_test(
        "5 disconnected closed outers (archipelago pattern)",
        "SPLIT_RELATION or DISSOLVE: all become standalone")
    lat, lon = grid_pos(test_num)
    members = []
    positions = [(-0.001, -0.001), (-0.001, 0.001), (0.001, 0), (0, -0.002), (0, 0.002)]
    for dy, dx in positions:
        w = closed_polygon(lat + dy, lon + dx, 0.0005, 5)
        members.append(("way", w, "outer"))
    add_relation(members, tags)

def gen_277():
    """2 groups: group 1 has 2 outers sharing an inner, group 2 is standalone."""
    _, tags = make_test(
        "disconnected: 2 outers + shared inner + 1 standalone outer",
        "SPLIT_RELATION")
    lat, lon = grid_pos(test_num)
    o1 = closed_polygon(lat, lon - 0.002, 0.001, 6)
    o2 = closed_polygon(lat, lon, 0.001, 6)
    # inner spatially between o1 and o2
    inner = closed_polygon(lat, lon - 0.001, 0.0004, 4)
    standalone = closed_polygon(lat, lon + 0.003, 0.0008, 5)
    add_relation([("way", o1, "outer"), ("way", o2, "outer"),
                  ("way", inner, "inner"), ("way", standalone, "outer")], tags)


# ============================================================
# CATEGORY P: Way ordering shouldn't matter
# ============================================================

def gen_278():
    """Members listed inner-first, then outer."""
    _, tags = make_test(
        "members listed inner before outer (unusual order)",
        "DISSOLVE or SKIP")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.0015, 6)
    inner = closed_polygon(lat, lon, 0.0005, 4)
    add_relation([("way", inner, "inner"), ("way", outer, "outer")], tags)

def gen_279():
    """3 open ways in scrambled order."""
    _, tags = make_test(
        "3 open ways listed in non-chaining order",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = polygon_nodes(lat, lon, 0.001, 9)
    w1 = add_way(nids[0:4])
    w2 = add_way(nids[3:7])
    w3 = add_way(nids[6:] + [nids[0]])
    # List in scrambled order
    add_relation([("way", w2, "outer"), ("way", w3, "outer"), ("way", w1, "outer")], tags)


# ============================================================
# CATEGORY Q: More complex touching inner patterns
# ============================================================

def gen_280():
    """Outer + inner where inner touches at 2 separate single nodes."""
    _, tags = make_test(
        "inner touches outer at 2 separate single shared nodes",
        "TOUCHING_INNER_MERGE: 2 inner segments produce ways")
    lat, lon = grid_pos(test_num)
    outer_nids = polygon_nodes(lat, lon, 0.002, 8)
    inner_nids = [
        outer_nids[0],
        add_node(lat + 0.0008, lon + 0.0005),
        add_node(lat + 0.0003, lon + 0.0008),
        outer_nids[3],
        add_node(lat - 0.0003, lon + 0.0005),
        add_node(lat - 0.0008, lon - 0.0002),
    ]
    outer_w = add_way(outer_nids + [outer_nids[0]])
    inner_w = add_way(inner_nids + [inner_nids[0]])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)

def gen_281():
    """Inner is a rectangle that perfectly aligns with one side of outer square."""
    _, tags = make_test(
        "rectangular inner sharing entire top edge with square outer (2 shared nodes)",
        "TOUCHING_INNER_MERGE")
    lat, lon = grid_pos(test_num)
    # Square outer
    tl = add_node(lat + 0.001, lon - 0.001)
    tr = add_node(lat + 0.001, lon + 0.001)
    br = add_node(lat - 0.001, lon + 0.001)
    bl = add_node(lat - 0.001, lon - 0.001)
    outer_w = add_way([tl, tr, br, bl, tl])
    # Inner rectangle along top edge
    it1 = add_node(lat + 0.0005, lon - 0.0005)
    it2 = add_node(lat + 0.0005, lon + 0.0005)
    inner_w = add_way([tl, tr, it2, it1, tl])
    add_relation([("way", outer_w, "outer"), ("way", inner_w, "inner")], tags)


# ============================================================
# CATEGORY R: Additional geometry stress tests
# ============================================================

def gen_282():
    """2 concentric hexagons (outer + non-touching inner)."""
    _, tags = make_test(
        "concentric hexagons (outer + non-touching inner)",
        "SKIP: non-touching inner, keep relation")
    lat, lon = grid_pos(test_num)
    outer = closed_polygon(lat, lon, 0.002, 6)
    inner = closed_polygon(lat, lon, 0.001, 6, start_angle=math.pi/6)
    add_relation([("way", outer, "outer"), ("way", inner, "inner")], tags)

def gen_283():
    """Outer made of 6 tiny segments (each way has only 2 nodes)."""
    _, tags = make_test(
        "ring from 6 minimal 2-node open ways",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = polygon_nodes(lat, lon, 0.001, 6)
    members = []
    for i in range(6):
        w = add_way([nids[i], nids[(i + 1) % 6]])
        members.append(("way", w, "outer"))
    add_relation(members, tags)

def gen_284():
    """2 outers that nearly touch (gap of ~1m) — should remain separate."""
    _, tags = make_test(
        "2 outers nearly touching (tiny gap, remain separate)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.00055, 0.0005, 6)
    w2 = closed_polygon(lat, lon + 0.00055, 0.0005, 6)
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_285():
    """Outer with 200 nodes (stress test)."""
    _, tags = make_test(
        "high-node-count outer (200 nodes)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.002, 200)
    add_relation([("way", w, "outer")], tags)

def gen_286():
    """C-shaped outer (horseshoe, very concave)."""
    _, tags = make_test(
        "C-shaped horseshoe outer (deep concavity, nearly closed)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = []
    for i in range(16):
        angle = math.pi * 1.6 * i / 15 - math.pi * 0.3
        r = 0.0015
        nids.append(add_node(lat + r * math.sin(angle), lon + r * math.cos(angle)))
    # Close via a short path
    nids.append(add_node(lat, lon - 0.0005))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)

def gen_287():
    """T-shaped polygon."""
    _, tags = make_test(
        "T-shaped polygon outer",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    n = [
        add_node(lat + 0.001, lon - 0.002),   # top-left
        add_node(lat + 0.001, lon + 0.002),    # top-right
        add_node(lat + 0.0005, lon + 0.002),   # inner top-right
        add_node(lat + 0.0005, lon + 0.0003),  # inner right step
        add_node(lat - 0.001, lon + 0.0003),   # bottom-right
        add_node(lat - 0.001, lon - 0.0003),   # bottom-left
        add_node(lat + 0.0005, lon - 0.0003),  # inner left step
        add_node(lat + 0.0005, lon - 0.002),   # inner top-left
    ]
    w = add_way([n[i] for i in range(8)] + [n[0]])
    add_relation([("way", w, "outer")], tags)

def gen_288():
    """Plus (+) shaped polygon."""
    _, tags = make_test(
        "plus-shaped polygon outer",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    s = 0.0005
    n = [
        add_node(lat + s, lon - s),   # 0
        add_node(lat + s, lon + s),   # 1
        add_node(lat + 2*s, lon + s), # 2
        add_node(lat + 2*s, lon - s), # 3  (top arm)
        # wait, let me do this properly
    ]
    # Clear and redo
    nodes_list = nodes  # reference to global
    # Remove last 4
    for _ in range(4):
        nodes_list.pop()
    # Plus shape
    n = [
        add_node(lat + 2*s, lon - s),   # 0 top-left of top arm
        add_node(lat + 2*s, lon + s),   # 1 top-right of top arm
        add_node(lat + s, lon + s),     # 2
        add_node(lat + s, lon + 2*s),   # 3
        add_node(lat - s, lon + 2*s),   # 4
        add_node(lat - s, lon + s),     # 5
        add_node(lat - 2*s, lon + s),   # 6
        add_node(lat - 2*s, lon - s),   # 7
        add_node(lat - s, lon - s),     # 8
        add_node(lat - s, lon - 2*s),   # 9
        add_node(lat + s, lon - 2*s),   # 10
        add_node(lat + s, lon - s),     # 11
    ]
    w = add_way([n[i] for i in range(12)] + [n[0]])
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY S: More intermingled multipolygon combos
# ============================================================

def gen_289():
    """Donut: relation A is the ring, relation B is the hole (both have same inner)."""
    _, tags1 = make_test(
        "donut rel1: outer ring + inner hole",
        "SKIP: 1 outer + 1 non-touching inner")
    lat, lon = grid_pos(test_num)
    ring = closed_polygon(lat, lon, 0.002, 10)
    hole = closed_polygon(lat, lon, 0.001, 8)
    add_relation([("way", ring, "outer"), ("way", hole, "inner")], tags1)

    _, tags2 = make_test(
        "donut rel2: hole as outer (simple dissolve)",
        "DISSOLVE",
        {"landuse": "grass"})
    add_relation([("way", hole, "outer")], tags2)

def gen_291():
    """3 relations: each has 1 outer, all 3 outers share the same inner."""
    _, tags1 = make_test(
        "3-way shared inner: rel1",
        "EXTRACT_OUTERS or SKIP")
    lat, lon = grid_pos(test_num)
    shared_inner = closed_polygon(lat, lon, 0.0003, 4)
    o1 = closed_polygon(lat + 0.001, lon, 0.001, 6)
    add_relation([("way", o1, "outer"), ("way", shared_inner, "inner")], tags1)

    _, tags2 = make_test("3-way shared inner: rel2", "EXTRACT_OUTERS or SKIP")
    o2 = closed_polygon(lat - 0.0005, lon - 0.0008, 0.001, 6)
    add_relation([("way", o2, "outer"), ("way", shared_inner, "inner")], tags2)

    _, tags3 = make_test("3-way shared inner: rel3", "EXTRACT_OUTERS or SKIP")
    o3 = closed_polygon(lat - 0.0005, lon + 0.0008, 0.001, 6)
    add_relation([("way", o3, "outer"), ("way", shared_inner, "inner")], tags3)

def gen_294():
    """2 relations whose consolidations would conflict (share 1 open way)."""
    _, tags1 = make_test(
        "shared consolidation: rel1 uses ways A+B",
        "CONSOLIDATE + DISSOLVE")
    lat, lon = grid_pos(test_num)
    # Shared way
    s1 = add_node(lat + 0.001, lon)
    s2 = add_node(lat - 0.001, lon)
    shared_w = add_way([s1, s2])
    # Rel1's other way (left)
    l1 = add_node(lat + 0.0008, lon - 0.001)
    l2 = add_node(lat - 0.0008, lon - 0.001)
    w1 = add_way([s2, l2, l1, s1])
    add_relation([("way", shared_w, "outer"), ("way", w1, "outer")], tags1)

    _, tags2 = make_test(
        "shared consolidation: rel2 uses ways A+C",
        "CONSOLIDATE + DISSOLVE")
    r1 = add_node(lat + 0.0008, lon + 0.001)
    r2 = add_node(lat - 0.0008, lon + 0.001)
    w2 = add_way([s2, r2, r1, s1])
    add_relation([("way", shared_w, "outer"), ("way", w2, "outer")], tags2)


# ============================================================
# CATEGORY T: Remaining geometry tests to reach 100
# ============================================================

def gen_296():
    """Arrow/chevron shape."""
    _, tags = make_test(
        "arrow/chevron shaped outer",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    n = [
        add_node(lat, lon - 0.002),        # tip
        add_node(lat + 0.001, lon + 0.001),  # top
        add_node(lat + 0.0003, lon),        # inner top
        add_node(lat - 0.0003, lon),        # inner bottom
        add_node(lat - 0.001, lon + 0.001),  # bottom
    ]
    w = add_way([n[i] for i in range(5)] + [n[0]])
    add_relation([("way", w, "outer")], tags)

def gen_297():
    """Zigzag/sawtooth shape."""
    _, tags = make_test(
        "zigzag sawtooth shaped outer (10 teeth)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = []
    for i in range(10):
        x = lon - 0.002 + i * 0.0004
        nids.append(add_node(lat + 0.001, x))
        nids.append(add_node(lat + 0.0005, x + 0.0002))
    # Bottom edge
    nids.append(add_node(lat - 0.001, lon + 0.002))
    nids.append(add_node(lat - 0.001, lon - 0.002))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)

def gen_298():
    """Pac-Man shape (circle with wedge cut out)."""
    _, tags = make_test(
        "pac-man shape (circle with angular wedge, concave)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = []
    center = (lat, lon)
    # Mouth opening
    nids.append(add_node(lat, lon))  # center (mouth vertex)
    for i in range(12):
        angle = math.pi * 0.3 + 2 * math.pi * i / 12 * (1 - 0.15)
        nids.append(add_node(lat + 0.0015 * math.sin(angle), lon + 0.0015 * math.cos(angle)))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)

def gen_299():
    """Dumbbell: two circles connected by narrow corridor."""
    _, tags = make_test(
        "dumbbell shape (2 circles + narrow connector)",
        "DISSOLVE")
    lat, lon = grid_pos(test_num)
    nids = []
    # Left circle
    for i in range(8):
        angle = math.pi/2 + 2 * math.pi * i / 8
        nids.append(add_node(lat + 0.001 * math.sin(angle), lon - 0.0015 + 0.001 * math.cos(angle)))
    # Corridor top
    nids.append(add_node(lat + 0.0002, lon - 0.0005))
    nids.append(add_node(lat + 0.0002, lon + 0.0005))
    # Right circle
    for i in range(8):
        angle = math.pi/2 + 2 * math.pi * i / 8
        nids.append(add_node(lat + 0.001 * math.sin(angle), lon + 0.0015 + 0.001 * math.cos(angle)))
    # Corridor bottom
    nids.append(add_node(lat - 0.0002, lon + 0.0005))
    nids.append(add_node(lat - 0.0002, lon - 0.0005))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)


# ===== Assemble =====

def generate_all():
    gen_200()
    gen_201()
    gen_202()
    gen_203()
    gen_204()
    gen_205_real()   # 205
    gen_206()
    gen_207()
    gen_208()
    gen_209()
    gen_210()        # 210
    gen_211()        # 211-212
    gen_213()        # 213-215
    gen_216()        # 216-217
    gen_218()        # 218-219
    gen_220()        # 220-222
    gen_223()
    gen_224()
    gen_225()
    gen_226()
    gen_227()
    gen_228()        # 228
    gen_229()
    gen_230()
    gen_231()
    gen_232()
    gen_233()        # 233
    gen_234()
    gen_235()        # 235
    gen_236()
    gen_237()
    gen_238()
    gen_239()
    gen_240()        # 240
    gen_241()
    gen_242()
    gen_243()        # 243
    gen_244()        # 244-245
    gen_246()        # 246-247
    gen_248()        # 248-251
    gen_252()
    gen_253()        # 253
    gen_254()
    gen_255()
    gen_256()
    gen_257()
    gen_258()
    gen_259()        # 259
    gen_260()
    gen_261()
    gen_262()        # 262
    gen_263()        # 263-265
    gen_266()        # 266-267
    gen_268()
    gen_269()        # 269
    gen_270()
    gen_271()        # 271-272
    gen_273()
    gen_274()
    gen_275()        # 275
    gen_276()
    gen_277()        # 277
    gen_278()
    gen_279()        # 279
    gen_280()
    gen_281()        # 281
    gen_282()
    gen_283()
    gen_284()
    gen_285()
    gen_286()
    gen_287()
    gen_288()        # 288
    gen_289()        # 289-290
    gen_291()        # 291-293
    gen_294()        # 294-295
    gen_296()
    gen_297()
    gen_298()
    gen_299()        # 299


def escape_xml(s):
    return (str(s)
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace("'", '&apos;')
        .replace('"', '&quot;'))


def write_osm(filename):
    with open(filename, 'w', encoding='utf-8', newline='\n') as f:
        f.write("<?xml version='1.0' encoding='UTF-8'?>\n")
        f.write("<osm version='0.6' generator='multipoly-gone geometry test generator'>\n")

        for nid, lat, lon, tags in nodes:
            if tags:
                f.write(f"  <node id='{nid}' action='modify' visible='true' lat='{lat:.11f}' lon='{lon:.11f}'>\n")
                for k, v in tags.items():
                    f.write(f"    <tag k='{escape_xml(k)}' v='{escape_xml(v)}' />\n")
                f.write("  </node>\n")
            else:
                f.write(f"  <node id='{nid}' action='modify' visible='true' lat='{lat:.11f}' lon='{lon:.11f}' />\n")

        for wid, node_ids, tags in ways:
            f.write(f"  <way id='{wid}' action='modify' visible='true'>\n")
            for nid in node_ids:
                f.write(f"    <nd ref='{nid}' />\n")
            for k, v in tags.items():
                f.write(f"    <tag k='{escape_xml(k)}' v='{escape_xml(v)}' />\n")
            f.write("  </way>\n")

        for rid, members, tags in relations:
            f.write(f"  <relation id='{rid}' action='modify' visible='true'>\n")
            for mtype, ref, role in members:
                f.write(f"    <member type='{mtype}' ref='{ref}' role='{escape_xml(role)}' />\n")
            for k, v in tags.items():
                f.write(f"    <tag k='{escape_xml(k)}' v='{escape_xml(v)}' />\n")
            f.write("  </relation>\n")

        f.write("</osm>\n")


if __name__ == '__main__':
    generate_all()
    print(f"Generated {test_num - 199} test cases (IDs 200-{test_num})")
    print(f"  {len(nodes)} nodes, {len(ways)} ways, {len(relations)} relations")
    write_osm('tests/testdata-geometry.osm')
    print("Written to tests/testdata-geometry.osm")
