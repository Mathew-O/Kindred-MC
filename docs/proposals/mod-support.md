# Mod support: reason by what things do, detect what is installed, grow support one folder at a time

Status: proposal, 2026-11-09. IDs F-38 (the capability model), F-36 (the mod enabler and profiles) and F-37 (deep
integrations) are reserved in the [tracker](../feature-spec.md). F-38 starts now, in its own session; F-36 and
F-37 follow once the current track is stable. The pack specs are in [modpacks.md](modpacks.md) and
[cobblemon.md](cobblemon.md).

## The idea in one paragraph

Kindred should know a log because it is tagged `#minecraft:logs`, food because it has a food component, a weapon
because it has an attack attribute, a threat because the game says so, and a recipe because the server's recipe
manager has it, never because a name was typed into Java. Then most mods need nothing: new biomes, woods, stones,
decor, furniture, food and gear are understood on sight. What the game cannot say about a mod (a chair is sat on, a
cooking pot takes a recipe type, a drink quenches thirst, a creature explodes) lives in a small **profile**, one
folder per mod under `mod-support/`, detected and loaded automatically when that mod is installed. Mods that need
real new behaviour (a Pokémon battle, a machine) get an **integration**: a separate jar compiled against the mod's
API, loaded only when the mod is present. Support grows a folder at a time, and the player tells the operator what it
does and does not understand on this server.

## Where the code stands

- Crafting already uses the server's `RecipeManager`: a modded recipe is craftable on day one.
- Food is read from the `FOOD` component (21 sites): it eats what is edible, whatever the mod.
- Hostiles are detected by class; tactics (creeper fuse, skeleton arrows) are per vanilla mob.
- The adapter uses tags in about 120 places; `companion-core` has about 400 hard-coded `minecraft:` ids, in the
  build palettes and generators, `Need` (a fixed enum of 15), `Worth`, the Nether, End and trading plans, lessons and
  the atlas priors.

## F-38 The capability model (start now)

A refactor with no mod installed, in `companion-core` and the adapter, that pays off on its own.

**Capabilities, not names.** A `Capabilities` view, built by the adapter from the game and handed to core as
immutable records, answers questions core now answers with ids:

| Question | Source today | Source after |
| --- | --- | --- |
| Is this a log, plank, ore, food, fuel, bed, door, chest, torch, crop, sapling? | id lists | tags (`#minecraft:logs`, `#minecraft:planks`, `#c:ores/<kind>`, `#c:foods`, `#minecraft:beds`, `#minecraft:doors`, `#c:chests`, `#minecraft:crops`, `#minecraft:saplings`), the fuel registry, the food component, block classes where tags are absent |
| Can it break this with what it holds? | id and tool tables | the block's mineable tags and tool tiers, the item's tool material |
| How hard does this hit, how much does it protect? | id tables | attack damage and speed attributes, armour and toughness attributes, enchantments |
| Is this a threat, and what kind? | class and vanilla names | the hostile class, the attributes (health, damage, speed), ranged by the projectile-attack interface, and a `behaviour` from the profile (explodes, ranged, climbs, flies) |
| Can I stand, climb, swim, pass? | block checks | collision and fluid state (as now), `#minecraft:climbable`, `#minecraft:fences`, the profile's `hazard` for blocks the game does not flag |
| What is this worth? | a table of ids | recipe trees from the recipe manager, gather rates by capability, the profile's overrides |
| What do I need for X? | `Need` enum | **needs as data**: a `Need` is a record (id, what satisfies it as a tag or set, count, how to get it: gather, craft, smelt, trade), the vanilla set shipped as a resource, profiles adding more |
| What does a build need? | plank and log ids | palettes by tag and by "the wood it holds" (the generators already know the shape, not the species) |

Rules for the refactor: ids remain only as fallbacks where no tag exists (the Nether portal frame is obsidian; a
brewing stand is a brewing stand), each listed in one file, `core/VanillaIds`, with the tag it would use if one
existed; no new id outside that file passes review. Fixtures stay the same: a tag-based planner on vanilla must
behave exactly as before, so the full suite is the regression test, plus a JUnit test that fails if a `minecraft:`
literal appears in core outside `VanillaIds` and the vanilla needs resource.

