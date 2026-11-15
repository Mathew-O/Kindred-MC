# F-25c4di1d — Its own body over its bed's room, it steps clear before it sets the bed down

Date: 2026-11-15
Agent / branch: LOOPS, claude/bed-step-1115
Build: a5170b23 (the change and its fixtures), merged with F-25c4di1c's head as 2f48ce05
Status: complete (2026-11-15). Its parent F-25c4di1, the respawn loop's deaths, stays open.

## What this row claims
- When its own body's box overlaps either of its bed's two cells, it walks to its cell's middle (within 0.15) before setting the bed down, at most three times a night.
- A refused set-down names what stood in the bed's cells: "The bed was not set down: in its cells minecraft:sheep Sheep", or "its own body".

## Why
- **The cause under F-25c4di1c's backoff:** vanilla places no bed where an entity stands in its cells, and says nothing (F-25c4di1c's evidence). A player at its pocket's edge overlaps the foot cell with its own body (0.6 across).
- **Nothing in the logs said what was in the way.** A refusal for a mob or an item frame read the same as one for its own body.

## The change
- **`PortalRunCampaign.carriedBedNight`:** just before the set-down, if the player's box intersects the foot or head cell, it is offered "Step clear of its bed's room": a `native_goto` to its own feet's cell with `centre_within` 0.15, as the scaffold's "Over the middle" uses.
  - At most `BED_CLEAR_TRIES`, 3, a night; reset in the morning.
  - With its body at the middle, its box is 0.45 from the centre at most, clear of the next cell.
- **`NativeBedAction`:** at "The bed was not set down", the reason's tail names each entity in the bed's two cells, by type and name, or "its own body".
- **Nothing is given or moved by the fixture's code:** the step is the player's own walk.

## Fixtures (staged): `NativeBedRetryGameTests`
- **Common staging:**
  - rock round it with no sky; its pocket two high, closed, its bed's room open north of it, two long, on solid floor;
  - its bed and nothing else; no home bed; night held; no goal, its own play;
  - each `native_bed` it starts is counted, and each `native_bed` record's detail is kept.
- **`standingOverItsBedsRoomItStepsClearAndSetsTheBedDownFirstTime` (new):** the player at the pocket's north edge, its body a tenth of a block into the bed's foot cell. That was F-25c4di1c's staging as first written.
  - Fails at a second try, or with no bed down after 1,200 ticks of night. Passes once its bed stands in the room on its first try.
  - **Before** (a5170b23 with its main-source changes stashed, i.e. F-25c4di1c's code): 3 of 3 fail, "Its bed tried 3 times". The backoff holds, and the refusal stands.
  - **After:** 3 of 3 pass. The bed goes down on its first try in each, "Native bed set down and the respawn point set".
- **`aRefusedSetDownIsTriedAgainOnlyAfterAWhileAndGivenUpForTheNight` (F-25c4di1c's), restaged:** the player at its cell's middle, and a sheep with its AI off in the bed's foot cell.
  - **Why restaged:** once its own body steps clear, F-25c4di1c's first staging puts the bed down at once and would no longer meet a refusal.
  - **Its assertions stand:** no two tries within 100 ticks, at most 4 in the night, at least 1. It now also asks that the refusal name the sheep.
  - **Before** (F-25c4di1c's code): 3 of 3 fail on the naming alone. The record said only "The bed was not set down".
  - **After:** 3 of 3 pass. 3 tries about 200 ticks apart ([63, 263, 464], [34, 234, 438], [44, 245, 446]), then given up; each refusal reads "The bed was not set down: in its cells minecraft:sheep Sheep".
- **The other fixtures it could touch,** 20 classes in one run on 2f48ce05: all 153 required tests pass.
  - BedRetry, WakeAtBed, BedPocket, DeathConservation, End, FactionDeath, FactionShelter, FactionSpawnClaim, FollowUp, NightMeanwhile, NightPortal;
  - NightShelter, NightWalk, Nights, PassageSeal, PortalCampaign, Respawn, RespawnCave, ShelterSeal and Survival.

## Corpus gate (F-27a, 458 scenes, on 2f48ce05; F-25c4di1c's head, its parent)
- **Each run against the 16cb7a2 baseline** (6 runs; 242 scenes pass all six there):

| Run | Required tests failed | Scenes passing, of 458 | Baseline-passing scenes passing, of 242 |
| --- | --- | --- | --- |
| 1 | 122 | 336 | 237 |
| 2 | 126 | 332 | 237 |
| 3 | 117 | 341 | 231 |

- **For comparison,** F-25c4di1c's gate: 335, 339 and 333 pass a run, and 236, 232 and 230 of the 242.
- **Over the three runs:** 289 of 458 scenes pass all three (63.1%), and 62 are newly passing against the baseline.
- **Replay players acting after their own batch:** 0, 0 and 0 (main's `report.py`, with F-27a3's skips).
- **The change in the corpus:** "Step clear of its bed's room" 0 times in each run, and no set-down refused (`native_bed` BLOCKED 0, 0, 0). The change never acted in any corpus scene; the staged fixtures are its proof.
- **Flagged:** 5 scenes. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on F-25c4di1c's head, as `gate.sh` does. **The report confirms 1 and clears 4:**

| Scene | Branch failed (alone) | Base failed (alone) | One-sided Fisher p, branch worse |
| --- | --- | --- | --- |
| diag-holepit-079bdc2-8675309-echo-51440 | 0 of 3 | 1 of 3 | 1.0 |
| e40a14-80b7150-101-echo-75842 | 3 of 3 | 2 of 3 | 0.5 |
| f29e-on-4h-20261030-echo-272968 | 0 of 3 | 0 of 3 | 1.0 |
| f29e-on-4h-707-kindred-222518 (confirmed) | 2 of 3 | 1 of 3 | 0.5 |
| factions-110-d5057a4-110-kindred-190720 | 3 of 3 | 2 of 3 | 0.5 |

- **No changed line acts in any of the five:** no line of any of their players mentions a bed in any of the three full runs.
- **f29e-on-4h-707-kindred-222518, the lead's three-part rule:**
  1. The base fails it alone at least once: 1 of 3 here.
  2. The branch is not worse at a one-sided Fisher p < 0.10: p = 0.5.
  3. No changed line acts in it: above.
- **It is the scene flagged on every gate this day.** From the kept gate XML it passed 23 of 24 full runs on the gates up to F-25c4dg, and fails 13 of 18 after. That is a step on main between those gates' bases, being looked at separately.
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4di1d/`.

## Remaining (F-25c4di1, open)
- **Still untried:** what it does in the cave on waking with nothing (dig in by hand rather than walk out), and armour after the first death.
- **Not shown:** that the runs' loops end.
