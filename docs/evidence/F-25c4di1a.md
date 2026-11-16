# F-25c4di1a — Its carried bed set down only in a pocket with no way in left

Date: 2026-11-15
Agent / branch: LOOPS, claude/bed-pocket-1115
Build: c00cf589 (the change and its fixture), on F-25c4di's head bde2e347
Status: complete (2026-11-15). Its parent F-25c4di1, the respawn loop's deaths, stays open.

## What this row claims
- On a night away from home with a bed in its pack, it sets the bed down only in a pocket with no way in left. Then its spawn moves there, as vanilla moves it.
- With a way into its pocket or its bed's room left, the bed stays in its pack, and the night goes on as before, without it.
- **It does not claim** that the respawn loop's deaths are fixed. They are F-25c4di1's, below.

## The runs
- **E-40 rung c (`e40c4-b23acc0`, #334), seed 303, Kindred** (F-25c4di's evidence, from RUNNER 2's `claude/corpus-data-e40c4-1115`):
  - its seal's first block at its pocket at 314, 51, 171 was refused ("Placement preconditions, claim, or policy no longer valid"), and the bed went down;
  - woken among mobs, it died 23 times, every respawn at 314, 51, 172.
- **E-40 iteration 23 (`e40a23-3115b1c`, #352), seed 101, Sol** (RUNNER 2's `claude/corpus-data-e40a23-1115`):
  - its pocket was never sealed. `native_bed` was BLOCKED 85 times, then the bed went down at −377, 31, −143 the moment a skeleton fight broke off;
  - it died 12 times there, 13 of the batch's 28 deaths, every respawn at −376, 31, −145.

## Why
- **`carriedBedNight` set the bed down whenever `seal` gave it nothing to do.**
- **`seal` gives nothing in three cases:**
  - the pocket is closed;
  - it is a cave, too open to seal (more openings than `2 + 2 × cells`);
  - no block of its own will go in (none carried, or each placement refused).
- **The last two left the bed in an open pocket,** and the spawn with it.

## The change (`PortalRunCampaign`)
- **`waysIntoBedPocket`:** the openings round the cell its feet are in and round each half of its bed's room, at foot and head height and over the head, other than the pocket and the room themselves.
- **In `carriedBedNight`, after the seal:** with any way in left, it returns nothing, so the night goes on as before, without the bed. It notes "its bed kept in its pack: its pocket has a way in at …" once.
- **`seal`'s own opening count** is now the shared helper `waysIn`. It behaves as before.
- **Nothing is given and the spawn is not moved:** what changes is only where it chooses to set its own bed down.

## Fixtures (staged): `NativeBedPocketGameTests`
- **Common setup:**
  - rock round it with no sky;
  - its bed in its hotbar and nothing else, so no block to seal with;
  - no home bed, so home is out of reach;
  - night held; no goal, its own play (`OPTIMAL_SURVIVAL`, resumed).
- **Fails** at the first bed block seen in the staged rock. **Passes** after 600 ticks of night with none, alive and the bed still in its pack.
- **`atACaveItCannotSealItDoesNotSetItsBedDown`** (as 101 Sol): its feet on the floor of a cave five wide, seven deep and three high, open north, south, west and over its head (seven ways in); room for a bed east of its feet.
- **`withAWayInItCannotCloseItDoesNotSetItsBedDown`** (as 303 Kindred, its seal refused): its feet at the end of a tunnel one wide and two high running five west (one way in, two cells); room for a bed open north of its feet.
- **A slip on the way:**
  - The tunnel's first version had its bed room east. The room search looks north first, and dug a room into the rock north by hand; with no pickaxe that took the whole 600 ticks. It passed before the fix, for that reason alone.
  - With the room open north, as the run's room was open, it reproduces the run.
- **Before** (bde2e347's `PortalRunCampaign`): 3 of 3 fail, both tests in each. Each fails with "Its bed set down in a pocket with a way in": the cave's bed went down north in the cave, and the tunnel's in its room.
- **After:** 3 of 3 pass, both tests in each. Every run notes "its bed kept in its pack: its pocket has a way in at …" for both players.
- **The other fixtures it could touch,** 18 classes in one run, including the sealed-pocket bed night (`homeOutOfReachItSleepsInTheBedItCarriesTakesItBackAndSetsHomeAgain`): all 150 required tests pass.
  - BedPocket, DeathConservation, End, FactionDeath, FactionShelter, FactionSpawnClaim, FollowUp, NightMeanwhile, NightPortal;
  - NightShelter, NightWalk, Nights, PassageSeal, PortalCampaign, Respawn, RespawnCave, ShelterSeal and Survival.
- **On the merged tree** (main d1643998): BedPocket, Nights, RespawnCave and ShelterSeal pass in one run, all 14 tests.

## Corpus gate (F-27a, 458 scenes, on c00cf589; F-25c4di's head bde2e347, its parent)
- **The corpus does not all pass on its base,** so raw failure counts say little. Each run against the 16cb7a2 baseline (6 runs; 242 scenes pass all six there):

| Run | Required tests failed | Scenes passing, of 458 | Baseline-passing scenes passing, of 242 |
| --- | --- | --- | --- |
| 1 | 124 | 334 | 235 |
| 2 | 124 | 334 | 236 |
| 3 | 116 | 342 | 235 |

- **For comparison,** F-25c4di's gate on the same 458 scenes (3332aec): 347, 341 and 337 pass a run, and 233, 234 and 236 of the baseline's 242.
- **Over the three runs:** 287 of 458 scenes pass all three (62.7%), and 62 are newly passing against the baseline.
- **Replay players acting after their own batch:** 0, 0 and 1.
  - The one, in run 3, is `Rc0794a1_1`. Its only line after its batch is "break at …: its collection ended (authority ended) after 48 ticks, its own drops uncollected", logged in the second the next batch began, beside another player's "dropped before it ran … stop=true".
  - That is its stop revoking a break in progress, not an action. The counter skips the stop's "dropped before it ran" lines but not this one.
  - The same line appears 11, 16 and 13 times in these runs, and 7, 11 and 7 times in F-25c4di's; only here did one fall on a batch's edge.
  - No line this change touches writes it. `report.py` is left as it is here.
- **The change in the corpus:** "Set its bed down in its pocket for the night" 2, 2 and 0 times, against 4, 2 and 2 in F-25c4di's three runs. "Sleep in the bed it carries" 0 times in either.
- **Flagged:** 2 scenes. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on bde2e347, as `gate.sh` does. **0 confirmed, 2 cleared:**

| Scene | Branch failed (alone) | Base failed (alone) | One-sided Fisher p, branch worse |
| --- | --- | --- | --- |
| e40a14-80b7150-101-echo-75842 | 1 of 3 | 2 of 3 | 0.95 |
| f29e-on-4h-707-kindred-222518 | 1 of 3 | 3 of 3 | 1.0 |

- **The lead's three-part rule, for each:**
  1. The base fails it alone at least once: 2 of 3 and 3 of 3.
  2. The branch is not worse at a one-sided Fisher p < 0.10: p = 0.95 and 1.0.
  3. No changed line acts in it: neither scene's player takes a night or bed step in any of the three full runs (0 lines with "bed", 0 with "night"). The changed lines run only on a carried-bed night, after its seal.
- **Both were flagged on F-25c4di's gate too,** and cleared there.
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4di1/`.

## Remaining (F-25c4di1, open)
- **Tried and kept:** where it sets its spawn. A bed is no longer set down where mobs can walk in.
- **Still untried:**
  - what it does on waking, with nothing, in a cave that killed it;
  - armour 0 after the first death.
- **Not shown:** that the runs' death loops end. This change keeps the spawn from moving into an open pocket. It does not move a spawn already set there, and the runs' scenes start after the bed was set.
- **On a night it keeps its bed,** a no-goal player holes up as a player with no bed does (the fixtures show it takes no action for the watch). It does not dig a fresh, closed pocket elsewhere for the bed.
