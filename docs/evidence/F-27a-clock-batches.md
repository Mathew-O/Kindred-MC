# F-27a — A corpus batch keeps each scene's own day and night

Date: 2026-11-14
Agent / branch: Claude RUNNER 2 / claude/corpus-clock-batches-1114
Status: complete (the harness change and its re-baseline). CI on the PR is still to come.

A harness change (`CorpusGameTests`, `tools/corpus`). No player behaviour changes, and no scene changes.

## What was wrong
- **One clock a batch:** every replay sets the world clock, weather and difficulty to its record as it starts, so the last scene of a batch to start sets them for all.
- **The old grouping:** batches went by difficulty, weather and a day-or-night flag (night meant 13000 to 22999), then by time.
- **The player's rule is not that flag:** it shelters on vanilla `World.isNight` (`PortalRunCampaign.mayShelterNow`), and the clock runs during a scene.
- **The result:** on main 65332e1, 20 of 422 scenes played under another day or night than their own:
  - corpus_21: 4 scenes of 11004 to 11286, with a setter at 11529 that runs into dusk;
  - corpus_40: 7 rain scenes of 3599 to 9027, with a setter at 11419 that runs into rain's night at 12010;
  - corpus_24: 9 scenes, of which 12851 and 12912 sat with 23006 to 23470, so they played at dawn.
- **Data:** `claude/corpus-data-dc5ad4d-1114`, `clockband/`.

**Vanilla's night**, worked out over every tick of the day with 1.21.1's own `MathHelper`:
- the formula is `World.calculateAmbientDarkness` and `DimensionType.getSkyAngle`, read from the merged jar's bytecode;
- night is ambient darkness of 4 or more;
- clear: night is [12542, 23460);
- rain: [12010, 23992);
- thunder (with rain): night at every tick.

## The change
- **`CorpusGameTests`:**
  - `worldKey` drops the day-or-night flag: difficulty, thunder, rain, then time from 0 to 23999.
  - A scene joins the open batch only if `clockFits`: for every scene in it and every batch-mate's time, the ticks of its run that are night under that time and day under its own, or the reverse, are at most `CLOCK_TOLERANCE`.
  - Every pair is checked, because which scene sets the clock is whichever reaches its staging last.
  - A batch never runs round from 23999 to 0, since times only rise within a group.
  - The constants are `NIGHT_CLEAR_FROM/TO`, `NIGHT_RAIN_FROM/TO` and `CLOCK_TOLERANCE` (200).
- **`tools/corpus/batches.py` (new):** replays the batching from the scene files with those constants, read from the Java source. `--log` checks a run's batches against it.
- **`tools/corpus/layout.py`:** its `world_key` follows the new order.

**Cost estimated before the runs:** tolerance 0 makes 118 batches in place of 46. The estimate was the sum over batches of the longest scene plus 600 ticks, which comes to 2.02× the old grouping's. The six runs below show that this measure overstates the cost:

| `CLOCK_TOLERANCE` (ticks) | Batches | ×today | Singletons |
| --- | --- | --- | --- |
| 0 | 118 | 2.02 | 71 |
| 100 | 79 | 1.51 | 13 |
| 200 | 69 | 1.34 | 9 |
| 400 | 55 | 1.15 | 4 |
| 800 | 48 | 1.03 | 1 |

