# Data: e40a25s-0d3432b (iteration 24's bisect, step 8), for LOOPS

Main 0d3432b, one jar for both arms (sha256 286527a9…), ten seeds, `goal=nether; ticks=144000`, earned, strategist off,
three at a time; 2026-11-21 13:16 to 19:08 UTC, arms alternated by half-batch. 20 of 20 exit 0, 144,000 ticks each.

- `e40a25s-0d3432b-on/`: arm A, replays on (the default); 74 replay captures.
- `e40a25s-0d3432b-off/`: arm B, `KINDRED_REPLAYS=off`; no capture.
- Each `<seed>/`: `eval-result.json` and `console.txt.gz`. Each arm: `build.json`, `summary.json`, `summary.md`
  (score.py, `--no-metrics`).

| Arm | Stuck total | Hemmed in | Hole or pit | Busy-work loop |
| --- | --- | --- | --- | --- |
| A, replays on | 162 | 9 | 5 | 16 |
| B, replays off | 267 | 23 | 12 | 18 |

The consoles were checked before pushing: no key value and nothing secret-looking.
