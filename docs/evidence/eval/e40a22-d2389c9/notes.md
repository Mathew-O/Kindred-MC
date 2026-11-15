# e40a22-d2389c9: runner notes

**Question (lead), stated before the run:** with the table loop gone (#274, F-25c7a/b; 18 of e40a21's 76 stuck spells by LOOPS's count), does stuck under a tenth of the run reach 7 of 10 seeds? And does the batch hold the other three exit criteria:
- median deaths a game hour of 0.5 or less;
- an iron pickaxe median under 30 minutes;
- a lit portal on 7 of 10 seeds or more?

If all four hold, this is batch 1 of 2.

**Setup:**
- **Build:** d2389c9 (main, #274's merge), run 16:35 to 18:44 UTC on 2026-11-14.
- **Settings:** iteration 21's command with nothing added: the same ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three at a time, nothing else on the cores.
- **Completion:** 10 of 10 exit 0, 144000 ticks each.
- **Scenario line:** the same as iteration 21's, `starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on`. Every other switch is at its default on this build. Policy in each result: `teleport` false, `freeItems` false.
- **Scoring:** this build's score.py (`--no-metrics`), paired with iteration 21 by this build's compare.py in [`compare-e40a22.md`](../compare-e40a22.md).
- **Method:** per E-40's "Reading a batch" note, runs of one build differ, so a per-seed difference is not by itself evidence of a change.

**Answer: no, this is not batch 1 of 2.**

| Exit criterion | e40a21-2354e67 | e40a22-d2389c9 | Met |
| --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) on 7 of 10 | 1 of 10 (101) | 4 of 10 (202, 20261030, 606, 707) | no |
| Deaths a game hour, median, 0.5 or less | 0.5 [0 to 2.0] | 0.75 [0 to 3.0] | no |
| First iron pickaxe, median, under 30 min | 16.25 [10.9 to 44.2] | 21.55 [12.7 to 54.6] | yes |
| Portal lit on 7 of 10 | 9 of 10 | 8 of 10 (all but 31102026, iron pickaxe; 707, obsidian) | yes |

| | e40a21-2354e67 | e40a22-d2389c9 |
| --- | --- | --- |
| Stuck game minutes, all seeds (median) | 228 (18.0) | 240 (13.5) |
| Deaths, all seeds | 14 | 20 |
| First obsidian, median | 61.45 (10 of 10) | 40.85 (9 of 10) |
| First portal, median | 65.65 | 54.8 |
| Stuck spells | 76 | 80 |

**Stuck minutes by seed**, e40a22 (e40a21):

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Stuck minutes | 99 (0) | 9 (48) | 9 (18) | 45 (36) | 18 (12) | 12 (15) | 15 (42) | 6 (18) | 9 (27) | 18 (12) |

404 is exactly a tenth, which is not under.

**Paired by seed against e40a21** (helped means better for e40a22):

| Measure | Helped | Hurt | Median [95% interval] | Sign test |
| --- | --- | --- | --- | --- |
| Stuck minutes | 6 | 4 | −6.0 [−19.5, +7.5] | p=0.754 |
| Deaths an hour | 3 | 4 | 0.0 [−0.5, +1.5] | p=1.0 |
| Iron pickaxe | 3 | 6 | +2.15 min [−4.3, +9.8] | p=0.508 |
| First portal | 6 | 3 | −10.15 min [−34.55, +22.25] | p=0.508 |
| Portal lit | 0 | 1 | | p=1.0 |

Nothing passes the sign test.

## The table loop
#274 targets the alternation "on the way, for what comes later: Place a crafting table" ↔ "take workstations along: Pick up the crafting table".

- **Counted here:** a spell replay counts as the loop when its kept log holds both a "Place a crafting table" step and a "Pick up the crafting table" step.
  - e40a21: 10 of 76 spells (202 5, 20261030 3, 505 2).
  - e40a22: 1 of 80 (303).
- **The other count:** LOOPS's 18 for e40a21 came from another method. The two counts are not the same measure.
- **The steps in all consoles:** "Place a crafting table" 2471 → 2166, "Pick up the crafting table" 1796 → 1161, "on the way, for what comes later" 859 → 508.

## Where the stuck time went
- **101, Kindred: 99 of the batch's 240 stuck minutes, one stall from about tick 45,440 to the end.**
  - Gathering logs for sticks, it climbed "out from under the water" and pillared up into a hole at about −244, 46, −79.
  - From then on, every "Head back up toward home" was `native_goto BLOCKED: No walkable route toward -271, 71, -112`, with "try elsewhere" refused the same way.
  - Its runner was BLOCKED ("has started nothing for 1201 ticks … decision=WAIT … runner=BLOCKED … activity=RESERVING"), 48 times to the run's end.
  - It accounts for 22 of the batch's 24 "no walkable route" spells (e40a21: 6).
  - The kept spell replays are in the runner's run folder.
- **Without that stall,** 101 would still have 33 minutes (Kindred's other spell 3, Sol 15, Echo 15), over a tenth. So the batch is 4 of 10 under a tenth either way.
- **303 (45 min):** Kindred 10 spells (30 min), Sol 5 (15).

## Deaths
- **By cause, all seeds, e40a21 → e40a22:**

  | Cause | e40a21 | e40a22 |
  | --- | --- | --- |
  | Zombie | 6 | 8 |
  | Skeleton | 2 | 2 |
  | Lava | 2 | 2 |
  | Suffocation | 1 | 4 |
  | Drowned (the mob) | 0 | 2 |
  | Drowning | 1 | 0 |
  | Fire | 0 | 1 |
  | Enderman | 0 | 1 |
  | Fall | 1 | 0 |
  | Cave spider | 1 | 0 |

- **By kind (F-26c):** cornered 5 → 9, dark melee 3 → 2, lava 2 → 2, drowning 1 → 0, fall 1 → 0, starving with a mob near 1 → 0, other 1 → 7.
- **707:** 6 of the 20 deaths (Kindred 4), and 3.0 an hour.
- **Suffocation:** the 4 deaths were on 31102026 (2), 404 and 707.

## Nights and the replays' cost on the server thread

| Seed | World days | Nights asleep | Spell replays | Death replays | X, ms | Y, ms | Largest X, ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 10.007 | 10 | 33 | 1 | 101.9 | 70.3 | 9.2 |
| 202 | 8.46 | 8 | 3 | 3 | 25.4 | 18.6 | 7.4 |
| 20261030 | 6.0 | 0 | 3 | 0 | 21.0 | 12.9 | 9.6 |
| 303 | 10.153 | 12 | 15 | 1 | 77.6 | 49.5 | 10.2 |
| 31102026 | 6.0 | 0 | 6 | 3 | 36.6 | 21.4 | 7.8 |
| 404 | 6.0 | 0 | 4 | 1 | 26.0 | 20.8 | 6.9 |
| 505 | 8.553 | 8 | 5 | 3 | 34.6 | 22.9 | 9.6 |
| 606 | 6.0 | 0 | 2 | 0 | 13.6 | 11.8 | 7.7 |
| 707 | 6.0 | 0 | 3 | 6 | 39.2 | 28.7 | 7.1 |
| 8675309 | 6.0 | 0 | 6 | 2 | 41.6 | 25.0 | 14.7 |
| All | | | 80 | 20 | 417.5 | 281.9 | 14.7 |

- **Sleep:** players slept on 4 of 10 seeds (101, 202, 303, 505), the four whose world ran past 6 days.
- **Replays:** the 80 spell and 20 death replays match the batch's stuck spells and deaths. The largest single capture, 14.7 ms, is under one 50 ms tick.

## Errors in the consoles
There are no ERROR lines on any of the ten seeds. e40a21 had "Kindred shutdown flush incomplete; reconcile on startup" after the run on 8 of 10.

## Method note: same-build variance does not come from parallel servers
- **Setup:** seed 505, `goal=nether; ticks=24000`, one jar per build.
  - Three runs alone, then three at once.
  - `detcmp.py` finds each player's first differing goal or native line.
  - Ticks come from the console clock, about ±100: one second is about 70 to 100 ticks at sprint.

| Build | First divergence, alone (ticks) | First divergence, three at once (ticks) |
| --- | --- | --- |
| 5cfe873 (2026-11-12) | 100, 100, 105 | 81, 81, 220 |
| 235be23 (2026-11-14) | 110, 110, 119 | 84, 84, 148 |

- **Where runs part:** at a player's line 2 to 5, the opening "Stock some food - Hunt pig". After the same "Approach pig COMPLETED", the pig is somewhere else. Vanilla's mob AI randomness is seeded each run.
- **Running at once:** three at once does not diverge measurably earlier than alone, within the clock's resolution.
- **The lever for scene variance** is the scene's own randomness, not the batch or the parallelism.
- **Data:** `det3-a{1,2}-r{1,2,3}` (235be23) and `det-a{1,2}-r*` (5cfe873) in the runner's run folder.

## A caveat on the corpus baseline
`e40a15-dbf4e5c-707-sol-10569`'s "pass" in `tools/corpus/baseline.json` (16cb7a2, #300) predates F-22c72 and F-22c73, which main added after that baseline. F-27a2's gate confirmed it and the lead cleared it (see [F-27a2](../../F-27a2.md)). It is flagged for the next re-baseline.
