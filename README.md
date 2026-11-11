# Corpus data: diag-factions303-079bdc2 (F-27a)

Data only, for the failure corpus; no code. Laid out as `claude/corpus-data-1110`:
`runs/diag-factions303-079bdc2/303/{eval-result.json, replays/…, console.txt.gz}` and
`runs/diag-factions303-079bdc2/summary.json`.

- A diagnostic build, not a ladder iteration: main at `079bdc2` (after #166, the stuck-spell capture), scenario
  `goal=nether; mode=factions; ticks=144000` with `--policy bases=true`, strategist off, earned, replays on; seed 303;
  exit 0 with 144074 ticks run. The console is the whole run. Import with `--mode factions`.
- 23 replays: 21 `spell` (one each time the watchdog counted a stuck spell) and 2 `death`, each a `replay.json` and a
  `replay.snbt`.
- `runs/diag-factions303-079bdc2/spell-buckets.tsv` maps each spell replay to its stuck bucket under F-26c's `score.py`
  (seed, player, replay tick, the watchdog's stuck tick, bucket, replay folder); every replay was matched to its spell,
  160 ticks after it. Buckets: hole or pit 8 (Kindred-27500, -31220, -35080, -107560; Sol-27680, -99140, -102920,
  -106640), no walkable route 4, hemmed in 3, water loop 2, busy-work loop 2, refused target repeated 1, mining for a
  find 1.
- The kept world (`world-kept/`) is not here; it stays on the runner's container and is read by position on request.
