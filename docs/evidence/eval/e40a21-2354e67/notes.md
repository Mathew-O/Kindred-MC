# e40a21-2354e67: runner notes

- Question (lead), stated before the run: is main since e40a20 (79a9aad) the first of two consecutive batches that
  meet the exit criteria (stuck under a tenth of the run on 7 of 10 seeds or more, median deaths a game hour 0.5 or
  less, iron pickaxe median under 30 minutes, a lit portal on 7 of 10 or more)? Deaths, which missed in iteration 20
  (0.75), are the measure to watch.
- Build 2354e67 (main, #242's merge; its first parent 8bddef45 is #244), `goal=nether; ticks=144000`, earned, the ten
  seeds of iterations 18 to 20, three at a time, iteration 20's command with nothing added; 10 of 10 exit 0, 144000
  ticks each, run 09:43 to 11:49 UTC on 2026-11-12.
- Switches: the scenario line is `starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on;
  strategist=off; voice=off; keep=none; lessons=on; atlas=on; rescue=on; advice=on`. Every other switch is at
  `EvalScenario`'s default on this build, which the line leaves out: `mode=survival`, no `players`, `founders=0`, and
  `sleep=on` (E-70a's nights, on by default since #216). Policy in each result: `teleport` false, `freeItems` false;
  kind earned. Replays are on by default under an evaluation run, as in iterations 18 to 20.
- Scored with this build's score.py (`--no-metrics`, so no RunMetrics, as iteration 20); paired with iteration 20 by
  this build's compare.py in [`compare-e40a21.md`](../compare-e40a21.md).

**Answer: no, this is not batch 1 of 2.** Stuck under a tenth on 1 of 10 (101). Deaths are met (median 0.5), the
portal is met (9 of 10) and the iron pickaxe is met (16.25).

| | e40a20-79a9aad | e40a21-2354e67 |
| --- | --- | --- |
| Portal lit | 8 of 10 (202 diamond, 31102026 obsidian) | 9 of 10 (31102026 obsidian) |
| Stuck under a tenth of the run (under 12 game minutes) | 4 of 10 (101, 303, 505, 606) | 1 of 10 (101) |
| Stuck game minutes, all seeds (median) | 210 (12.0) | 228 (18.0) |
| Deaths a game hour, median [range] | 0.75 [0 to 3.0] | 0.5 [0 to 2.0] |
| Deaths, all seeds | 19 | 14 |
| First iron pickaxe, median [range] | 17.25 [10.3 to 49.3] | 16.25 [10.9 to 44.2] |
| First obsidian, median | 43.35 (9 of 10) | 61.45 (10 of 10) |
| First portal, median | 46.3 | 65.65 |
| Stuck spells | 70 | 76 |

Stuck game minutes by seed, e40a21 (e40a20): 101 0 (9), 202 48 (33), 20261030 18 (27), 303 36 (0), 31102026 12 (12),
404 15 (39), 505 42 (9), 606 18 (6), 707 27 (12), 8675309 12 (63). 31102026 and 8675309 are exactly a tenth, which is
not under.

Paired by seed against e40a20 (helped = better for e40a21): stuck minutes helped 4, hurt 5, median +6.0
[−16.5, +24.0], p=1.0; portal helped 1, hurt 0, p=1.0; deaths an hour helped 5, hurt 4, median −0.25 [−1.0, +0.5],
p=1.0; iron pickaxe helped 5, hurt 5, median −1.45 [−4.3, +3.75], p=1.0; first portal helped 2, hurt 7, median +13.2
min [−11.6, +32.3], p=0.18. No difference passes the sign test at ten seeds.

Deaths by cause, all seeds, e40a20 → e40a21: zombie 8 → 6, skeleton 4 → 2, drowning 2 → 1, lava 2 → 2, spider 1 → 0,
zombie_villager 1 → 0, creeper 1 → 0, fall 0 → 1, cave_spider 0 → 1, suffocation 0 → 1.

Deaths by kind (F-26c), e40a20 → e40a21: lava 2 → 2, drowning 2 → 1, fall 0 → 1, starving with a mob near 1 → 1,
cornered 9 → 5, dark melee 4 → 3, other 1 → 1.

Every stuck spell, e40a20 → e40a21 (70 → 76): water loop 8 → 3, no walkable route 7 → 6, refused target repeated
0 → 4, hole or pit 2 → 12, hemmed in 29 → 19, waiting for daylight or a teammate 1 → 1, mining for a find 6 → 8,
busy-work loop 17 → 23.

## Nights and the replays' cost on the server thread

Nights asleep counts the console's `native_sleep COMPLETED` lines. The world's days are the result's `days`: a night
slept through is skipped, so a seed whose players slept runs more days in its 144000 ticks. The replay columns are as
in iteration 20: each capture logs "captured in X ms on the server thread (Y ms of its time)", and share is X against
the run's server time (144000 ticks at the seed's mean tick).

| Seed | World days | Nights asleep | Spell replays | Death replays | X, ms | Y, ms | Largest X, ms | Share of the run |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 8.716 | 8 | 0 | 1 | 6.0 | 5.7 | 6.0 | 0.0003% |
| 202 | 10.997 | 11 | 16 | 0 | 96.9 | 63.6 | 12.9 | 0.0049% |
| 20261030 | 6.0 | 0 | 6 | 0 | 57.4 | 32.2 | 20.1 | 0.0032% |
| 303 | 12.022 | 17 | 12 | 2 | 66.7 | 52.9 | 10.8 | 0.0026% |
| 31102026 | 6.0 | 0 | 4 | 0 | 29.7 | 20.0 | 9.4 | 0.0016% |
| 404 | 6.0 | 0 | 5 | 1 | 59.5 | 39.0 | 19.2 | 0.0033% |
| 505 | 6.0 | 0 | 14 | 4 | 99.2 | 67.6 | 22.5 | 0.0057% |
| 606 | 6.0 | 0 | 6 | 4 | 90.1 | 53.4 | 20.3 | 0.0049% |
| 707 | 6.0 | 0 | 9 | 1 | 43.2 | 36.4 | 8.5 | 0.0022% |
| 8675309 | 6.0 | 0 | 4 | 1 | 31.9 | 30.9 | 7.8 | 0.0018% |
| All | | | 76 | 14 | 580.6 | 401.7 | 22.5 | |

- Players slept on 3 of 10 seeds (101, 202, 303), the three whose world ran past 6 days. The 76 spell and 14 death
  replays match the batch's 76 stuck spells and 14 deaths. The largest single capture, 22.5 ms, is under one 50 ms
  tick.

## Errors in the consoles

- One ERROR line each on 8 of 10 seeds (all but 404 and 707), after the run: "Kindred shutdown flush incomplete;
  reconcile on startup", a `TimeoutException` from `PlayerRuntime.finishShutdown` (PlayerRuntime.java:14874). It
  comes after "144000 ticks run, result written" and the world save, so the results are whole; it says the metadata
  writer had not drained when the shutdown wait ran out. No other ERROR line in any console.
