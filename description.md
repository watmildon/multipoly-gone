# overview
we're making another plugin for the OpenStreetMap editor JOSM.

We previously made one for roadway cleanup (C:\Repos\TIGER-ROAR) but today it's about correcting a disagreeable mapping behavior, overuse of the multipolygon relation datatype when the much simpler way datatype would suffice.

## the problem
There are 2 ways to represent an area in OSM. 
1) a closed way where the start and end node are identical
2) a relation of type multipolygon where the members with the role of `outer` form a closed ring

They are necessary in SOME cases because the geometry is too compled otherwise (ex: and area with a hole in it OR an area that will exceed the way node limit of 2000). However, they are used in many cases where they are no geometrically necessary. All such cases should be converted to simpler ways.

Some reading: https://wiki.openstreetmap.org/wiki/Relation:multipolygon

particularly: https://wiki.openstreetmap.org/wiki/Relation:multipolygon#Mapping_style,_best_practice

## the start of the art
Some plugins will let you simply a single MP into simpler closed way representations but nothing offers a one click "fix all of this area" experience. this is also called "reconstruct polygon".

https://wiki.openstreetmap.org/wiki/JOSM/Plugins/Relation_Toolbox


## Our experience
A pane line the relation toolbox that will list all MP that are not geometrically necessary. A user can select one or many and hit the "gone" button to automatically redo the geometry into the simpler from. Alternately they can hit "all gone" and the plugin will run for all of the data loaded into a layer.

## constraints
We are never allowed to change the semantics of the representation. Only how it is encoded in the database.

## To start
A test data file has been created and added to this repository. It is in .osm xml format. We will use the `_test_id` field to identify which things to work on as we go. Here is a description of each id and the expected behavaior of the plugin when faced with that object.

0 - nothing. this object is geometrically necessary. it has an inner and and outer that do not share any portion of their path.
1 - this object has two distinct outers but there's not reason they should be joined together. this is "using a relation as a category". The tags should be duplicated onto the ways (currenty with no tags) and the relation deleted
2 - this object has only one outer and no inners. it should receive the same treatment as 1
3 - a relation with 2 outers forming a closed loop. A new closed way should be made that has the same nodes as the current loop but with the simpler representation. The tags moved to this new way and the relation deleted.
4 - similar to 3. this time we add an additional cleanup step. After dissolving the relation into a new closed way, we need to clean up any ways that are now "unused". 