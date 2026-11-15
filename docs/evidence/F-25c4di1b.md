# F-25c4di1b — Woken with nothing at its bed just after a death there, it takes the bed up

Date: 2026-11-15
Agent / branch: LOOPS, claude/respawn-wake-1115
Build: cc67d1d1 (the change and its fixture), on F-25c4di1a's head f80148fe
Status: complete (2026-11-15). Its parent F-25c4di1, the respawn loop's deaths, stays open.

## What this row claims
- Woken within reach of its own carried bed just after a death there, it takes the bed up at once, with one break by its own hand from where it wakes.
- The bed comes back into its pack by vanilla's own drop and pickup, and its next death wakes it elsewhere, by vanilla's own rule.
- **It does not claim** that the respawn loop's deaths end; see Remaining.

## The run: E-40 iteration 23 (`e40a23-3115b1c`, #352), seed 101, Sol
- **Source:** RUNNER 2's `claude/corpus-data-e40a23-1115`, `e40a23-3115b1c/101/console.txt.gz`.
- **The bed:** set down at 07:51:43, underground at −377, 31, −143, after 85 `native_bed` BLOCKED.
- **The deaths:** 12, from tick 87,674 to 135,766. Each was 15 to 50 blocks from the bed, through the caves round it (x −424 to −301, y 23 to 51):
  - to zombies 7 times, skeletons 2, a creeper 1, a slime 1 and a witch 1;
  - armour 0 and holding nothing from the second death on.
- **Every respawn** was at −376, 31, −145, by the bed. Its first death that run, before the bed, had respawned it at −268, 71, −107, on the surface.
- **It never took the bed up.** That is a morning chore (`safeForChore`: no hostile within 16, half health or more), and a hostile was always about down there.

## Why
- **Its spawn stayed in the caves that kept killing it,** because the only way it took up its bed waited on a calm that the caves never gave.
- **The way out a player takes:** break its own bed the moment it wakes, which by vanilla's rule sends the next respawn to its world spawn or home bed. The lead ruled on 2026-11-15 that this is earned: a player breaking its own bed with its own hand, vanilla doing the rest.

