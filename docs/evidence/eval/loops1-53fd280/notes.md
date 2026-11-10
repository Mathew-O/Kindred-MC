# loops1-53fd280: M-01f measured, then reverted

- Question (LOOPS): does the global set-aside of refused walks (M-01f, #120, merge 53fd280) cut each seed's "No walkable
  route" BLOCKED count and the team's stuck minutes against main just before it, without raising deaths an hour?
- Pair: main-7828527, the first parent of #120's merge, so the only change between the two is #120. Same ten seeds,
  `goal=nether; ticks=144000`, strategist off, earned; 10 of 10 exit 0 on both. #120 is reverted on main by #144.
- Answer: no. Paired by seed (compare-loops1-53fd280.md), loops1 against main-7828527:
  - "no walkable route" stuck minutes: 0 on every seed before; after, 12, 9, 3 and 6 on 404, 505, 707 and 8675309.
    Hurt on 4 of 10, helped on 0, median 0 [0, 6], sign test p=0.125.
  - Stuck game minutes, the team: 174 to 240 in all; hurt on 8 of 10, median +6 [0, +18], p=0.109.
  - Deaths: 16 to 26 in all; deaths an hour hurt on 4, helped on 2, median 0.0 [-0.25, +1.0], p=0.688.
  - Portal lit: hurt on 5 of 10, helped on 1, median -0.5 [-1.0, 0.0], p=0.219.
  - "BLOCKED: No walkable route" lines: 2659 to 2983; the set-aside's "lets its walks to around ... be for ten
    minutes" lines: 0 to 37.
- Stuck buckets (F-26c): main-7828527 hole or pit 20, busy-work loop 15, hemmed in 12, mining 6, water 4, waiting 1;
  loops1 hemmed in 31, hole or pit 18, busy-work loop 14, no walkable route 10, mining 6, waiting 1.
