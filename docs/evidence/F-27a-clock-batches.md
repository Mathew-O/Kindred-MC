# F-27a — A corpus batch keeps each scene's own day and night

Date: 2026-11-14
Agent / branch: Claude RUNNER 2 / claude/corpus-clock-batches-1114
Status: in progress. The grouping change and its tests are done. The PR waits for the six-run re-baseline on this harness, which runs after E-40's iteration 22.

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
  - The constants are `NIGHT_CLEAR_FROM/TO`, `NIGHT_RAIN_FROM/TO` and `CLOCK_TOLERANCE` (0).
- **`tools/corpus/batches.py` (new):** replays the batching from the scene files with those constants, read from the Java source. `--log` checks a run's batches against it.
- **`tools/corpus/layout.py`:** its `world_key` follows the new order.

**Cost:** tolerance 0 makes 118 batches in place of 46. As the sum over batches of the longest scene plus 600 ticks, that is 2.02× today's:

| `CLOCK_TOLERANCE` (ticks) | Batches | ×today | Singletons |
| --- | --- | --- | --- |
| 0 | 118 | 2.02 | 71 |
| 100 | 79 | 1.51 | 13 |
| 200 | 69 | 1.34 | 9 |
| 400 | 55 | 1.15 | 4 |
| 800 | 48 | 1.03 | 1 |

## Validation
- **New tests:** `tools/corpus/test_batches.py`, five of them:
  - CorpusGameTests sets the clock rule and the measured night intervals;
  - every batch of the corpus keeps its scenes' day and night within the tolerance;
  - the three cases found are split;
  - a batch is one group, in time order, never round the wrap;
  - `clock_difference` on worked cases.
- **Before (main's CorpusGameTests and layout.py):** 2 failures ("CorpusGameTests does not check a batch's clock"; "e40c1-eed2dc5-101-echo-59004 batched with f29e-on-707-sol-11529") and 2 errors (the constants unset).
- **After:** `python3 -m unittest discover -s tools/corpus`: 20 tests, OK, 3 of 3.
- **The Java's batches match the mirror:** a `runCorpus` of the 30 scenes of today's corpus_21, 24 and 40 (`KINDRED_CORPUS_ONLY`) on this branch logged 23 batches. Each has the same members, in the same order, as `batches.py` gives for those 30.
- **Still to come:** the six-run baseline on this harness, a full run's batches checked by `batches.py --log`, and CI.

## Remaining limitations and handoff
- **Approximations:** rain and thunder are counted at full strength from the scene's start; vanilla ramps them over about 100 ticks. Which scene starts last still varies by a few ticks.
- **Exact next action:** the lead picks `CLOCK_TOLERANCE`. Then the six-run re-baseline on this branch, alone on the cores, after iteration 22, and one PR with `baseline.json`.
- **Updated documentation:** `docs/validation.md` (the failure corpus).
- **Manifest changes:** none.