**The tolerance: 200 ticks (the lead's choice, 2026-11-14).**
- **What it allows:** at most 10 s of game time at the edge of dusk or dawn played under the other light.
- **What it fixes:** under the old grouping, 92 scenes had a batch-mate whose time changed some of their ticks' day or night, 9 of them by more than 800 ticks.
- **Cost, measured:** 40 to 41 min a full run (below). The batch measure predicted about 51.
- **Against 0:** by the same batch measure, 0 would cost about 77 min a run (2.02 times). The 67 scenes that cross dusk or dawn within their own run could each share a batch only with a scene crossing on the same tick, so many would run alone. That estimate is unmeasured, and the measure overstated 200's cost, so 77 is likely high. Even so, 0 starts 49 more batches than 200 for at most 10 s of edge light a scene.
- **Against 100:** the fidelity gained is small.
- **The minutes:**
  - The old grouping took 36.5 to 39.4 min a run on dc5ad4d and 43 to 45 min on 06f6cb4 (its baseline's notes). CI GREEN measured about 43.
  - The new grouping took 40 to 41 min on 16cb7a2, inside that range.
  - A run's time follows its scenes' own ticks and the server's load more than its batch count. So the batch measure's 1.34 times did not show up.

## Validation
- **New tests:** `tools/corpus/test_batches.py`, five of them:
  - CorpusGameTests sets the clock rule and the measured night intervals;
  - every batch of the corpus keeps its scenes' day and night within the tolerance;
  - the three cases found are split;
  - a batch is one group, in time order, never round the wrap;
  - `clock_difference` on worked cases.
- **Before (main's CorpusGameTests and layout.py):** 2 failures ("CorpusGameTests does not check a batch's clock"; "e40c1-eed2dc5-101-echo-59004 batched with f29e-on-707-sol-11529") and 2 errors (the constants unset).
- **After:** `python3 -m unittest discover -s tools/corpus`: 20 tests, OK, 3 of 3.
- **The Java's batches match the mirror:** `runCorpus` of the 30 scenes of today's corpus_21, 24 and 40 (`KINDRED_CORPUS_ONLY`) on this branch, at each tolerance. Each logged batch has the same members, in the same order, as `batches.py` gives for those 30.
  - At 0: 23 batches.
  - At 200: 12 batches. The dusk scenes 11004 to 11529 now run in three batches, 12851 and 12912 apart from the dawn scenes, and the rain scenes 3599 to 9027 apart from 11419.
- **The six-run re-baseline (2026-11-14, 07:12 to 11:16 UTC):**
  - Build: 16cb7a2, which is main 9d33596 with this change. Main has since added F-22c72 and F-22c73 (PortalRunCampaign changes), and they are not in it.
  - Setup: all 422 scenes, alone on a 4-core container, `runCorpus --max-workers=2`, every run from an empty corpus world.
  - Per run (minutes / peak game-JVM VmHWM in MB / scenes failed): 40/2859/124, 40/3056/119, 41/3012/129, 40/3249/128, 40/3964/125, 40/2805/125. `GAME TESTS COMPLETE` came at 40.4 min.
  - `tools/corpus/baseline.json` is now 16cb7a2 over 6 runs: 229 of 422 pass all six (54.3%), against 239 on 06f6cb4. 22 scenes that passed there fail 2 or more of 6 here, and 27 pass here that did not. Replay players acting after their own batch: 0 in every run.
- **A full run's batches:** `batches.py --log` on each of the six logs: 69 batches, the same members as the mirror in every run.
  - In run 4, corpus_15 and corpus_18 logged their scenes' "placed at" lines in another order. Their members were the same.
  - `--log` now compares members, not logged order. Which member sets the clock depends on which starts last, so order was never what the rule rests on.
- **Server stops:** every run's Gradle run ended 8 to 19 s after `GAME TESTS COMPLETE`, so no stop needed jstack dumps (B's rule: past 5 min, take three).
  - The watcher logged one "run 4" stop at 09:59. That was a false positive: it read the pid of run 5's JVM, which had started 11 s after run 4 ended.
  - Its three dumps show run 5's server thread mid-scene (`Navigator.find`). They are kept with the run data and are not evidence of a hang.

## Remaining limitations and handoff
- **Approximations:** rain and thunder are counted at full strength from the scene's start; vanilla ramps them over about 100 ticks. Which scene starts last still varies by a few ticks.
- **Exact next action:** CI on the PR, then the lead's merge. The next re-baseline should be on a main that includes F-22c72 and F-22c73.
- **Updated documentation:** `docs/validation.md` (the failure corpus).
- **Manifest changes:** none.
