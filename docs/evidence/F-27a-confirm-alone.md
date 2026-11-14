# F-27a — The gate confirms each flagged scene alone

Date: 2026-11-14
Agent / branch: Claude RUNNER 2 / claude/gate-confirm-alone-1114
Status: in progress (part of F-27a; linked from F-27a.md)

A harness change (`tools/corpus/gate.sh`). No player behaviour changes; no scene, baseline or scoring changes.

## What was wrong
- **The confirm was not alone.** `gate.sh` set `KINDRED_CORPUS_ONLY` to every flagged scene at once, so the flagged scenes ran together, in batches by `CorpusGameTests.worldKey`.
- **A batch shares one world clock.** Weather and difficulty are shared too, and the last scene in the batch to start sets them for all. A confirmed scene could pass or fail by a batch-mate's clock.
- **Shown on `e40a20-79a9aad-31102026-echo-102580`** (data: `claude/corpus-data-dc5ad4d-1114`, `echo102580/`). Its own time is 6579, midday.
  - In #280's confirm it ran beside `diag-factions303-079bdc2-303-echo-36120` (time 12119, dusk), which started last in their batch. The world sat at dusk, the player dug in for the night and tunnelled 8 or more blocks lower, and that counts as out of the stuck spell's box.
  - It passed 3 of 3 on main 3196401 and 3 of 3 on the branch that way.
  - With the same five scenes on dc5ad4d it passed 3 of 3 again. Truly alone on dc5ad4d it passed 0 of 3, and on 3196401 1 of 3.

## The change
- **One scene per run:** each flagged scene gets a run of its own (`KINDRED_CORPUS_ONLY` set to its id only), three times on the branch and three times on main as the branch has it.
- **Files:** a scene's run is `confirm-<side>-<i>.<n>.xml`, where `n` is its line in `flagged.txt`. Round `i` of every scene is put together in `confirm-<side>-<i>.xml`, which `report.py` reads as before. The report's rule is unchanged.
- **Empty world:** every run still starts from an empty corpus world. The report's world list now names each scene's run (`confirm-branch-1.1`, …).
- **Cost:** N flagged scenes × 3 × 2 server starts, in place of 3 × 2. One scene's run took 34–40 s here (Gradle up to date, server start, one scene). Five flags are 30 runs, about 20 minutes more than before.

## Past gates
Their verdicts stand. Branch and main were confirmed the same way, the flagged scenes together under one batch's clock, so each comparison was a fair pairing. It was not "alone", as the reports said.

## Validation
- **New test:** `tools/corpus/test_gate.py`, `ConfirmAlone.test_each_flagged_scene_alone_three_times_on_each_side`.
  - Setup: three scenes flagged by a stand-in `gradlew` that records each run's `KINDRED_CORPUS_ONLY`.
  - It checks 18 confirming runs, each of one scene, nine on each side, every scene three times a side, and that each round's runs read as one in the report ("branch failed 0 of 3, main passed 3 of 3" for each).
- **Before (today's gate.sh):** the test fails, "6 != 18: ['s1,s2,s3', …]". `EmptyWorld.test_every_run_and_both_confirms_start_from_an_empty_world` also fails there, on the world list's new run names.
- **After:**
  - `python3 -m unittest discover -s tools/corpus`: 16 tests, OK, 3 of 3.
  - The shared fixture is now `GateTree`, with no tests of its own.
- **Not run:** a full gate on real scenes (three full runs and the confirms, about 1.5 hours). The single-scene `KINDRED_CORPUS_ONLY` runs it now makes are the runs in the data above.

## Remaining limitations and handoff
- **Same-build scenes still vary:** a scene alone still varies from run to run on one build (E-40's method note). Three a side is unchanged.
- **The clock boundary, reported separately:** a scene's batch in the full runs still shares a clock. worldKey's day-or-night boundary (13000) against the player's dusk shelter (about 12000) is being counted on its own. Any change to the grouping waits for those numbers.
- **Exact next action:** none for this slice.
- **Updated documentation:** `docs/validation.md` (the failure corpus).
- **Manifest changes:** none.
