# F-27a: e40a23's seed-101 Sol respawn loop joins the corpus

Date: 2026-11-15
Agent / branch: Claude RUNNER 2 / claude/corpus-e40a23-1115
Status: complete (a scene-only part of F-27a)

This is a scene-only addition, as with #233, #311 and the e40c4 scenes: 10 scenes and their baseline entries, with no
code change.

**What is added** (from E-40 iteration 23, `e40a23-3115b1c`: main 3115b1c, ten seeds, `goal=nether; ticks=144000`,
earned; its [notes](eval/e40a23-3115b1c/notes.md)):
- **The loop:** seed 101 Sol slept in the bed it carries underground at −377, 31, −143. It then died 12 times between
  ticks 87,674 and 135,766, under cover in the cave around it, and respawned at −376, 31, −145 each time.
- **The scenes:** the 10 replays the harness took in that window, 9 deaths and 1 stuck spell. The other 3 deaths
  have no replay.
  - Imported from `claude/corpus-data-e40a23-1115@a8f8884` with `tools/corpus/import.py --keep-structures`.
  - The spell's class is score.py's F-26c bucket for it (its watchdog line at tick 120,360).
- **The same pattern** as e40c4's seed-303 Kindred loop ([its scenes](F-27a-e40c4-scenes.md)), now seen on two
  builds.

**Six runs of only these 10** (`KINDRED_CORPUS_ONLY`):
- **Setup:** alone on a 4-core container, `runCorpus --max-workers=2`, every run from an empty corpus world, on
  990d35c (main 399aeb7 with the scenes).
- **Failed per run:** 7, 6, 7, 8, 6, 7.
- **Replay players still acting after their own batch:** 0 in every run. Every spacer passed 6 of 6.
- **Results:** 1 pass, 6 intermittent, 3 fail.

| Scene | Kind | Class | Passed of 6 | Baseline |
| --- | --- | --- | --- | --- |
| `e40a23-3115b1c-101-sol-87674` | death | death: slime | 5 | intermittent |
| `e40a23-3115b1c-101-sol-90411` | death | death: creeper | 1 | intermittent |
| `e40a23-3115b1c-101-sol-96652` | death | death: zombie | 5 | intermittent |
| `e40a23-3115b1c-101-sol-110399` | death | death: zombie | 1 | intermittent |
| `e40a23-3115b1c-101-sol-113884` | death | death: zombie | 1 | intermittent |
| `e40a23-3115b1c-101-sol-116233` | death | death: zombie | 1 | intermittent |
| `e40a23-3115b1c-101-sol-120520` | spell | stuck: hole or pit | 0 | fail |
| `e40a23-3115b1c-101-sol-132003` | death | death: skeleton | 5 | intermittent |
| `e40a23-3115b1c-101-sol-133523` | death | death: witch | 0 | fail |
| `e40a23-3115b1c-101-sol-135766` | death | death: zombie | 0 | fail |

**Baseline:** `tools/corpus/baseline.json` keeps its entries as they were and gains these 10: 468 in all when filed; 471 with #381's waking scene and #386's two merged in. Its
`notes` say which runs made them.

**Who works on them:** LOOPS has the loop. The console excerpts are on the data branch; there is no kept world, since iteration 23 ran without `--keep-world`.

## Validation
- `python3 -m unittest discover -s tools/corpus`: OK, with the 471 scenes.
- `support.py tracker`: in order.
- No runtime claims beyond the six runs above.

## Remaining limitations and handoff
- **Not tested:** these entries come from runs of the 10 alone. In a full run they share batches with other scenes,
  and the next full re-baseline records them with the rest.
- **Exact next action:** none for these scenes.
- **Updated documentation:** this file, linked from `F-27a.md`.
- **Manifest changes:** none.
