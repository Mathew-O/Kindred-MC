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

## Console around one spell at the spot: Sol, stuck tick 129540

Every Kindred or Sol line, and the watchdog's, from 09:30:59 to 09:31:03 by the console's clock (83 lines). The
watchdog line for this spell is at 09:31:01 (console line 16004). Between "thinking at tick 132000" (09:31:45) and
"thinking at tick 144000" (09:33:42) the run made about 103 ticks a second, so this is about ±200 ticks around the
spell. Console lines carry no tick, so the window is placed by clock time. Kindred is not at the spot yet (its first
spell there is at tick 191440).

```
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol goal at -35 63 -13: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:30:59] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
[09:30:59] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Sol goal at -35 63 -13: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall
[09:31:00] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Sol goal at -35 63 -13: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:00] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:31:00] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Sol goal at -35 63 -15: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall
[09:31:01] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Goal made no progress for 3600 ticks near -35, 63, -14; trying -11, 63, -14 (attempt 1)
[09:31:01] Sol goal at -35 63 -14: Wood: no progress here for a while - try elsewhere, toward -11, 63, -14
[09:31:01] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:01] Sol spell replay captured in 0.8 ms on the server thread (0.8 ms of its time)
[09:31:01] [kindred-replays/INFO]: Sol death replay written to kindred/replays/world/Sol-129700 (698 KB)
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol native_goto COMPLETED [try elsewhere, toward -11, 63, -14]
[09:31:02] Sol goal at -34 63 -13: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall (tried elsewhere 1 time)
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol goal at -35 63 -15: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came (tried elsewhere 1 time)
[09:31:02] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall (tried elsewhere 1 time)
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:02] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came (tried elsewhere 1 time)
[09:31:02] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Sol goal at -35 63 -13: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall (tried elsewhere 1 time)
[09:31:03] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:31:03] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Sol goal at -35 63 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came (tried elsewhere 1 time)
[09:31:03] Kindred native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Sol native_goto COMPLETED [Climb toward the surface for logs: to the wall]
[09:31:03] Kindred goal at 29 80 -15: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
[09:31:03] Kindred goal at 28 80 -15: Wood: Gather logs for a table and a wooden pickaxe - Cut through east for logs: advance
[09:31:03] Kindred goal at 27 80 -15: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
[09:31:03] Sol goal at -35 63 -13: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: to the wall (tried elsewhere 1 time)
[09:31:03] Kindred goal at 26 80 -15: Wood: Gather logs for a table and a wooden pickaxe - Cut through east for logs: advance
[09:31:03] Sol native_goto COMPLETED [Climb toward the surface for logs: back the way it came]
[09:31:03] Kindred goal at 25 80 -14: Wood: Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs: back the way it came
```
