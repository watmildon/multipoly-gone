#!/usr/bin/env python3
"""
Generate testdata-proposed.osm with 100+ test cases for the multipoly-gone plugin.

Each relation has:
  _test_id:       unique test identifier
  _test_notes:    describes the scenario
  _test_expected: what should happen when fixed

Test cases are laid out in a grid pattern to avoid overlaps when viewed in JOSM.
"""

import math

# ===== Global counters =====
_next_node_id = -200000
_next_way_id = -200000
_next_rel_id = -200000

nodes = []       # list of (id, lat, lon, tags_dict)
ways = []        # list of (id, node_ids_list, tags_dict)
relations = []   # list of (id, members_list, tags_dict)
                 # members_list: list of (type, ref_id, role)


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


def closed_polygon(center_lat, center_lon, radius=0.001, n=6, tags=None):
    """Create a closed way polygon centered at (lat, lon)."""
    nids = []
    for i in range(n):
        angle = 2 * math.pi * i / n
        lat = center_lat + radius * math.sin(angle)
        lon = center_lon + radius * math.cos(angle)
        nids.append(add_node(lat, lon))
    nids.append(nids[0])  # close it
    return add_way(nids, tags)


def closed_polygon_nodes(center_lat, center_lon, radius=0.001, n=6):
    """Create nodes for a polygon and return their IDs (NOT closed yet)."""
    nids = []
    for i in range(n):
        angle = 2 * math.pi * i / n
        lat = center_lat + radius * math.sin(angle)
        lon = center_lon + radius * math.cos(angle)
        nids.append(add_node(lat, lon))
    return nids


def split_way_at(nids_closed, split_indices):
    """
    Given a closed list of node IDs (last == first), split into open ways
    at the given indices. Returns list of way IDs.
    """
    n = len(nids_closed) - 1  # number of unique nodes
    splits = sorted(set(s % n for s in split_indices))
    if len(splits) < 2:
        # Need at least 2 split points to make open ways
        splits = [0, n // 2]

    way_ids = []
    for i in range(len(splits)):
        start = splits[i]
        end = splits[(i + 1) % len(splits)]
        if end <= start:
            end += n
        seg_nids = []
        for j in range(start, end + 1):
            seg_nids.append(nids_closed[j % n])
        way_ids.append(add_way(seg_nids))
    return way_ids


def grid_pos(test_num, cols=10):
    """Calculate lat/lon for a test case based on its number, arranged in a grid."""
    row = test_num // cols
    col = test_num % cols
    base_lat = 47.5
    base_lon = -122.5
    spacing = 0.005
    return base_lat + row * spacing, base_lon + col * spacing


# ===== Test case generators =====

test_num = 0

def make_test(notes, expected, rel_tags_extra=None):
    """Helper to build common relation tags."""
    global test_num
    test_num += 1
    tid = str(test_num)
    tags = {"type": "multipolygon"}
    if rel_tags_extra:
        tags.update(rel_tags_extra)
    tags["_test_id"] = tid
    tags["_test_notes"] = notes
    tags["_test_expected"] = expected
    return tid, tags


# ============================================================
# CATEGORY 1: Simple DISSOLVE (closed outers, no inners)
# ============================================================

def gen_dissolve_1_closed_outer():
    """Single closed outer, no inners."""
    tid, tags = make_test(
        "1 closed outer, no inners, landuse=grass",
        "DISSOLVE: tag the way with landuse=grass, delete relation",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 5)
    add_relation([("way", w, "outer")], tags)

def gen_dissolve_2_closed_outers():
    """Two closed outers, no inners."""
    tid, tags = make_test(
        "2 closed outers, no inners, natural=wood",
        "DISSOLVE: tag both ways with natural=wood, delete relation",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.0008, 0.0006, 5)
    w2 = closed_polygon(lat, lon + 0.0008, 0.0006, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_dissolve_3_closed_outers():
    """Three closed outers, no inners."""
    tid, tags = make_test(
        "3 closed outers, no inners, landuse=farmland",
        "DISSOLVE: tag all 3 ways with landuse=farmland, delete relation",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat + 0.0008, lon, 0.0005, 4)
    w2 = closed_polygon(lat - 0.0005, lon - 0.0008, 0.0005, 4)
    w3 = closed_polygon(lat - 0.0005, lon + 0.0008, 0.0005, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_dissolve_single_triangle():
    """Single closed triangular outer."""
    tid, tags = make_test(
        "1 closed triangle outer, natural=scrub",
        "DISSOLVE: tag way with natural=scrub, delete relation",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 3)
    add_relation([("way", w, "outer")], tags)

def gen_dissolve_single_complex():
    """Single closed outer with many nodes."""
    tid, tags = make_test(
        "1 closed outer with 20 nodes, landuse=meadow",
        "DISSOLVE: tag way with landuse=meadow, delete relation",
        {"landuse": "meadow"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.0015, 20)
    add_relation([("way", w, "outer")], tags)

def gen_dissolve_with_source_tag():
    """Outer way has source tag (insignificant)."""
    tid, tags = make_test(
        "1 closed outer with source= tag (insignificant), natural=heath",
        "DISSOLVE: way gets natural=heath; source tag already present is OK",
        {"natural": "heath"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6, {"source": "bing"})
    add_relation([("way", w, "outer")], tags)

def gen_dissolve_5_closed_outers():
    """Five closed outers, no inners."""
    tid, tags = make_test(
        "5 closed outers, no inners, natural=wetland",
        "DISSOLVE: tag all 5 ways with natural=wetland, delete relation",
        {"natural": "wetland"})
    lat, lon = grid_pos(test_num)
    for i in range(5):
        angle = 2 * math.pi * i / 5
        wlat = lat + 0.001 * math.sin(angle)
        wlon = lon + 0.001 * math.cos(angle)
        w = closed_polygon(wlat, wlon, 0.0004, 4)
        tags_copy = dict(tags) if i == 0 else None
        if i == 0:
            members = [("way", w, "outer")]
        else:
            members.append(("way", w, "outer"))
    add_relation(members, tags)

def gen_dissolve_with_multiple_tags():
    """Relation has multiple transferrable tags."""
    tid, tags = make_test(
        "1 closed outer, relation has landuse=grass + name=Test Park",
        "DISSOLVE: way gets both landuse=grass and name=Test Park",
        {"landuse": "grass", "name": "Test Park"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 2: CONSOLIDATE_RINGS + DISSOLVE
# ============================================================

def gen_consolidate_2way_chain():
    """2 open ways forming a ring."""
    tid, tags = make_test(
        "2 open outer ways forming 1 ring, landuse=grass",
        "CONSOLIDATE + DISSOLVE: chain into closed way, tag, delete relation",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 3])
    add_relation([("way", w, "outer") for w in way_ids], tags)

def gen_consolidate_3way_chain():
    """3 open ways forming a ring."""
    tid, tags = make_test(
        "3 open outer ways forming 1 ring, natural=water",
        "CONSOLIDATE + DISSOLVE: chain into closed way, tag, delete relation",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 9)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 3, 6])
    add_relation([("way", w, "outer") for w in way_ids], tags)

def gen_consolidate_4way_chain():
    """4 open ways forming a ring."""
    tid, tags = make_test(
        "4 open outer ways forming 1 ring, natural=scrub",
        "CONSOLIDATE + DISSOLVE: chain into closed way, tag, delete relation",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 8)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 2, 4, 6])
    add_relation([("way", w, "outer") for w in way_ids], tags)

def gen_consolidate_5way_chain():
    """5 open ways forming a ring."""
    tid, tags = make_test(
        "5 open outer ways forming 1 ring, landuse=farmland",
        "CONSOLIDATE + DISSOLVE: chain into closed way, tag, delete relation",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 10)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 2, 4, 6, 8])
    add_relation([("way", w, "outer") for w in way_ids], tags)

