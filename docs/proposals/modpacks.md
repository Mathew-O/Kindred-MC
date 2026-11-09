# Pack support: the five pack shapes people run on Fabric, and what each asks of Kindred

Status: proposal, 2026-11-09. E-56 (certified packs) and its children E-56a to E-56e are reserved in the
[tracker](../feature-spec.md). Depends on F-35 and F-36 ([mod support](mod-support.md)); Cobblemon has its own
spec ([cobblemon.md](cobblemon.md)).

Pack popularity shifts month to month and the best-known pack of a kind is often on a different Minecraft version
from ours (1.21.1), so this spec is organised by **pack shape**, each with the pack it is named for today. The
session that implements a pack checks the current most-downloaded Fabric 1.21.x pack of that shape on CurseForge
and Modrinth at the time, pins its exact mod list, and records both in the pack's folder. A pack is a list of mod
folders under `mod-support/` plus a policy file; certifying a pack is certifying its mods together on one test
server.

## How a pack is certified

1. `mod-support/packs/<pack>/pack.json`: the pack's name, source and version, its mod list with exact versions,
   the policy decisions below, and the mods deliberately left `unknown`.
2. A test server configuration that installs exactly that list (downloaded at build time, never committed).
3. The earned Nether scenario on the evaluation engine, three seeds, two game hours, with the pack installed:
   nothing crashes, no AI player uses a forbidden mechanic, the stages reached are within the vanilla ranges or the
   difference is explained by the pack (a pack that makes the night harder may cost a stage).
4. Each `supported` mod's own fixtures passing on that server.
5. `evidence.md` in the pack folder, and the operator guide's "packs" table updated.

Policy decisions every pack needs, taken once in `pack.json`: teleportation mods (forbidden to AI players unless the
server announces otherwise), storage mods that hold more than vanilla (allowed to use, never to duplicate),
difficulty mods (obeyed; the survival rules read the attributes), map mods (ignored: the atlas is what it has seen),
and any mod that gives items for nothing (forbidden).

## E-56a The performance and vanilla-plus pack (Fabulously Optimized, Simply Optimized, Additive)

**What it is.** Client-side performance and polish, often with a few server-side quality mods. The server, if it
runs any of it, runs Lithium, FerriteCore and the like.

**What it asks.** Nothing of the player: there is no new content. What it asks of Kindred is to *run well beside
them*: no mixin conflicts, no tick-time regressions. This is the pack to certify first because it is the one most
servers already have, and the certification is the test that Kindred coexists with the common performance mods.

**Kindred's behaviour.** Unchanged.

**Acceptance.** The test server with the performance mods installed runs the full native suite with the same result
as vanilla; the three-seed scenario within the vanilla ranges; tick time no worse. Plausibility 10, effort a half
session, mostly waiting on runs.

## E-56b The cozy, food and furniture pack (Farmer's Delight and its add-ons, Supplementaries, Handcrafted, Comforts)

**What it is.** The roleplay-server pack: cooking, farming, decor, furniture, hammocks, lanterns, sitting on things.
Also the pack where Kindred can feel most alive for the least work, so it is the first *content* pack.

**What it asks.** Tier 1 and 2 in [mod support](mod-support.md): everything decorative is content; food is edible by
component; the stations need profiles (cooking pot, cutting board, skillet, stove) with the generic interactions;
chairs and benches need `sittable`; hammocks and sleeping bags need `bed` (most use vanilla bed tags; check);
Supplementaries' hazards (a lit brazier) need `hazard` entries.

**Kindred's behaviour.** Cooks the pack's dishes when it has the makings and a station (a Farmer's Delight meal is
worth more than its parts by the recipe tree, so `Worth` will choose it); harvests and replants the pack's crops when
they use vanilla's age property; sits on a chair when idle at its base and in the evening (an idle action, E-33b's
look-about from the chair); uses a hammock as a bed; decorates its own base from the pack's palette by tag when it
holds the blocks; never breaks a person's furniture (a decorative block in someone else's claim or base is theirs,
as now). With the **scene context** setting (an operator's one-paragraph description of the server's setting given to
the voice and the strategist), it talks in the setting's terms: a tavern keeper, a baker.

