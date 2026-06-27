#!/usr/bin/env python3
"""
Strip unnecessary metadata from .osm test data files.

Strips timestamp, uid, user, changeset, action, and visible attributes from
node/way/relation elements. Keeps id, version (required for positive-ID
primitives), lat, lon, ref, role, and all tags — everything the plugin
and JOSM's OsmReader need. See CLAUDE.md "Required Shape for .osm Test Files".

Usage:
    python tests/strip-osm-metadata.py                    # strip every testdata*.osm file
    python tests/strip-osm-metadata.py tests/newfile.osm  # strip specific files
"""

import glob
import re
import sys

ATTRS_TO_STRIP = ['timestamp', 'uid', 'user', 'changeset', 'action', 'visible']


def strip_file(filepath):
    with open(filepath, 'r', encoding='utf-8', newline='') as f:
        content = f.read()
    content = content.replace('\r\n', '\n')

    original_size = len(content)

    for attr in ATTRS_TO_STRIP:
        # Single-quoted values: attr='...'
        content = re.sub(
            r"(<(?:node|way|relation)\b[^>]*?) " + attr + r"='[^']*'",
            r'\1',
            content
        )
        # Double-quoted values: attr="..."
        content = re.sub(
            r'(<(?:node|way|relation)\b[^>]*?) ' + attr + r'="[^"]*"',
            r'\1',
            content
        )

    new_size = len(content)
    if new_size < original_size:
        with open(filepath, 'w', encoding='utf-8', newline='\n') as f:
            f.write(content)
        print(f'  {filepath}: {original_size:,} -> {new_size:,} bytes ({100 - new_size*100//original_size}% smaller)')
    else:
        print(f'  {filepath}: already clean ({original_size:,} bytes)')


def main():
    if len(sys.argv) > 1:
        # Strip specific files passed as arguments
        files = sys.argv[1:]
    else:
        # Strip every testdata*.osm file in tests/
        files = sorted(glob.glob('tests/testdata*.osm'))

    if not files:
        print('No files to process.')
        return

    print(f'Stripping metadata from {len(files)} file(s):')
    for filepath in files:
        strip_file(filepath)
    print('Done.')


if __name__ == '__main__':
    main()
