```json
{
  "title": "Incomplete Knots",
  "icon": "createmanaindustry:incomplete_emerald_knot",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 3,
  "associated_items": ["createmanaindustry:incomplete_emerald_knot", "createmanaindustry:incomplete_prismatic_knot", "createmanaindustry:incomplete_diamond_knot", "createmanaindustry:incomplete_echo_knot", "createmanaindustry:incomplete_astral_knot"]
}
```

[Knots](^trickster:items/mana/knots) can be fabricated entirely by machine.
Crafting a knot's gem with a glass block yields an **Incomplete Knot** —
an empty vessel holding no mana, with a blue progress bar showing how much
mana has been poured in.

;;;;;

A **Spout** fills the incomplete knot with **Liquid Mana** — 1000 mB
carries `manaPerBucket` mana (2048 by default). Each fill operation adds to
the accumulated mana; once the vessel holds the knot's full creation cost,
it **transforms into the finished Knot** on the spot:

- Emerald — 512 mana
- Prismatic — 8,192 mana
- Diamond — 8,192 mana
- Echo — 65,536 mana
- Astral — 524,288 mana

;;;;;

Finished Knots can be *cracked* by pressing them in a **Mechanical Press**,
producing the corresponding [Cracked Knot](^trickster:items/mana/cracked_knots).
The full pipeline — deploy, fill, press — runs on standard Create
automation, and Knots holding Liquid Mana can also be charged or drained
through fluid pipes directly.
