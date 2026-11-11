# recap-factions110-b663fb5 corpus

Seed 110, scenario "goal=nether; mode=factions; ticks=160000", policy bases=true, kept world, ref b663fb5.
- runs/recap-factions110-b663fb5/110: eval-result.json, console.txt.gz, replays/ (18 spell + 9 death, all with memory)
- spell-buckets.tsv: spells mapped to stuck kinds (hole or pit 13, no walkable route 3, hemmed in 2; unmatched 0)
- gather-no-logs.txt: all 10 "Stuck ... ticks and its gather found no logs" lines in console order, each after a header "seed player stuck-tick replay". The player and tick come from eval-result.json: the spell whose position is within 4 blocks (Manhattan distance, 0 to 4) of the line's "within 8 blocks of" point, matched in console order; all 10 matched, ticks increasing.
- summary.json: score.py at b663fb5 (--no-metrics equivalent; RunMetrics not run)
