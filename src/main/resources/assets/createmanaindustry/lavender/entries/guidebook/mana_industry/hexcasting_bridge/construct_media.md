```json
{
  "title": "Construct Media Storage",
  "icon": "createmanaindustry:liquid_media_bucket",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 1,
  "required_advancements": ["hexcasting:root"],
  "associated_items": ["createmanaindustry:liquid_media_bucket"]
}
```

A [Spell Construct](^trickster:items/infrastructure/spell_construct) can be
fed Hex Casting *media* directly. Items holding media — charged amethyst,
media batteries, and the like — inserted into the construct are absorbed
into its internal media storage instead of a regular inventory slot,
alongside any Knot you seat for Trickster mana.

;;;;;

The stored media powers Hex casting performed *by the construct*: the
**Eval Iota** trick spends it to run Hex spells, and the Hex pattern
**Execute Trick** running inside a construct draws from the same pool. An
inserted creative unlocker provides infinite media. Media is persisted with
the construct's data, so a construct keeps its fuel between chunk loads.

;;;;;

Automation is straightforward: hoppers and mechanical arms can feed media
items into the construct, and media batteries hold a buffer of
[Liquid Media](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media)
ready to be converted — the same tank that fills casting items on the Hex
side.
