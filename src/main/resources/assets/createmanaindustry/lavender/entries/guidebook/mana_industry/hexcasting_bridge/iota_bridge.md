```json
{
  "title": "The Iota Bridge",
  "icon": "trickster:amethyst_knot",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 0,
  "required_advancements": ["hexcasting:root"],
  "associated_items": ["createmanaindustry:incomplete_cypher"]
}
```

Hex Casting stores data in *iotas*; Trickster works in *fragments*. The
bridge translates between them losslessly in both directions. On the
Trickster side, an **Iota Fragment** (`createmanaindustry:iota`) wraps a
complete Hex iota, serialised to NBT and gzip-compressed — nothing is lost
in transit, and its glyph displays exactly as Hex Casting would render it.

;;;;;

On the Hex side, a **Trick Iota** wraps a complete Trickster *SpellPart*
tree, serialised with Trickster's own compressed spell format. Both carry
their contents losslessly, so a spell can travel: read it into a Trick
Iota, hand it to a Trickster, have it modified and returned, and execute it
again as if it never left.

;;;;;

The entry points mirror each other:

- **Trickster → Hex**: [Read Iota](^createmanaindustry:mana_industry/tricks)
  pulls an iota from a held item; **Eval Iota** executes the Hex spell
  inside it.
- **Hex → Trickster**: the *Read Trick From Item* pattern (`qqqqqa`) reads
  a spell from a held knot into a Trick Iota; *Execute Trick*
  (`wdwewawqwqw`) runs it — even inside a spell circle, drawing Trickster
  mana from slate knots.

Both patterns are documented in the Hex Casting book under *Create: Mana
Industry → The Trickster's Art*.
