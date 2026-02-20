#!/usr/bin/env python3
"""
Strip unnecessary metadata from real-world .osm test data files.

Removes timestamp, uid, user, changeset, and action attributes from
node/way/relation elements. Keeps id, version, visible, lat, lon, ref,
role, and all tags — everything the plugin and JOSM's OsmReader need.

Usage:
    python tests/strip-osm-metadata.py                    # strip all real-world test files
    python tests/strip-osm-metadata.py tests/newfile.osm  # strip a specific file

Skips testdata.osm (hand-crafted with intentional negative IDs and action attrs).
"""

import glob
import re
import sys

ATTRS_TO_STRIP = ['timestamp', 'uid', 'user', 'changeset', 'action']
SKIP_FILES = ['testdata.osm']


def strip_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    original_size = len(content)

    for attr in ATTRS_TO_STRIP:
        content = re.sub(
            r"(<(?:node|way|relation)\b[^>]*?) " + attr + r"='[^']*'",
            r'\1',
            content
        )

    new_size = len(content)
    if new_size < original_size:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f'  {filepath}: {original_size:,} -> {new_size:,} bytes ({100 - new_size*100//original_size}% smaller)')
    else:
        print(f'  {filepath}: already clean ({original_size:,} bytes)')


def main():
    if len(sys.argv) > 1:
        # Strip specific files passed as arguments
        files = sys.argv[1:]
    else:
        # Strip all .osm files in tests/ except skipped ones
        files = sorted(glob.glob('tests/testdata*.osm'))
        files = [f for f in files if not any(f.endswith(s) for s in SKIP_FILES)]

    if not files:
        print('No files to process.')
        return

    print(f'Stripping metadata from {len(files)} file(s):')
    for filepath in files:
        strip_file(filepath)
    print('Done.')


if __name__ == '__main__':
    main()
