# F-27a: e40c4's seed-303 respawn loop joins the corpus

Date: 2026-11-15
Agent / branch: Claude RUNNER 2 / claude/corpus-e40c4-1115
Status: complete (a scene-only part of F-27a)

This is a scene-only addition, as with #233 and #311: 14 scenes and their baseline entries, with no code change.

**What is added** (from E-40 rung c's fourth probe, `e40c4-b23acc0`: main b23acc0, ten seeds, `goal=dragon; ticks=288000`, earned; #334):
- **The loop:** seed 303 Kindred died 23 times between ticks 194,201 and 237,417.
  - It respawned every time at 314, 51, 172. That is where it had slept in the bed it carries, 43 s before its first death, while tunnelling toward lava for the portal cast.
- **The scenes:** the 14 replays the harness took in that window, 12 deaths and 2 stuck spells. The other 11 deaths have no replay.
  - Imported from `claude/corpus-data-e40c4-1115@63c0da5` with `tools/corpus/import.py --keep-structures`.
  - The two spells' class is score.py's F-26c bucket for each.

**What is not added:** seed 101 Kindred's reconciliation block (about tick 223,500, "Attack item conservation check failed"). Its blocked runner raised no stuck spell and no death, so no replay exists after tick 189,720. Its kept world and save are on the data branch, for a staged fixture (LOOPS).

**Six runs of only these 14** (`KINDRED_CORPUS_ONLY`):
- **Setup:** alone on a 4-core container, `runCorpus --max-workers=2`, every run from an empty corpus world, on ade8e6e (main d257164 with the scenes).
- **Failed per run:** 8, 5, 9, 6, 5, 7.
- **Replay players still acting after their own batch:** 0 in every run.
- **Results:** 3 pass, 9 intermittent, 2 fail.

| Scene | Kind | Class | Passed of 6 | Baseline |
| --- | --- | --- | --- | --- |
| `e40c4-b23acc0-303-kindred-194201` | death | death: spider | 4 | intermittent |
| `e40c4-b23acc0-303-kindred-196504` | death | death: zombie | 2 | intermittent |
| `e40c4-b23acc0-303-kindred-200600` | spell | stuck: refused target repeated | 0 | fail |
| `e40c4-b23acc0-303-kindred-205340` | spell | stuck: hole or pit | 5 | intermittent |
| `e40c4-b23acc0-303-kindred-207782` | death | death: zombie | 3 | intermittent |
| `e40c4-b23acc0-303-kindred-209176` | death | death: skeleton | 4 | intermittent |
| `e40c4-b23acc0-303-kindred-210785` | death | death: creeper | 0 | fail |
| `e40c4-b23acc0-303-kindred-213748` | death | death: zombie | 1 | intermittent |
| `e40c4-b23acc0-303-kindred-216560` | death | death: zombie | 4 | intermittent |
| `e40c4-b23acc0-303-kindred-218103` | death | death: spider | 2 | intermittent |
| `e40c4-b23acc0-303-kindred-230257` | death | death: zombie | 6 | pass |
| `e40c4-b23acc0-303-kindred-234510` | death | death: skeleton | 6 | pass |
| `e40c4-b23acc0-303-kindred-235741` | death | death: creeper | 6 | pass |
| `e40c4-b23acc0-303-kindred-237417` | death | death: zombie | 1 | intermittent |

**Baseline:** `tools/corpus/baseline.json` keeps its 444 entries as they were and gains these 14, 458 in all. Its `notes` say which runs made them.

**Who works on them:** LOOPS has the loop. The console excerpts (the sleep, the first death, the whole loop) and the kept world are on the data branch.

## Validation
- `python3 -m unittest discover -s tools/corpus`: OK, with the 458 scenes.
- `support.py tracker`: in order.
- No runtime claims beyond the six runs above.

## Remaining limitations and handoff
- **Not tested:** these entries come from runs of the 14 alone. In a full run they share batches with other scenes, and the next full re-baseline records them with the rest.
- **Exact next action:** none for these scenes.
- **Updated documentation:** this file, linked from `F-27a.md`.
- **Manifest changes:** none.
