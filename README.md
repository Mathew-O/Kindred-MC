# Corpus data: e40c2-d640518 (F-27a)

Data only, for the failure corpus; no code. Laid out as `claude/corpus-data-1110`:
`runs/e40c2-d640518/<seed>/{eval-result.json, replays/…, console.txt.gz}` and `runs/e40c2-d640518/summary.json`.

- Build d640518 (the head of claude/cast-outcome-1109), scenario `goal=dragon; ticks=288000`, strategist off, the ten
  seeds of `tools/eval/seeds-10.txt`, all exit 0 with 288000 ticks run. Each seed's console is its whole run.
- The kept worlds (`world-kept/`) are not here; they stay on the runner's container and are read by position on request.
