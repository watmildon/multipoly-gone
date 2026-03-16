<img width="1669" height="1374" alt="image" src="https://github.com/user-attachments/assets/962c7018-a1e8-46a7-9b8a-e21757fb8b5f" />

# Multipoly-Gone

A [JOSM](https://josm.openstreetmap.de/) plugin that detects geometrically unnecessary [multipolygon relations](https://wiki.openstreetmap.org/wiki/Relation:multipolygon) and converts them into simpler representations. It will also offer to streamline relations with `type=boundary`. Just a few clicks, and we get a tidier map.

The plugin should never change the semantics of an OSM object, only the representaton. If you find that it's clobbering things, that is a bug and I would very much like to get those fixed.

## Installation

Download `multipoly-gone.jar` from [Releases](https://github.com/watmildon/multipoly-gone/releases) and drop it into your JOSM plugins directory:

* **Windows:** `%APPDATA%\JOSM\plugins\`
* **macOS:** `~/Library/JOSM/plugins/`
* **Linux:** `~/.local/share/JOSM/plugins/`

Restart JOSM and add it through the plugins preference page.

After some more battle hardening, this may be made available in a more standard way. I don't want to hand out experimental hammers just yet.

## Fixing geometrically unnecessay or unusually constructed multipolygons

1. Open a data layer containing multipolygon or boundary relations
2. Open the Multipoly-Gone panel (Alt+Shift+M, or Windows menu > Multipoly-Gone)
3. Click **Refresh** to scan the current layer
4. The **Fixable** tab shows relations that can be simplified, with a description of what will change
5. Select a relation and click **Gone** to fix it, or **All Gone** to fix everything

Double-click a relation in the list to zoom to it. The **Skipped** tab shows relations that couldn't be "fixed" and why.

* **Open outer ways** that can be chained into a single closed way
* **Standalone outer rings** with no inners that don't need a relation at all
* **Touching inner/outer pairs** that can merge into a single way
* **Disconnected components** that should be separate relations
* **Self-intersecting rings** (bowties) decomposed into clean sub-rings
* **Overlapping open outer ways** consolidate overlapping open ways into sensible rings
* **Consolidated abutting inners** consolidate abutting closed ways and joined open inners into single rings
* **Replace untagged inner ways** imports sometimes add the inner and the geometry for what the inner is as two objects, we consolidate them

Relations with identity tags (`name`, `ref`, `wikidata`, etc.) are protected from being dissolved or split — only consolidation and extraction operations apply. I would love [feedback about tags that constitute "identity"](https://github.com/watmildon/multipoly-gone/issues/8).

## Replacing giant areas with collections of smaller areas

Often landuses and other features are mapped across huge swathes of the map. It is advisable to break these into more managable components and highways and other features are typical and useful breakpoints. The "Break Polygon" tab will evaluate a selected area (closed way or mulptipolygon) and offer to break things and offset them along intervening features.

1. Select the way or multipolygon you wish to break apart
2. Open the Multipoly-Gone panel (Alt+Shift+M, or Windows menu > Multipoly-Gone)
3. Select the Break Polygon tab
4. Review the suggested breaks, hit Break
5. Apply the JOSM simplify command if necessary

You can set which tags you think should be broken along and add estimated buffer distances in the Preferences pane of Multipoly-Gone. The set of features to break along is an open question. There is a [thread for discussing such things](https://github.com/watmildon/multipoly-gone/issues/21) are you come across new and useful ideas.

<img width="500" height="400" alt="image" src="https://github.com/user-attachments/assets/3732327d-c5d0-49ce-8f43-9c8afe0e36b1" />

## Unglue

A common incorrect mapping pattern is to attach area features (`leisure=park`, `landuse=residential`, etc) onto centerway features (`highway`, `waterway`, etc). It is tedius to unglue nodes and offset the area. The Unglue tab detects these features and offsets them away from the centerline by half the feature's width, producing cleaner geometry.

The default set of "centerline" features are `highway`, `waterway`, and `railway`, adjustable in the preferences.

Unglue All currently works off of an allowlist of tags when looking for things to unglue and offset.  The defaults are `landuse`,`leisure`,`building`,`amenity`,`natural`.

For best results, add `width`/`lanes` etc tagging to centerlines before ungluing. This give you the best shot at less cleanup.

<img width="400" height="250" alt="image" src="https://github.com/user-attachments/assets/b8913095-90cd-4159-877b-79f30e9318bf" />
<img width="250" height="250" alt="image" src="https://github.com/user-attachments/assets/a1619857-18ef-43ec-858a-fa377aa83e4f" />


## Configuration

Settings are in JOSM Preferences > Multipoly-Gone. You can customize:

* Which tags protect a relation's identity (prevent dissolve/split)
* Which tags are considered insignificant for cleanup purposes (separately for multipolygons and boundaries)
* Whether to include JOSM's built-in discardable keys (TIGER, etc.)
* Which tags to break on and offsets

## Break Polygon — Offset Calculation

### 1. Road Width Resolution (priority order)

What other tags should be used and what is a reasonable "offset" for each?

| Priority | Source | Example | Width |
|----------|--------|---------|-------|
| 1 | OSM `width` tag | `width=40` | 40m |
| 2 | OSM `lanes` tag | `lanes=2` | 2 x 3.5m = 7m |
| 3 | Configured tag filters (defaults below) | `highway=primary` | 7m |
| 4 | Hardcoded fallback | *(no tags match)* | 3.5m |

### Default Tag Filter Widths

These all err on the side of being too small.

| Tag | Width (meters) |
|-----|---------------|
| `highway=motorway` | 12 |
| `highway=trunk` | 10 |
| `highway=primary` | 7 |
| `highway=secondary` | 7 |
| `highway=tertiary` | 7 |
| `highway=unclassified` | 7 |
| `highway=residential` | 7 |
| `highway=service` | 7 |
| `highway=track` | 3.5 |
| `railway` | 7 |
| `waterway=stream` | 3.5 |
| `waterway=river` | 12 |

Configured via `multipolygone.roadWidths` preference. Format: `key=value=width` entries separated by `;`.
