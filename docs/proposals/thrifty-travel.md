# Thrifty habits, sight without presence, and unobserved walks

Status: proposal, 2026-11-11, by the owner's direction. IDs E-70 (a to c), F-39 and F-40 are reserved in the
[tracker](../feature-spec.md); claim one in Active work before implementing it. The owner decided on 2026-11-11 that
E-70a to E-70c are implemented regardless of the performance work, and approved F-39 and F-40 for design. F-40 needs a
contract amendment, proposed below as D11.

## Why

Each player is a real server player, so the server keeps 289 chunks (a 17 by 17 area at view distance 8) loaded and
ticking around it. The brain senses about 32 blocks (`WorldKnowledge.HORIZONTAL_RADIUS`), a 5 by 5 area. Chunks are
the dominant cost of a player, and fresh terrain is the likely cause of the multi-second ticks the evaluation runs
record. Three of the five items below are simply how a good player behaves and also cut deaths and stuck time; two
are engine features that let a player see far and travel known roads without holding chunks.

Measure for all five: the evaluation writer records, per player, **chunk-loads** (chunks that entered its loaded
area) and **chunk-minutes** (chunks times game minutes held), and `score.py` reports them per game hour. The counter
is part of these slices; the performance campaign itself stays with the owner's timing.

---

## E-70a Nights in bed

**Today.** `LifeFirst` sleeps a player in a bed of its own that it can reach (F-25c9, F-25c9a). Away from home it
holes up: a sealed pocket and a wait. One player stood sealed in for 41 minutes in a ladder run; most deaths are at
night or in the dark. Nobody logs off; that is not part of this design.

**Design.**

1. **In by dusk, as default behaviour** (not a learned habit). From the time needed to get home (distance over its
   pace, from where it stands) the player turns for home early enough to be in bed when sleeping becomes possible.
2. **A bed carried.** A bed is a standing want (`Need.BED` exists) from the first three wool and planks. Too far
   from home at dusk, it places its own bed in its sealed, lit pocket (the hole-up it already digs), sleeps, and
   takes the bed back in the morning. Vanilla moves its spawn to that bed; on coming home it sets its home bed
   again. A death in between returns it to the world spawn, as it would a person.
3. **No bed yet.** Today's hole-up, bounded as now, and the bed's want is raised to urgent for the morning.
4. **When a person goes to bed, so does every AI player in the Overworld that can**, at a priority just under
   safety, so a person's night skips.
5. **An AI player never holds up a person's night.** One that cannot be in bed (a fight, no bed, underground
   mid-task) is left out of the sleeping count, as vanilla leaves out spectators; the rule for people is unchanged.
   Operator switch `aiPlayersCountForSleep`, default false.
6. **With no person online**, all AI players in the Overworld in bed is vanilla's own night skip. Players in the
   Nether and the End carry on.
