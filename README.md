# Data: e40a24-83321ac (E-40 iteration 24), for LOOPS

The run: main 83321ac (#377's merge), ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three at a
time, replays on, `--keep-world` with run.py from #376's branch (each kept world has its `level.dat`; the worlds are
on the runner's container, not here). 2026-11-16 01:34 to 03:31 UTC; 10 of 10 exit 0, 144,000 ticks each.

- `e40a24-83321ac/summary.json`, `summary.md`: score.py (main 711f5a6, `--no-metrics`).
- `e40a24-83321ac/<seed>/`: `eval-result.json`, `console.txt.gz`, and the seed's **waking scenes only**
  (`replays/world/<player>-<tick>/`, `kind: respawn`; F-27a4). The 19 death and 83 spell replays stay on the container.

## The 21 waking scenes (one for each of the 21 deaths)
Box 33 × 17 × 33 unless named; "inside" is `deathInside`.

| Seed | Scene | Death | Inside | Drops in the box |
| --- | --- | --- | --- | --- |
| 101 | Echo-10430 | −376 30 −137 | no | none |
| 101 | Echo-119619 | −403 12 −203 | no | none |
| 101 | Kindred-7093 | −389 30 −119 | no | none |
| 202 | Echo-126981 | 96 −24 −81 | no | none |
| 202 | Echo-64743 | −95 −39 125 | no | none |
| 202 | Sol-45837 | −92 −36 −150 | no | none |
| 20261030 | Kindred-43461 | 0 −46 106 | no | none |
| 31102026 | Echo-129107 | 22 38 171 | no | none |
| 31102026 | Echo-29276 | 103 70 211 | no | none |
| 505 | Kindred-13819 | −54 42 26 (drowned) | no | stick 4, acacia sapling 3 (ages 1,534 to 2,225: not its own) |
| 505 | Kindred-7579 | 30 27 6 | no | none |
| 505 | Sol-9988 | 36 29 33 (creeper) | no | cobblestone 1 (age 5,919: not its own) |
| 707 | Kindred-19392 | 103 17 −104 | no | none |
| 707 | Kindred-43720 | 32 25 −151 | no | none |
| 707 | Kindred-47599 | −6 77 40 (zombie) | **yes**, box 44 × 23 × 64 | cobblestone 4, raw copper 2 (age 1,646), rotten flesh 2 (age 19) |
| 707 | Sol-143252 | −23 70 32 (skeleton) | **yes**, box 47 × 21 × 63 | **its inventory, age 19**: iron chestplate, shield, stone sword, stone pickaxe 3, water bucket, bucket, furnace, crafting table, coal 21, torch 2, lapis 12, cobblestone 631, dirt 150, granite 42, andesite 41 and more |
| 707 | Sol-26483 | 12 9 11 | no | none |
| 707 | Sol-51739 | 17 29 −185 | no | none |
| 8675309 | Kindred-50488 | 14 13 3 | no | none |
| 8675309 | Kindred-58746 | 25 31 59 | no | none |
| 8675309 | Sol-61008 | −3 37 83 (zombie) | **yes**, box 56 × 34 × 43 | **its inventory, ages 19 and 229**: iron pickaxe, iron chestplate, iron ingot, shield, stone sword, stone pickaxe, wooden pickaxe, water bucket, bucket, furnace, fishing rod, coal 7, cobblestone 899, flint 13 and more |

**For F-25c4di1f:** 707 Sol-143252 and 8675309 Sol-61008 hold a whole dropped inventory, 19 ticks old, in reach.
707 Kindred-47599 has a few drops (some older than its death). The rest have no drops of their own in the box.
Every waking passed its capture ("alive at the end of 2400 ticks").
