# F-27a: seed 101's first waking scene joins the corpus

Date: 2026-11-16
Agent / branch: Claude RUNNER 2 / claude/corpus-wake101-1116
Status: complete (a scene-only part of F-27a)

This is a scene-only addition, as with #370: 1 scene and its baseline entry, with no code change.

**What is added** (from `wake101-3360ef2`: main 3360ef2, with F-27a4's waking-scene cutter (#375), seed 101,
`goal=nether; ticks=144000`, earned, strategist off, replays on; 2026-11-16, exit 0, 144,000 ticks):
- **The waking:** Sol died once, to a spider at −384, 29, −131 (tick 117,908, by day, under cover, hunger 2,
  armour 6). It respawned at −277, 71, −120, the world spawn, since it had not slept yet. Its first bed came after.
- **The scene:** `wake101-3360ef2-101-sol-117928`, kind `respawn`, box 33 × 17 × 33 round the respawn, its death
  107 blocks off and outside the box (`deathInside: false`), `drops: []`. At the cut: health 12.1, food 2, task
  "waking".
  - Imported from `claude/corpus-data-wake101-1116@154c096` with `tools/corpus/import.py --keep-structures`.
  - Its class is import.py's: "waking: its death outside the box".
- **No respawn loop this time.** e40a23's 12 deaths at Sol's bed did not recur: runs of one build differ from the
  first animal on, and this run was on a later build (3360ef2 against 3115b1c).

**What it measures, and what it does not:** survival on waking at the world spawn, hungry (food 2) and hurt. It
cannot measure drop recovery (F-25c4di1f): its death is 107 blocks off and no drops are in its box. Do not count it as
1f's measure.

**Six runs of only this scene** (`KINDRED_CORPUS_ONLY`):
- **Setup:** alone on a 4-core container, `runCorpus --max-workers=2`, every run from an empty corpus world, on
  a919e5b (main 3360ef2 with the scene).
  - A first attempt failed to start on a full disk (no test ran); space was freed and all six were run again.
- **Failed per run:** 0, 0, 0, 0, 0, 0. **Result:** pass, 6 of 6 ("alive at the end of 2400 ticks").
- **Its armour back on at the budget's end** (reported, not the pass): no in every run, 0 of 4 armour slots.
- **Replay players still acting after their own batch:** 0 in every run. Its spacer passed 6 of 6.

| Scene | Kind | Class | Passed of 6 | Armour back on | Baseline |
| --- | --- | --- | --- | --- | --- |
| `wake101-3360ef2-101-sol-117928` | respawn | waking: its death outside the box | 6 | 0 of 6 | pass |

**Baseline:** `tools/corpus/baseline.json` keeps its entries as they were and gains this one. Its `notes` say which
runs made it.

**Who works on it:** LOOPS. The run's console, result and all five replays are on the data branch.

## Validation
- `python3 -m unittest discover -s tools/corpus`: OK.
- `support.py tracker`: in order.
- No runtime claims beyond the six runs above.

## Remaining limitations and handoff
- **Not tested:** the entry comes from runs of the scene alone. In a full run it shares a batch with other scenes,
  and the next full re-baseline records it with the rest.
- **Exact next action:** none for this scene. Wakings with drops in reach are to come from E-40 iteration 23 with
  replays on.
- **Updated documentation:** this file, linked from `F-27a.md`.
- **Manifest changes:** none.
