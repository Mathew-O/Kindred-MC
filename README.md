# e40a21-2354e67 corpus data and stuck-spell analysis (RUNNER 2, 2026-11-12)

E-40 iteration 21, filed in PR #253: ten earned seeds on main `2354e67`, `goal=nether; ticks=144000`.

- `runs/e40a21-2354e67/<seed>/`: `eval-result.json`, `console.txt.gz` and `replays/world/<Player>-<tick>/replay.{json,snbt}`.
  There are 90 replays: 76 spell replays and 14 death replays, one for each of the batch's stuck spells and deaths. They are for LOOPS to take into the
  corpus as #233 took e40a20's.
- `analysis/spells.py <score.py> <run dir> <out.json>`: every stuck spell, bucketed by that build's own
  `score.stuck_kind` over its own 60-line window (this reproduces both summaries' counts exactly). For each spell it records:
  - seed, player, detection tick and start tick (detection − 3600; every watchdog line in both batches says 3600);
  - the player's 10 lines before the start;
  - the number of lines inside the spell, its first step, and its step labels by count.
- `analysis/spells-e40a21.json` and `analysis/spells-e40a20.json`: that output, run on the kept consoles. The e40a20 file was run with 79a9aad's score.py.
  Its stuck rules are identical to 2354e67's: score.py's changes between the builds only add the chunk and day
  counts. So the bucket shift is not a scorer change.

## What the spells show

**Hole or pit, 2 → 12, is one new pattern: the obsidian stage's "Fill a bucket with water".** 9 of the 12 are that
step, every one of them looping between "Tunnel for water: advance / step down" and "Climb toward the surface for water: back the way it came / to
the wall / step up":
- 303 Kindred at 36600 and 40460;
- 31102026 Sol at 97200;
- 505 Kindred at 73300, 99520, 103640 and 121540;
- 606 Sol at 42860;
- 707 Kindred at 47320.

The three others are:
- 202 Sol at 9340: branch mining for iron, climbing toward coal and logs;
- 303 Sol at 64200: dug in for the night, then climbed for logs;
- 303 Kindred at 44380, whose first step is a "dig toward elsewhere" after an earlier water spell.

Counted over all spells, those whose top labels name "Fill a bucket with water" rose from 5 to 16. In e40a21 they are 9 hole or pit,
2 mining for a find, 2 busy-work loop, 1 no walkable route, 1 waiting and 1 water loop.

Planned steps across the ten consoles:

| | e40a20 | e40a21 |
| --- | --- | --- |
| "- Tunnel for water" steps | 2328 | 9574 |
| "- Climb toward the surface for water" steps | 6688 | 9032 |
| Completed "Tunnel for water" walks | 966 | 4612 |
| Completed "Climb toward the surface for water: back the way it came" | 164 | 652 |

"Tunnel for water" steps by seed, e40a20 → e40a21:
- 101: 0 → 1204
- 303: 366 → 373
- 31102026: 1955 → 7274
- 505: 0 → 435
- 707: 0 → 280
- 8675309: 0 → 7
- the other four: 0 or near it in both.

Buckets filled are about the same: 30 → 29 completed "Fill a bucket" steps over the ten seeds. So the water is found, but slowly. The first obsidian median moved from 43.35 to 61.45 min, and the first portal from 46.3 to 65.65.

**What lines up with it:** only one merge since e40a20 removed the water climb and tunnel code from `PortalRunCampaign`: #157
(ec581d54, F-35d step 3 / F-35e / F-35f, "the simulator plans, digs and sees with the game's own core code"). It
removed the under-water climb state there (`underWaterAt`, `underWaterGaveUpAt`, `UNDER_WATER_REACH`, "Water or lava
under every way down") and reads it from a new core `Digger` (888 lines) instead. `git log -S` finds those strings
removed only by #157.

The other suspects touch `PortalRunCampaign` but not that code:
- F-35k (#231): a ledge, "make a way elsewhere";
- F-35l (#234): a thin floor over a drop;
- F-22c65 (#242): the climb for *food* ("back the way it came" is no climb there).

This is one batch against one batch, and the logs show where the steps changed, not why. Whether #157 changed the
water climb's behaviour **can't be told from these logs**. A run of main just before #157 against #157's merge would
settle it, or the simulator's own water scenes.

**Hemmed in, 29 → 19:**
- e40a20's 8675309 alone had 15; e40a21's has none;
- e40a21's 202 has 9 and 707 has 4, all "Iron: no way on from here - Climb to find another way".

**Seeds that got worse:** the dominant spell kind, then the first step of the first spell of that kind:
- 202 (33 → 48 min): hemmed in 9; Kindred at 108140, "Iron: no way on from here - Climb to find another way: to the
  wall". e40a20's 202 was water loop 6.
- 303 (0 → 36): a three-way tie of 4 each.
  - Hole or pit: Kindred at 36600, "Iron: Fill a bucket with water - Climb toward the surface for water: back the way it came".
  - Refused target repeated: Kindred at 63820, "Iron: Cast the portal frame from lava and water - Explore the cave toward lava".
  - Busy-work loop: Sol at 19300, "Iron: Mine iron ore (0/18) - Explore the cave toward iron".
- 505 (9 → 42): a tie of 4 each.
  - Hole or pit: Kindred at 73300, "Iron: Fill a bucket with water - Climb toward water at -69, 62, -74: out from under the water: advance".
  - Busy-work loop: Sol at 55540, "Iron: Cast the portal frame from lava and water - Tunnel past liquid toward lava".
- 707 (12 → 27): hemmed in 4; Kindred at 77580, "Iron: no way on from here - Climb to find another way: clear -89,
  106, -125".

**Seeds that got better:**
- 8675309 (63 → 12): hemmed in 15 → 0.
- 404 (39 → 15): hemmed in 9 → 4.

## The shutdown ERROR

"Kindred shutdown flush incomplete; reconcile on startup" is not new. It appears on 4 of 10 e40a20 consoles (202,
20261030, 31102026, 404), and on 8 of 10 e40a21 consoles (all but 404 and 707). Every time, it comes after the result
was written and the world was saved.

## The #157 pair (added 13:30 UTC)

This is one pair. `6a6d882` is main just before #157, and `ec581d5` is #157's merge. Seeds 505, 31102026 and 303 ran on each side, at `goal=nether; ticks=144000`, three at a time, earned, alone on the cores, 12:00 to 13:23. All six runs are complete at 144,000 ticks.
- The runs are in `runs/p157-before-6a6d882/` and `runs/p157-after-ec581d5/`.
- The measures are in `analysis/p157-measures.txt` (from `analysis/p157measure.py`), with the spell lists in `analysis/spells-p157-{before,after}.json`.
- Each side was scored with its own score.py; the stuck rules are the same as 2354e67's.

| Measure, 505 / 31102026 / 303 | before 6a6d882 | after ec581d5 | e40a20 (79a9aad) | e40a21 (2354e67) |
| --- | --- | --- | --- | --- |
| "- Tunnel for water" steps | 0 / 3119 / 0 | 0 / 1031 / 126 | 0 / 1955 / 366 | 435 / 7274 / 373 |
| Completed "Climb toward the surface for water: back the way it came" | 0 / 39 / 1 | 29 / 62 / 63 | 0 / 23 / 4 | 285 / 101 / 153 |
| Hole-or-pit spells (minutes) | 0 (0) / 2 (6) / 2 (6) | 2 (6) / 8 (24) / 1 (3) | 0 / 0 / 0 | 4 (12) / 1 (3) / 4 (12) |
| Of them, the water-bucket step | 0 / 0 / 0 | 1 / 3 / 1 | 0 / 0 / 0 | 4 / 1 / 2 |
| Spells with the tunnel ↔ climb-for-water cycle | 0 / 0 / 0 | 0 / 2 / 1 | 0 / 0 / 0 | 4 / 1 / 2 |
| Buckets filled | 3 / 2 / 1 | 3 / 2 / 3 | 3 / 2 / 3 | 5 / 1 / 3 |
| First obsidian, min | 38.9 / not reached / 54.0 | 41.7 / 78.5 / 29.2 | 33.1 / 101.5 / 42.2 | 113.6 / 116.4 / 84.0 |

**Verdict, from one pair: #157 changed it.**

What #157 brings in:
- The climb back for water: 40 completed climb-backs before, 154 after.
- The water-bucket hole-or-pit spells: 0 before, 5 after, on every seed.
- The tunnel ↔ climb cycle inside a spell: 0 before, 3 after.

None of these is present before #157, or in e40a20.

What it does not bring in:
- The rise in "Tunnel for water" steps, which falls on 31102026 (3119 → 1031).
- The late obsidian: on this pair obsidian is no later after #157 (it is earlier on 31102026 and 303).

e40a21 has more of all of these than ec581d5, so later merges add to it.

## The second question: why e40a21 is worse than ec581d5 (added 14:50 UTC)

These runs re-measure the same three seeds on #240's merge (`4119b62`) and on `2354e67` (e40a21's own build, here on the three seeds alone). Both use the same settings as the pair, alone on the cores, 13:28 to 14:39, with all six runs complete at 144,000 ticks. They are in `runs/p240-4119b62/` and `runs/p242-2354e67-3seed/`, with the measures in `analysis/q2-measures.txt` and `analysis/q2-extra.txt`.

| 505 / 31102026 / 303 | Climb-back for water completed | Water-bucket hole-or-pit | Cycle spells | "Tunnel for water" steps | First obsidian, min | Mean tick, ms |
| --- | --- | --- | --- | --- | --- | --- |
| 6a6d882 (before #157) | 0 / 39 / 1 (40) | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 3119 / 0 | 38.9 / not reached / 54.0 | 15.8 / 14.1 / 15.4 |
| ec581d5 (#157) | 29 / 62 / 63 (154) | 1 / 3 / 1 | 0 / 2 / 1 | 0 / 1031 / 126 | 41.7 / 78.5 / 29.2 | 12.0 / 13.2 / 16.9 |
| 4119b62 (#240) | 0 / 91 / 96 (187) | 0 / 1 / 2 | 0 / 1 / 0 | 0 / 3216 / 112 | 29.1 / 119.0 / 48.1 | 10.6 / 13.2 / 12.7 |
| 2354e67, three seeds | 4 / 1 / 79 (84) | 0 / 0 / 1 | 0 / 0 / 1 | 14 / 74 / 1043 | 73.3 / not reached / not reached | 13.9 / 13.1 / 14.9 |
| 2354e67, e40a21's batch | 285 / 101 / 153 (539) | 4 / 1 / 2 | 4 / 1 / 2 | 435 / 7274 / 373 | 113.6 / 116.4 / 84.0 | 12.2 / 12.8 / 17.6 |

- **e40a21 is not reproduced by its own build.** The three-seed rerun of 2354e67 has 84 climb-backs against 539, and 1 water-bucket spell against 7. Its obsidian is not better either: two seeds never reach it.
  - So the e40a21-against-ec581d5 gap is not #240 or #242 shown. It is the spread between runs of one build on one seed.
  - Mean tick is alike in every run (10.6 to 17.6 ms), so the gap is not load.
  - These runs cannot tell e40a21's ten-seed batch conditions from plain run-to-run nondeterminism. Both run three at a time, and the same seed and jar give different games.
- **The #157 verdict above is withdrawn.** The same-build spread (84 to 539 climb-backs) is larger than the pair's difference (40 to 154). What still holds is narrower:
  - The water-bucket hole-or-pit spells and the tunnel ↔ climb cycle are 0 on the one pre-#157 run, and on e40a20. They are present on every post-#157 run: 5, 3, 1 and 7 water-bucket spells.
  - Climb-back is 40 before and 84 to 539 after.
  - That is consistent with #157, not shown. Settling it needs repeats on each side.
- **For E-40:** one run a side on these seeds cannot resolve a change of this size.
