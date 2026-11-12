# diag-water-157: the water-bucket loop, #157 and the same build again (RUNNER 2, 2026-11-12)

- **Question (lead):** e40a21's hole-or-pit spells rose from 2 to 12, and 9 of the 12 are the obsidian stage's "Fill a bucket with water" step, looping "Tunnel for water" ↔ "Climb toward the surface for water: back the way it came". The lead asked two things:
  1. Did #157 (F-35d step 3 / F-35e / F-35f, which moved the water climb and tunnel code into core's `Digger`) bring this loop in?
  2. Why is e40a21 so much worse than #157's merge on the same seeds?
- **Runs:** seeds 505, 31102026 and 303, `goal=nether; ticks=144000`, three at a time, earned, alone on the cores. All 12 runs are complete at 144,000 ticks.
  - `p157-before-6a6d882`: main just before #157 (the first parent of `ec581d5`).
  - `p157-after-ec581d5`: #157's merge.
  - `p240-4119b62`: #240's merge.
  - `p242-2354e67-3seed`: e40a21's own build, on these three seeds alone.

  Between `ec581d5` and `2354e67`, only #240 (7 files) and #242 (1 file) change `src/main`; #244 changes none.
- **Scoring:** each run with its own build's score.py (`--no-metrics`). The stuck rules are identical in every build here.
- **Counting:** step counts are console lines. "Tunnel for water" steps are goal lines containing " - Tunnel for water". Climb-backs are `COMPLETED [Climb toward the surface for water: back the way it came` lines. A cycle spell is one whose five commonest labels include both "Tunnel for water" and "Climb toward the surface for water". Buckets are `native_bucket COMPLETED [Fill a bucket` lines. Obsidian is the team's first.
- **Data:** the consoles and per-spell lists are on the data branch `claude/corpus-data-e40a21-1112`.

| 505 / 31102026 / 303 | Climb-back for water | Water-bucket hole-or-pit | Cycle spells | "Tunnel for water" steps | Buckets filled | First obsidian, min | Mean tick, ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6a6d882 (before #157) | 0 / 39 / 1 (40) | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 3119 / 0 | 3 / 2 / 1 | 38.9 / not reached / 54.0 | 15.8 / 14.1 / 15.4 |
| ec581d5 (#157) | 29 / 62 / 63 (154) | 1 / 3 / 1 | 0 / 2 / 1 | 0 / 1031 / 126 | 3 / 2 / 3 | 41.7 / 78.5 / 29.2 | 12.0 / 13.2 / 16.9 |
| 4119b62 (#240) | 0 / 91 / 96 (187) | 0 / 1 / 2 | 0 / 1 / 0 | 0 / 3216 / 112 | 2 / 2 / 2 | 29.1 / 119.0 / 48.1 | 10.6 / 13.2 / 12.7 |
| 2354e67, these three seeds | 4 / 1 / 79 (84) | 0 / 0 / 1 | 0 / 0 / 1 | 14 / 74 / 1043 | 4 / 1 / 3 | 73.3 / not reached / not reached | 13.9 / 13.1 / 14.9 |
| 2354e67, in e40a21's batch | 285 / 101 / 153 (539) | 4 / 1 / 2 | 4 / 1 / 2 | 435 / 7274 / 373 | 5 / 1 / 3 | 113.6 / 116.4 / 84.0 | 12.2 / 12.8 / 17.6 |
| 79a9aad, in e40a20's batch | 0 / 23 / 4 (27) | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 1955 / 366 | 3 / 2 / 3 | 33.1 / 101.5 / 42.2 | - |

All seeds' spells (of them hole or pit): 6a6d882 22 (4), ec581d5 31 (11), 4119b62 26 (8), 2354e67 three-seed 33 (4), e40a21 30 (9).

**Answers, each from one run a side:**

1. **e40a21 is not reproduced by its own build.** Run again on the three seeds, 2354e67 has 84 climb-backs against e40a21's 539, and 1 water-bucket spell against 7. Its obsidian is no better: two of the three seeds never reach it.
   - So the gap between e40a21 and ec581d5 is not shown to be #240 or #242. It is within the spread between runs of one build on one seed.
   - Mean tick is alike in every run, so the gap is not load.
   - These runs cannot tell e40a21's ten-seed batch conditions from plain run-to-run nondeterminism. Both run three at a time, and the same seed and jar give different games.
2. **#157: consistent with it, not shown.** The same-build spread (84 to 539 climb-backs) is larger than the pair's difference (40 to 154), so the pair alone does not show that #157 changed it. What holds:
   - The water-bucket hole-or-pit spells and the cycle are absent on the one pre-#157 run and in e40a20.
   - They are present on every post-#157 run: 5, 3, 1 and 7 water-bucket spells.
   - Settling it needs repeats on each side, for example three runs a side of 6a6d882 and ec581d5 on these seeds.
3. **What is not #157's:** the rise in "Tunnel for water" steps (it falls on 31102026 across #157) and the late obsidian (no later after #157 on this pair).
4. **For E-40:** on these seeds, one run a side cannot resolve a change of this size. Per-seed stuck and obsidian differences between batches are within the same-build spread.
