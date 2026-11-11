# e40a19-6957e28: runner notes

- Question (lead): does main hold 10 of 10 portals and reach 7 of 10 stuck under a tenth? It is also F-38 slice 1's
  measure (#117).
- Build 6957e28 (main), `goal=nether; ticks=144000`, strategist off, earned, the ten seeds of e40a18, three at a time;
  10 of 10 exit 0, 144000 ticks. Scored with F-26c's score.py (no RunMetrics) and paired with e40a18 in
  [`compare-e40a19.md`](../compare-e40a19.md).

| | e40a18-53cd517 | e40a19-6957e28 |
| --- | --- | --- |
| Portal lit | 10 of 10 | 9 of 10 (101 missed; its furthest stage was obsidian) |
| Stuck under a tenth of the run (under 12 game minutes) | 6 of 10 | 1 of 10 (606, 3 min) |
| Stuck game minutes, all seeds (median) | 138 (9.0) | 255 (22.5) |
| Deaths a game hour, median [range] | 0.5 [0 to 2.5] | 0.5 [0 to 2.0] |
| First iron pickaxe, median [range] | 15.9 [13.4 to 28.8] | 17.85 [12.7 to 26.4] |
| First portal, median | 53.3 [26.9 to 102.8] (10 reached) | 76.45 [36.4 to not reached] (9 reached) |
| Stuck spells: hemmed in / hole or pit / no walkable route / busy-work loop | 2 / 21 / 1 / 11 | 16 / 21 / 11 / 23 |

Stuck game minutes by seed, e40a19 (e40a18): 101 12 (6), 202 18 (12), 20261030 39 (15), 303 39 (3), 31102026 12 (9),
404 48 (24), 505 27 (9), 606 3 (51), 707 15 (0), 8675309 42 (9).

Paired by seed: stuck minutes hurt on 9 of 10, helped on 1, median +16.5 [+6.0, +28.5], p=0.021; portal hurt on 1
(101), p=1.0; deaths an hour helped on 6, hurt on 4, median −0.5 [−1.25, +1.25], p=0.754; iron pickaxe hurt on 6,
helped on 4, median +0.8 min [−1.7, +3.5], p=0.754. Only the stuck rise passes the sign test at ten seeds.

Every stuck spell, e40a18 → e40a19 (46 → 85, by bucket, the quiet tier included): water loop 2 → 5, no walkable
route 1 → 11, refused target repeated 0 → 1, hole or pit 21 → 21, hemmed in 2 → 16, waiting for daylight or a
teammate 1 → 0, mining for a find 8 → 8, busy-work loop 11 → 23, other and unmatched 0 → 0.

Every death, e40a18 → e40a19: lava 1 → 0, drowning 0 → 0, fall 2 → 0, starving with a mob near 2 → 2, cornered
6 → 5, dark melee 2 → 3, other 2 → 3.

"Mine logs" steps (for F-38 slice 2's baseline). The console logs no sapling pickups and no logs or planks by kind, so
only these are counted:

| Seed | Goal lines "Mine logs at" | Break completed | Break blocked or cancelled | Distinct targets |
| --- | --- | --- | --- | --- |
| 101 | 93 | 37 | 14 | 37 |
| 202 | 59 | 33 | 10 | 33 |
| 20261030 | 62 | 48 | 0 | 48 |
| 303 | 61 | 37 | 5 | 37 |
| 31102026 | 33 | 23 | 1 | 23 |
| 404 | 99 | 66 | 2 | 66 |
| 505 | 131 | 90 | 11 | 90 |
| 606 | 99 | 62 | 6 | 62 |
| 707 | 79 | 63 | 13 | 56 |
| 8675309 | 162 | 100 | 14 | 104 |
| Total | 878 | 559 | 76 (70 BLOCKED, 6 CANCELLED) | 556 |

- The exit criteria on this batch: portal on 7 of 10 or more (9, met); median deaths an hour 0.5 or less (0.5, met);
  iron pickaxe median under 30 minutes (17.85, met); stuck under a tenth on 7 of 10 (1, not met).
- Whether the stuck rise is code in 53cd517..6957e28 or e40a18 being lucky is the question of e40a18r, a repeat of
  53cd517 on the same seeds, queued next.
