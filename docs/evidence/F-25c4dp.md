# F-25c4dp — With no pickaxe, its climb by hand under a block it may not clear by hand steps aside round it, rather than stopping

Date: 2026-11-23
Agent / branch: Claude, LOOPS / `claude/pillar-step-aside-1123`, from main `23dbe8fa`, at the lead's word
Build: the claim `e53b4377`; the fixture `cfc61129`, its body names and budget `f62200d8`; the change `35de85a1`; main (with #498 and
#499) merged in as `6d67f9fd`; the dig's lines to the console `1f308ae5`, and gated there
Design parent: F-25c4 (the portal run's climb)
Status: complete (2026-11-23). The live read is E-40 iteration 30 or 31.

## Scope and implementation

**Found:** a read of E-40 iteration 29 (main `9a0921b5`; `claude/corpus-data-e40a29-consoles-1123`). Batch 2's 707 Kindred, its
pickaxe gone, climbed by hand from y −23 to y −13 in deepslate. Each step was "clear 16, y+2, 3 by hand", then "pillar up".
- At y −13 no clear of (16, −11, 3) was offered. With no head room `pillarOut` could not run, and every hand step of the climb was
  refused.
- `wayUp` was empty, and GATHER_LOGS fell to "Head back up toward home", refused about a hundred times ("No walkable route toward −1,
  69, 0"). It then waited "for a feasible step" until its spell.

**Which refusal, read from the spell's replay world:** Kindred-78320, `claude/corpus-data-e40a29r-707-replay-1123` at `a5df02c0`, read
offline for diagnosis at the lead's word.
- The block over its head, (16, −11, 3), is **deepslate iron ore**, hardness 4.5. That is over what it clears by hand
  (`HAND_HARDNESS`, 3.5), so `handClearable` refused it on hardness. It is 2.4 from its eyes, within reach.
- **Round it:** iron ore also west of the ore, (15, −11, 3), and north of its head, (16, −12, 2). Plain deepslate (3.0) east and south
  at head height, and over those.
- **What it carried:** stacks of cobbled deepslate, food 4, no pickaxe.

**The cause:** `Digger.climb` asks every way up for the block over its head first. A block it can neither break nor clear by hand
refused all four ways. The step aside (`fromBeside`) was reached only when that block was refused for sand resting on it or a liquid
beside it (F-25c4bd), never for its hardness.

**User-visible behavior:** an AI player with no pickaxe, climbing out by hand, that comes up under a block it cannot break by hand
digs into the plain rock beside it, steps in, and climbs on from there. Shut in by such blocks on every side, it does nothing new.

**Changed files:**
- `Digger.climb` (core): the block over its head counts as refused for hardness when it may neither break it with its tools nor
  clear it by hand. The climb then hands over to `fromBeside`'s existing step aside. This is only when that block is not already
  refused for sand or a liquid (`headRoomRefused`), and only on its feet and not swimming.
- `Digger.fromBeside` (core), on that path only:
  - a pocket beside it is taken, or dug, only where its own head room it may dig: open, harvestable with its tools, or clearable by
    hand once beside it (`headRoomDiggable`);
  - so it does not step aside under more ore. In the replay world, west of the ore was ore.
- `Digger.Body.handClearableLater` (core, a default of `handClearable`), implemented by the adapter as `handClearable` without the
  reach and line of sight.
  - **Its only call** is `headRoomDiggable(body, side)`, which asks about `side.up(2)`. Both callers in `fromBeside` pass
    `side = feet.offset(way)`, `feet` the body's own feet cell.
  - So the only cell it is asked about is **one across and two up from where the player stands**: the head room of the pocket it is
    about to dig beside it, diagonal behind the block its next swing opens. That is no unseen ground further off.
  - The sim's and the core tests' bodies take the default.
- **A line, said once a place,** where the way aside begins: "the block over its head at <pos> one it may not dig: a way aside round
  it".
  - The dig's lines (`Digger.Body.event`) now also go to the console, with the player's name: "<name> digs: …" (`1f308ae5`).
  - They are few, and each is said once a situation.

Nothing is given or moved: its own hands dig its own way. No timeout or stuck threshold is changed. The hand-hardness limit is
unchanged.

**Schema/configuration changes:** none. No saved keys.

**Gametest:** `NativeHandPillarAsideGameTests` (new, registered in `fabric.mod.json`).

## Acceptance evidence

**Fixture (staged), two cases, each in its own batch, each body its own name:**
- **STAGED, both cases:**
  - deepslate round it, no sky; a shaft one wide and two high, its feet on cobbled deepslate;
  - deepslate iron ore over its head, west of that and north of its head, as in the replay world;
  - the player in the shaft with no pickaxe, two stacks of cobbled deepslate and eight bread;
  - the portal goal, no logs; daylight held; 20 ticks for the light to settle before the goal.
- `underABlockItCannotClearByHandItStepsAsideAndClimbsOn`: fails unless, within 6,000 ticks, its feet are three or more above where
  it began, past the ore's level. By hand a deepslate block takes about 300 ticks, and the way aside and up takes eight or more.
- `boxedInByBlocksItCannotClearByHandItDoesNothingNew` (the negative control): ore also beside its feet and its head on every side,
  and over those. It fails if, after 600 ticks, it has moved, any ore is gone, or a step aside or a pocket beside it was offered.
- **The fixture's home is unset,** so main's fallback is the stand alone, not the live run's refused walk home. The stand is the
  live run's shape.

| Check | Build | Result |
| --- | --- | --- |
| Fixture before the change | main's source with the final fixture (`f62200d8`) | the open case fails 3 of 3: at y 0, "waiting for a feasible step (tried elsewhere 1 time)", for 6,000 ticks; the control passes 3 of 3 |
| Fixture after | `35de85a1` | 3 of 3 pass: a pocket dug east by hand, a step aside, a staircase by hand, three up at 3,399 to 3,400 ticks; the control unmoved, nothing dug |
| Core unit tests | `35de85a1` | 1,084 of 1,084 |
| Related classes ×1 (26) | `35de85a1` | 171 of 171 |
| Fixture and 3 close classes ×1 | `1f308ae5` (main merged in) | 8 of 8; the line said once, with the name |

**The related classes:** NativeHandPillarAside, CaveLedge, CavernLedge, CoverClimb, LakeBedClimb, Navigation, NightShelter,
PassageSeal, PitPocket, PortalCampaign, Rescue, RetraceLower, SealedUnderSea, Settle, ShelterSeal, Spire, Stair, StrandedPickup,
StrandedReturn, Survival, TripDown, WaterCut, WaterExit, GravelTunnel, FloodedShaft, SeabedWater.

**The 3 close classes:** NativeCoverClimb, NativeStair, NativeCastFloor.

**Log of the run:**
- **Two bodies of one name:** the fixture's first draft made a body named "Climber" in both cases. The second's login stopped the
  first: it froze in the middle of its climb.
- **The budget:** the first budget, 2,400 ticks, ran out two up, at about 300 ticks a block by hand.
- **The reach:** the first change asked `handClearable` about the pocket's head room. It refused that cell for reach and line of
  sight, because it is buried until the pocket is dug. Hence `handClearableLater`.

## Corpus gate (`1f308ae5`; baseline `3354cb33`)

**The marker, stated before the gate:** "<name> digs: the block over its head at <pos> one it may not dig: a way aside round it".
- It is said, once a place, whenever the new path returns a step. Only that path is changed, and it adds steps only where every way
  up was refused and nothing else was offered.
- So every decision the change alters passes through the line. The fixture's positive control fired it once.

| Run | Minutes | Peak game-JVM VmHWM | Required tests failed | Marker lines | Marker bodies |
| --- | --- | --- | --- | --- | --- |
| 1 | 65 | 3,245 MB | 125 | 1 | 1 |
| 2 | 62 | 3,127 MB | 125 | 1 | 1 |
| 3 | 58 | 3,137 MB | 116 | 2 | 2 |

**Coverage:**
- `Rc943e8a_1`, e40a20-79a9aad-101-sol-26800: a line in all three runs. The baseline counts the scene as failing; it passed all three
  runs here.
- `R914413e_1`, e40a13-fe2ba3b-20261030-sol-106709: a line in run 3, which it passed. It failed run 2, with no line there.

**Against the baseline:** 19 scenes newly pass, and 6 are flagged, all six known intermittent (KI).

| Scene | Body | Full runs | Failure | Marker |
| --- | --- | --- | --- | --- |
| diag-holepit-079bdc2-404-echo-72860 (KI) | Re871322_1 | F P F | still in its stuck spell | 0 in every run |
| diag-holepit-079bdc2-8675309-echo-45700 (KI) | R38ecd9_1 | F P F | still in its stuck spell | 0 in every run |
| e40a14-80b7150-707-echo-51759 (KI) | Re6e713e_1 | P F F | died again | 0 in every run |
| e40a20-79a9aad-8675309-kindred-112700 (KI) | R61cdc54_1 | F P F | died again; still in its stuck spell | 0 in every run |
| e40c1-eed2dc5-101-echo-59004 (KI) | R43404a6_1 | F F F | died again | 0 in every run |
| e40c1-eed2dc5-101-sol-13531 (KI) | R55d4c76_1 | F P F | died again | 0 in every run |

**Result: 0 confirmed. The gate passes.** The lead cleared all six by the marker rule: no flagged body read a line in any run, and the positive control is `Rc943e8a_1` in all three runs, `R914413e_1` in run 3, and the fixture.

**Kept:** the runs are on `claude/spacer-runs-1112`, under `gate25c4dp/`.

## Remaining limitations and handoff

- **The live read, the next E-40 iteration after its merge:** with no pickaxe, a stand under a block it may not clear by hand should
  go. The line counts how often the way aside is begun. If such stands remain, this row reopens.
- **The way aside, then up, is a staircase by hand,** three blocks a step, slower than the pillar it left. Where the plain rock goes on,
  it gets out.
- **The live run's fallback,** "Head back up toward home" refused about a hundred times, is untouched. It is now reached only where no
  way aside is open.
