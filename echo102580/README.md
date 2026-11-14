# e40a20-79a9aad-31102026-echo-102580: why it fails on dc5ad4d (RUNNER 2, 2026-11-14)

Runs: each alone (`KINDRED_CORPUS_ONLY=<id>`), empty corpus world, `--max-workers=2`, nothing else on the cores.

| Build | Alone | Other |
| --- | --- | --- |
| 06f6cb4 (baseline sha) | 6/6 pass | baseline full runs 6/6 |
| 5cfe873 (#219) | 3/3 | |
| 0ed915a (#256, F-35m seen columns) | 0/3 | |
| 5aeb1ed (#258) | 0/3 | |
| 3196401 (#267) | 1/3 | |
| 14975f8 (#276) | 1/3 | |
| dc5ad4d (#270) | 0/3 | its full-run batch of ten 0/3; full runs 0/3; #280's five flagged scenes together 3/3 |

`xml/` holds every result; `steps/` each run's goal and action lines with scene-relative coordinates (`tools/steps.py`).
