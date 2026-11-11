# e40a20-79a9aad: runner notes

- Question (lead), stated before the run: on main after #181 (F-25c4de) and #126 (F-35c), is the team stuck under a
  tenth of the run on 7 of 10 seeds, with the other three exit criteria still met (portal on 7 of 10 or more, median
  deaths an hour 0.5 or less, iron pickaxe median under 30 minutes)? The first batch of a possible two in a row,
  set against iteration 19 and the pooled 53cd517 baseline (stuck under a tenth on 10 of 20).
- Build 79a9aad (main), `goal=nether; ticks=144000`, strategist off, earned, the ten seeds of iterations 18 and 19,
  three at a time; 10 of 10 exit 0, 144000 ticks. The command is iteration 18 and 19's. Replays are on as they were
  there (on by default under an evaluation run); new since iteration 19 are the stuck-spell replays (#166) and the
  goal's memory in them (#198).
- 8675309 was run twice: its first run was cut off at about tick 132000 by a restart of the runner's container (not
  scored, not kept here), and it was run again from the start on the same build, alone, while the other nine ran
  three at a time.
- Scored with F-26c's score.py (no RunMetrics); paired with iterations 19, 18 and 18r in
  [`compare-e40a20.md`](../compare-e40a20.md).

**Answer: no.** Stuck under a tenth on 4 of 10 (101, 303, 505, 606), and the median deaths an hour is 0.75. Portal 8
of 10 and the iron pickaxe median (17.25) are met.

| | e40a19-6957e28 | e40a20-79a9aad | pooled 53cd517 (e40a18, e40a18r) |
| --- | --- | --- | --- |
| Portal lit | 9 of 10 | 8 of 10 (202 diamond, 31102026 obsidian) | 18 of 20 |
| Stuck under a tenth of the run (under 12 game minutes) | 1 of 10 | 4 of 10 | 10 of 20 |
| Stuck game minutes, all seeds (median) | 255 (22.5) | 210 (12.0) | 138 (9.0) and 228 (12.0) |
| Deaths a game hour, median [range] | 0.5 [0 to 2.0] | 0.75 [0 to 3.0] | 0.5 and 0.5 |
| First iron pickaxe, median [range] | 17.85 [12.7 to 26.4] | 17.25 [10.3 to 49.3] | 15.9 and 14.4 |
| First portal, median | 76.45 | 46.3 | 53.3 and 58.45 |
| Stuck spells | 85 | 70 | 46 and 76 |

Stuck game minutes by seed, e40a20 (e40a19): 101 9 (12), 202 33 (18), 20261030 27 (39), 303 0 (39), 31102026 12 (12),
404 39 (48), 505 9 (27), 606 6 (3), 707 12 (15), 8675309 63 (42). 31102026 and 707 are exactly a tenth, which is not
under.

Paired by seed (helped = better for e40a20):

- Against e40a19: stuck helped 6, hurt 3, median −3.0 [−13.5, +7.5], p=0.508; portal helped 1, hurt 2, p=1.0; deaths
  an hour helped 5, hurt 4, median −0.25, p=1.0; iron pickaxe helped 6, hurt 4, median −2.25 [−6.3, +2.9], p=0.754;
  first portal helped 8, hurt 2, median −16.2 [−34.7, +10.5], p=0.109.
- Against e40a18: stuck helped 2, hurt 7, median +7.5 [0.0, +16.5], p=0.18; portal hurt 2, p=0.5; deaths helped 2,
  hurt 5, p=0.453; iron pickaxe helped 3, hurt 7, p=0.344; first portal helped 6, hurt 4, p=0.754.
- Against e40a18r: stuck helped 5, hurt 4, median −1.5 [−9.0, +18.0], p=1.0; portal helped 1, hurt 1, p=1.0; deaths
  helped 4, hurt 4, p=1.0; iron pickaxe helped 5, hurt 5, p=1.0; first portal helped 4, hurt 5, p=1.0.
- No difference passes the sign test at ten seeds against any of the three.

Every stuck spell, e40a19 → e40a20 (85 → 70): water loop 5 → 8, no walkable route 11 → 7, refused target repeated
1 → 0, hole or pit 21 → 2, hemmed in 16 → 29, waiting for daylight or a teammate 0 → 1, mining for a find 8 → 6,
busy-work loop 23 → 17, other and unmatched 0 → 0.

Every death, e40a19 → e40a20: lava 0 → 2, drowning 0 → 2, fall 0 → 0, starving with a mob near 2 → 1, cornered 5 → 9,
dark melee 3 → 4, other 3 → 1.

## The replays' cost on the server thread

Each capture logs "captured in X ms on the server thread (Y ms of its time)": X is DeathReplays' `nanosOnThread`, Y its
`cpuNanosOnThread`. Share is X against the run's server time (144000 ticks at the seed's mean tick).

| Seed | Spell replays | Death replays | X, ms | Y, ms | Largest X, ms | Share of the run |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 3 | 3 | 37.2 | 21.5 | 9.4 | 0.0023% |
| 202 | 11 | 6 | 82.0 | 54.9 | 12.5 | 0.0033% |
| 20261030 | 9 | 2 | 49.9 | 34.3 | 11.6 | 0.0026% |
| 303 | 0 | 0 | 0.0 | 0.0 | - | 0% |
| 31102026 | 4 | 1 | 21.6 | 17.0 | 5.7 | 0.0010% |
| 404 | 13 | 0 | 81.9 | 49.6 | 16.9 | 0.0040% |
| 505 | 3 | 3 | 24.1 | 18.1 | 6.0 | 0.0015% |
| 606 | 2 | 0 | 11.0 | 8.2 | 5.7 | 0.0006% |
| 707 | 4 | 1 | 26.9 | 13.9 | 11.2 | 0.0015% |
| 8675309 | 21 | 3 | 63.0 | 61.9 | 3.9 | 0.0029% |
| All | 70 | 19 | 397.6 | 279.4 | 16.9 | |

The 70 spell replays are the batch's 70 stuck spells and the 19 death replays its 19 deaths. The largest single
capture, 16.9 ms, is under one 50 ms tick.
