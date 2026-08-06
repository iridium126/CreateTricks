```json
{
  "title": "New Tricks",
  "icon": "trickster:amethyst_knot",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 4
}
```

Create: Mana Industry registers three tricks of its own. The first lets
you spend mana to *push* kinetic machinery around; the other two are the
front door to the [Hexcasting Bridge](^createmanaindustry:mana_industry/hexcasting_bridge).

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:temporary_kinetic_stress|>

**Kinetic Stress Ploy** — temporarily applies stress and rotational speed
to any kinetic machine, as if a source had just been attached. The stress
magnitude is `|speed| × 4`; when the duration runs out the machine returns
to its previous state. Costs `manaPerStress × stress × duration × 2` mana,
and returns the target position.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_iota|>

**Read Iota** — reads a Hex Casting iota from an item holding one, within
16 blocks, producing an *Iota Fragment*. The slot argument is optional and
defaults to the other hand.

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:eval_iota|>

**Eval Iota** — executes the Hex Casting spell stored inside an
[Iota Fragment](^createmanaindustry:mana_industry/hexcasting_bridge), with
the remaining arguments as its initial stack. Casting inside a Spell
Construct spends media stored in the construct; casting as a player spends
media from your inventory. See the
[Hexcasting Bridge](^createmanaindustry:mana_industry/hexcasting_bridge)
for the full picture.
