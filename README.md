# Corpus data: diag-holepit-079bdc2 (F-27a)

Data only, for the failure corpus; no code. Laid out as `claude/corpus-data-1110`:
`runs/diag-holepit-079bdc2/<seed>/{eval-result.json, replays/…, console.txt.gz}` and
`runs/diag-holepit-079bdc2/summary.json`.

- A diagnostic build, not a ladder iteration: main at `079bdc2` (after #166, the stuck-spell capture), scenario
  `goal=nether; ticks=144000`, strategist off, earned, replays on; the five seeds where e40a18 had its hole-or-pit
  spells (202, 31102026, 404, 606, 8675309); all exit 0 with 144000 ticks run. Each seed's console is its whole run.
- 34 replays: 24 `spell` (one each time the watchdog counted a stuck spell) and 10 `death`, each a `replay.json` and a
  `replay.snbt`.
- `runs/diag-holepit-079bdc2/spell-buckets.tsv` maps each spell replay to its stuck bucket under F-26c's `score.py`
  (seed, player, replay tick, the watchdog's stuck tick, bucket, replay folder). Every replay was matched to its spell,
  160 to 180 ticks after it. Buckets: mining for a find 11, busy-work loop 9, hole or pit 2 (31102026 Echo-11660,
  606 Sol-52260), no walkable route 1, waiting for daylight or a teammate 1.
- The kept worlds (`world-kept/`) are not here; they stay on the runner's container and are read by position on request.