**Acceptance.** The literal count in core outside the two allowed files is zero; the full native suite passes as
before; a ten-seed evaluation shows no change outside the ranges; a fixture with a *renamed* wood (a datapack that
adds a tagged log and plank) is gathered, planked and built with, with no code change.

## F-36 The mod enabler and profiles

### Detection

At server start Kindred reads the installed mods from the loader (`FabricLoader.getInstance().getAllMods()`: id,
version), dumps the registries and tags it needs (blocks, items, entities, recipes, fuel, food, attributes), and
classifies each mod:

| Class | Meaning | What the player does |
| --- | --- | --- |
| `content` | adds only blocks, items, recipes, biomes or entities the capability model already understands | nothing to do; works |
| `supported` | a profile is bundled or installed, and its version range matches | works as the profile says |
| `partial` | a profile exists but is marked partial, or the version is outside its range | works for what the profile covers; the rest listed |
| `unknown` | adds recipe types, entities with unknown behaviour, or systems (a new bar on the HUD) with no profile | treated conservatively (unknown hostiles are threats, unknown stations unused); listed for the operator |
| `policy` | gives free travel or free items (teleports, creative-style storage) | forbidden to AI players unless the server announces the rule for everyone (D6) |

The result is one log block at start, `/aiplayer mods` (and the K panel's Mods tab) listing every mod with its
class and, for `partial` and `unknown`, exactly what Kindred will not do, and a line in the watch page. Nothing is
sent anywhere; the dump stays on the server.

### The folder

```
mod-support/
  README.md                  how to add a mod, the profile format, the certification steps
  _template/                 a profile with every field documented, and a fixture skeleton
  <modid>/
    profile.json             the profile (below)
    README.md                what works, what does not, the versions tested, who maintains it
    fixtures/                GameTests run on a test server with that mod installed
    evidence.md              the last certification run
```

Bundled profiles ship inside the Kindred jar (the folder is a resource root); an operator may add or override one
in `config/kindred/mods/<modid>/profile.json` without rebuilding. A profile is data, never code; it cannot grant
anything the capability model and the installed skills do not already allow.

### The profile

```json
{
  "mod": "farmersdelight",
  "versions": ">=1.2 <2.0",
  "status": "partial",
  "capabilities": {
    "edible":    ["#farmersdelight:bowl_foods"],
    "sittable":  ["#farmersdelight:chairs"],
    "climbable": [],
    "hazard":    { "blocks": [], "entities": [] },
    "logs":      [], "planks": [], "ores": {}
  },
  "stations": [
    { "block": "farmersdelight:cooking_pot", "recipeType": "farmersdelight:cooking", "interaction": "open_and_insert" },
    { "block": "farmersdelight:cutting_board", "recipeType": "farmersdelight:cutting", "interaction": "place_and_use_tool" }
  ],
  "needs": [],
  "mobs":  [],
  "policy": { "teleport": "forbid", "freeItems": "forbid" },
  "names": { "farmersdelight:cutting_board": "a cutting board" },
  "notes": "Cooking and cutting work; drinks are not counted as food."
}
```

Everything the registry dump can derive is left out; a profile holds only what the game cannot say. Fields are
closed: an unknown field is a load error, so a profile cannot smuggle behaviour. `stations` map a block and a recipe
type to one of a fixed set of interactions the adapter implements generically (`open_and_insert`,
`place_and_use_tool`, `use_with_item`, `wait_and_take`); a station that fits none needs an integration (F-37).
`needs` adds data needs (a drink that quenches thirst) only when the system that consumes them exists in core.
`mobs` names a behaviour from a fixed set (`melee`, `ranged`, `explodes`, `climbs`, `flies`, `passive`,
`neutral`) and a flee distance.

### Certification

A mod moves from `unknown` to `supported` when its folder has a profile, fixtures that pass three of three on a
test server with the mod installed at the versions in range, and `evidence.md`. The test server for a mod is a
Gradle run configuration that downloads the mod at its pinned version into a scratch `mods/` folder (never
committed), the same way the Factions pack manifest works. CI runs certified mods' fixtures weekly, not on every
push, so the hour-long suite stays an hour.

### Drafting profiles with a model

A profile can be drafted by the strategist's model from the registry dump and the mod's language file ("what is
`farmersdelight:cooking_pot` for?"), then checked by the fixtures. The draft is never loaded without the fixtures
passing; the model's job is to save the writer typing, not to decide behaviour.

**Acceptance.** Detection and classification in a GameTest with a datapack that adds a tagged wood (content), an
unknown recipe type (unknown) and a profile in `config/kindred/mods/` (supported); `/aiplayer mods` output;
closed-field validation refusing an unknown field; a station interaction fixture per interaction kind on a vanilla
stand-in; the template folder; the first real profile certified end to end (the food pack in [modpacks.md](modpacks.md)).

## F-37 Integrations: real new behaviour, loaded only when present

Some mods change what playing *is*: a Pokémon battle, a Create contraption, a thirst bar. A profile cannot express
that. An integration is a separate Gradle module, `integrations/kindred-<modid>`, compiled against the mod's API,
depending on both Kindred and the mod, declaring the custom entrypoint `kindred:integration`. Kindred discovers
integrations through that entrypoint and never references them otherwise, so a server without the mod never loads
the class. An integration may register: capabilities (as a profile does, in code when the mod exposes them only
through its API), skills (recorded actions like any native skill, under the arbiter, the edits policy and the
ledger), needs and the rules that satisfy them, event kinds for the World Record, channels or messages for the Wire,
and HUD sections. It may not bypass the ledger, the arbiter, the policy or the visibility rules: the service
interfaces expose only those paths. The first integration is Cobblemon ([cobblemon.md](cobblemon.md)).

**Acceptance.** The entrypoint, the service interfaces and a stub integration in the test sources that registers one
skill and one need and is exercised by a GameTest; a server without the stub's declared dependency never loads it.

## Architectural decisions to take now

For the sessions building Factions and the World Record, so mod support slots in later without rework:

1. **No new `minecraft:` ids in core.** Tags, components and attributes; anything that must be an id goes in
   `VanillaIds` with a note. The JUnit guard lands with F-38; until then, review for it.
2. **Needs are data.** Nothing new is added to the `Need` enum; new needs wait for F-38's records.
3. **Skills are a registry, already.** `ProposalGate`'s installed-skill set stays the one list of what a body can
   do; integrations add to it through the entrypoint, never by editing `NativePlayerSkills`.
4. **Event kinds and channels are strings with a registry**, not enums, in F-31 and F-34, so an integration can log
   a capture, a catch or a battle and speak on a channel without touching core.
5. **Palettes by tag.** Factions' hub and market generators (M-06f), like the base generators, take planks and logs by
   tag and "the wood it holds".
6. **The policy switch for travel and free items** (`teleport`, `freeItems`) exists in the edits policy from the
   start, forbidden by default, announced per server like a mode item (D6); Factions' safe-zone and claim rules read
   it.
7. **The HUD has sections**, not a fixed layout (M-06l): an integration adds a section the same way Factions does.
8. **Perception interest sets are tags.** The atlas's sightings and the perception interest sets are keyed by tag
   so a modded ore is an ore.

## Order and cost

| Step | Sessions | When |
| --- | --- | --- |
| F-38 the capability model | 2–3 (Opus, medium effort; it is careful, repetitive work with the suite as the net) | now, in its own session |
| F-36 detection, the folder, the profile format, the template, `/aiplayer mods` | 1–2 | after the current track |
| The first certified profiles (the food and furniture pack) | 1 | with F-36 |
| F-37 the integration entrypoint and services | 1 | before Cobblemon |
| Cobblemon | 3–4 | after F-37 |
| The remaining packs | 0.5–1 each | as demand shows |