## The change
- **`PortalRunCampaign.died`:** with its own carried bed standing (the one it set down and recorded), it notes the bed, the death's place and cause, and a window of 600 ticks.
- **`carriedBedNight`, first:** woken within reach of that bed (eyes within 4.5) in that window, it is offered one `native_break` of the bed by hand (`carried_bed`, as the morning chore's), labelled "Take its bed back up: woken at it after its death".
  - The `safeForChore` wait is waived for that break only. Nothing else about the chore changes.
  - It logs one line, once: "<name> takes up its bed at <bed>: woken at it after its death at <death> (<cause>)".
  - **One break only.** The offer stands until the runtime says the break has run, however it went (`wakeBedBreakEnded`, from the end of a life task or a goal task). It also ends once the bed is gone, the player is out of reach, or the window is past. A refused break is not offered again; the bed then waits for the morning chore, as before.
- **`PlayerRuntime`:** at a life task's end, it tells the campaign (`wakeBedBreakEnded`).
- **Nothing is given, moved or recreated:**
  - the bed drops and is picked up as any break's drops are, and a lost drop is reconciled the usual way;
  - the spawn is reset by vanilla, on the next respawn, because the bed is gone.

## Fixture (staged): `NativeWakeAtBedGameTests.wokenWithNothingAtItsBedAfterADeathItTakesTheBedUp`
- **Setup** (its own play first):
  - stone underfoot and air over it; no home bed, so home is out of reach;
  - a stone pickaxe, 32 cobblestone and a white bed in its pack; night; its own play, no goal;
  - by its own play it digs in, seals its pocket, sets its bed down there and sleeps in it, which sets its spawn there.
- **Staged:**
  - the fixture kills it (the fixture's death, not play);
  - a husk with its AI off on the ground four blocks off: a hostile within 16, as there always was in the run. A husk, as a zombie burns in the held daylight;
  - easy difficulty for the test, so the husk is not taken away; the server's own difficulty is put back as it ends;
  - daylight held.
- **It wakes at its bed with nothing.**
- **Passes if:**
  - within 400 ticks of waking, its bed is taken up and back in its pack;
  - then, killed again by the fixture, it does not wake in its pocket.
- **Slips on the way:**
  - The first version had a zombie, which burned in the held daylight. With it gone, the morning chore took the bed up, and the test failed only on the zombie's absence.
  - The first version of the change cleared its offer on making it. On a body just respawned, that first offer was not dispatched, and the one chance was lost: 3 of 3 failed after it. The offer now stands until its break has run.
- **Before** (f80148fe's `PortalRunCampaign` and `PlayerRuntime`): 3 of 3 fail with "Its bed still down 400 ticks after waking by it". The morning chore is put off for the husk.
- **After:** 3 of 3 pass.
  - Each logs "Waker takes up its bed at …: woken at it after its death at … (Waker was killed)".
  - The bed is taken up 19 to 21 ticks after waking.
  - Killed again, each wakes at the world's spawn: 10, −60, −3; 2, −60, 4; −1, −60, 4.
- **The other fixtures it could touch,** 19 classes in one run: all 151 required tests pass.
  - WakeAtBed, BedPocket, DeathConservation, End, FactionDeath, FactionShelter, FactionSpawnClaim, FollowUp, NightMeanwhile, NightPortal;
  - NightShelter, NightWalk, Nights, PassageSeal, PortalCampaign, Respawn, RespawnCave, ShelterSeal and Survival.

## Corpus gate (F-27a, 458 scenes, on cc67d1d1; F-25c4di1a's head f80148fe, its parent)
- **Each run against the 16cb7a2 baseline** (6 runs; 242 scenes pass all six there):

| Run | Required tests failed | Scenes passing, of 458 | Baseline-passing scenes passing, of 242 |
| --- | --- | --- | --- |
| 1 | 125 | 333 | 234 |
| 2 | 113 | 345 | 235 |
| 3 | 106 | 352 | 237 |

- **For comparison,** F-25c4di1a's gate on the same 458 scenes: 334, 334 and 342 pass a run, and 235, 236 and 235 of the baseline's 242.
- **Over the three runs:** 288 of 458 scenes pass all three (62.9%), and 63 are newly passing against the baseline.
- **Replay players acting after their own batch:** 0, 0 and 0, counted by main's `report.py` with F-27a3's skips (#356).
- **The change in the corpus:** "takes up its bed at" 0 times in each run. No scene's player dies with its own carried bed standing.
- **A loop seen, not this change's:**
  - In run 3 only, `e40a20-79a9aad-101-sol-107500`'s player had `native_bed` BLOCKED "The bed was not set down" 426 times in a row, in its sealed pocket (`R3e9cba7_1`). That made 429 "Set its bed down" lines, against 2 and 2 in runs 1 and 2.
  - The scene passes 3 of 3 here, on F-25c4di1a's gate and on F-25c4di's, and the take-up never fired.
  - It is the refusal e40a23's 101 Sol showed before its bed went down (85 BLOCKED), on main before either change: a refused set-down offered again with no backoff. It is left for a row of its own.
- **Flagged:** 3 scenes. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on f80148fe, as `gate.sh` does. **0 confirmed, 3 cleared:**

| Scene | Branch failed (alone) | Base failed (alone) | One-sided Fisher p, branch worse |
| --- | --- | --- | --- |
| e40a13-fe2ba3b-606-echo-81682 | 1 of 3 | 2 of 3 | 0.95 |
| e40a15-dbf4e5c-404-kindred-36022 | 1 of 3 | 0 of 3 | 0.5 |
| f29e-on-4h-707-kindred-222518 | 1 of 3 | 1 of 3 | 0.8 |

- **Where the changed code runs in them:**
  - Each flagged player dies in some of the full runs, so the new lines in `died` do run there.
  - None has a bed: no line of any of the three mentions one in any full run. So `seeCarriedBed` finds nothing, no bed is noted, and the take-up is never offered.
  - The one other new line, in the runtime, acts only on the take-up's own label.
- **No changed line acts in them:** `died()`'s new lines run there but change nothing without a bed.
- **kindred-36022, the lead's three-part rule:**
  1. **Main fails it alone at least once.** It passed alone 3 of 3 on this gate's base. But on MODES's E-71b1 gate (#359, `docs/evidence/E-71b1.md`, its corpus gate), main 7d5ec42 failed it alone 2 of 3 (passed 1 of 3), against that branch's 1 of 3.
  2. **The branch is not worse at a one-sided Fisher p < 0.10:** here 1 of 3 failed against the base's 0 of 3, p = 0.5.
  3. **No changed line acts in it:** above.
- It was cleared on F-25c4df's and F-25c4di's gates too.
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4di1b/`.

## Remaining (F-25c4di1, open)
- **Tried and kept:**
  - where it sets its spawn (F-25c4di1a);
  - taking its bed up on waking just after a death there (this row).
- **Still untried:**
  - what it does in the cave on waking with nothing: dig in by hand rather than walk out (the lead's next lever);
  - armour 0 after the first death.
- **Not shown:**
  - that the runs' loops end. The runs' own scenes start after the bed was set and the deaths had begun, and their players are not woken at the bed in them.
  - the take-up with a mob that is attacking it. In the run, mobs were about and some targeting it; with a hostile in pursuit, the fight comes first.
