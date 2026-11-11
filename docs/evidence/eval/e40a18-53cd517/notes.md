# e40a18-53cd517: runner notes

- Question (lead): where does main stand against e40a17 after #98, #119 and #144 (the revert of #120)? With the a15
  stuck regression's buckets: hemmed in, hole or pit, no walkable route.
- Build 53cd517 (main with #144 merged), `goal=nether; ticks=144000`, strategist off, earned, the ten seeds of e40a17;
  10 of 10 exit 0, 144000 ticks. Scored with F-26c's score.py (no RunMetrics); e40a17 scored again with the same
  rules from its consoles on `claude/corpus-data-1110`, and the two paired in `compare-e40a18.md`.
- A first start of this iteration on a27a044 (main with #120 still in it) was stopped at 12:00 with no results kept.

| | e40a17-7bc5b42 | e40a18-53cd517 |
| --- | --- | --- |
| Portal lit | 7 of 10 | 10 of 10 |
| Stuck under a tenth of the run (under 12 game minutes) | 3 of 10 | 6 of 10 |
| Stuck game minutes, all seeds (median) | 192 (12.0) | 138 (9.0) |
| Deaths a game hour, median [range] | 1.0 [0 to 3.0] | 0.5 [0 to 2.5] |
| First iron pickaxe, median [range] | 20.45 [15.7 to 40.4] | 15.9 [13.4 to 28.8] |
| First portal, median | 60.05 (7 reached) | 53.3 [26.9 to 102.8] (10 reached) |
| Stuck spells: hemmed in / hole or pit / no walkable route | 20 / 11 / 1 | 2 / 21 / 1 |

Paired by seed: portal helped on 3 of 10 (101, 31102026, 707), hurt on 0, p=0.25; iron pickaxe sooner on 8 of 10,
median −4.65 min [−10.65, +0.1], p=0.109; stuck minutes helped on 7, hurt on 2, median −4.5 [−16.5, +6.0], p=0.18;
deaths an hour helped on 5, hurt on 3, median −0.25 [−1.5, +1.0]. No seed entered the Nether on either build
(the goal is the portal).
