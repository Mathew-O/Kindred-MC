# F-25c4di1c — A refused set-down of its bed is offered again only after a while, and given up for the night after three

Date: 2026-11-15
Agent / branch: LOOPS, claude/bed-retry-1115
Build: 822b33db (the change and its fixture), on F-25c4di1b's head 4fc62f53
Status: complete (2026-11-15). Its parent F-25c4di1, the respawn loop's deaths, stays open.

## What this row claims
- When setting its carried bed down is refused, it is offered again only after 200 ticks.
- After three refusals in a night, it is not offered again that night; the night goes on as before, without the bed. Both reset in the morning.
- **It does not claim** to remove the refusal's cause. That is F-25c4di1d, its own body over the bed's cells.

## The loop
- **A corpus run on F-25c4di1b's gate (#362), run 3:** `e40a20-79a9aad-101-sol-107500`'s player (`R3e9cba7_1`) had `native_bed` BLOCKED "The bed was not set down" 426 times in a row in its sealed pocket. The scene passed 3 of 3 there, on F-25c4di1a's gate and on F-25c4di's.
- **E-40 iteration 23 (`e40a23-3115b1c`, #352), seed 101, Sol** (RUNNER 2's `claude/corpus-data-e40a23-1115`): 85 BLOCKED in a row at 07:51:32 to 07:51:43, before its bed went down at −377, 31, −143. That was on main, before F-25c4di1a or 1b.

## Why
- **The refusal:** `NativePlayerBody.placeBed` runs vanilla's `interactBlock` with the bed. Vanilla places no bed where an entity overlaps its cells, and says nothing. `placeBed` still returns true, and `NativeBedAction` then finds no bed: "The bed was not set down".
- **The loop:** `carriedBedNight` offered the set-down again at the next decision, and nothing about the spot had changed. A player standing at its pocket's edge overlaps the bed's foot cell with its own body.

## The change (`PortalRunCampaign`, `PlayerRuntime`)
- **`bedTaskEnded(task, completed)`:** the runtime's word to the campaign as one of its bed's own steps ends. It is called at a life task's end and from `taskFinished`. A set-down that did not complete counts as a refusal; the next offer waits `BED_DOWN_BACKOFF`, 200 ticks.
- **After `BED_DOWN_REFUSALS`, 3, in a night,** the set-down is not offered again that night. It says so once: "gives up setting its bed down tonight: refused 3 times".
- **Both reset in the morning,** with the night's other bed counters.
- **F-25c4di1b's one break on waking** now ends through the same hook (formerly `wakeBedBreakEnded`), however it went.

## Fixture (staged): `NativeBedRetryGameTests.aRefusedSetDownIsTriedAgainOnlyAfterAWhileAndGivenUpForTheNight`
- **Setup:**
  - rock round it with no sky; its pocket two high, closed, its bed's room open north of it, two long, on a solid floor;
  - the player at the pocket's north edge, its body (0.6 across) a tenth of a block into the bed's foot cell, where vanilla will not set a bed down;
  - its bed and nothing else; no home bed; night held; no goal, its own play.
- **Each `native_bed` it starts is counted.** Fails if two start within 100 ticks of each other, or more than 4 in 1,200 ticks of night. Passes otherwise, alive, with at least one tried.
- **Before** (4fc62f53's code): 3 of 3 fail with the run's own message, "The bed was not set down". Run 3 reached 338 tries, 5 ticks apart.
- **After:** 3 of 3 pass. Each made 3 tries, about 200 ticks apart ([50, 250, 452], [60, 260, 464], [30, 232, 434]), then "gives up setting its bed down tonight: refused 3 times".
- **F-25c4di1d restages this test** with a sheep in the foot cell, since its own body is then stepped clear (F-25c4di1d's evidence).
- **The other fixtures it could touch,** 20 classes:
  - **First run:** 151 of 152 required tests passed. The one failure was `NativePortalCampaignGameTests.goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater`, "The obsidian was mined and picked up: held 0", the portal-obsidian intermittent the lead named on #355's re-run.
  - **That class alone,** 3 times on the branch and 3 times on the parent 4fc62f53: all 97 tests pass in each of the six.
  - **Re-run of the 20:** all 152 pass.
  - **The classes:** BedRetry, WakeAtBed, BedPocket, DeathConservation, End, FactionDeath, FactionShelter, FactionSpawnClaim, FollowUp, NightMeanwhile, NightPortal, NightShelter, NightWalk, Nights, PassageSeal, PortalCampaign, Respawn, RespawnCave, ShelterSeal and Survival.

## Corpus gate (F-27a, 458 scenes, on 822b33db; F-25c4di1b's head 4fc62f53, its parent)
- **Each run against the 16cb7a2 baseline** (6 runs; 242 scenes pass all six there):

| Run | Required tests failed | Scenes passing, of 458 | Baseline-passing scenes passing, of 242 |
| --- | --- | --- | --- |
| 1 | 123 | 335 | 236 |
| 2 | 119 | 339 | 232 |
| 3 | 125 | 333 | 230 |

- **For comparison,** F-25c4di1b's gate: 333, 345 and 352 pass a run, and 234, 235 and 237 of the 242.
- **Over the three runs:** 285 of 458 scenes pass all three (62.2%), and 64 are newly passing against the baseline.
- **Replay players acting after their own batch:** 0, 0 and 0 (main's `report.py`, with F-27a3's skips).
- **The change in the corpus:** no set-down was refused in any run (`native_bed` BLOCKED 0, 0 and 0). The set-down was offered 2, 2 and 0 times, and 1, 1 and 0 beds went down. So the backoff never acted. The 426 refusals of F-25c4di1b's run 3 were in a scene that does not reach its night in these runs.
- **Flagged:** 6 scenes. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on 4fc62f53, as `gate.sh` does. **The report confirms 1 and clears 5:**

| Scene | Branch failed (alone) | Base failed (alone) | One-sided Fisher p, branch worse |
| --- | --- | --- | --- |
| e40a13-fe2ba3b-707-echo-80901 | 1 of 3 | 1 of 3 | 0.8 |
| e40a14-80b7150-101-echo-75842 | 2 of 3 | 2 of 3 | 0.8 |
| e40a15-dbf4e5c-303-kindred-66804 | 1 of 3 | 1 of 3 | 0.8 |
| e40a15-dbf4e5c-707-sol-10569 | 3 of 3 | 3 of 3 | 1.0 |
| e40c1-eed2dc5-8675309-echo-173225 | 0 of 3 | 0 of 3 | 1.0 |
| f29e-on-4h-707-kindred-222518 (confirmed) | 2 of 3 | 1 of 3 | 0.5 |

- **No changed line acts in any of the six:** no line of any of their players mentions a bed in any of the three full runs. The changed lines act only on a refused set-down of its bed.
- **f29e-on-4h-707-kindred-222518, the lead's three-part rule:**
  1. **The base fails it alone at least once:** 1 of 3 here. It also failed 2 of 3 alone on F-25c4df's base and 1 of 3 on F-25c4di's.
  2. **The branch is not worse at a one-sided Fisher p < 0.10:** 2 of 3 failed against 1 of 3, p = 0.5.
  3. **No changed line acts in it:** above.
- **It was flagged on every gate this day:** F-25c4df, F-25c4di, F-25c4di1a, F-25c4di1b and this one.
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4di1c/`.

## Remaining (F-25c4di1, open)
- **The refusal's cause,** its own body over the bed's cells, is F-25c4di1d's.
- **F-25c4di1's own remaining work is as before:** what it does in the cave on waking with nothing, armour after the first death, and whether the runs' loops end.