**Acceptance.** Profiles for Farmer's Delight and one furniture mod certified; fixtures: a dish cooked at a pot from
owned ingredients and conserved; a chair sat on and left; a hammock slept in; a modded crop harvested and replanted;
the scene context changing a reply's terms and nothing else; the three-seed scenario. Plausibility 9, effort one
session after F-36.

## E-56c The world and exploration pack (Terralith, Biomes O' Plenty, Tectonic, structure mods like YUNG's and
Towns and Towers)

**What it is.** Bigger, prettier, stranger worlds and more structures.

**What it asks.** Almost nothing of behaviour and a little of knowledge: new biomes with no prior in the atlas (an
`unknown biome` fallback to generic priors, and a profile may map a modded biome to a vanilla-like one: "this is a
plains"), new woods and stones by tag, new structures as `structure` sightings when their tags say so, and
navigation across taller and steeper terrain (gap jumps and ladders from the organic work help; the route planner's
stairs and pillars already do most of it). A village from a structure mod is a village if it has villagers, which
is how the trading code finds one anyway.

**Kindred's behaviour.** Unchanged in kind; the evaluation shows whether the stages slip on harder terrain.

**Acceptance.** A profile mapping the pack's biomes to priors; a fixture gathering and building with a pack wood;
the three-seed scenario with stage times reported against vanilla, differences explained by terrain. Plausibility 8,
effort a half session after F-35.

## E-56d The RPG and combat pack (Prominence II RPG, Better Adventures, packs built on Better Combat, Spell Engine,
Epic Fight-style mods and new dungeons)

**What it is.** Combat rewritten (directional swings, combos, spells), classes, new bosses and dungeons, loot.

**What it asks.** The hardest tier short of machines. Better Combat changes the swing timing the tactical model
assumes: the `melee` behaviour profile needs the mod's attack timings (it exposes them per weapon), or combat falls
back to "keep distance and block". Spells are new skills (an integration, F-37) or ignored. New bosses are `unknown`
threats unless profiled (`behaviour`, flee distance, "do not engage without X"). Dungeon loot follows the trust and
value rules unchanged. Difficulty is read from attributes.

**Kindred's behaviour.** Survives and progresses; fights the pack's mobs with the fallback rules and profiled
behaviours; does not use spells or classes unless an integration exists; leaves bosses it cannot judge (`FightOdds`
with unknown-at-worst, as now).

**Acceptance.** A profile for Better Combat's timings and for the pack's common hostiles; the duel fixtures passing
with the mod installed; the three-seed scenario with deaths an hour reported and explained; bosses left alone
asserted. Plausibility 6, effort one to two sessions, and only after the RPG pack of the moment is confirmed to be on
1.21.1 (Prominence II has been a 1.20.1 pack).

## E-56e The kitchen-sink pack (Better MC [Fabric], All of Fabric)

**What it is.** Everything at once: food, biomes, furniture, waystones, Create, backpacks, new mobs, often a
thirst or temperature mod.

**What it asks.** Every tier. The plan is **Better MC-lite**: certify the pack with its tier 1 and 2 mods
`supported`, its difficulty and combat mods `partial`, Create and other machines `unknown` (Kindred lives beside
them and never touches them), and waystones and backpacks under `policy` (travel forbidden; a backpack used only as
a container it earned). A thirst or temperature mod stays `unknown` until F-35's data needs and a profile give the
player the rule (drink, dress warm); until then the pack's version with that mod disabled is the certified one, and
the folder says so.

**Kindred's behaviour.** Plays survival on the pack as on vanilla plus the food and furniture of E-56b, ignores the
machines, never teleports, treats the pack's mobs by profile or conservatively.

**Acceptance.** `pack.json` with every mod classed; the three-seed scenario with no forbidden mechanic used and no
crash; the food and furniture fixtures passing on the pack's server; a note in the operator guide on what Kindred
does not do on this pack. Plausibility 6 for lite, 3 for full; effort two sessions for lite after E-56b and E-56c.

## Order

E-56a (coexistence, cheap, most servers), E-56b (the roleplay win), E-56c (near-free), then E-56d or E-56e by
demand. Cobblemon runs on its own track ([cobblemon.md](cobblemon.md)) after F-37.
