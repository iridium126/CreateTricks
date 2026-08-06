```json
{
  "title": "Kinetics Spell Core",
  "icon": "createmanaindustry:kinetics_spell_core",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 2,
  "associated_items": ["createmanaindustry:kinetics_spell_core"]
}
```

The **Kinetics Spell Core** is a [Spell Core](^trickster:items/infrastructure/spell_core)
that draws its power from Create's *gear chains*. Place it in one of the four
corner slots of a
[Modular Spell Construct](^trickster:items/infrastructure/modular_spell_construct)
and link the construct into a **Cogwheel Chain** from Bits 'n' Bobs.

;;;;;

A linked construct gets an execution limit of `chain speed / 32` times its
normal limit — the faster the chain spins, the more spell execution the
construct can perform, down to a minimum of 1. Without a chain the core is
completely inert and the construct cannot execute at all.

;;;;;

Each core also *demands* stress from the network: **4 SU × speed** per core,
drawn through the chain like any other kinetic load. When a construct is
broken, the remaining chain is rebuilt automatically and the lost chain
length refunded. The rotating gear is rendered by the core itself.