7. **Night work on purpose.** A need that only the night fills (ender pearls from endermen, gunpowder, phantom
   membranes) is declared as night work in the needs queue; then it stays up, armoured, near home, and says so.
   Match modes keep their own rules (a speedrunner's bed is a tool; a manhunt runner does not sleep).

**Files.** `LifeFirst` (the dusk timing, the carried bed, the three cases), the needs queue (the standing bed, night
work), `NativeBedAction` and `NativeSleepAction` (reused), a small mixin on the sleep count, `EvalBootstrap`
(`sleep=on|off` in the scenario so the comparison is clean).

**Acceptance.**
- JUnit: the turn-for-home time; each case; night work.
- GameTests: home in reach, in bed at the first sleeping tick; out of reach with a bed, the bed placed in a sealed
  pocket, slept in, taken back, and its home bed set again on return; a mock person lies down and the AI players are
  in bed within the limit and the night skips; one AI player in a fight does not hold the night; no bed, the bounded
  hole-up and the urgent want.
- Corpus: the night death scenes and the sealed-hole stuck scenes pass.
- Engine, ten seeds, `sleep=on` against `off`: deaths an hour, stuck minutes and stages, reported per tick and per
  game day (a skipped night means a run covers more days).

## E-70b The beaten path

**Today.** Targets (a tree, an ore sighting, animals, a frontier chunk) are chosen by distance, worth and danger.
The same walk takes the same line, and a slightly nearer target across unvisited ground wins over one beside its own
trail. The atlas (F-30) keeps explored chunks, sightings and danger, but no memory of where the player has walked.

**Design.**

1. **Trail memory in the atlas.** Per chunk, a count of the player's own passes with a decay over game days, and
   for longer walks the waypoint line it took (kept as a `Trail` between two named places once walked end to end,
   with its length and the day it was last walked). Bounded like the rest of the atlas.
2. **The beaten set**, from the player's own knowledge only: its trails, roads it built (E-62a), its base and
   claims, teammates' places shared on the team channel, people it can see. **Never the server's loaded-chunk
   state**: that would reveal a hidden opponent in a match or an enemy in Factions.
3. **A chunk price in every choice.** A candidate's price is the number of chunks on the way that are outside the
   beaten set; each costs as much as eight blocks of walking (tunable). It is a weight, never a ban: a far ore
   sighting worth the trip still wins, and frontier exploration stays the atlas's job, priced.
4. **Long walks follow trails** where the detour is under a quarter of the straight distance.
5. **Idle and waits** are spent at its base, with teammates or near people it knows, not where it happened to stop.

**Files.** `atlas.Atlas` (trail counts and `Trail`), `PlayerMemoryStore` (persisted with the atlas), the candidate
scoring in `TaskDirector`/`TaskPreferences` users and the campaign's target choices, `ExpeditionPlanner`.

**Acceptance.**
- JUnit: decay, the trail record, the price, the detour rule.
- GameTests: two like trees, one beside its trail and one a little nearer across unvisited chunks: it takes the
  trail's; a far sighting of something it needs still wins; with a hidden opponent holding chunks loaded nearby in a
  match, the choice does not change (no leak).
- Engine: chunk-loads a player-hour before and after, stages no worse.

## E-70c Travel like a veteran

**Today.** A trip is walked, or rowed where F-10f's boats apply. The playbook (F-25c8) knows the Nether shortcut to
the stronghold as one of its ways; nothing chooses a way of travel in general.

**Design.** `TripPlanner` in `companion-core`, pure. For a trip between two places it knows, over 300 blocks, it
compares the modes the player actually has:

| Mode | Needs | Time | Chunk price | Lands with |
| --- | --- | --- | --- | --- |
| Walk | nothing | distance at its pace | every new chunk on the way | now |
| Boat | a boat, water it knows on the way | faster on water | fewer chunk-minutes | now (F-10f) |
| Nether | two portals it knows, or ten obsidian and a flint and steel for the far one; the Nether leg over ground it has walked or a highway of its own; fire resistance when it has it | an eighth of the distance plus the portals | about an eighth of the chunks | after the ladder's rung c holds |
| Rail | its own line between the places | cart speed | half the chunk-minutes | E-62c |
| Ice road | its own packed or blue ice road, a boat | the fastest overland | least chunk-minutes | E-62a |
| Horse or camel | a saddled mount | faster than walking | fewer chunk-minutes | E-61a |
| Elytra | elytra, rockets, a landing plan; never over land nobody has generated | fastest | the most chunk-loads a second | E-58e |
| Unobserved walk | a trail it has walked, daylight, nobody to see (F-40) | walking pace | none on the way | F-40 |

Each mode gives a time, a chunk price, a risk (from the danger map and the dimension) and what it must hold; the
planner takes the least weighted sum and logs why ("by the Nether: 140 blocks for 1,120"). Portals are paired on
purpose: the atlas keeps both sides of each, and the far portal's place is computed (x and z over eight) and built
from obsidian it carries (E-62d). The first version has walk and boat; each other mode plugs in as its slice lands.

**Files.** `TripPlanner` (new, core), the atlas's portal pins, the campaign's and the expedition's long walks (they
ask the planner), `NativeBoatAction` and the portal steps reused.

**Acceptance.**
- JUnit: the comparison over staged places and holdings, each mode's needs, the reason line.
- GameTests: water between two places and a boat in hand: it rows; two linked portals staged 800 blocks apart with a
  remembered Nether leg: it goes by the Nether and arrives; the Nether leg unknown: it does not.
- Engine: chunk-loads a kilometre by mode.

---

## F-39 Sight without presence

