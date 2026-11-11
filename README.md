# Corpus data: diag-factions110-079bdc2 (F-27a)

Data only, for the failure corpus; no code. Laid out as `claude/corpus-data-1110`:
`runs/diag-factions110-079bdc2/110/{eval-result.json, replays/…, console.txt.gz}` and
`runs/diag-factions110-079bdc2/summary.json`.

- A diagnostic build, not a ladder iteration: main at `079bdc2` (after #166, the stuck-spell capture), scenario
  `goal=nether; mode=factions; ticks=288000` with `--policy bases=true`, strategist off, earned, replays on; seed 110;
  exit 0 with 288001 ticks run. The console is the whole run. Import with `--mode factions`.
- 57 replays: 45 `spell` (one each time the watchdog counted a stuck spell) and 12 `death`, each a `replay.json` and a
  `replay.snbt`.
- `runs/diag-factions110-079bdc2/spell-buckets.tsv` maps each spell replay to its stuck bucket under F-26c's `score.py`
  (seed, player, replay tick, the watchdog's stuck tick, bucket, replay folder); every replay was matched to its spell,
  160 ticks after it (36) or 180 (9). Buckets: hole or pit 35, busy-work loop 4, no walkable route 3, hemmed in 1,
  water loop 1, waiting for daylight or a teammate 1.
- 29 of the 45 spells (25 of them hole or pit) are Kindred's and Sol's at one spot, within 2 blocks of (-35, 63, -14),
  from tick 129540 to 275120. In the kept world that is the surface of a bamboo jungle (ground at y 62), not a hole;
  the goal there is "Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs".
- The kept world (`world-kept/`) is not here; it stays on the runner's container and is read by position on request.
