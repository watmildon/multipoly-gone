<img width="1669" height="1374" alt="image" src="https://github.com/user-attachments/assets/962c7018-a1e8-46a7-9b8a-e21757fb8b5f" />

# Multipoly-Gone

A [JOSM](https://josm.openstreetmap.de/) plugin that detects geometrically unnecessary [multipolygon relations](https://wiki.openstreetmap.org/wiki/Relation:multipolygon) and converts them into simpler representations. It will also offer to streamline relations with `type=boundary`. Just a few clicks, and we get a tidier map.

The plugin should never change the semantics of an OSM object, only the representaton. If you find that it's clobbering things, that is a bug and I would very much like to get those fixed.

## What it fixes

* **Open outer ways** that can be chained into a single closed way
* **Standalone outer rings** with no inners that don't need a relation at all
* **Touching inner/outer pairs** that can merge into a single way
* **Disconnected components** that should be separate relations
* **Self-intersecting rings** (bowties) decomposed into clean sub-rings

Relations with identity tags (`name`, `ref`, `wikidata`, etc.) are protected from being dissolved or split — only consolidation and extraction operations apply.


## Installation

Download `multipoly-gone.jar` from [Releases](https://github.com/watmildon/multipoly-gone/releases) and drop it into your JOSM plugins directory:

* **Windows:** `%APPDATA%\JOSM\plugins\`
* **macOS:** `~/Library/JOSM/plugins/`
* **Linux:** `~/.local/share/JOSM/plugins/`

Restart JOSM and add it through the plugins preference page.

## Usage

1. Open a data layer containing multipolygon or boundary relations
2. Open the Multipoly-Gone panel (Alt+Shift+M, or Windows menu > Multipoly-Gone)
3. Click **Refresh** to scan the current layer
4. The **Fixable** tab shows relations that can be simplified, with a description of what will change
5. Select a relation and click **Gone** to fix it, or **All Gone** to fix everything

Double-click a relation in the list to zoom to it. The **Skipped** tab shows relations that couldn't be "fixed" and why.

## Configuration

Settings are in JOSM Preferences > Multipoly-Gone. You can customize:

* Which tags protect a relation's identity (prevent dissolve/split)
* Which tags are considered insignificant for cleanup purposes (separately for multipolygons and boundaries)
* Whether to include JOSM's built-in discardable keys (TIGER, etc.)
