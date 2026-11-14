# F-27a — The 422-scene re-baseline on the isolated, fresh-world harness

Date: 2026-11-12
Agent / branch: claude/rebaseline-422-1112
Build: the runs on 06f6cb4 (main 4ac2f3e with #258); this commit changes only the baseline and this file
Status: in progress (part of F-27a; linked from F-27a.md once merged)

`tools/corpus/baseline.json` is replaced. The 332 scenes of #215 came from runs in grown worlds on 811b0f7, and the 90 of #233 from runs of those scenes alone. All 422 now come from six full runs on one tree, each from an empty world, with the spacers and per-scene names.

## The runs
- **Tree:** 06f6cb4, which is main 4ac2f3e with #258 (replay names), now in main's history as 5aeb1ed. It has the harness of #228 (empty world each run), #241 (spacers) and #258.
- **How:** six full `runCorpus --max-workers=2` runs, alone on a 4-core container, one after another, each from an empty corpus world.
- **Results:** each run's xml and summary are on `claude/spacer-runs-1112` under `rebase422/`. The logs are kept locally.

| Run | Time | Peak (game JVM VmHWM) | Failed of 422 | Players after their batch |
| --- | --- | --- | --- | --- |
| run-1 | 45 min | 2775 MB | 120 | 0 |
| run-2 | 43 min | 3463 MB | 121 | 0 |
| run-3 | 45 min | 2777 MB | 119 | 0 |
| run-4 | 44 min | 3535 MB | 123 | 0 |
| run-5 | 43 min | 3273 MB | 121 | 0 |
| run-6 | 44 min | 3462 MB | 108 | 0 |

- **Peak memory:** 2775–3535 MB, against 2623 MB on main before the spacers (#241). It is recorded so that a later memory regression shows.
- **Players acting after their own batch:** 0 in every run. Before #258 it was 1–2 a run, because two pairs of scenes shared a replay player's name.

## The rates
| Scene set | Harness | Pass all runs | Intermittent | Fail |
| --- | --- | --- | --- | --- |
| #215's 332 | grown worlds, no spacers (811b0f7, 6 runs) | 165 (49.7%) | 109 | 58 |
| the same 332 | fresh worlds, spacers, own names (06f6cb4, 6 runs) | 195 (58.7%) | 95 | 42 |
| all 422 | fresh worlds, spacers, own names (06f6cb4, 6 runs) | 239 (56.6%) | 116 | 67 |

- **Per-run passes:** 302, 301, 303, 299, 301 and 314 of 422; on the 332, 249, 245, 251, 244, 246 and 258.
- **The 332's moves, old class to new:**
  - pass → pass: 150
  - intermittent → pass: 32
  - fail → pass: 13
  - pass → intermittent: 15
  - fail → intermittent: 10
  - intermittent → intermittent: 70
  - intermittent → fail: 7
  - fail → fail: 35
- **This is a harness shift, not a change in play.** The tree differs from 811b0f7 too, so the two tell apart only together.

## Against the replaced baseline
`report.py` on these six runs, against the baseline it replaces, flags 13 scenes that passed there and fail in 2 or more of the 6 here:
- `diag-factions303-079bdc2-303-kindred-84180`
- `diag-holepit-079bdc2-31102026-echo-108000`
- `e40a13-fe2ba3b-707-sol-64680`
- `e40a14-80b7150-8675309-kindred-128400`
- `e40a15-dbf4e5c-8675309-echo-87839`
- `e40a16-922f4fd-101-echo-18586`
- `e40a16-922f4fd-202-echo-55002`
- `e40a17-7bc5b42-707-sol-48263`
- `e40a20-79a9aad-101-sol-107500`
- `e40a20-79a9aad-202-echo-110820`
- `e40a20-79a9aad-31102026-echo-106260`
- `e40a20-79a9aad-8675309-kindred-94280`
- `f29e-on-891567637-kindred-123297`

As a re-baseline these are recorded, not confirmed: the new baseline holds them as they ran here.

## Main since the baseline tree
Main has since added:
- #219: the knowledge cap, a body change;
- #256: F-35m, far-column sight, body behaviour;
- #257: F-31d;
- docs.

Gates on branches that include them compare against this tree until the next re-baseline, and say so.
