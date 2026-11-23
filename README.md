# e40c5 consoles (data only)

Rung c partial probe `e40c5-b6f2d4b` (main b6f2d4ba, without #492; evidence in #504): whole server consoles, gzipped, for the six seeds run.

- `e40c5-b6f2d4b/<seed>/console.txt.gz` and `eval-result.json` for 101, 202, 20261030, 31102026, 404 (complete, 288000 ticks).
- `e40c5-b6f2d4b/303/console.txt.gz`: cut at about tick 240000 by the runner session's background limit; no eval-result was written.
- `summary.json`, `summary.md`: score.py (`--no-metrics`) over the five complete seeds.
