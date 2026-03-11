#!/usr/bin/env python3
"""
Canonicalize negative IDs in .osm test data files.

JOSM assigns arbitrary negative IDs to new primitives, and re-saving a file
assigns fresh IDs — causing every line to show as changed in git diffs even
when the geometry is identical. This script remaps all negative IDs to a
deterministic sequential numbering (-1, -2, -3, ...) based on order of first
appearance in the file.

Usage:
    python3 tests/canonicalize-osm-ids.py tests/unit-tests-break-polygon.osm
    python3 tests/canonicalize-osm-ids.py tests/*.osm  # multiple files

The file is modified in-place. Line endings are normalized to LF.
"""

import re
import sys


def canonicalize(filepath):
    with open(filepath, 'r', encoding='utf-8', newline='') as f:
        content = f.read()
    content = content.replace('\r\n', '\n')

    # Find all negative IDs in order of first appearance.
    # Match id='...' and ref='...' attributes with negative values.
    neg_id_pattern = re.compile(r"(?:id|ref)='(-\d+)'")
    seen = {}
    next_id = -1

    def remap(match):
        nonlocal next_id
        old_id = match.group(1)
        if old_id not in seen:
            seen[old_id] = str(next_id)
            next_id -= 1
        return match.group(0).replace(old_id, seen[old_id])

    new_content = neg_id_pattern.sub(remap, content)

    if new_content == content:
        print(f"  {filepath}: already canonical")
        return False

    with open(filepath, 'w', encoding='utf-8', newline='\n') as f:
        f.write(new_content)

    count = len(seen)
    print(f"  {filepath}: remapped {count} IDs")
    return True


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: canonicalize-osm-ids.py <file.osm> [file2.osm ...]")
        sys.exit(1)

    changed = 0
    for path in sys.argv[1:]:
        if canonicalize(path):
            changed += 1

    if changed:
        print(f"\n{changed} file(s) updated. Review and commit.")
    else:
        print("\nAll files already canonical.")
