#!/usr/bin/env python3
"""
Append new test cases to testdata-proposed.osm.

testdata-proposed.osm is the source of truth for test data. This script
appends new test cases to it without regenerating the file.

Usage: edit the __main__ block at the bottom to define your new test case(s),
then run: python3 tests/append-test-case.py
"""

import math
import re


class AppendContext:
    """Context for appending test cases to an existing .osm file.

    Scans the file for current max IDs and test numbers, then provides
    helpers to create new OSM primitives that are appended on write().
    """

    def __init__(self, osm_file):
        self.osm_file = osm_file
        self.nodes = []       # (id, lat, lon, tags_dict)
        self.ways = []        # (id, node_ids, tags_dict)
        self.relations = []   # (id, members, tags_dict)

        with open(osm_file, 'r', encoding='utf-8', newline='') as f:
            content = f.read()
        # Normalize to LF so we don't introduce CRLF on Windows
        content = content.replace('\r\n', '\n')

        # Find max negative ID magnitude across all primitives
        all_ids = [int(m) for m in re.findall(r"id='-(\d+)'", content)]
        max_id = max(all_ids) if all_ids else 200000
        self._next_id = -(max_id + 1)

        # Find max _test_id
        test_ids = [int(m) for m in re.findall(r"_test_id' v='(\d+)'", content)]
        self._max_test_id = max(test_ids) if test_ids else 0
        self._next_test_id = self._max_test_id

        # Store content without closing </osm>
        self._content = content.rstrip()
        if self._content.endswith('</osm>'):
            self._content = self._content[:-len('</osm>')].rstrip()

    def _next(self):
        nid = self._next_id
        self._next_id -= 1
        return nid

    def add_node(self, lat, lon, tags=None):
        """Add a node and return its ID."""
        nid = self._next()
        self.nodes.append((nid, lat, lon, tags or {}))
        return nid

    def add_way(self, node_ids, tags=None):
        """Add a way and return its ID."""
        wid = self._next()
        self.ways.append((wid, list(node_ids), tags or {}))
        return wid

    def add_relation(self, members, tags=None, notes=None, expected=None):
        """Add a relation and return its ID.

        If notes/expected are provided, auto-assigns the next _test_id and adds
        _test_id, _test_notes, _test_expected tags.

        Args:
            members: list of (type, ref_id, role) tuples
            tags: dict of relation tags (type=multipolygon added if not present)
            notes: test case description (triggers auto _test_id assignment)
            expected: expected behavior description
        """
        rid = self._next()
        all_tags = {'type': 'multipolygon'}
        if tags:
            all_tags.update(tags)
        if notes is not None:
            self._next_test_id += 1
            all_tags['_test_id'] = str(self._next_test_id)
            all_tags['_test_notes'] = notes
            if expected:
                all_tags['_test_expected'] = expected
        self.relations.append((rid, list(members), all_tags))
        return rid

    def closed_polygon(self, center_lat, center_lon, radius=0.001, n=6, tags=None):
        """Create a closed way polygon (regular n-gon) and return its way ID."""
        nids = self.closed_polygon_nodes(center_lat, center_lon, radius, n)
        nids.append(nids[0])  # close it
        return self.add_way(nids, tags)

    def closed_polygon_nodes(self, center_lat, center_lon, radius=0.001, n=6):
        """Create nodes for a regular n-gon and return their IDs (not closed)."""
        nids = []
        for i in range(n):
            angle = 2 * math.pi * i / n
            lat = center_lat + radius * math.sin(angle)
            lon = center_lon + radius * math.cos(angle)
            nids.append(self.add_node(lat, lon))
        return nids

    def split_way_at(self, nids_closed, split_indices):
        """Split a closed node list into open ways at the given indices.

        Args:
            nids_closed: list of node IDs where last == first (closed ring)
            split_indices: indices at which to split

        Returns:
            list of way IDs
        """
        n = len(nids_closed) - 1
        splits = sorted(set(s % n for s in split_indices))
        if len(splits) < 2:
            splits = [0, n // 2]

        way_ids = []
        for i in range(len(splits)):
            start = splits[i]
            end = splits[(i + 1) % len(splits)]
            if end <= start:
                end += n
            seg_nids = [nids_closed[j % n] for j in range(start, end + 1)]
            way_ids.append(self.add_way(seg_nids))
        return way_ids

    def grid_pos(self, test_num=None, cols=10):
        """Calculate lat/lon for a test case, arranged in a grid.

        If test_num is not provided, uses the next test_id that will be assigned.
        """
        if test_num is None:
            test_num = self._next_test_id + 1
        row = test_num // cols
        col = test_num % cols
        return 47.5 + row * 0.005, -122.5 + col * 0.005

    def write(self):
        """Append all new primitives to the .osm file."""
        if not self.nodes and not self.ways and not self.relations:
            print("Nothing to append.")
            return

        lines = []
        for nid, lat, lon, tags in self.nodes:
            if tags:
                lines.append(f"  <node id='{nid}' action='modify' visible='true'"
                             f" lat='{lat:.11f}' lon='{lon:.11f}'>")
                for k, v in tags.items():
                    lines.append(f"    <tag k='{_esc(k)}' v='{_esc(v)}' />")
                lines.append("  </node>")
            else:
                lines.append(f"  <node id='{nid}' action='modify' visible='true'"
                             f" lat='{lat:.11f}' lon='{lon:.11f}' />")

        for wid, node_ids, tags in self.ways:
            lines.append(f"  <way id='{wid}' action='modify' visible='true'>")
            for nid in node_ids:
                lines.append(f"    <nd ref='{nid}' />")
            for k, v in tags.items():
                lines.append(f"    <tag k='{_esc(k)}' v='{_esc(v)}' />")
            lines.append("  </way>")

        for rid, members, tags in self.relations:
            lines.append(f"  <relation id='{rid}' action='modify' visible='true'>")
            for mtype, ref, role in members:
                lines.append(f"    <member type='{mtype}' ref='{ref}'"
                             f" role='{_esc(role)}' />")
            for k, v in tags.items():
                lines.append(f"    <tag k='{_esc(k)}' v='{_esc(v)}' />")
            lines.append("  </relation>")

        new_xml = '\n'.join(lines)

        with open(self.osm_file, 'w', encoding='utf-8', newline='\n') as f:
            f.write(self._content)
            f.write('\n')
            f.write(new_xml)
            f.write('\n</osm>\n')

        test_ids = [t['_test_id'] for _, _, t in self.relations if '_test_id' in t]
        if test_ids:
            print(f"Appended test case(s) {', '.join(test_ids)} "
                  f"({len(self.nodes)} nodes, {len(self.ways)} ways, "
                  f"{len(self.relations)} relations)")
        else:
            print(f"Appended {len(self.nodes)} nodes, {len(self.ways)} ways, "
                  f"{len(self.relations)} relations (no test relations)")


def _esc(s):
    """Escape XML special characters."""
    return (str(s)
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace("'", '&apos;')
            .replace('"', '&quot;'))


if __name__ == '__main__':
    # ================================================================
    # Define new test cases below, then run:
    #   python3 tests/append-test-case.py
    #
    # Example:
    #
    #   ctx = AppendContext('tests/testdata-proposed.osm')
    #   lat, lon = ctx.grid_pos()
    #   outer = ctx.closed_polygon(lat, lon, radius=0.002, n=6)
    #   inner = ctx.closed_polygon(lat, lon, radius=0.0005, n=4)
    #   ctx.add_relation(
    #       members=[('way', outer, 'outer'), ('way', inner, 'inner')],
    #       tags={'natural': 'water'},
    #       notes='1 outer + 1 inner, no touching',
    #       expected='DISSOLVE: tag outer, delete relation')
    #   ctx.write()
    # ================================================================
    ctx = AppendContext('tests/testdata-proposed.osm')

    # ---- Test 130: Valid island-within-a-hole ----
    # Big outer > inner (hole) > small outer (island, needs chaining from 2 open ways)
    # Identity-protected (has name tag), so extraction is blocked.
    # Expected: CONSOLIDATE_RINGS only, relation survives.
    lat, lon = ctx.grid_pos()
    outer_big = ctx.closed_polygon(lat, lon, radius=0.002, n=8)
    inner_hole = ctx.closed_polygon(lat, lon, radius=0.0015, n=6)
    # Island: 2 open ways that chain into a closed ring
    island_nodes = ctx.closed_polygon_nodes(lat, lon, radius=0.0005, n=4)
    island_nodes_closed = island_nodes + [island_nodes[0]]
    island_ways = ctx.split_way_at(island_nodes_closed, [0, 2])
    ctx.add_relation(
        members=[('way', outer_big, 'outer'), ('way', inner_hole, 'inner')]
                + [('way', wid, 'outer') for wid in island_ways],
        tags={'natural': 'water', 'name': 'Test Lake'},
        notes='island-within-a-hole: outer > inner > outer (island needs chaining)',
        expected='CONSOLIDATE_RINGS: chain island, relation survives (identity-protected)')

    # ---- Test 131: Invalid nested outer with inners present ----
    # Big outer with a small outer nested inside it (no mediating inner).
    # An inner IS present but is offset — it does NOT enclose the nested outer.
    # Expected: SKIP with NESTED_OUTER_RINGS.
    lat, lon = ctx.grid_pos()
    outer_big2 = ctx.closed_polygon(lat, lon, radius=0.002, n=8)
    # Small outer nested inside the big one (at center)
    small_outer = ctx.closed_polygon(lat, lon, radius=0.0004, n=4)
    # Inner is offset so it does NOT contain the small outer
    inner_offset = ctx.closed_polygon(lat + 0.001, lon + 0.001, radius=0.0005, n=4)
    ctx.add_relation(
        members=[('way', outer_big2, 'outer'), ('way', small_outer, 'outer'),
                 ('way', inner_offset, 'inner')],
        tags={'natural': 'water'},
        notes='nested outer with non-mediating inner (inner offset, does not enclose nested outer)',
        expected='SKIP: NESTED_OUTER_RINGS (inner does not mediate the nesting)')

    ctx.write()
