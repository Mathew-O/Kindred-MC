# e40a18r-53cd517: runner notes

- Question (lead): was e40a19's stuck rise caused by code in 53cd517..6957e28, or was e40a18 lucky? The rule was fixed
  before the run: the regression is real if e40a18r beats e40a19 on stuck minutes, paired by seed, on 7 or more of 10
  with p < 0.10 (then a bisect at d7040305, #152); otherwise e40a18 was luck and the two 53cd517 runs pool as the
  baseline.
- Build 53cd517 again (e40a18's build), `goal=nether; ticks=144000`, strategist off, earned, the same ten seeds,
  three at a time, worlds kept; 10 of 10 exit 0, 144000 ticks. Scored with F-26c's score.py (no RunMetrics); paired with
  e40a18 and with e40a19 in [`compare-e40a18r.md`](../compare-e40a18r.md).

**Answer: luck.** Against e40a19 on stuck minutes, e40a18r helped on 6 of 10, hurt on 3, the same on 1; median −6.0
[−30.0, +9.0], sign test p=0.508. No bisect; the two 53cd517 runs pool as the baseline.

| | e40a18-53cd517 | e40a18r-53cd517 | e40a19-6957e28 |
| --- | --- | --- | --- |
| Portal lit | 10 of 10 | 8 of 10 (31102026 and 8675309 missed) | 9 of 10 |
| Stuck under a tenth of the run (under 12 game minutes) | 6 of 10 | 4 of 10 (202, 20261030, 303, 404) | 1 of 10 |
| Stuck game minutes, all seeds (median) | 138 (9.0) | 228 (12.0) | 255 (22.5) |
| Deaths a game hour, median [range] | 0.5 [0 to 2.5] | 0.5 [0 to 4.0] | 0.5 [0 to 2.0] |
| First iron pickaxe, median [range] | 15.9 [13.4 to 28.8] | 14.4 [10.4 to 25.7] | 17.85 [12.7 to 26.4] |
| Stuck spells | 46 | 76 | 85 |
| Hemmed in / hole or pit / no walkable route / busy-work loop | 2 / 21 / 1 / 11 | 17 / 25 / 4 / 11 | 16 / 21 / 11 / 23 |

Stuck game minutes by seed, e40a18r: 101 12, 202 9, 20261030 9, 303 6, 31102026 21, 404 9, 505 18, 606 78, 707 12,
8675309 54.

Paired, e40a18r against e40a19 (helped = better for e40a18r): stuck as above; portal helped 1, hurt 2, p=1.0; deaths an
hour helped 2, hurt 7, median +0.5 [0, +0.75], p=0.18; iron pickaxe helped 7, hurt 3, median −2.0 [−6.15, +2.4],
p=0.344; first portal helped 7, hurt 3, median −6.45, p=0.344.

Paired, e40a18r against e40a18 (the same build twice): stuck helped 3, hurt 7, median +7.5 [−3.0, +19.5], p=0.344;
portal hurt 2, p=0.5; deaths helped 4, hurt 3, p=1.0; iron pickaxe helped 7, hurt 3, median −1.0, p=0.344.

Every stuck spell, e40a18r (76): water loop 11, no walkable route 4, refused target repeated 0, hole or pit 25, hemmed in
17, waiting for daylight or a teammate 3, mining for a find 5, busy-work loop 11, other and unmatched 0.

Every death, e40a18r: lava 2, drowning 2, fall 0, starving with a mob near 1, cornered 13, dark melee 1, other 3.

## The pooled 53cd517 baseline (e40a18 and e40a18r, 20 runs)

- Stuck under a tenth of the run: 10 of 20. Portal lit: 18 of 20.
- Hemmed in swung from 2 to 17 spells on the same build and seeds, so a change of that size in hemmed in is within one
  build's run-to-run spread, not a code signal by itself.
- e40a19's no walkable route (11, against 1 and 4) and busy-work loop (23, against 11 and 11) lie outside both baseline
  runs; each is one batch's count, not a verdict.
