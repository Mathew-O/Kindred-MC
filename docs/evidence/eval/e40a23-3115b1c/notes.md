# e40a23-3115b1c: runner notes

**Question (lead), stated before the run:** main now has today's fixes, F-25c4dg, F-25c6b, F-22c79, E-08a1 and M-06i2. Does it meet the exit criteria for batch 1 of 2?
- stuck under a tenth of the run on 7 of 10 seeds or more;
- median deaths a game hour of 0.5 or less;
- an iron pickaxe median under 30 minutes;
- a lit portal on 7 of 10 seeds or more.

**Setup:**
- **Build:** main 3115b1c (#338's merge), run 07:32 to 09:36 UTC on 2026-11-15. There are 35 merges since iteration 22's d2389c9.
- **Settings:** iteration 22's command with nothing added: the same ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three at a time, nothing else on the cores.
- **Completion:** 10 of 10 exit 0, 144000 ticks each.
- **Scoring:** this build's score.py (`--no-metrics`), paired with iteration 22 by this build's compare.py in [`compare-e40a23.md`](../compare-e40a23.md).
- **Method:** per E-40's "Reading a batch" note and iteration 22's method note, runs of one build differ from the first animal on. So a per-seed difference is not by itself evidence of a change.

**Answer: no, this is not batch 1 of 2.**

| Exit criterion | e40a22-d2389c9 | e40a23-3115b1c | Met |
| --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) on 7 of 10 | 4 of 10 | 5 of 10 (101, 20261030, 303, 505, 707) | no |
| Deaths a game hour, median, 0.5 or less | 0.75 [0 to 3.0] | 0.75 [0 to 7.0] | no |
| First iron pickaxe, median, under 30 min | 21.55 | 19.55 | yes |
| Portal lit on 7 of 10 | 8 of 10 | 8 of 10 (all but 202, diamond; 707, obsidian) | yes |

| | e40a22-d2389c9 | e40a23-3115b1c |
| --- | --- | --- |
| Stuck game minutes, all seeds | 240 | 108 |
| Stuck spells | 80 | 36 |
| Deaths, all seeds | 20 | 28 (13 of them 101's Sol) |

**Stuck minutes by seed**, e40a23 (e40a22):

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Stuck minutes | 9 (99) | 21 (9) | 0 (9) | 6 (45) | 21 (18) | 12 (12) | 9 (15) | 12 (6) | 3 (9) | 15 (18) |

404 and 606 are exactly a tenth, which is not under.

**Paired by seed against e40a22** (helped means better for e40a23):

| Measure | Helped | Hurt | Median | Sign test |
| --- | --- | --- | --- | --- |
| Stuck minutes | 6 | 3 | −4.5 [−22.5, +3.0] | p=0.508 |
| Deaths an hour | 5 | 3 | −0.25 | p=0.727 |
| Iron pickaxe | 5 | 5 | +1.75 min | p=1.0 |
| First portal | 7 | 2 | −4.15 min | p=0.18 |
| Portal | 1 | 1 | | p=1.0 |

Nothing passes the sign test.

## 101: Sol's respawn loop at its bed, again
- **The sleep:** Sol slept in the bed it carries underground at −377, 31, −143 (07:51:43).
- **The loop:** it then died 12 times between ticks 87,674 and 135,766, about 40 game minutes. All were under cover in the cave around it: zombie 7, skeleton 2, and one each to a slime, a creeper and a witch. 12 respawns were at −376, 31, −145.
- **Armour:** 0 from the second of those deaths on.
- **The same pattern** as e40c4's seed 303 (Kindred, 23 deaths at its bed's spawn underground; with LOOPS). It is now seen in two runs on two builds.
- **Its share:** this one loop is most of seed 101's 7.0 deaths an hour, and 13 of the batch's 28 deaths (its 13th death, at tick 5628, was earlier). Without its 12 deaths, 101 would be at 1.0 an hour (2 deaths), and the batch's median still 0.75.

## Deaths and stuck spells
- **Deaths by cause (28):** zombie 14, skeleton 4, lava 3, drowning 3, and one each to a spider, a slime, a creeper and a witch.
  - By kind (F-26c): dark melee 11, cornered 10, lava 3, drowning 3, other 1.
- **Stuck spells by kind (36):** busy-work loop 13, hole or pit 7, mining for a find 7, hemmed in 4, water loop 3, no walkable route 1, refused target repeated 1.
- **No walkable route: 1, against e40a22's 24** (22 of which were its 101 stall).

## Nights, replays and the console
- **Sleep:** players slept on 5 of 10 seeds (101 7, 202 5, 303 8, 505 3, 606 7), those whose world ran past 6 days.
- **Replays:** 36 spell replays and 24 death replays for the 28 deaths. The largest single capture was 15.0 ms, under one 50 ms tick.
- **Console:** no ERROR line on any seed, and no reconciliation block.
- **The table steps,** across all consoles: "Place a crafting table" 2,675, "Pick up the crafting table" 1,201.
