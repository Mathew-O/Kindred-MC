# F-27a — e40a22's seed-101 stall and its 20 deaths join the corpus

Date: 2026-11-14
Agent / branch: Claude RUNNER 2 / claude/corpus-e40a22-1114
Status: complete (a scene-only part of F-27a)

A scene-only addition: 22 scenes and their baseline entries, with no code change, in the way of #233.

- **What is added:** from E-40 iteration 22's earned run, `e40a22-d2389c9` (main d2389c9, ten seeds, `goal=nether; ticks=144000`):
  - **Seed 101 Kindred's stall,** the batch's longest. Kindred pillared up out from under water into a hole near −244, 46, −79. From then until the end of the run, "Head back up toward home" was `No walkable route toward -271, 71, -112`. Two scenes:
    - spell 1 at tick 47820, the watchdog's first spell ("hole or pit");
    - spell 2 at tick 51620, the first "no walkable route".
  - **All 20 of the batch's deaths,** one scene each, from the death replay the harness takes before the death. This includes the 9 cornered, the 4 suffocations and seed 707's 6.
  - **Imported:** from `claude/corpus-data-e40a22-1114@a90215a` with `tools/corpus/import.py --keep-structures`, so each structure is in `companion-server-adapter/src/corpus/structures`.
  - **The two spells' class:** set to score.py's F-26c bucket for that spell. The importer's own class for a spell is its task's last words.
- **What is not added:** a scene from just before the pillar. The harness captures a replay only when a stuck spell starts or before a death, so nothing exists from before spell 1. The stall began at about tick 43,200 by the watchdog's "Stuck 4439 ticks".
- **Six runs of only these 22** (`KINDRED_CORPUS_ONLY`):
  - Setup: alone on a 4-core container, `runCorpus --max-workers=2`, every run from an empty corpus world.
  - Code: bf1734f, which is main fbc5f0f with these scenes. The harness is #300's clock batches.
  - Failures per run: 11, 10, 10, 10, 12, 10.
  - Replay players still acting after their own batch: 0 in every run.
  - Each run took 2 to 3 minutes, peak VmHWM 1.4 to 1.9 GB.
- **Results:** 10 pass, 3 intermittent, 9 fail.
  - **The stall:** both spells fail 6 of 6.
  - **Suffocations:** 3 of the 4 fail 6 of 6 (707 Echo, 31102026 Echo, 404 Sol). 31102026 Sol passes 6 of 6.
  - **Cornered deaths:** of the 9, 5 pass, 2 intermittent (101 Echo, 707 Kindred 136143) and 2 fail (707 Kindred 68291, 707 Sol 24111).
  - **Seed 707:** of its 6 deaths, 4 fail, 1 is intermittent and 1 passes.
- **Baseline:** `tools/corpus/baseline.json` keeps 16cb7a2's 422 scenes as they were and gains these 22, 444 in all. Its `notes` say which runs made these entries.
- **Who works on them:**
  - The stall scenes go to LOOPS.
  - The death scenes go to CI GREEN. REFACTOR is diagnosing the deaths.
  - Each death's console window from 600 ticks before is on the data branch under `extract/`, with the full consoles.

| Scene | Kind | Class | F-26c death kind | Passed of 6 | Baseline |
| --- | --- | --- | --- | --- | --- |
| `e40a22-d2389c9-101-echo-17476` | death | death: zombie | cornered | 3 | intermittent |
| `e40a22-d2389c9-101-kindred-47820` | spell | stuck: hole or pit | - | 0 | fail |
| `e40a22-d2389c9-101-kindred-51620` | spell | stuck: no walkable route | - | 0 | fail |
| `e40a22-d2389c9-202-kindred-37140` | death | death: fire | other | 6 | pass |
| `e40a22-d2389c9-202-kindred-48843` | death | death: zombie | cornered | 6 | pass |
| `e40a22-d2389c9-202-kindred-54486` | death | death: drowned | dark melee | 3 | intermittent |
| `e40a22-d2389c9-303-sol-2453` | death | death: drowned | other | 6 | pass |
| `e40a22-d2389c9-31102026-echo-30737` | death | death: suffocation | other | 0 | fail |
| `e40a22-d2389c9-31102026-echo-76218` | death | death: enderman | cornered | 6 | pass |
| `e40a22-d2389c9-31102026-sol-17675` | death | death: suffocation | other | 6 | pass |
| `e40a22-d2389c9-404-sol-50932` | death | death: suffocation | other | 0 | fail |
| `e40a22-d2389c9-505-echo-129201` | death | death: lava | lava | 0 | fail |
| `e40a22-d2389c9-505-kindred-33951` | death | death: zombie | cornered | 6 | pass |
| `e40a22-d2389c9-505-kindred-73283` | death | death: zombie | dark melee | 6 | pass |
| `e40a22-d2389c9-707-echo-2597` | death | death: suffocation | other | 0 | fail |
| `e40a22-d2389c9-707-kindred-134636` | death | death: skeleton | cornered | 6 | pass |
| `e40a22-d2389c9-707-kindred-136143` | death | death: zombie | cornered | 3 | intermittent |
| `e40a22-d2389c9-707-kindred-51330` | death | death: lava | lava | 0 | fail |
| `e40a22-d2389c9-707-kindred-68291` | death | death: zombie | cornered | 0 | fail |
| `e40a22-d2389c9-707-sol-24111` | death | death: zombie | cornered | 0 | fail |
| `e40a22-d2389c9-8675309-echo-9103` | death | death: skeleton | other | 6 | pass |
| `e40a22-d2389c9-8675309-kindred-42831` | death | death: zombie | cornered | 6 | pass |

## Validation
- `python3 -m unittest discover -s tools/corpus`: OK, with the 444 scenes.
- `support.py tracker`: in order.
- No runtime claims beyond the six runs above.

## Remaining limitations and handoff
- **Not tested:** these entries come from runs of the 22 alone. In a full run they share batches with other scenes, so a full run may score them differently. The next full re-baseline records them with the rest.
- **Exact next action:** none for these scenes. Fixes for the stall (LOOPS) and the deaths (CI GREEN) gate against these entries.
- **Updated documentation:** this file, linked from `F-27a.md`.
- **Manifest changes:** none.
