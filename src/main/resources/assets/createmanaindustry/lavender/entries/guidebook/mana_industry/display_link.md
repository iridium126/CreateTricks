```json
{
  "title": "Display Link Arguments",
  "icon": "create:display_link",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 5,
  "associated_items": ["create:display_link"]
}
```

A Create **Display Link** aimed at a
[Spell Construct](^trickster:items/infrastructure/spell_construct) writes
its text straight into the construct's *spell arguments* — the first eight
lines become arguments one through eight. For a
[Modular Spell Construct](^trickster:items/infrastructure/modular_spell_construct)
each of the four executor slots gets its own eight-line argument block.

;;;;;

The stored text is written into the executing spell's arguments on every
tick, replacing them as `String Fragments` — missing lines become
`Void Fragments`, so a spell reading its arguments always sees the current
display state. This turns the humble Display Link into a *live
configuration panel* for running spells: toggle numbers, labels, or whole
strings without touching the spell itself.

;;;;;

Combine it with the [Kinetics Spell Core](^createmanaindustry:mana_industry/kinetics_spell_core):
a chain-driven modular construct can re-read its arguments constantly,
letting a row of Display Links drive a fully automated spell factory.
