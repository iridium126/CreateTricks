# Create: Mana Industry

[中文](./README_zh.md)

A NeoForge addon for **Create** that bridges kinetic (rotational) power with magical
mods: convert stress into mana, atomize fluids into volumetric mist, automate
spell-casting item production, and more — all through Create's mechanical systems.

## Features

### Core Mechanics

- **Kinetic Mana Generator** — consumes Create rotational stress to produce mana,
  charging Trickster knots placed in Spell Constructs or Charging Arrays.
  Stress-per-RPM is adjustable via an in-world scroll box.
- **Kinetic Atomizer** — atomizes piped fluids into a volumetric mist field whose
  radius scales with speed. Mist concentration decays with distance; overlapping
  fields combine. Required for mist-based recipes.
- **Condenser** — condenses mist back into liquid when water flows through it,
  injecting the recovered fluid into a Create Item Drain below. Efficiency
  scales with water pressure and mist concentration.
- **Allay Burner** — a blaze-burner-style heat source centered on a captured
  allay. It burns amethyst materials and Liquid Media for burn time, mirrors a
  Jukebox's record slot, and emits a Liquid Soul mist field while burning.
  Adds the `allayheated` heat requirement for basin recipes.
- **Mist field system** — a server-side spatial data store (per-dimension
  `ConcurrentHashMap`) for atomizer mist fields. Concentration is computed
  on-the-fly via Euclidean distance, with dominant-source resolution for
  overlapping fields. Timed emissions support recipe byproducts.

### Fluid System

- **Liquid Mana** — a full-bright fluid that stores mana; compatible with
  Create's pipe and tank networks. Configured via `manaPerBucket`.
- **Liquid Media** — a pink, glowing fluid that stores Hexcasting media
  (1 bucket = `mediaPerBucket` media units, default 400,000). Bridge fluid
  between Create and Hexcasting energy systems.
- **Bidirectional conversion** — Heated Mixing: Liquid Media ↔ Liquid Mana (1:1).
  Mist Mixing via Kinetic Atomizer: Liquid Mana → Liquid Media vapour.
  Condenser: mist → Liquid Media. Full circulation roundtrip.

### Recipe Types

- **Heated Compacting** — heated basin recipe for the Mechanical Press.
  Converts amethyst dust/shards/charged amethyst into Liquid Media (output
  dynamically recalculated from config values).
- **Mist Compacting** — press recipe that requires or produces mist byproducts.
- **Mist Mixing** — mixer recipe with mist conditions and emissions
  (duration, radius, amount configurable per recipe).

### Trickster Integration

- **Trickster trick:** `temporary_kinetic_stress` — temporarily applies stress
  and speed to any kinetic block entity at a mana cost, with configurable
  magnitude and duration.
- **Sequenced assembly** — craft Trickster knots via Create's assembly line:
  incomplete knot → Spout filling with Liquid Mana → Mechanical Press.
  Supports emerald, prismatic, diamond, echo, and astral knots.
- **Display Link targets** — write text into Spell Construct and Modular
  Spell Construct arguments. Individual arguments or core+argument pairs.
- **Mechanical Arm support** — arms can insert/extract knot items from
  Trickster Charging Arrays, Spell Constructs, and Modular Spell Constructs.
- **Kinetics Spell Core** — attach to a Modular Spell Construct to link it
  with cogwheel-chain networks (Bits 'n' Bobs), exposing kinetic stats as
  Trickster data for spell logic.

### Hexcasting Integration

- **Automated casting item pipeline** — fully automate Cypher, Trinket, and
  Artifact production:
  1. Deployer + Iota-holding item → writes patterns onto the item
  2. Spout + Liquid Media → fills media up to config-defined capacity
  3. Mechanical Press → finalizes into the complete Hexcasting item,
     transferring all patterns, media, and pigment intact
- **Media battery assembly** — Deployer + Glass Bottle + Large Scroll
  (Craft Battery op_id) → fills with Liquid Media → Press → Phial of Media.
  Final capacity matches the filled media amount.
- **Custom Hexcasting action** — `Read Iota from Block`
  (pattern: `wqwqwqwqwqwaw`, 0 media). Reads an Iota from an
  Iota-holding item resting on a Create Depot or Placard.
- **Custom Hexcasting action** — `Light Burner`
  (pattern: `qwawq`). Consumes a vector (block position) and a number
  (burn time in seconds) to light the Create Blaze Burner or the Allay
  Burner at that position and add the given burn time. Media is charged
  per second at the burner's own burn rate, so the spell is economically
  equivalent to pouring in Liquid Media. Empty burners (no blaze inside)
  and SEETHING/creative burners are left untouched.
- **Slate pattern stonecutting** — dynamically registers stonecutter recipes
  for every non-great-spell Hexcasting action. Place a blank Slate in a
  Stonecutter to apply any pattern.
- **Media battery fluid handler** — finalized batteries support fill/drain
  via Create Fluid Pipes, enabling use as media buffers in automation.
- **Patchouli guide entries** — 7 entries added to Hexcasting's "Hexbook"
  covering all integration features, with full English and Chinese
  localization.

### Visual & Network

- **Volumetric mist rendering** — mist fields render as semi-transparent
  colored volumes using Veil shader post-processing (Veil optional but
  recommended).
- **Network sync** — mist field state synced to clients via custom packets
  for real-time rendering updates.

## Dependencies

### Required
- [NeoForge](https://neoforged.net) (1.21.1, 21.1+)
- [Create](https://createmod.net) (6.0.10+)

### Optional
- [Trickster](https://modrinth.com/mod/trickster) (2.0.0-beta.48+) — knot automation, kinetic spell core, Display Link targets, stress trick
- [Hexcasting](https://modrinth.com/mod/hexcasting) (0.12.0-devel-pre-35+) — casting item pipeline, media batteries, custom hex action, slate stonecutting
- [Create: Bits 'n' Bobs](https://modrinth.com/mod/create-bits-n-bobs) (2.1.9-beta+) — cogwheel chain integration
- [Veil](https://modrinth.com/mod/veil) (4.1.4+) — volumetric mist shader rendering

## Configuration

All values are in the common config (`createmanaindustry-common.toml`):

| Key | Default | Description |
|---|---|---|
| `manaPerStress` | 0.001 | Mana per stress unit per tick |
| `manaPerBucket` | 2048 | Mana in one bucket of Liquid Mana |
| `mediaPerBucket` | 400000 | Media in one bucket of Liquid Media |
| `mediaConsumedPerTick` | 50 | Media consumed per tick while the Allay Burner is burning (drives fuel burn duration and the Light Burner spell's cost) |
| `kineticStressTrickManaMultiplier` | 2.0 | Mana cost multiplier for temporary stress trick |
| `mistMaxRadius` | 16 | Max atomizer mist radius in blocks |
| `mistFluidPerTick` | 8 | Base fluid consumption per tick at 256 RPM |
| `mistBaseConcentration` | 1.0 | Concentration at distance 0 |
| `condenseEfficiency` | 5.0 | Base mB/tick condensed per concentration unit |
| `cypherMaxMedia` | 6400000 | Max media for incomplete cyphers |
| `trinketMaxMedia` | 64000000 | Max media for incomplete trinkets |
| `artifactMaxMedia` | 640000000 | Max media for incomplete artifacts |
| `batteryMaxMedia` | 640000000 | Max media for incomplete media batteries |

## Build

```bash
./gradlew build
```

## License

MIT License