**The idea.** A person standing on a hill sees terrain for hundreds of blocks because their client draws it. An AI
player has no client; it knows only the 32 blocks its senses scan. Far sight gives it a person's horizon, for the
terrain only, **without loading a single chunk**.

**How.** Minecraft 1.21.1 reads a saved chunk's data in the background without loading it
(`VersionedChunkStorage.getNbt(ChunkPos)`, checked against the pinned mappings). From that data the adapter decodes,
off the server thread, only what a distant viewer sees:

- the surface heightmap of the chunk;
- the block at the top of each column (from the section's palette), reduced to a few kinds: water, lava, sand, snow,
  ice, a wood by its tag, leaves, path and farmland, light sources, and the handful of blocks that mark a place from
  afar (a bell, hay, cut stone and planks in a row for a village; the copper and tuff of a trial ruin are underground
  and never seen);
- the biome at the surface.

Chunks already loaded by anyone are read from memory on the server thread instead (a heightmap read). The result is
handed back as immutable `FarColumn` records and becomes sightings in the atlas.

**What it may see: exactly what a person there would.**

- Only chunks that are fully generated and saved. It never generates terrain; an ungenerated chunk is fog.
- Only within the server's view distance of the player (a person's client shows no more), and half that at night,
  with light sources still visible at the full range.
- Only columns in **line of sight**: a ray from its eyes to the column's top must clear every column between
  (computed over the heightmaps). A hill hides the valley behind it.
- Only with the sky above it. Underground, underwater, and in the Nether (whose top is a bedrock roof) it sees
  nothing far.
- Never anything below a column's surface. Never the chunk's structure data, which would give away strongholds,
  buried treasure and anything else hidden. Never entities: seeing players and mobs stays the existing rule.
- A spyglass (E-64b) doubles the range in the direction it looks, as the item does for a person.

**When.** On the surface, at most one look every ten seconds while moving, and on purpose from a height or before a
long trip (a glance, E-33b, pointed where it looks). At most four chunk reads a second a player, off-thread;
results cached until that chunk is next loaded and saved.

**What it is for.** The atlas's frontier is ranked by what it can see (a village across the plain, a lava pool for
obsidian, a river to row), expeditions go somewhere rather than outward, and E-70b's beaten path does not cost it
its horizon. With F-39 a player's loaded area can later shrink to what it touches without the player seeing less
than it does today.

**Files.** `FarSight` (core, pure: line of sight over heightmap grids, ranges, the kinds), `FarSightReader` (adapter:
the background read, the decode of heightmaps and top blocks, the cache, the budget), `atlas.Atlas` (sightings
marked "seen from afar" with the place it was seen from), a unit-tested decoder for the packed heightmap and palette
formats.

**Acceptance.**
- JUnit: line of sight and occlusion over staged heightmaps; the night range; nothing without sky; the decoder
  against a recorded chunk's data.
