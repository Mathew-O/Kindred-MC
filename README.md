# Corpus data: e40a20-79a9aad (F-27a)

Data only, for the failure corpus; no code. Laid out as `claude/corpus-data-1110`:
`runs/e40a20-79a9aad/<seed>/{eval-result.json, replays/…, console.txt.gz}` and `runs/e40a20-79a9aad/summary.json`.

- Iteration 20 of E-40's ladder: main at `79a9aad` (after #198, the goal's memory in spell replays), scenario
  `goal=nether; ticks=144000`, strategist off, earned, replays on, the ten development seeds; all exit 0 with 144000
  ticks run. 8675309's console and replays are from its second run (the first was cut off by a container restart and
  is not here). 303 had no stuck spell and no death, so it has no `replays/`.
- 89 replays: 70 `spell` (one each time the watchdog counted a stuck spell) and 19 `death`, each a `replay.json`
  (with `memory`) and a `replay.snbt`.
- `runs/e40a20-79a9aad/spell-buckets.tsv` maps each spell replay to its stuck bucket under F-26c's `score.py`
  (seed, player, replay tick, the watchdog's stuck tick, its x, y, z, bucket, replay folder); every replay was matched
  to its spell, 160 to 180 ticks after it. Buckets: hemmed in 29, busy-work loop 17, water loop 8, no walkable route 7,
  mining for a find 6, hole or pit 2, waiting for daylight or a teammate 1.
- `runs/e40a19-6957e28/spell-buckets.tsv` is iteration 19's 85 spells by the same rules (seed, player, stuck tick,
  x, y, z, bucket), for comparison. Iteration 19's build (6957e28) predates #166, so it has no spell replays; its
  buckets come from its consoles. Buckets: busy-work loop 23, hole or pit 21, hemmed in 16, no walkable route 11,
  mining for a find 8, water loop 5, refused target repeated 1.
