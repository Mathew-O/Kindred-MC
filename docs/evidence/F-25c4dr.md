# F-25c4dr — The cast's walk up to its lava, after a refused fill, does not come back from the cell it began in

Date: 2026-11-23
Agent / branch: Claude, LOOPS / `claude/cast-walkup-1123`, from main `853b686c`, at the lead's word
Build: the claim `b850e5c4`; the fixture `ffd96c49`, its check on the lava `3312b73a`; the change `fefd33ad`, gated there; main
(`d71b4dc6`, with #510) merged in as `bfb53006`
Design parent: F-25c4 (the portal run's cast)
Status: complete (2026-11-23). The live read is the next E-40 iteration after its merge. Not the stuck fix: the loop was 12 of
E-40 iteration 30 batch 1's 258 stuck minutes (RUNNER 2).

## Scope and implementation

**Found:** RUNNER 2's read of E-40 iteration 30's first batch (main `853b686c`). "Cast: walk up to the lava" was issued again and
again from one cell (`PortalRunCampaign.java:6823`). RUNNER 2 measured it at 12 of the batch's 258 stuck minutes: four spells of
three minutes, each ended by the watchdog. It is loud in lines and small in time.

**The cause, from the code:**
- A refused "Cast: fill" sets `castFailedFrom` to the cell it stood in. Only a fill or cast that takes clears it.
- From that cell `lavaForCast` offers the walk up, `go(castLava, 1.5, …)`. `NativeGoToSkill.goalReached` takes the feet's
  distance to the target, within range + 0.01. A source one across and one down is √2 ≈ 1.41 from the feet, so the walk is
  arrived where it begins and completes without a step.
- The feet are still on `castFailedFrom`, so the next tick offers the same walk up, and the fill is never tried again.
- None of #411, #488, #495, #485, #486, #480 or #478 changed these lines. They and the set and clear of `castFailedFrom` are as
  old as the graft root (`60cb3a7f`), and `NativeBucketAction` has no commits between `9a0921b5` and `853b686c`.

**User-visible behavior:** an AI player whose bucket will not take the lava from where it stands, the lava right beside its
feet, no longer stands there "walking up" to it. It opens the rock over the lava when that is in reach and opens onto the lava
alone, or digs toward it, or tries the fill once more. A second refusal sets that source aside, as before.

**Changed files:**
- `PortalRunCampaign.lavaForCast` (adapter): on the cell its fill was refused from, the walk up is offered only where it would
  move the feet (more than 1.5 from the source), and at most once from each cell.
- `PortalRunCampaign.besideItsLava` (adapter, new), the bound, in this order:
  1. a `native_break` with `over_lava` of the cell over the source ("Cast: open the rock over its lava at …"). Only where that
     cell is solid, not set aside, within `REACH`, eligible for mining, and `onlyLavaBelow` holds (lava under it, no fluid in it
     or on its other five sides). This is the opening the tunnel already makes over lava (F-25c4ac, F-25c4cz). The player does
     not move.
  2. else the existing dig toward the lava (`digToward`, F-25c4ad), "Cast: dig toward the lava at …".
  3. else the fill once more from here. Its second refusal sets the source aside through the existing `fillRefusals`.
- The records of walk ups offered and of the marker said clear when a fill or cast takes.

**What it reads:** nothing it has not seen. It chooses no new stand. The only cells it looks at are the one over the source,
beside its feet, and that cell's six sides, as the existing opening does. No walk goes toward lava.

Nothing is given or moved: its own tools break its own way. No timeout or stuck threshold is changed.

**Schema/configuration changes:** none. No saved keys.

**Gametest:** `NativeCastWalkUpGameTests` (new, registered in `fabric.mod.json`).

## Acceptance evidence

**Fixture, `aWalkUpToLavaBesideItsFeetDoesNotComeBackFromWhereItBegan`. STAGED:**
- a stone room with no sky, five across and three high, lit by glowstone;
- one lava source in its floor, one east of its feet and one down, with stone round it but for its top;
- the cast's kit (an iron pickaxe, a stone sword, an iron chestplate, bread, cobblestone, sticks, flint and steel, a water
  bucket and an empty bucket), and the Nether portal goal;
- the source seen by the campaign's own sweep while its top was open; then a block of stone set on its top, so the fill is
  refused from where it stands, the lava within 1.5 of its feet.

It fails if:
- more than two walks up complete with the feet where they were when the previous task ended;
- it has no bucket of lava within 1,600 ticks;
- at any tick it is in the lava, or its feet are in the source's cell or the cell over it. This is REFACTOR's point: one of
  iteration 29's lava deaths died as a dig beside it opened onto lava. The verdict is kept, so a later tick cannot pass it.

| Check | Build | Result |
| --- | --- | --- |
| Fixture before the change | main's source with the fixture (`3312b73a`) | fails 3 of 3: "Its walk up to the lava completed where it began 814 / 803 / 801 times … in 15 / 15 / 14 ticks, at 0, 0, 0". Its trail is the fill, then the walk up |
| Main's `NativeObsidianPickupGameTests` alongside | `3312b73a` | 4 of 4, 3 of 3 runs |
| Fixture after | `fefd33ad` | 3 of 3 pass: the marker once ("the rock over it opened"), then the fill. Lava in hand after 61 / 24 / 20 ticks, no walk up, never in or over the lava |
| Fixture and 7 close classes ×3 | `fefd33ad` | 18 of 18 a run |
| Fixture, 7 close classes and NativeHandPillarAside ×1 | `bfb53006` (main `d71b4dc6` merged in) | 20 of 20 |
| Core unit tests | `bfb53006` | not completed: main's `GeneratorsTest.thePlannerBuildsAWholeHutAndTakesItsScaffoldDown` ran 21 minutes in `ConstructionPlanner.walkable` and was stopped (221 s on `853b686c` today). This change touches no core code; the lead is running that test on main |

**The close classes:** NativeCastPool, NativeCastFloor, NativeCastRefusal, NativeToldLava, NativeLavaProbe, NativeBucket,
NativeCastWaterLost.

## Corpus gate (`fefd33ad`; baseline `3354cb33`)

**The marker, stated before the gate:** "F-25c4dr-marker: <name> at <pos>, its fill of the lava at <pos> refused there, no walk up
that moves it: <the rock over it opened | a dig toward it | the fill once more>".
- It is said once a cell at the bound, the only new path.
- Wherever the walk up's own change refuses a walk up, the bound runs, so every decision the change alters reaches a cell where
  the line is said.

| Run | Minutes | Peak game-JVM VmHWM | Required tests failed | Marker lines | Marker bodies |
| --- | --- | --- | --- | --- | --- |
| 1 | 32 | 3,101 MB | 128 | 2 | `R35841e5_1`, `Rfecba24_1` |
| 2 | 32 | 3,877 MB | 111 | 1 | `Rfecba24_1` |
| 3 | 31 | 3,030 MB | 123 | 2 | `R35841e5_1`, `Rfecba24_1` |

**Coverage:** every line in the gate took "a dig toward it", and each body passed its scene in every run where its line fired.
- `R35841e5_1`, e40a13-fe2ba3b-404-echo-73717.
- `Rfecba24_1`, e40a22-d2389c9-707-kindred-134636.
- The fixture's line took "the rock over it opened".

**Against the baseline** (`report.py`, its exact `corpus.<scene>` matching): 25 scenes newly pass, and 7 are flagged, 5 of them
known intermittent (KI).

| Scene | Body | Full runs | Failure | Marker |
| --- | --- | --- | --- | --- |
| e40a16-922f4fd-707-kindred-14534 (KI) | Rd7126e4_1 | F P F | died again (skeleton) | 0 in every run |
| e40a20-79a9aad-8675309-kindred-112700 (KI) | R61cdc54_1 | F F F | died again (no progress); still in its stuck spell | 0 in every run |
| e40a20-79a9aad-8675309-kindred-94280 (KI) | Raf728a0_1 | F F F | still in its stuck spell | 0 in every run |
| e40c4-b23acc0-303-kindred-200600 | Rc0794a1_1 | F F P | still in its stuck spell | 0 in every run |
| f29e-on-891567637-kindred-65718 | Ra3033d_1 | F P F | died again (witch) | 0 in every run |
| factions-110-d5057a4-110-sol-115671 (KI) | R65fc281_1 | P F F | died again (phantom fall) | 0 in every run |
| factions-e40a16seeds-b7b4bec-707-kindred-88286 (KI) | R3c6bbb7_1 | F F F | died again (zombie) | 0 in every run |

None of the seven bodies has a "Cast: fill a bucket", "walk up" or "open the rock over its lava" goal line in any run: none
reached the code that changed.

**Result: 0 confirmed. The gate passes.** The lead cleared all seven by the marker rule (2026-11-23).

**Kept:** the runs are on `claude/spacer-runs-1112`, under `gate25c4dr/`.

## Remaining limitations and handoff

- **The live read, the next E-40 iteration after its merge:** no walk up repeated from one cell. The marker counts the bound.
- **Not the stuck time:** RUNNER 2 put this loop at 12 of 30 b1's 258 stuck minutes, each spell ended by the watchdog. The time
  is in the hemmed-in family and in "Head back up toward home" (F-25c4bx2's read).
- **Not REFACTOR's lava deaths:** three of iteration 28–29's four "tried to swim in lava" deaths came on "Cast: walk to the
  lava", the dig down, and the dig toward the lava, each after an urgent-retreat cancel. This row leaves those as they were; the
  dig toward the lava is now also reached from the bound. REFACTOR stages any fixture for them on top of this branch.
- **Not CastPool's CI reds:** CI GREEN's census of `NativeCastPoolGameTests` found 13 reds, all before #411 (F-25c4dj1's
  evidence).
