# F-25c4dj — The cast's lava in a pool no walk reaches is dug toward, not walked for every tick

Date: 2026-11-16
Agent / branch: LOOPS, claude/cast-walk-1116
Build: ff25b027 (the change and its fixture), on main 3360ef2
Status: complete (2026-11-16)

## The loop: E-40 iteration 23 (`e40a23-3115b1c`, #352), seed 505, Sol
- **Source:** RUNNER 2's `claude/corpus-data-e40a23-1115`, `e40a23-3115b1c/505/console.txt.gz`.
- **What happened:**
  - Casting its portal frame, it was digging toward the pool it had seen at y −55 ("Cast: dig toward the lava at −46, −55, 25").
  - At 08:47:50 it came out on a shelf over a cave at y −40, and the dig gave nothing.
  - "Cast: walk to the lava" was then offered every tick, each a different source of the one pool ("No walkable route toward −50, −55, 17", "−51, −55, 16", …): 565 refusals in 17 s from 08:47:50, then 781 in 30 s from 08:48:06. In all, 1,346 refusals at 26 to 33 a second.
- **Refiled from task #10,** a refused `native_place` retried at ~32/s. Not reproduced there:
  - e40a23's 10 consoles hold 281 placement refusal spells, none of 10 or more;
  - the soak's console (soak-554b0a6, 36 game days; RUNNER 2's count) holds none either. Its only spells of 10 or more are two bed set-downs (308 refusals at 4.4 to 4.9/s), F-25c4di1c's to back off. The soak ran factions with no goal: no casts, so this loop is not in it.

## Why
- **`Digger.toward`** stairs down while the lava is below, then tunnels along its level. Its staircase (`digDown`) refuses a step that opens onto a drop deeper than the navigator takes. On a shelf over a cave every step does, so `digDown` gave nothing, and so did `toward`.
- **`lavaForCast`** then fell through to the walk. Its refusal ("No walkable route") set that source aside, `knownCastLava` took the next nearest source of the same pool, and the walk was offered again at once.

## The change
- **`Digger.toward` (companion-core):** its staircase refused every way, it tunnels along its own level, with the usual turns, to ground it can stair down from. The next call stairs down there.
- **`PortalRunCampaign.lavaForCast`:** a pool known unwalkable (`lavaNoWalk`) with no dig nearer is set aside for 1,200 ticks, every source of it within 8 across and 4 up and down. The cast's own search (the pocket it follows, the cave's lava, a look round, the dig down toward lava) takes over, never the walk again. It notes "its lava's pool at … set aside a while: no walk reaches it, no dig gets nearer (n sources)".
- **Not only a slower walk:** the fixture's pass is the lava in its bucket.

## Fixture (staged): `NativeCastPoolGameTests.aPoolNoWalkReachesIsDugTowardNotWalkedForEveryTick`
- **Setup:**
  - rock with no sky; its alcove on a one-block shelf, walled but for the east, where it opens over a cavern 19 × 13 × 19;
  - the cavern runs under the alcove on every side, so a drop of 13 is under every step a staircase from it would take;
  - a pool of lava 5 × 5 on the cavern's floor, east of it and 14 below its feet, in sight;
  - the cast's kit (iron pickaxe, stone sword, iron chestplate, bread, cobblestone, sticks, flint and steel, a water bucket and an empty bucket); the Nether portal goal, which casts its frame with no diamond pickaxe; daylight held;
  - the pool seen by the campaign's own sweep from where it stands.
- **Fails** if more than 3 walks to the lava are refused, or if it has no bucket of lava within 2,400 ticks. **Passes** with its bucket of lava, alive.
  - The lead's bar was the lava or a dig started toward it. With the fix it fills its bucket at about 1,050 ticks, so the bar is the lava.
- **Slips on the way:**
  - A first staging had the pool under the alcove, seen through a slot beside its feet. From its eye over a one-block ceiling, every sight line to the pool crosses the cell under its own feet, so it never saw the pool.
  - Putting the pool in its knowledge directly did not hold: the sweep forgets what it re-scans and does not see.
  - The alcove opening east over the cavern gives it the pool in sight, as Sol had its pool.
- **Before** (main 3360ef2's `Digger` and `PortalRunCampaign`): 3 of 3 fail. 15, 30 and 30 walks to the lava refused in 17 to 19 ticks, each a different source, as the run's.
- **After:** 3 of 3 pass. 1 walk refused, then 91 steps of "Cast: dig toward the lava" (off the shelf, a staircase down beside the cavern, along to the pool), and its bucket filled at 1,050, 1,044 and 1,048 ticks.
- **companion-core's tests:** all pass.
- **The other fixtures it could touch,** 18 classes:
  - **First run:** 127 of 128 passed. The one failure was `NativePortalCampaignGameTests.atNightBelowGroundTheWaterAtTheSurfaceWaitsForDaylight` ("It stayed below, the water left for the morning"). It stages no lava, so never reaches the changed lines. That class alone passed 3 of 3 on the branch and 3 of 3 on main 83321ac (97 tests each).
  - **Re-run:** all 128 pass.
  - **The classes:** CastPool, CastRefusal, CastTable, CastWaterLost, DeepPortal, DigFill, FrameReturn, GravelTunnel, HalfCastFrame, LavaProbe, ObsidianPickup, ObstructedPortal, PitPocket, PortalCampaign, PortalLight, TeamPortal, RespawnCave and FollowUp.

## Corpus gate (F-27a, 458 scenes, on ff25b027; main 3360ef2, the merge-base)

| Run | Required tests failed | Scenes passing, of 458 | Baseline-passing scenes passing, of 242 |
| --- | --- | --- | --- |
| 1 | 119 | 339 | 236 |
| 2 | 124 | 334 | 232 |
| 3 | 118 | 340 | 237 |

- **The required-test counts are each run's whole failures,** scenes main fails too included. Against the baseline's 242 passing scenes, the branch failed 6, 10 and 5.
- **Over the three runs:** 281 of 459 scenes pass all three (61.2%). Replay players acting after their own batch: 0, 0, 0.
- **Where the changed lines can act:** only where a "Cast: dig toward the lava" step or a refused "Cast: walk to the lava" appears, the only callers of `Digger.toward` being the cast's. In the three runs, that is three scenes' players:

| Scene | Lines (runs 1, 2, 3) | Baseline | Branch, runs 1–3 |
| --- | --- | --- | --- |
| e40a20-79a9aad-606-echo-70440 | 33, 32, 36 | pass | P P P |
| e40a22-d2389c9-505-echo-129201 | 0, 0, 24 | fail | P P P |
| e40a17-7bc5b42-707-echo-36256 | 0, 0, 78 | fail | F F P |

- **Flagged:** 3 scenes, none of them one of those three. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on 3360ef2, as `gate.sh` does. **0 confirmed, 3 cleared:**

| Scene | Branch failed (alone) | Base failed (alone) | One-sided Fisher p, branch worse |
| --- | --- | --- | --- |
| diag-holepit-079bdc2-8675309-echo-51440 | 0 of 3 | 1 of 3 | 1.0 |
| e40a15-dbf4e5c-303-kindred-66804 | 1 of 3 | 1 of 3 | 0.8 |
| e40a15-dbf4e5c-707-sol-10569 | 3 of 3 | 2 of 3 | 0.5 |

- **No changed line acts in any flagged scene:** none of their players logs a "Cast: dig toward the lava" step or a refused walk to the lava in any of the three full runs.
- **Where it is kept:** the runs, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4dj/`.

## Remaining
- None for this row.
