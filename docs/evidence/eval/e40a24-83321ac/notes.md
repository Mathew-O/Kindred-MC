# e40a24-83321ac: runner notes

**Question (lead), stated before the run:** on current main, with replays on so the waking-scene cutter (F-27a4)
records wakings, does main meet the exit criteria for batch 1 of 2?
- stuck under a tenth of the run on 7 of 10 seeds or more;
- median deaths a game hour of 0.5 or less;
- an iron pickaxe median under 30 minutes;
- a lit portal on 7 of 10 seeds or more.

**Setup:**
- **Build:** main 83321ac (#377's merge), run 01:34 to 03:31 UTC on 2026-11-16. 30 merges since iteration 23's
  3115b1c. #355 (the bed fixes F-25c4di1a to 1d) had not merged, so it is not in this build.
- **Settings:** iteration 22's command: the same ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three
  at a time, nothing else on the cores. The scenario line is identical to iterations 22's and 23's.
- **Added:** replays on (the eval default, as in 22 and 23), and `--keep-world`, with run.py from #376's branch so each
  kept world has its `level.dat`. That run.py differs from main's only in what `--keep-world` keeps.
- **Completion:** 10 of 10 exit 0, 144000 ticks each.
- **Scoring:** score.py and compare.py from main 711f5a6 (`--no-metrics`), against iterations 22 and 23 in
  [`compare-e40a24.md`](../compare-e40a24.md). The exit figures below were recomputed from the summaries by the same
  rule as iterations 22 and 23; that recomputation gives their filed figures exactly (4 and 5 of 10, 0.75 and 0.75,
  21.55 and 19.55, 8 and 8).

**Answer: no, this is not batch 1 of 2.**

| Exit criterion | Threshold | e40a22-d2389c9 | e40a23-3115b1c | e40a24-83321ac | Met |
| --- | --- | --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) | 7 of 10 | 4 of 10 | 5 of 10 | **1 of 10** (303) | no |
| Deaths a game hour, median | 0.5 or less | 0.75 [0 to 3.0] | 0.75 [0 to 7.0] | **1.25** [0 to 3.0] | no |
| First iron pickaxe, median | under 30 min | 21.55 | 19.55 | 24.95 (10 of 10) | yes |
| Portal lit | 7 of 10 | 8 of 10 | 8 of 10 | 8 of 10 (all but 101, 8675309) | yes |

| | e40a22-d2389c9 | e40a23-3115b1c | e40a24-83321ac |
| --- | --- | --- | --- |
| Stuck game minutes, all seeds | 240 | 108 | 249 |
| Stuck spells | 80 | 36 | 83 |
| Deaths, all seeds | 20 | 28 (13 of them 101's Sol) | 21 |

**Stuck minutes by seed**, e40a24 (e40a23; e40a22):

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Stuck minutes | 18 (9; 99) | 33 (21; 9) | 24 (0; 9) | 9 (6; 45) | 24 (21; 18) | 21 (12; 12) | 24 (9; 15) | 21 (12; 6) | 36 (3; 9) | 39 (15; 18) |

**Paired by seed** (helped means better for e40a24):

| Measure | Against | Helped | Hurt | Median difference [95%] | Sign test |
| --- | --- | --- | --- | --- | --- |
| Stuck minutes | e40a23 | 0 | **10** | **+10.5 [+6.0, +24.0]** | **p=0.002** |
| Stuck minutes | e40a22 | 2 | 8 | +12.0 [−13.5, +21.0] | p=0.109 |
| Deaths an hour | e40a23 | 3 | 2 | 0.0 [−0.75, +0.75] | p=1.0 |
| Deaths an hour | e40a22 | 3 | 3 | 0.0 [−0.5, +0.5] | p=1.0 |
| Iron pickaxe | e40a23 | 5 | 5 | +2.05 min | p=1.0 |
| First portal | e40a23 | 2 | 8 | +9.55 min | p=0.109 |

## Stuck: worse than iteration 23 on every seed
- **The one sign test that passes:** stuck minutes against e40a23, worse on all 10 seeds (p=0.002), the interval wholly
  above zero. Per E-40's "Reading a batch" note, runs of one build differ from the first animal on, so one seed's
  difference is not evidence. But a change in the same direction on 10 of 10 is past that noise.
- **Stuck spells by kind (83, against e40a23's 36):** busy-work loop 30 (13), hole or pit 24 (7), hemmed in 15 (4),
  water loop 7 (3), refused target repeated 5 (1), mining for a find 2 (7), no walkable route 0 (1).
- **Not separated:** which of the 30 merges since 3115b1c moved it. That needs a bisect, the lead's call.

## Deaths
- **Deaths by cause (21):** skeleton 6, zombie 5, drowning 3, lava 2, creeper 2, and one each to suffocation, a fall
  and a drowned.
  - By kind (F-26c): cornered 10, drowning 3, lava 2, other 3, and one each dark melee, fall and starving with a mob
    near.
- **No respawn loop:** e40a23's 12 deaths at Sol's bed on 101 did not recur; 101 had 3 deaths.

## Waking scenes (F-27a4), for LOOPS
- **21 wakings,** one for each death. All passed their capture ("alive at the end of 2400 ticks").
- **The death inside the box:** 3 of 21. Two hold the player's whole dropped inventory, 19 ticks old, in reach:
  707 Sol-143252 (iron chestplate, shield, tools, cobblestone 631 and more) and 8675309 Sol-61008 (iron pickaxe, iron
  chestplate, shield, cobblestone 899 and more). The third, 707 Kindred-47599, has a few drops.
- **The rest:** 18 with the death outside the box; 2 of them have older drops in the box that are not their own.
- **Where:** `claude/corpus-data-e40a24-1116@3c00679` (results, consoles and the 21 waking replays; its README has
  them all). Sent to LOOPS for import.

## Nights, replays and the console
- **Replays:** 83 spell replays, 19 death replays for 21 deaths, and 21 waking replays. The largest single capture
  was 17.1 ms, under one 50 ms tick.
- **Console:** no ERROR line on any seed, and no reconciliation line.
- **The table steps,** across all consoles: "Place a crafting table" 2,568, "Pick up the crafting table" 1,118.
- **Kept worlds:** each seed's, with `level.dat`, on the runner's container (712 MB for the batch), not in the
  repository.
