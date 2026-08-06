```json
{
  "title": "Kinetic Mana Generator",
  "icon": "createmanaindustry:kinetic_mana_generator",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 1,
  "associated_items": ["createmanaindustry:kinetic_mana_generator"]
}
```

The **Kinetic Mana Generator** converts Create's *stress* into charged
[Knots](^trickster:items/mana/knots). Point its face at a Knot seated in a
[Spell Construct](^trickster:items/infrastructure/spell_construct) or
[Charging Array](^trickster:items/mana/charging_array), power it, and mana
flows into the Knot every tick.

;;;;;

A scroll box sets the *stress per RPM* the machine demands — from 4 up to
256, defaulting to 4. Each tick it consumes `stress × speed` and converts it
to `stressConsumed × manaPerStress` mana (0.001 by default), split equally
among all Knots in a Charging Array. Only *traditional* mana is charged;
full or infinite Knots are left alone, and an overstressed machine idles.

;;;;;

Goggles show the required stress at the current speed. Because the machine
feeds whatever sits on its output face, automated arms and hoppers can keep
supplying fresh Knots — a **Mechanical Arm** interaction point lets arms
insert and extract Knots from the array or construct directly.