def gen_consolidate_2_chains():
    """2 separate open-way chains each forming a ring."""
    tid, tags = make_test(
        "4 open ways forming 2 separate rings, natural=wood",
        "CONSOLIDATE + DISSOLVE: chain into 2 closed ways, tag both, delete relation",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    # Ring 1
    nids1 = closed_polygon_nodes(lat, lon - 0.001, 0.0006, 6)
    nids1.append(nids1[0])
    wids1 = split_way_at(nids1, [0, 3])
    # Ring 2
    nids2 = closed_polygon_nodes(lat, lon + 0.001, 0.0006, 6)
    nids2.append(nids2[0])
    wids2 = split_way_at(nids2, [0, 3])
    members = [("way", w, "outer") for w in wids1 + wids2]
    add_relation(members, tags)

def gen_consolidate_mixed_closed_and_open():
    """1 closed outer + 2 open outers forming another ring."""
    tid, tags = make_test(
        "1 closed outer + 2 open outers forming another ring, landuse=meadow",
        "CONSOLIDATE + DISSOLVE: chain open ways, tag all, delete relation",
        {"landuse": "meadow"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.001, 0.0006, 5)
    nids2 = closed_polygon_nodes(lat, lon + 0.001, 0.0006, 6)
    nids2.append(nids2[0])
    wids2 = split_way_at(nids2, [0, 3])
    members = [("way", w1, "outer")] + [("way", w, "outer") for w in wids2]
    add_relation(members, tags)

def gen_consolidate_2way_with_many_nodes():
    """2 open ways forming a ring with many nodes."""
    tid, tags = make_test(
        "2 open ways forming 1 ring with 30 nodes total, natural=wetland",
        "CONSOLIDATE + DISSOLVE: chain into closed way, tag, delete relation",
        {"natural": "wetland"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.0015, 30)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 15])
    add_relation([("way", w, "outer") for w in way_ids], tags)


# ============================================================
# CATEGORY 3: CONSOLIDATE with tagged source ways
# ============================================================

def gen_consolidate_tagged_highway():
    """2 open outers, one with highway=residential."""
    tid, tags = make_test(
        "2 open outers forming ring; one has highway=residential+name=Main St",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, leave highway way untouched, tag reused way",
        {"landuse": "residential"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 8)
    nids.append(nids[0])
    # Way 1: highway (first 5 nodes)
    w1 = add_way(nids[0:5], {"highway": "residential", "name": "Main St"})
    # Way 2: untagged (nodes 4 back to 0)
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_tagged_building():
    """2 open outers, one with building=yes."""
    tid, tags = make_test(
        "2 open outers forming ring; one has building=yes",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, leave building way untouched",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.0008, 8)
    nids.append(nids[0])
    w1 = add_way(nids[0:5], {"building": "yes"})
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_tagged_waterway():
    """2 open outers, one with waterway=riverbank."""
    tid, tags = make_test(
        "2 open outers forming ring; one has waterway=stream",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, leave waterway way untouched",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"waterway": "stream"})
    w2 = add_way(nids[3:7], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_tagged_barrier():
    """2 open outers, one with barrier=fence."""
    tid, tags = make_test(
        "2 open outers forming ring; one has barrier=fence",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, leave barrier way untouched",
        {"landuse": "farmyard"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"barrier": "fence"})
    w2 = add_way(nids[3:7], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_both_tagged():
    """2 open outers, both with significant tags."""
    tid, tags = make_test(
        "2 open outers forming ring; both have significant tags (highway + barrier)",
        "CONSOLIDATE + DISSOLVE: cannot reuse either, creates new way, both tagged ways survive cleanup",
        {"natural": "grassland"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"highway": "track"})
    w2 = add_way(nids[3:7], {"barrier": "wall"})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_3way_one_tagged():
    """3 open outers, one with highway tag."""
    tid, tags = make_test(
        "3 open outers forming ring; middle one has highway=tertiary",
        "CONSOLIDATE + DISSOLVE: reuse one untagged way for the ring, leave highway way alone",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 9)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {})
    w2 = add_way(nids[3:7], {"highway": "tertiary"})
    w3 = add_way(nids[6:10], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_consolidate_source_only_tag():
    """2 open outers, one with only source= tag (insignificant)."""
    tid, tags = make_test(
        "2 open outers forming ring; one has only source=survey (insignificant tag)",
        "CONSOLIDATE + DISSOLVE: source= is insignificant, so normal consolidation (create new way)",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"source": "survey"})
    w2 = add_way(nids[3:7], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY 4: EXTRACT_OUTERS (relation kept)
# ============================================================

def gen_extract_1outer_1inner():
    """2 closed outers + 1 closed inner → extract 1 outer."""
    tid, tags = make_test(
        "2 closed outers + 1 closed inner; inner inside outer1",
        "EXTRACT: extract outer2 as standalone way, keep relation with outer1+inner",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.001, 0.0008, 6)
    w2 = closed_polygon(lat, lon + 0.001, 0.0006, 6)
    inner = closed_polygon(lat, lon - 0.001, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", inner, "inner")], tags)

def gen_extract_2outers_1inner():
    """3 closed outers + 1 closed inner → extract 2 outers."""
    tid, tags = make_test(
        "3 closed outers + 1 closed inner; inner inside outer1",
        "EXTRACT: extract outer2+outer3, keep relation with outer1+inner",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.001, 6)
    w2 = closed_polygon(lat + 0.0015, lon - 0.0015, 0.0004, 5)
    w3 = closed_polygon(lat + 0.0015, lon + 0.0015, 0.0004, 5)
    inner = closed_polygon(lat, lon, 0.0004, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer"),
                  ("way", inner, "inner")], tags)

def gen_extract_2outers_2inners_same():
    """2 closed outers + 2 closed inners both in outer1."""
    tid, tags = make_test(
        "2 closed outers + 2 inners; both inners inside outer1",
        "EXTRACT: extract outer2, keep relation with outer1+2inners",
        {"landuse": "forest"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.001, 0.001, 8)
    w2 = closed_polygon(lat, lon + 0.0015, 0.0005, 5)
    inner1 = closed_polygon(lat + 0.0003, lon - 0.001, 0.0003, 4)
    inner2 = closed_polygon(lat - 0.0003, lon - 0.001, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner")], tags)

def gen_extract_3outers_2inners_different():
    """3 closed outers + 2 inners in different outers."""
    tid, tags = make_test(
        "3 closed outers + 2 inners; inner1 in outer1, inner2 in outer2; outer3 standalone",
        "EXTRACT: extract outer3, keep relation with outer1+inner1 and outer2+inner2",
        {"natural": "wetland"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.0015, 0.0008, 6)
    w2 = closed_polygon(lat, lon + 0.0015, 0.0008, 6)
    w3 = closed_polygon(lat + 0.002, lon, 0.0004, 4)
    inner1 = closed_polygon(lat, lon - 0.0015, 0.0003, 4)
    inner2 = closed_polygon(lat, lon + 0.0015, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner")], tags)

def gen_extract_consolidated_outer():
    """2 open outers forming ring + 1 closed outer + 1 inner in closed outer."""
    tid, tags = make_test(
        "2 open outers forming ring + 1 closed outer with inner",
        "CONSOLIDATE + EXTRACT: chain open ways, extract as standalone, keep relation with closed outer+inner",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    # Closed outer with inner
    w1 = closed_polygon(lat, lon - 0.0015, 0.0008, 6)
    inner = closed_polygon(lat, lon - 0.0015, 0.0003, 4)
    # Open ways forming a ring
    nids = closed_polygon_nodes(lat, lon + 0.0015, 0.0006, 6)
    nids.append(nids[0])
    wids = split_way_at(nids, [0, 3])
    members = [("way", w1, "outer"), ("way", inner, "inner")] + [("way", w, "outer") for w in wids]
    add_relation(members, tags)

def gen_extract_4outers_1inner():
    """4 closed outers + 1 inner → extract 3."""
    tid, tags = make_test(
        "4 closed outers + 1 inner; inner inside outer1",
        "EXTRACT: extract outer2+outer3+outer4, keep relation with outer1+inner",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.001, 6)
    w2 = closed_polygon(lat + 0.002, lon - 0.001, 0.0004, 4)
    w3 = closed_polygon(lat + 0.002, lon, 0.0004, 4)
    w4 = closed_polygon(lat + 0.002, lon + 0.001, 0.0004, 4)
    inner = closed_polygon(lat, lon, 0.0004, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer"),
                  ("way", w4, "outer"), ("way", inner, "inner")], tags)


# ============================================================
# CATEGORY 5: TOUCHING_INNER_MERGE
# ============================================================

def make_touching_inner(center_lat, center_lon, shared_count=2):
    """Create outer + inner sharing specified number of nodes."""
    outer_n = 8
    inner_n = 6
    outer_r = 0.001
    inner_r = 0.0005

    outer_nids = []
    for i in range(outer_n):
        angle = 2 * math.pi * i / outer_n
        lat = center_lat + outer_r * math.sin(angle)
        lon = center_lon + outer_r * math.cos(angle)
        outer_nids.append(add_node(lat, lon))

    inner_nids = []
    shared_added = 0
    for i in range(inner_n):
        if i < shared_count and i < outer_n:
            # Share node from outer
            inner_nids.append(outer_nids[i])
            shared_added += 1
        else:
            angle = 2 * math.pi * i / inner_n + 0.2
            lat = center_lat + inner_r * math.sin(angle)
            lon = center_lon + inner_r * math.cos(angle)
            inner_nids.append(add_node(lat, lon))

    outer_nids.append(outer_nids[0])
    inner_nids.append(inner_nids[0])

    w_outer = add_way(outer_nids)
    w_inner = add_way(inner_nids)
    return w_outer, w_inner

def gen_touching_inner_2shared():
    """Outer + inner sharing 2 nodes → 1 merged way."""
    tid, tags = make_test(
        "1 closed outer + 1 closed inner sharing 2 nodes",
        "TOUCHING_INNER_MERGE: merge into 1 closed way, tag, delete relation",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w_outer, w_inner = make_touching_inner(lat, lon, 2)
    add_relation([("way", w_outer, "outer"), ("way", w_inner, "inner")], tags)

def gen_touching_inner_3shared():
    """Outer + inner sharing 3 nodes → multiple merged ways."""
    tid, tags = make_test(
        "1 closed outer + 1 closed inner sharing 3 nodes",
        "TOUCHING_INNER_MERGE: merge into multiple closed ways, tag, delete relation",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w_outer, w_inner = make_touching_inner(lat, lon, 3)
    add_relation([("way", w_outer, "outer"), ("way", w_inner, "inner")], tags)

def gen_touching_inner_4shared():
    """Outer + inner sharing 4 nodes → multiple merged ways."""
    tid, tags = make_test(
        "1 closed outer + 1 closed inner sharing 4 nodes",
        "TOUCHING_INNER_MERGE: merge into multiple closed ways, tag, delete relation",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w_outer, w_inner = make_touching_inner(lat, lon, 4)
    add_relation([("way", w_outer, "outer"), ("way", w_inner, "inner")], tags)


# ============================================================
# CATEGORY 6: EXTRACT + DISSOLVE combinations
# ============================================================

def gen_extract_then_dissolve():
    """2 closed outers + 1 inner in outer1, inner doesn't touch outer → extract outer2, dissolve remainder."""
    tid, tags = make_test(
        "2 closed outers + 1 non-touching inner in outer1",
        "EXTRACT outer2 + keep relation with outer1+inner (no touching merge possible)",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.001, 0.001, 8)
    w2 = closed_polygon(lat, lon + 0.0015, 0.0005, 5)
    inner = closed_polygon(lat, lon - 0.001, 0.0004, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", inner, "inner")], tags)

def gen_extract_then_touching_merge():
    """2 closed outers + 1 inner touching outer1 → extract outer2, merge outer1+inner."""
    tid, tags = make_test(
        "2 closed outers + 1 inner touching outer1 (2 shared nodes)",
        "EXTRACT outer2 + TOUCHING_INNER_MERGE of outer1+inner",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    # Create touching outer+inner pair
    w_outer, w_inner = make_touching_inner(lat, lon - 0.001, 2)
    # Standalone outer
    w2 = closed_polygon(lat, lon + 0.0015, 0.0005, 5)
    add_relation([("way", w_outer, "outer"), ("way", w2, "outer"), ("way", w_inner, "inner")], tags)


# ============================================================
# CATEGORY 7: Figure-8 / touching rings
# ============================================================

def gen_figure8_touching():
    """2 sets of open ways that form 2 rings sharing a node."""
    tid, tags = make_test(
        "4 open ways forming 2 rings touching at 1 shared node (figure-8)",
        "CONSOLIDATE + DISSOLVE: create 2 closed ways, tag both, delete relation",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    # Shared node
    shared = add_node(lat, lon)
    # Ring 1: shared → A → B → shared
    a1 = add_node(lat + 0.001, lon - 0.001)
    b1 = add_node(lat - 0.001, lon - 0.001)
    w1a = add_way([shared, a1])
    w1b = add_way([a1, b1, shared])
    # Ring 2: shared → C → D → shared
    c1 = add_node(lat + 0.001, lon + 0.001)
    d1 = add_node(lat - 0.001, lon + 0.001)
    w2a = add_way([shared, c1])
    w2b = add_way([c1, d1, shared])
    add_relation([("way", w1a, "outer"), ("way", w1b, "outer"),
                  ("way", w2a, "outer"), ("way", w2b, "outer")], tags)


# ============================================================
# CATEGORY 8: Various tag combinations
# ============================================================

def gen_tag_natural_wood():
    tid, tags = make_test(
        "1 closed outer, natural=wood",
        "DISSOLVE: tag way with natural=wood",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 5)
    add_relation([("way", w, "outer")], tags)

def gen_tag_natural_water():
    tid, tags = make_test(
        "1 closed outer, natural=water",
        "DISSOLVE: tag way with natural=water",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "outer")], tags)

def gen_tag_landuse_residential():
    tid, tags = make_test(
        "1 closed outer, landuse=residential",
        "DISSOLVE: tag way with landuse=residential",
        {"landuse": "residential"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "outer")], tags)

def gen_tag_landuse_industrial():
    tid, tags = make_test(
        "1 closed outer, landuse=industrial",
        "DISSOLVE: tag way with landuse=industrial",
        {"landuse": "industrial"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 5)
    add_relation([("way", w, "outer")], tags)

def gen_tag_building_yes():
    tid, tags = make_test(
        "1 closed outer, building=yes",
        "DISSOLVE: tag way with building=yes",
        {"building": "yes"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.0005, 4)
    add_relation([("way", w, "outer")], tags)

def gen_tag_amenity_parking():
    tid, tags = make_test(
        "1 closed outer, amenity=parking",
        "DISSOLVE: tag way with amenity=parking",
        {"amenity": "parking"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.0008, 4)
    add_relation([("way", w, "outer")], tags)

def gen_tag_leisure_park():
    tid, tags = make_test(
        "1 closed outer, leisure=park + name=Central Park",
        "DISSOLVE: tag way with leisure=park and name=Central Park",
        {"leisure": "park", "name": "Central Park"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 8)
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 9: SKIP / NOT FIXABLE cases
# ============================================================

def gen_skip_no_outers():
    """Relation with only inner ways → should be skipped (no outers)."""
    tid, tags = make_test(
        "Only inner ways, no outers",
        "SKIP: no outer ways means nothing to fix",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    inner = closed_polygon(lat, lon, 0.0005, 4)
    add_relation([("way", inner, "inner")], tags)

def gen_skip_open_ways_no_ring():
    """2 open ways that don't form a ring (endpoints don't match)."""
    tid, tags = make_test(
        "2 open ways whose endpoints don't connect - cannot form ring",
        "SKIP: ways don't form valid rings, not fixable",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat, lon)
    n2 = add_node(lat + 0.001, lon)
    n3 = add_node(lat, lon + 0.001)
    n4 = add_node(lat + 0.001, lon + 0.001)
    n5 = add_node(lat + 0.002, lon + 0.001)
    w1 = add_way([n1, n2, n3])
    w2 = add_way([n4, n5])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_skip_odd_degree():
    """3 open ways where one endpoint has odd degree."""
    tid, tags = make_test(
        "3 open ways; endpoint has odd degree (3 ways meet at one node)",
        "SKIP: odd degree endpoint means cannot form simple rings",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    center = add_node(lat, lon)
    n1 = add_node(lat + 0.001, lon)
    n2 = add_node(lat, lon + 0.001)
    n3 = add_node(lat - 0.001, lon)
    w1 = add_way([center, n1])
    w2 = add_way([center, n2])
    w3 = add_way([center, n3])
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_skip_single_open_way():
    """Single open way (not closed) → can't form ring by itself."""
    tid, tags = make_test(
        "Single open way, not closed",
        "SKIP: single open way cannot form a ring",
        {"natural": "heath"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat, lon)
    n2 = add_node(lat + 0.001, lon)
    n3 = add_node(lat + 0.001, lon + 0.001)
    w = add_way([n1, n2, n3])
    add_relation([("way", w, "outer")], tags)

def gen_skip_no_multipolygon_type():
    """Relation type is not multipolygon → ignored entirely."""
    global test_num
    test_num += 1
    tid = str(test_num)
    tags = {
        "type": "route",  # NOT multipolygon
        "_test_id": tid,
        "_test_notes": "Relation type is 'route' not 'multipolygon'",
        "_test_expected": "SKIP: plugin only processes type=multipolygon",
        "route": "bus"
    }
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "")], tags)

def gen_skip_inner_no_ring():
    """1 closed outer + 2 open inner ways that don't form a ring."""
    tid, tags = make_test(
        "1 closed outer + 2 open inners that don't connect to form ring",
        "SKIP: inner ways can't form valid rings",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w_outer = closed_polygon(lat, lon, 0.001, 6)
    n1 = add_node(lat + 0.0003, lon)
    n2 = add_node(lat, lon + 0.0003)
    n3 = add_node(lat - 0.0003, lon)
    n4 = add_node(lat, lon - 0.0003)
    w_inner1 = add_way([n1, n2])
    w_inner2 = add_way([n3, n4])
    add_relation([("way", w_outer, "outer"), ("way", w_inner1, "inner"), ("way", w_inner2, "inner")], tags)


# ============================================================
# CATEGORY 10: Edge cases with way geometry
# ============================================================

def gen_square_outer():
    """Perfectly square outer."""
    tid, tags = make_test(
        "1 closed square outer (4 nodes), landuse=grass",
        "DISSOLVE: tag square way with landuse=grass",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.0005, lon - 0.0005)
    n2 = add_node(lat + 0.0005, lon + 0.0005)
    n3 = add_node(lat - 0.0005, lon + 0.0005)
    n4 = add_node(lat - 0.0005, lon - 0.0005)
    w = add_way([n1, n2, n3, n4, n1])
    add_relation([("way", w, "outer")], tags)

def gen_rectangle_outer():
    """Elongated rectangle outer."""
    tid, tags = make_test(
        "1 closed elongated rectangle outer, building=yes",
        "DISSOLVE: tag way with building=yes",
        {"building": "yes"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.0002, lon - 0.001)
    n2 = add_node(lat + 0.0002, lon + 0.001)
    n3 = add_node(lat - 0.0002, lon + 0.001)
    n4 = add_node(lat - 0.0002, lon - 0.001)
    w = add_way([n1, n2, n3, n4, n1])
    add_relation([("way", w, "outer")], tags)

def gen_large_polygon():
    """Large polygon with many nodes."""
    tid, tags = make_test(
        "1 closed outer with 50 nodes, landuse=farmland",
        "DISSOLVE: tag way with landuse=farmland",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.002, 50)
    add_relation([("way", w, "outer")], tags)

def gen_tiny_polygon():
    """Very small polygon."""
    tid, tags = make_test(
        "1 closed tiny outer (3 nodes, ~1m across), natural=tree_group",
        "DISSOLVE: tag way with natural=tree_group",
        {"natural": "tree_group"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.00001, 3)
    add_relation([("way", w, "outer")], tags)

def gen_concave_polygon():
    """Concave (L-shaped) polygon."""
    tid, tags = make_test(
        "1 closed concave L-shaped outer, landuse=commercial",
        "DISSOLVE: tag way with landuse=commercial",
        {"landuse": "commercial"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat + 0.001, lon)
    n3 = add_node(lat, lon)
    n4 = add_node(lat, lon + 0.001)
    n5 = add_node(lat - 0.001, lon + 0.001)
    n6 = add_node(lat - 0.001, lon - 0.001)
    w = add_way([n1, n2, n3, n4, n5, n6, n1])
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 11: Multiple inner rings
# ============================================================

def gen_2inners_different_outers():
    """2 outers + 2 inners, each inner in a different outer → nothing to extract."""
    tid, tags = make_test(
        "2 closed outers + 2 inners, each inner in a different outer",
        "NOT FIXABLE single-relation: each outer has its own inner, nothing to extract. May trigger SPLIT_RELATION if disconnected.",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.0015, 0.0008, 6)
    w2 = closed_polygon(lat, lon + 0.0015, 0.0008, 6)
    inner1 = closed_polygon(lat, lon - 0.0015, 0.0003, 4)
    inner2 = closed_polygon(lat, lon + 0.0015, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner")], tags)

def gen_3inners_1outer():
    """1 outer + 3 inners, none touching → relation kept."""
    tid, tags = make_test(
        "1 closed outer + 3 non-touching closed inners",
        "NOT FIXABLE: multiple non-touching inners, relation must be kept as-is",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.002, 8)
    inner1 = closed_polygon(lat + 0.0008, lon, 0.0003, 4)
    inner2 = closed_polygon(lat - 0.0005, lon - 0.0007, 0.0003, 4)
    inner3 = closed_polygon(lat - 0.0005, lon + 0.0007, 0.0003, 4)
    add_relation([("way", w1, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner"), ("way", inner3, "inner")], tags)

def gen_1outer_1inner_notouch():
    """1 outer + 1 inner, non-touching → relation kept."""
    tid, tags = make_test(
        "1 closed outer + 1 non-touching closed inner (island in lake)",
        "NOT FIXABLE as dissolve: inner doesn't share nodes with outer. Relation must be kept.",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.001, 6)
    inner = closed_polygon(lat, lon, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", inner, "inner")], tags)


# ============================================================
# CATEGORY 12: Consolidated inner rings
# ============================================================

def gen_consolidate_inner_2way():
    """1 closed outer + 2 open inner ways forming ring."""
    tid, tags = make_test(
        "1 closed outer + 2 open inner ways forming 1 inner ring",
        "NOT FIXABLE as dissolve (has inner), but inner consolidation may occur internally",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w_outer = closed_polygon(lat, lon, 0.001, 6)
    # Inner ring from 2 open ways
    inner_nids = closed_polygon_nodes(lat, lon, 0.0004, 4)
    inner_nids.append(inner_nids[0])
    inner_ways = split_way_at(inner_nids, [0, 2])
    members = [("way", w_outer, "outer")] + [("way", w, "inner") for w in inner_ways]
    add_relation(members, tags)


# ============================================================
# CATEGORY 13: Relations with extra tags
# ============================================================

def gen_relation_many_tags():
    """Relation with many tags to transfer."""
    tid, tags = make_test(
        "1 closed outer, relation has 5 transferrable tags",
        "DISSOLVE: all tags except type= transferred to way",
        {"natural": "heath", "name": "Moorland Area", "description": "Test area",
         "note": "Testing tag transfer", "source": "survey"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "outer")], tags)

def gen_relation_name_only():
    """Relation with name but no primary feature tag."""
    tid, tags = make_test(
        "1 closed outer, relation has only name= tag (besides type)",
        "DISSOLVE: name tag transferred to way",
        {"name": "Mystery Area"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 5)
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 14: Way ordering variations
# ============================================================

def gen_consolidate_reversed_order():
    """2 open ways where endpoint matching requires reversal."""
    tid, tags = make_test(
        "2 open ways where both start nodes meet (requires reversing one)",
        "CONSOLIDATE + DISSOLVE: chain ways (one reversed), tag, delete relation",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    shared_start = add_node(lat, lon)
    n1 = add_node(lat + 0.001, lon - 0.0005)
    n2 = add_node(lat + 0.001, lon + 0.0005)
    n3 = add_node(lat - 0.001, lon + 0.0005)
    n4 = add_node(lat - 0.001, lon - 0.0005)
    shared_end = add_node(lat, lon - 0.001)
    # Both ways start from shared_start and end at different nodes
    # Way 1: shared_start → n1 → n2 → shared_end
    w1 = add_way([shared_start, n1, n2, shared_end])
    # Way 2: shared_end → n3 → n4 → shared_start  (need to reverse to chain)
    w2 = add_way([shared_end, n3, n4, shared_start])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_last_to_first():
    """2 open ways: way1.last == way2.first (natural direction)."""
    tid, tags = make_test(
        "2 open ways in natural chaining order (way1.last == way2.first)",
        "CONSOLIDATE + DISSOLVE: chain without reversal",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon - 0.0005)
    shared = add_node(lat + 0.001, lon + 0.0005)
    n3 = add_node(lat - 0.001, lon + 0.0005)
    n4 = add_node(lat - 0.001, lon - 0.0005)
    w1 = add_way([n1, shared])
    w2 = add_way([shared, n3, n4, n1])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY 15: SPLIT_RELATION (disconnected components)
# ============================================================

def gen_split_2_disconnected_closed():
    """2 closed outers with no shared nodes → might split."""
    tid, tags = make_test(
        "2 closed outers far apart (disconnected), no inners",
        "DISSOLVE directly (no split needed): 2 closed outers become tagged ways",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat + 0.001, lon - 0.0015, 0.0006, 5)
    w2 = closed_polygon(lat - 0.001, lon + 0.0015, 0.0006, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_split_3_components():
    """3 disconnected groups of open ways."""
    tid, tags = make_test(
        "6 open ways forming 3 disconnected rings",
        "DISSOLVE or SPLIT: each pair chains into a ring, all dissolved",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    members = []
    for i in range(3):
        offset_lon = lon + (i - 1) * 0.003
        nids = closed_polygon_nodes(lat, offset_lon, 0.0006, 6)
        nids.append(nids[0])
        wids = split_way_at(nids, [0, 3])
        members.extend([("way", w, "outer") for w in wids])
    add_relation(members, tags)

def gen_split_2_groups_with_inners():
    """2 disconnected outer groups, each with an inner."""
    tid, tags = make_test(
        "2 disconnected closed outers, each with 1 inner",
        "SPLIT_RELATION: split into 2 sub-relations, each with outer+inner",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.002, 0.001, 6)
    inner1 = closed_polygon(lat, lon - 0.002, 0.0004, 4)
    w2 = closed_polygon(lat, lon + 0.002, 0.001, 6)
    inner2 = closed_polygon(lat, lon + 0.002, 0.0004, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner")], tags)

def gen_split_mixed_dissolve_and_keep():
    """3 disconnected components: 2 can dissolve, 1 must keep (has inner)."""
    tid, tags = make_test(
        "3 disconnected components: comp1=closed outer only, comp2=closed outer only, comp3=outer+inner",
        "SPLIT_RELATION: dissolve comp1+comp2, keep comp3 as sub-relation",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.003, 0.0006, 5)
    w2 = closed_polygon(lat, lon, 0.0006, 5)
    w3 = closed_polygon(lat, lon + 0.003, 0.001, 6)
    inner3 = closed_polygon(lat, lon + 0.003, 0.0004, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"),
                  ("way", w3, "outer"), ("way", inner3, "inner")], tags)


# ============================================================
# CATEGORY 16: Cross-relation interactions
# ============================================================

def gen_shared_way_2relations():
    """Two relations sharing the same outer way."""
    tid1, tags1 = make_test(
        "Relation A: 1 closed outer shared with relation B, natural=wood",
        "DISSOLVE: may share the way with another relation being fixed simultaneously",
        {"natural": "wood"})
    tid2, tags2 = make_test(
        "Relation B: 1 closed outer shared with relation A, landuse=forest",
        "DISSOLVE: the shared way gets tags from one relation; both relations deleted",
        {"landuse": "forest"})
    lat, lon = grid_pos(test_num)
    shared_way = closed_polygon(lat, lon, 0.001, 6)
    w2 = closed_polygon(lat, lon + 0.002, 0.0006, 4)
    add_relation([("way", shared_way, "outer")], tags1)
    add_relation([("way", shared_way, "outer"), ("way", w2, "outer")], tags2)

def gen_shared_consolidation():
    """Two relations that share the same set of open outer ways."""
    tid1, tags1 = make_test(
        "Relation C: 2 open outers (same as relation D), natural=water",
        "CONSOLIDATE + DISSOLVE: shares consolidation with other relation",
        {"natural": "water"})
    tid2, tags2 = make_test(
        "Relation D: same 2 open outers as relation C, landuse=basin",
        "CONSOLIDATE + DISSOLVE: shares consolidation with other relation",
        {"landuse": "basin"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    wids = split_way_at(nids, [0, 3])
    add_relation([("way", w, "outer") for w in wids], tags1)
    add_relation([("way", w, "outer") for w in wids], tags2)


# ============================================================
# CATEGORY 17: Realistic landuse/natural patterns
# ============================================================

def gen_real_forest_clearing():
    """Forest with a clearing (outer=forest, inner=grass)."""
    tid, tags = make_test(
        "Forest with clearing: 1 closed outer + 1 non-touching inner",
        "NOT FIXABLE: true multipolygon (forest with grass clearing). Relation must be kept.",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.002, 8)
    clearing = closed_polygon(lat, lon, 0.0005, 5, {"landuse": "grass"})
    add_relation([("way", w, "outer"), ("way", clearing, "inner")], tags)

def gen_real_lake_island():
    """Lake with island (outer=water, inner=island)."""
    tid, tags = make_test(
        "Lake with island: 1 closed outer + 1 inner (island)",
        "NOT FIXABLE: true multipolygon (water with island). Relation kept.",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    lake = closed_polygon(lat, lon, 0.002, 10)
    island = closed_polygon(lat, lon, 0.0005, 5)
    add_relation([("way", lake, "outer"), ("way", island, "inner")], tags)

def gen_real_farmland_split():
    """Farmland split by road (2 open outers form ring including road edge)."""
    tid, tags = make_test(
        "Farmland with road edge: 2 open outers, one has highway=unclassified",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, preserve road",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.0015, 8)
    nids.append(nids[0])
    w1 = add_way(nids[0:5], {"highway": "unclassified"})
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_real_pond_complex():
    """Pond complex: multiple small ponds (closed outers) in one relation."""
    tid, tags = make_test(
        "4 small ponds as separate closed outers, natural=water",
        "DISSOLVE: tag all 4 ways with natural=water, delete relation",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    members = []
    positions = [(-0.001, -0.001), (-0.001, 0.001), (0.001, -0.001), (0.001, 0.001)]
    for dlat, dlon in positions:
        w = closed_polygon(lat + dlat, lon + dlon, 0.0004, 5)
        members.append(("way", w, "outer"))
    add_relation(members, tags)

def gen_real_residential_blocks():
    """Residential area: 1 big outer, 3 building inners."""
    tid, tags = make_test(
        "Residential area: 1 large closed outer + 3 non-touching building inners",
        "NOT FIXABLE: 3 inners, must keep multipolygon",
        {"landuse": "residential"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.002, 8)
    inner1 = closed_polygon(lat + 0.0008, lon, 0.0003, 4, {"building": "yes"})
    inner2 = closed_polygon(lat - 0.0005, lon - 0.0007, 0.0003, 4, {"building": "yes"})
    inner3 = closed_polygon(lat - 0.0005, lon + 0.0007, 0.0003, 4, {"building": "yes"})
    add_relation([("way", w, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner"), ("way", inner3, "inner")], tags)


# ============================================================
# CATEGORY 18: Stress testing consolidation
# ============================================================

def gen_consolidate_6way():
    """6 open ways forming 1 ring."""
    tid, tags = make_test(
        "6 open outer ways forming 1 ring, natural=heath",
        "CONSOLIDATE + DISSOLVE: chain 6 ways into closed way",
        {"natural": "heath"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.0015, 12)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 2, 4, 6, 8, 10])
    add_relation([("way", w, "outer") for w in way_ids], tags)

def gen_consolidate_8way():
    """8 open ways forming 1 ring."""
    tid, tags = make_test(
        "8 open outer ways forming 1 ring, landuse=orchard",
        "CONSOLIDATE + DISSOLVE: chain 8 ways into closed way",
        {"landuse": "orchard"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.0015, 16)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 2, 4, 6, 8, 10, 12, 14])
    add_relation([("way", w, "outer") for w in way_ids], tags)

def gen_consolidate_10way():
    """10 open ways forming 1 ring."""
    tid, tags = make_test(
        "10 open outer ways forming 1 ring, landuse=vineyard",
        "CONSOLIDATE + DISSOLVE: chain 10 ways into closed way",
        {"landuse": "vineyard"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.0015, 20)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 2, 4, 6, 8, 10, 12, 14, 16, 18])
    add_relation([("way", w, "outer") for w in way_ids], tags)


# ============================================================
# CATEGORY 19: Various consolidation + extract combos
# ============================================================

def gen_consolidate_extract_combo1():
    """2 open outers forming ring + 1 closed outer, no inners."""
    tid, tags = make_test(
        "2 open outers forming ring + 1 standalone closed outer, no inners",
        "CONSOLIDATE + DISSOLVE: chain open ways, tag all 2 results, delete relation",
        {"natural": "grassland"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon - 0.001, 0.0006, 6)
    nids.append(nids[0])
    wids = split_way_at(nids, [0, 3])
    w3 = closed_polygon(lat, lon + 0.001, 0.0005, 4)
    members = [("way", w, "outer") for w in wids] + [("way", w3, "outer")]
    add_relation(members, tags)

def gen_consolidate_extract_with_inner():
    """2 open outers forming ring + 1 closed outer with 1 inner."""
    tid, tags = make_test(
        "2 open outers forming ring (standalone) + 1 closed outer with 1 inner",
        "CONSOLIDATE + EXTRACT ring + keep relation with closed outer+inner",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    # Open outers forming standalone ring
    nids = closed_polygon_nodes(lat, lon - 0.002, 0.0006, 6)
    nids.append(nids[0])
    wids = split_way_at(nids, [0, 3])
    # Closed outer with inner
    w_outer = closed_polygon(lat, lon + 0.002, 0.001, 6)
    w_inner = closed_polygon(lat, lon + 0.002, 0.0003, 4)
    members = [("way", w, "outer") for w in wids] + [("way", w_outer, "outer"), ("way", w_inner, "inner")]
    add_relation(members, tags)


# ============================================================
# CATEGORY 20: More dissolve variations
# ============================================================

def gen_dissolve_large_8():
    """8 closed outers."""
    tid, tags = make_test(
        "8 closed outers arranged in a circle, natural=scrub",
        "DISSOLVE: tag all 8 ways with natural=scrub, delete relation",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    members = []
    for i in range(8):
        angle = 2 * math.pi * i / 8
        wlat = lat + 0.0015 * math.sin(angle)
        wlon = lon + 0.0015 * math.cos(angle)
        w = closed_polygon(wlat, wlon, 0.0004, 4)
        members.append(("way", w, "outer"))
    add_relation(members, tags)

def gen_dissolve_nested_style():
    """2 closed outers, one inside the other (but no inner role)."""
    tid, tags = make_test(
        "2 closed outers, smaller one geometrically inside larger one (both outer role)",
        "SKIP: ambiguous nested outers (likely missing inner role). JOSM validator flags this.",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.001, 6)
    w2 = closed_polygon(lat, lon, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY 21: Touching inner merge edge cases
# ============================================================

def gen_touching_inner_edge_all_shared():
    """Outer + inner where ALL nodes are shared (identical geometry)."""
    tid, tags = make_test(
        "1 outer + 1 inner that are identical (all nodes shared)",
        "TOUCHING_INNER_MERGE: all nodes shared, may produce degenerate result or skip",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    w_outer = add_way(list(nids))
    w_inner = add_way(list(nids))
    add_relation([("way", w_outer, "outer"), ("way", w_inner, "inner")], tags)

def gen_touching_inner_single_shared():
    """Outer + inner sharing only 1 node → figure-8 merge."""
    tid, tags = make_test(
        "1 outer + 1 inner sharing exactly 1 node",
        "TOUCHING_INNER_MERGE: merge into figure-8 way (shared node visited twice), delete relation",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    shared = add_node(lat + 0.001, lon)
    # Outer
    on1 = add_node(lat, lon - 0.001)
    on2 = add_node(lat - 0.001, lon)
    on3 = add_node(lat, lon + 0.001)
    w_outer = add_way([shared, on1, on2, on3, shared])
    # Inner sharing just the one node
    in1 = add_node(lat + 0.0005, lon - 0.0003)
    in2 = add_node(lat + 0.0005, lon + 0.0003)
    w_inner = add_way([shared, in1, in2, shared])
    add_relation([("way", w_outer, "outer"), ("way", w_inner, "inner")], tags)


# ============================================================
# CATEGORY 22: Consolidate + inner variations
# ============================================================

def gen_consolidate_outer_with_nontouching_inner():
    """2 open outers forming ring + 1 non-touching inner → consolidate, keep relation."""
    tid, tags = make_test(
        "2 open outers forming ring + 1 non-touching closed inner",
        "CONSOLIDATE: chain outers into ring, but inner means relation stays",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    wids = split_way_at(nids, [0, 3])
    inner = closed_polygon(lat, lon, 0.0003, 4)
    members = [("way", w, "outer") for w in wids] + [("way", inner, "inner")]
    add_relation(members, tags)


# ============================================================
# CATEGORY 23: More realistic patterns
# ============================================================

def gen_real_park_with_pond():
    """Park (outer) with pond (inner, tagged natural=water)."""
    tid, tags = make_test(
        "Park: 1 closed outer + 1 inner (pond with natural=water tag)",
        "NOT FIXABLE: inner has its own tags, true multipolygon. Relation kept.",
        {"leisure": "park", "name": "Lakeside Park"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.002, 10)
    pond = closed_polygon(lat + 0.0003, lon, 0.0005, 6, {"natural": "water"})
    add_relation([("way", w, "outer"), ("way", pond, "inner")], tags)

def gen_real_meadow_with_stream():
    """Meadow with stream cutting through (2 open outers, stream has waterway tag)."""
    tid, tags = make_test(
        "Meadow: 2 open outers, one is a waterway=stream",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, preserve stream",
        {"landuse": "meadow"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 8)
    nids.append(nids[0])
    w1 = add_way(nids[0:5], {"waterway": "stream"})
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_real_field_with_track():
    """Agricultural field with farm track on boundary."""
    tid, tags = make_test(
        "Field: 2 open outers, one is a highway=track",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, preserve track",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 8)
    nids.append(nids[0])
    w1 = add_way(nids[0:5], {"highway": "track", "tracktype": "grade3"})
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_real_wetland_complex():
    """Multiple wetland patches in one relation."""
    tid, tags = make_test(
        "6 closed outers representing wetland patches, natural=wetland",
        "DISSOLVE: tag all 6 ways with natural=wetland",
        {"natural": "wetland"})
    lat, lon = grid_pos(test_num)
    members = []
    for i in range(6):
        r = 2
        c = i % 3
        row = i // 3
        wlat = lat + row * 0.0012
        wlon = lon + (c - 1) * 0.0012
        w = closed_polygon(wlat, wlon, 0.0004, 5)
        members.append(("way", w, "outer"))
    add_relation(members, tags)


# ============================================================
# CATEGORY 24: Additional CONSOLIDATE edge cases
# ============================================================

def gen_consolidate_2way_minimal():
    """Minimal 2-way chain: each way has only 2 nodes."""
    tid, tags = make_test(
        "2 open ways, each with 2 nodes only (4 nodes total forming square)",
        "CONSOLIDATE + DISSOLVE: chain 2 tiny segments into closed way",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.0005, lon - 0.0005)
    n2 = add_node(lat + 0.0005, lon + 0.0005)
    n3 = add_node(lat - 0.0005, lon + 0.0005)
    n4 = add_node(lat - 0.0005, lon - 0.0005)
    w1 = add_way([n1, n2, n3])
    w2 = add_way([n3, n4, n1])
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)

def gen_consolidate_lots_of_nodes_per_way():
    """2 open ways, each with 30 nodes."""
    tid, tags = make_test(
        "2 open ways, each with 30 nodes (60 total), natural=wood",
        "CONSOLIDATE + DISSOLVE: chain into closed way with ~60 nodes",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.002, 60)
    nids.append(nids[0])
    way_ids = split_way_at(nids, [0, 30])
    add_relation([("way", w, "outer") for w in way_ids], tags)


# ============================================================
# CATEGORY 25: Multi-tag tagged ways in consolidation
# ============================================================

def gen_consolidate_multi_tag_way():
    """2 open outers, one has highway + name + surface tags."""
    tid, tags = make_test(
        "2 open outers; one has highway=residential, name=Oak Ave, surface=asphalt",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, all 3 tags on highway way preserved",
        {"landuse": "residential"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 8)
    nids.append(nids[0])
    w1 = add_way(nids[0:5], {"highway": "residential", "name": "Oak Ave", "surface": "asphalt"})
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY 26: Various polygon shapes for dissolve
# ============================================================

def gen_dissolve_pentagon():
    tid, tags = make_test(
        "1 closed pentagon outer, natural=scrub",
        "DISSOLVE: tag way",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 5)
    add_relation([("way", w, "outer")], tags)

def gen_dissolve_octagon():
    tid, tags = make_test(
        "1 closed octagon outer, landuse=allotments",
        "DISSOLVE: tag way",
        {"landuse": "allotments"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 8)
    add_relation([("way", w, "outer")], tags)

def gen_dissolve_star_shape():
    """Star-shaped polygon (concave)."""
    tid, tags = make_test(
        "1 closed star-shaped outer (10 nodes alternating radii), leisure=garden",
        "DISSOLVE: tag way",
        {"leisure": "garden"})
    lat, lon = grid_pos(test_num)
    nids = []
    for i in range(10):
        angle = 2 * math.pi * i / 10
        r = 0.001 if i % 2 == 0 else 0.0005
        nids.append(add_node(lat + r * math.sin(angle), lon + r * math.cos(angle)))
    nids.append(nids[0])
    w = add_way(nids)
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 27: Ways with only insignificant tags
# ============================================================

def gen_dissolve_way_with_created_by():
    """Outer way has created_by tag (insignificant)."""
    tid, tags = make_test(
        "1 closed outer with created_by=JOSM (insignificant tag), natural=heath",
        "DISSOLVE: created_by is insignificant, way gets natural=heath normally",
        {"natural": "heath"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6, {"created_by": "JOSM"})
    add_relation([("way", w, "outer")], tags)

def gen_consolidate_way_with_source_and_created_by():
    """2 open outers, one has source+created_by (both insignificant)."""
    tid, tags = make_test(
        "2 open outers; one has source=bing + created_by=JOSM (both insignificant)",
        "CONSOLIDATE + DISSOLVE: both tags insignificant, normal consolidation (create new way)",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 6)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"source": "bing", "created_by": "JOSM"})
    w2 = add_way(nids[3:7], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY 28: Extract then dissolve remainder
# ============================================================

def gen_extract_dissolve_3outer_1inner():
    """3 closed outers, 1 inner in outer1 → extract outer2+3, keep outer1+inner."""
    tid, tags = make_test(
        "3 closed outers + 1 inner in outer1; outer2+outer3 standalone",
        "EXTRACT outer2+outer3 + keep relation with outer1+inner",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon, 0.001, 8)
    inner = closed_polygon(lat, lon, 0.0003, 4)
    w2 = closed_polygon(lat, lon + 0.003, 0.0005, 5)
    w3 = closed_polygon(lat, lon - 0.003, 0.0005, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer"),
                  ("way", inner, "inner")], tags)


# ============================================================
# CATEGORY 29: Mixed inner ring consolidation
# ============================================================

def gen_open_inner_ring():
    """1 closed outer + 3 open inner ways forming a ring."""
    tid, tags = make_test(
        "1 closed outer + 3 open inner ways forming 1 inner ring",
        "Inner ring consolidation + keep relation (true multipolygon with hole)",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w_outer = closed_polygon(lat, lon, 0.002, 8)
    inner_nids = closed_polygon_nodes(lat, lon, 0.0006, 6)
    inner_nids.append(inner_nids[0])
    inner_ways = split_way_at(inner_nids, [0, 2, 4])
    members = [("way", w_outer, "outer")] + [("way", w, "inner") for w in inner_ways]
    add_relation(members, tags)


# ============================================================
# CATEGORY 30: More realistic multi-component
# ============================================================

def gen_split_farmland_complex():
    """4 disconnected closed outers (scattered farmland patches)."""
    tid, tags = make_test(
        "4 disconnected closed outers (scattered farmland patches)",
        "DISSOLVE: tag all 4 ways, delete relation (no splitting needed for dissolve-only)",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    members = []
    for i in range(4):
        dlat = (i // 2 - 0.5) * 0.003
        dlon = (i % 2 - 0.5) * 0.003
        w = closed_polygon(lat + dlat, lon + dlon, 0.0008, 6)
        members.append(("way", w, "outer"))
    add_relation(members, tags)


# ============================================================
# CATEGORY 31: Additional edge cases
# ============================================================

def gen_two_node_way():
    """Outer way with only 2 nodes (a line segment)."""
    tid, tags = make_test(
        "1 open way with only 2 nodes (degenerate)",
        "SKIP: single open way cannot form ring",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat, lon)
    n2 = add_node(lat + 0.001, lon)
    w = add_way([n1, n2])
    add_relation([("way", w, "outer")], tags)

def gen_self_intersecting_outer():
    """Self-intersecting (bowtie) closed outer."""
    tid, tags = make_test(
        "1 closed self-intersecting outer (bowtie shape)",
        "DISSOLVE: plugin doesn't check for self-intersection, tags the way normally",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    n1 = add_node(lat + 0.001, lon - 0.001)
    n2 = add_node(lat - 0.001, lon + 0.001)
    n3 = add_node(lat + 0.001, lon + 0.001)
    n4 = add_node(lat - 0.001, lon - 0.001)
    w = add_way([n1, n2, n3, n4, n1])
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 32: Various role edge cases
# ============================================================

def gen_empty_role_as_outer():
    """Way with empty role (treated as outer in OSM convention)."""
    tid, tags = make_test(
        "1 closed way with empty role (no role specified, treated as outer)",
        "DISSOLVE: ways without explicit role are treated as outer",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "")], tags)

def gen_outer_and_empty_role():
    """Mix of 'outer' and '' roles."""
    tid, tags = make_test(
        "2 closed ways: one 'outer' role, one empty role",
        "DISSOLVE: both treated as outer, tag both, delete relation",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.001, 0.0006, 5)
    w2 = closed_polygon(lat, lon + 0.001, 0.0006, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "")], tags)


# ============================================================
# CATEGORY 33: More extract combinations
# ============================================================

def gen_extract_5outers_2inners():
    """5 closed outers, 2 inners in 2 different outers."""
    tid, tags = make_test(
        "5 closed outers + 2 inners (1 inner in outer1, 1 inner in outer2)",
        "EXTRACT: extract outer3+4+5, keep relation with outer1+inner1 and outer2+inner2",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.002, 0.0008, 6)
    w2 = closed_polygon(lat, lon + 0.002, 0.0008, 6)
    w3 = closed_polygon(lat + 0.002, lon - 0.001, 0.0004, 4)
    w4 = closed_polygon(lat + 0.002, lon, 0.0004, 4)
    w5 = closed_polygon(lat + 0.002, lon + 0.001, 0.0004, 4)
    inner1 = closed_polygon(lat, lon - 0.002, 0.0003, 4)
    inner2 = closed_polygon(lat, lon + 0.002, 0.0003, 4)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer"),
                  ("way", w4, "outer"), ("way", w5, "outer"),
                  ("way", inner1, "inner"), ("way", inner2, "inner")], tags)


# ============================================================
# CATEGORY 34: Additional split-relation patterns
# ============================================================

def gen_split_all_dissolve():
    """3 disconnected closed outer groups, all can dissolve."""
    tid, tags = make_test(
        "3 disconnected closed outers, no inners",
        "DISSOLVE: tag all 3 ways, delete relation (simple dissolve, no split needed)",
        {"landuse": "farmland"})
    lat, lon = grid_pos(test_num)
    w1 = closed_polygon(lat, lon - 0.003, 0.0006, 5)
    w2 = closed_polygon(lat, lon, 0.0006, 5)
    w3 = closed_polygon(lat, lon + 0.003, 0.0006, 5)
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)


# ============================================================
# CATEGORY 35: Consolidation with 2+ tagged ways
# ============================================================

def gen_consolidate_2_tagged_1_untagged():
    """3 open outers, 2 have highway tags, 1 untagged."""
    tid, tags = make_test(
        "3 open outers; 2 have highway= tags, 1 untagged",
        "CONSOLIDATE + DISSOLVE: reuse the untagged way, leave both highway ways untouched",
        {"landuse": "grass"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 9)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"highway": "residential"})
    w2 = add_way(nids[3:7], {})
    w3 = add_way(nids[6:10], {"highway": "tertiary"})
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)


# ============================================================
# CATEGORY 36: More realistic patterns
# ============================================================

def gen_real_forest_multiple_patches():
    """8 separate forest patches as closed outers."""
    tid, tags = make_test(
        "8 separate forest patches (closed outers), natural=wood",
        "DISSOLVE: tag all 8 ways, delete relation",
        {"natural": "wood"})
    lat, lon = grid_pos(test_num)
    members = []
    for i in range(8):
        angle = 2 * math.pi * i / 8
        wlat = lat + 0.0015 * math.sin(angle)
        wlon = lon + 0.0015 * math.cos(angle)
        w = closed_polygon(wlat, wlon, 0.0003, 5)
        members.append(("way", w, "outer"))
    add_relation(members, tags)

def gen_real_meadow_river_boundary():
    """Meadow bounded by river on one side."""
    tid, tags = make_test(
        "Meadow: 3 open outers, one is waterway=river, one is highway=path",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, preserve river and path ways",
        {"landuse": "meadow"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 9)
    nids.append(nids[0])
    w1 = add_way(nids[0:4], {"waterway": "river"})
    w2 = add_way(nids[3:7], {"highway": "path"})
    w3 = add_way(nids[6:10], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer"), ("way", w3, "outer")], tags)

def gen_real_industrial_with_fence():
    """Industrial area with fence on boundary."""
    tid, tags = make_test(
        "Industrial area: 2 open outers, one has barrier=fence + fence_type=wire",
        "CONSOLIDATE + DISSOLVE: reuse untagged way, preserve fence",
        {"landuse": "industrial"})
    lat, lon = grid_pos(test_num)
    nids = closed_polygon_nodes(lat, lon, 0.001, 8)
    nids.append(nids[0])
    w1 = add_way(nids[0:5], {"barrier": "fence", "fence_type": "wire"})
    w2 = add_way(nids[4:9], {})
    add_relation([("way", w1, "outer"), ("way", w2, "outer")], tags)


# ============================================================
# CATEGORY 37: Additional role/member variations
# ============================================================

def gen_duplicate_way_member():
    """Same way listed twice as outer."""
    tid, tags = make_test(
        "Same closed way listed twice as outer member",
        "DISSOLVE: should handle duplicate gracefully, tag way once, delete relation",
        {"natural": "scrub"})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "outer"), ("way", w, "outer")], tags)


# ============================================================
# CATEGORY 38: Very complex consolidation scenarios
# ============================================================

def gen_consolidate_3_rings():
    """6 open ways forming 3 separate rings (no inners)."""
    tid, tags = make_test(
        "6 open ways forming 3 separate rings, natural=heath",
        "CONSOLIDATE + DISSOLVE: 3 rings chained, tagged, relation deleted",
        {"natural": "heath"})
    lat, lon = grid_pos(test_num)
    members = []
    for i in range(3):
        offset_lon = lon + (i - 1) * 0.002
        nids = closed_polygon_nodes(lat, offset_lon, 0.0006, 4)
        nids.append(nids[0])
        wids = split_way_at(nids, [0, 2])
        members.extend([("way", w, "outer") for w in wids])
    add_relation(members, tags)


# ============================================================
# CATEGORY 39: Relations with only type=multipolygon tag
# ============================================================

def gen_type_only():
    """Relation has no tags besides type=multipolygon."""
    tid, tags = make_test(
        "1 closed outer, relation has ONLY type=multipolygon (no feature tags)",
        "DISSOLVE: delete relation but way gets no tags (nothing to transfer except type)",
        {})
    lat, lon = grid_pos(test_num)
    w = closed_polygon(lat, lon, 0.001, 6)
    add_relation([("way", w, "outer")], tags)


# ============================================================
# CATEGORY 40: Consolidation where all ways are closed
# ============================================================

def gen_mixed_closed_and_open_multiway():
    """2 closed outers + 3 open outers forming 1 ring."""
    tid, tags = make_test(
        "2 closed outers + 3 open outers forming 1 ring, landuse=meadow",
        "CONSOLIDATE + DISSOLVE: chain open ways, tag all, delete relation",
        {"landuse": "meadow"})
    lat, lon = grid_pos(test_num)
    # 2 closed
    w1 = closed_polygon(lat, lon - 0.002, 0.0005, 4)
    w2 = closed_polygon(lat, lon + 0.002, 0.0005, 4)
    # 3 open forming ring
    nids = closed_polygon_nodes(lat, lon, 0.0006, 6)
    nids.append(nids[0])
    wids = split_way_at(nids, [0, 2, 4])
    members = [("way", w1, "outer"), ("way", w2, "outer")] + [("way", w, "outer") for w in wids]
    add_relation(members, tags)


# ============================================================
# CATEGORY 41: Additional touching inner patterns
# ============================================================

def gen_touching_inner_with_extract():
    """3 closed outers + 1 inner touching outer1 (sharing 2 nodes)."""
    tid, tags = make_test(
        "3 closed outers + 1 inner touching outer1 at 2 nodes",
        "EXTRACT outer2+outer3 + TOUCHING_INNER_MERGE outer1+inner",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)
    w_outer, w_inner = make_touching_inner(lat, lon - 0.001, 2)
    w2 = closed_polygon(lat, lon + 0.001, 0.0004, 4)
    w3 = closed_polygon(lat, lon + 0.002, 0.0004, 4)
    add_relation([("way", w_outer, "outer"), ("way", w2, "outer"), ("way", w3, "outer"),
                  ("way", w_inner, "inner")], tags)


# ============================================================
# CATEGORY 42: Adjoining inner ring consolidation
# ============================================================

def gen_consolidate_2_abutting_inners():
    """1 outer + 2 closed inners sharing 1 edge (2 shared nodes)."""
    tid, tags = make_test(
        "1 outer + 2 closed inners sharing 1 edge (2 shared nodes)",
        "CONSOLIDATE_INNERS: merge 2 inners into 1",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)

    # Large outer
    outer_w = closed_polygon(lat, lon, 0.002, 6)

    # Two abutting inners sharing an edge
    # Inner 1: square
    n1 = add_node(lat + 0.0005, lon - 0.0005)
    n2 = add_node(lat + 0.0005, lon + 0.0001)
    n3 = add_node(lat - 0.0003, lon + 0.0001)  # shared
    n4 = add_node(lat - 0.0003, lon - 0.0005)  # shared
    inner1 = add_way([n1, n2, n3, n4, n1])

    # Inner 2: shares edge n3-n4 (reversed direction: n4, n3)
    n5 = add_node(lat + 0.0005, lon + 0.0007)
    n6 = add_node(lat - 0.0003, lon + 0.0007)
    inner2 = add_way([n4, n3, n6, n5, n4])

    add_relation([("way", outer_w, "outer"),
                  ("way", inner1, "inner"),
                  ("way", inner2, "inner")], tags)


def gen_consolidate_3_chained_inners():
    """1 outer + 3 closed inners in chain (A-B share edge, B-C share edge)."""
    tid, tags = make_test(
        "1 outer + 3 closed inners chained: A-B, B-C share edges",
        "CONSOLIDATE_INNERS: iterative merge 3 inners into 1",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)

    outer_w = closed_polygon(lat, lon, 0.002, 6)

    # Three chained inners, each sharing one edge with the next
    # Inner A
    na1 = add_node(lat + 0.0005, lon - 0.0008)
    na2 = add_node(lat + 0.0005, lon - 0.0002)  # shared A-B
    na3 = add_node(lat - 0.0003, lon - 0.0002)  # shared A-B
    na4 = add_node(lat - 0.0003, lon - 0.0008)
    inner_a = add_way([na1, na2, na3, na4, na1])

    # Inner B: shares na2-na3 with A, and nb2-nb3 with C
    nb2 = add_node(lat + 0.0005, lon + 0.0004)  # shared B-C
    nb3 = add_node(lat - 0.0003, lon + 0.0004)  # shared B-C
    inner_b = add_way([na2, nb2, nb3, na3, na2])

    # Inner C: shares nb2-nb3 with B
    nc2 = add_node(lat + 0.0005, lon + 0.0010)
    nc3 = add_node(lat - 0.0003, lon + 0.0010)
    inner_c = add_way([nb2, nc2, nc3, nb3, nb2])

    add_relation([("way", outer_w, "outer"),
                  ("way", inner_a, "inner"),
                  ("way", inner_b, "inner"),
                  ("way", inner_c, "inner")], tags)


def gen_no_merge_1_shared_node():
    """1 outer + 2 closed inners sharing only 1 node (no edge)."""
    tid, tags = make_test(
        "1 outer + 2 closed inners sharing only 1 node (no edge)",
        "No CONSOLIDATE_INNERS: need >= 2 shared nodes for an edge",
        {"natural": "water"})
    lat, lon = grid_pos(test_num)

    outer_w = closed_polygon(lat, lon, 0.002, 6)

    # Two inners sharing exactly 1 node
    shared = add_node(lat, lon)
    n1 = add_node(lat + 0.0005, lon - 0.0005)
    n2 = add_node(lat + 0.0005, lon - 0.0001)
    n3 = add_node(lat - 0.0003, lon - 0.0003)
    inner1 = add_way([shared, n1, n2, shared])

    n4 = add_node(lat + 0.0005, lon + 0.0001)
    n5 = add_node(lat + 0.0005, lon + 0.0005)
    n6 = add_node(lat - 0.0003, lon + 0.0003)
    inner2 = add_way([shared, n4, n5, n6, shared])

    add_relation([("way", outer_w, "outer"),
                  ("way", inner1, "inner"),
                  ("way", inner2, "inner")], tags)


# ============================================================
# Generate everything
# ============================================================

def generate_all():
    # Category 1: Simple DISSOLVE
    gen_dissolve_1_closed_outer()
    gen_dissolve_2_closed_outers()
    gen_dissolve_3_closed_outers()
    gen_dissolve_single_triangle()
    gen_dissolve_single_complex()
    gen_dissolve_with_source_tag()
    gen_dissolve_5_closed_outers()
    gen_dissolve_with_multiple_tags()

    # Category 2: CONSOLIDATE_RINGS + DISSOLVE
    gen_consolidate_2way_chain()
    gen_consolidate_3way_chain()
    gen_consolidate_4way_chain()
    gen_consolidate_5way_chain()
    gen_consolidate_2_chains()
    gen_consolidate_mixed_closed_and_open()
    gen_consolidate_2way_with_many_nodes()

    # Category 3: CONSOLIDATE with tagged source ways
    gen_consolidate_tagged_highway()
    gen_consolidate_tagged_building()
    gen_consolidate_tagged_waterway()
    gen_consolidate_tagged_barrier()
    gen_consolidate_both_tagged()
    gen_consolidate_3way_one_tagged()
    gen_consolidate_source_only_tag()

    # Category 4: EXTRACT_OUTERS
    gen_extract_1outer_1inner()
    gen_extract_2outers_1inner()
    gen_extract_2outers_2inners_same()
    gen_extract_3outers_2inners_different()
    gen_extract_consolidated_outer()
    gen_extract_4outers_1inner()

    # Category 5: TOUCHING_INNER_MERGE
    gen_touching_inner_2shared()
    gen_touching_inner_3shared()
    gen_touching_inner_4shared()

    # Category 6: EXTRACT + DISSOLVE combinations
    gen_extract_then_dissolve()
    gen_extract_then_touching_merge()

    # Category 7: Figure-8
    gen_figure8_touching()

    # Category 8: Various tag combinations
    gen_tag_natural_wood()
    gen_tag_natural_water()
    gen_tag_landuse_residential()
    gen_tag_landuse_industrial()
    gen_tag_building_yes()
    gen_tag_amenity_parking()
    gen_tag_leisure_park()

    # Category 9: SKIP cases
    gen_skip_no_outers()
    gen_skip_open_ways_no_ring()
    gen_skip_odd_degree()
    gen_skip_single_open_way()
    gen_skip_no_multipolygon_type()
    gen_skip_inner_no_ring()

    # Category 10: Edge cases with geometry
    gen_square_outer()
    gen_rectangle_outer()
    gen_large_polygon()
    gen_tiny_polygon()
    gen_concave_polygon()

    # Category 11: Multiple inner rings
    gen_2inners_different_outers()
    gen_3inners_1outer()
    gen_1outer_1inner_notouch()

    # Category 12: Consolidated inner rings
    gen_consolidate_inner_2way()

    # Category 13: Relations with extra tags
    gen_relation_many_tags()
    gen_relation_name_only()

    # Category 14: Way ordering variations
    gen_consolidate_reversed_order()
    gen_consolidate_last_to_first()

    # Category 15: SPLIT_RELATION
    gen_split_2_disconnected_closed()
    gen_split_3_components()
    gen_split_2_groups_with_inners()
    gen_split_mixed_dissolve_and_keep()

    # Category 16: Cross-relation interactions
    gen_shared_way_2relations()
    gen_shared_consolidation()

    # Category 17: Realistic patterns
    gen_real_forest_clearing()
    gen_real_lake_island()
    gen_real_farmland_split()
    gen_real_pond_complex()
    gen_real_residential_blocks()

    # Category 18: Stress consolidation
    gen_consolidate_6way()
    gen_consolidate_8way()
    gen_consolidate_10way()

    # Category 19: Consolidation + extract combos
    gen_consolidate_extract_combo1()
    gen_consolidate_extract_with_inner()

    # Category 20: More dissolve variations
    gen_dissolve_large_8()
    gen_dissolve_nested_style()

    # Category 21: Touching inner edge cases
    gen_touching_inner_edge_all_shared()
    gen_touching_inner_single_shared()

    # Category 22: Consolidate + inner
    gen_consolidate_outer_with_nontouching_inner()

    # Category 23: More realistic patterns
    gen_real_park_with_pond()
    gen_real_meadow_with_stream()
    gen_real_field_with_track()
    gen_real_wetland_complex()

    # Category 24: Additional consolidate edge cases
    gen_consolidate_2way_minimal()
    gen_consolidate_lots_of_nodes_per_way()

    # Category 25: Multi-tag tagged ways
    gen_consolidate_multi_tag_way()

    # Category 26: Various polygon shapes
    gen_dissolve_pentagon()
    gen_dissolve_octagon()
    gen_dissolve_star_shape()

    # Category 27: Insignificant tags
    gen_dissolve_way_with_created_by()
    gen_consolidate_way_with_source_and_created_by()

    # Category 28: Extract then dissolve remainder
    gen_extract_dissolve_3outer_1inner()

    # Category 29: Mixed inner ring consolidation
    gen_open_inner_ring()

    # Category 30: More realistic multi-component
    gen_split_farmland_complex()

    # Category 31: Additional edge cases
    gen_two_node_way()
    gen_self_intersecting_outer()

    # Category 32: Role edge cases
    gen_empty_role_as_outer()
    gen_outer_and_empty_role()

    # Category 33: More extract combinations
    gen_extract_5outers_2inners()

    # Category 34: Additional split-relation patterns
    gen_split_all_dissolve()

    # Category 35: Consolidation with 2+ tagged ways
    gen_consolidate_2_tagged_1_untagged()

    # Category 36: More realistic patterns
    gen_real_forest_multiple_patches()
    gen_real_meadow_river_boundary()
    gen_real_industrial_with_fence()

    # Category 37: Duplicate member
    gen_duplicate_way_member()

    # Category 38: Complex consolidation
    gen_consolidate_3_rings()

    # Category 39: Type-only relation
    gen_type_only()

    # Category 40: Mixed closed+open
    gen_mixed_closed_and_open_multiway()

    # Category 41: Additional touching inner
    gen_touching_inner_with_extract()

    # Category 42: Adjoining inner ring consolidation
    gen_consolidate_2_abutting_inners()
    gen_consolidate_3_chained_inners()
    gen_no_merge_1_shared_node()


def write_osm(filename):
    with open(filename, 'w', encoding='utf-8') as f:
        f.write("<?xml version='1.0' encoding='UTF-8'?>\n")
        f.write("<osm version='0.6' generator='multipoly-gone test generator'>\n")

        # Write nodes
        for nid, lat, lon, tags in nodes:
            if tags:
                f.write(f"  <node id='{nid}' action='modify' visible='true' lat='{lat:.11f}' lon='{lon:.11f}'>\n")
                for k, v in tags.items():
                    f.write(f"    <tag k='{escape_xml(k)}' v='{escape_xml(v)}' />\n")
                f.write("  </node>\n")
            else:
                f.write(f"  <node id='{nid}' action='modify' visible='true' lat='{lat:.11f}' lon='{lon:.11f}' />\n")

        # Write ways
        for wid, node_ids, tags in ways:
            f.write(f"  <way id='{wid}' action='modify' visible='true'>\n")
            for nid in node_ids:
                f.write(f"    <nd ref='{nid}' />\n")
            for k, v in tags.items():
                f.write(f"    <tag k='{escape_xml(k)}' v='{escape_xml(v)}' />\n")
            f.write("  </way>\n")

        # Write relations
        for rid, members, tags in relations:
            f.write(f"  <relation id='{rid}' action='modify' visible='true'>\n")
            for mtype, ref, role in members:
                f.write(f"    <member type='{mtype}' ref='{ref}' role='{escape_xml(role)}' />\n")
            for k, v in tags.items():
                f.write(f"    <tag k='{escape_xml(k)}' v='{escape_xml(v)}' />\n")
            f.write("  </relation>\n")

        f.write("</osm>\n")


def escape_xml(s):
    return (str(s)
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace("'", '&apos;')
        .replace('"', '&quot;'))


if __name__ == '__main__':
    generate_all()
    print(f"Generated {test_num} test cases with {len(nodes)} nodes, {len(ways)} ways, {len(relations)} relations")
    write_osm('tests/testdata-proposed.osm')
    print("Written to tests/testdata-proposed.osm")