- GameTests: a lava pool six chunks off on open ground, its chunk saved and unloaded: the atlas gains the sighting
  while that chunk is never loaded and no ticket is added (asserted throughout); a ridge between hides it; a block of
  diamond ore under the far surface is never sighted; a player underground sights nothing; an ungenerated chunk
  stays ungenerated (the world's chunk count unchanged).
- Engine: time to the first village or lava pool reached, with and without, and no change in chunk-loads from the
  sight itself.

## F-40 Unobserved walks

**The idea.** A walk along a road the player has walked before, in daylight, with nobody anywhere near to see it,
changes nothing in the world but where the player is and what time it is. For such a walk the body leaves the world
at the start and returns at the end after exactly the time the walk takes, and no chunk on the way is loaded.

**D11, proposed for the contract** (the implementing slice edits `minecraft_player_spec.md` and the "teleport
shortcuts" line of `AGENTS.md` under a LIVE EDIT marker, citing the owner's approval of 2026-11-11):

> An unobserved walk is travel, not a teleport: along a trail the player has itself walked end to end, in daylight,
> at walking pace and never faster, charged the same time, hunger and activity allowance as the walk, changing
> nothing but its position, and made real before anyone could observe it. Anything else that moves a body without
> walking it stays forbidden.

**Eligibility, all of it, checked at the start:**

1. The whole route is a `Trail` in its atlas (E-70b) that it has walked end to end, last within ten game days, or a
   road or rail line it built. The first trip anywhere is always real.
2. Overworld, on the surface or its own lit road or tunnel. Not the Nether or the End (a highway of its own may
   qualify later, as its own slice).
3. Daylight for the whole trip: the arrival time is before dusk. No thunderstorm.
4. The trail's danger in the atlas is low, it is not hurt, and it is fed.
5. Nobody could observe any part of it now: no person within their view distance of the trail, no other AI player
   within its sight of it.
6. Not in a match, and not in a Factions world outside its own claims (a traveller nobody can meet cannot be
   ambushed). Co-op Survival first. Operator switch `unobservedWalks`, default on in Co-op and off elsewhere.
7. At most 2,000 blocks a trip.

**In transit.**

- The body is out of the world: no entity, no chunk tickets, nothing loaded for it. It stays in the player list (no
  "left the game", no "joined the game"); the mechanism is the implementer's to choose among the body's existing
  park-and-return paths, with those two requirements and the conservation rules of F-07.
- A durable `Transit` record (trail, start tick, arrival tick, the virtual position as a function of the tick) is
  written through the ordered writer before the body leaves, and the World Record keeps its start and end.
- **Costs are the walk's own**: time is the trail's length at vanilla walking pace (4.317 blocks a second), so an
  unobserved walk is never quicker than the real one, which may sprint; hunger as vanilla charges walking; the
  activity allowance is reserved and charged slice by slice as for any walk.
- Nothing else can happen: no item gained, lost or worn, no block changed, no experience, no skill.
- Asked where it is, it says so ("on the road to the mine"), and the panel and watch page show the virtual position
  to those who may know it.

**It becomes real again:**

- on arrival: the body returns at the trail's end if that place is still standable, else at the last point of the
  trail that is;
- **before anyone could see it**: each second the virtual position is checked against every person's view distance
  plus one chunk and every AI player's sight; on entering one, the body returns at the virtual point (snapped to
  the trail) and walks on for real, so an onlooker sees a player walking in from the distance;
- when dusk would come first, a storm starts, or it is told to stop (it appears where it is, stopped);
- across a restart: the record keeps the virtual point at shutdown; by the existing rule it is offline and stopped
  after a restart, and reconnects there.

**What stays unequal, said plainly.** A real walk in daylight on a known road still carries a small chance of an
incident (a creeper that outlived the night, a misstep). An unobserved walk carries none. The conditions keep it to
walks where that chance is small; it is not zero, and a person cannot do this at all. That is the price of the
saving, and the reason it is off in every mode where players compete.

**Files.** `Transit` and `TransitPlan` (core, pure: eligibility, the virtual position, observation, costs),
`TripPlanner` (the mode), the body's park and return in the adapter, the store (the record), the panel and watch
page (the virtual position), `EvalBootstrap` (transits, blocks and chunk-minutes saved).

**Acceptance.**
- JUnit: each eligibility condition; the virtual position over time; observation (it returns before it could be
  seen); arrival before dusk; the costs.
- GameTests: a trail of 120 blocks with nobody near: no player entity in the world mid-trip, no chunk of the route
  loaded at any tick, arrival at the expected tick, the inventory identical with its components, food as vanilla's
  walking leaves it, the allowance charged, and no join or leave message; a mock person standing by the middle of the
  trail: it is walking for real before it comes within their view; a route never walked: a real walk; dusk before
  arrival: a real walk; in a match: a real walk; a stop mid-trip: it appears at the virtual point, stopped; a restart
  mid-trip: it reconnects at the virtual point with every item.
- Engine: a co-op base routine on ten seeds with and without: chunk-minutes a player-hour, and stages and deaths
  no different beyond the ranges.

---

## Order

1. **E-70a** now, with the survival work: its scenes are already in the failure corpus.
2. **E-70b** next, with the movement work: trail memory first, then the price.
3. **E-70c** in two steps: walk against boat with E-70b; the Nether mode once the ladder's rung c holds.
4. **F-39** when the mechanics-first exit criteria are met: it is a new sense, not a corpus fix.
5. **F-40** after E-70b and F-39, with D11 written into the contract in the same change; before the owner's
   performance campaign, so that campaign measures the full weight.

Sizes: E-70a one session; E-70b one; E-70c a half now and a half later; F-39 one to one and a half; F-40 two.
