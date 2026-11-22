# e40a26-f8b7ffe and e40a26r-f8b7ffe: runner notes

Date: 2026-11-22
Agent / branch: Claude RUNNER 2 / `claude/e40a26-f8b7ffe-1122`
Status: two evaluation batches. No code change, no release gate.

**The read, stated before the runs (lead):** iteration 25's figures are the comparison, and 25's spread is the noise
band. A change is called real only outside that band.

**How the band is applied here:** a figure is outside the band when both of 26's batches fall outside the range that
25's two batches span. A figure that overlaps that range is not called a change. The band is one pair of batches, so it
is narrow for medians and totals; that caveat is repeated where it matters.

## Setup, both batches
- **Build:** main f8b7ffea (to #443), 2026-11-22. #441 is not in it. One jar, built from f8b7ffea with `remapJar` and
  passed by `--jar` to both batches (sha256 9a828268…).
- **Merges since iteration 25 (59f62762), first parent:** #416, #424, #430, #433, #436, #359, #431, #435, #432, #434,
  #427, #429, #437, #439, #440, #443. No cause is assigned to any of them below.
- **Harness:** run.py at 174e0b3, as for iterations 24 and 25. Scoring: score.py from #399's branch, `--no-metrics`.
- **Settings:** iteration 25's protocol exactly: the ten seeds, `goal=nether; ticks=144000`, earned, strategist off,
  three at a time, replays on. The scenario line is identical in both summaries and in iteration 25's. No
  `--keep-world` (1.7 GB free at the end), as for iteration 25.
- **Order (UTC, 2026-11-22):** batch 1 (`e40a26-f8b7ffe`) 03:45 to 06:45, then batch 2 (`e40a26r-f8b7ffe`) 06:45 to
  09:51. Each batch ran as two calls of five seeds (101, 202, 20261030, 303, 31102026, then 404, 505, 606, 707,
  8675309) into one build folder. Nothing else ran on the cores.
- **A re-run in batch 1:** the container restarted at about 04:36, during batch 1's first call. 101, 202 and 20261030
  had completed. 303 and 31102026 were cut mid-run; their consoles were kept outside the repository and their
  unfinished server folders removed. Both seeds were then run again from the start with the same jar and settings
  (04:37 to 05:25). The figures for 303 and 31102026 in batch 1 are from those re-runs.
- **Completion:** 20 of 20 exit 0, 144000 ticks each (counting the two re-runs, not the cut runs).

## Exit figures

| Exit criterion | Threshold | Batch 1 | Batch 2 | 25's band | Read |
| --- | --- | --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) | 7 of 10 | 5 of 10 (404, 505, 606, 707, 8675309): no | 6 of 10 (101, 202, 303, 404, 505, 606): no | 2 to 4 | outside, more seeds under |
| Deaths a game hour, median | 0.5 or less | 0.5 [0 to 3.5]: yes | 0.75 [0 to 2.5]: no | 0.5 to 0.75 | inside |
| First iron pickaxe, median | under 30 min | 18.65 (10 of 10): yes | 20.3 (10 of 10): yes | 16.6 to 17.15 | outside, later |
| Portal lit | 7 of 10 | 9 of 10 (not 31102026): yes | 9 of 10 (not 8675309): yes | 6 to 8 | outside, more lit |

| | Batch 1 | Batch 2 | 25's band | Read |
| --- | --- | --- | --- | --- |
| Stuck game minutes, all seeds | 135 | 138 | 183 to 189 | outside, lower |
| Stuck spells | 45 | 46 | 61 to 63 | outside, fewer |
| Hole or pit plus hemmed in (spells) | 15 (10 + 5) | 16 (12 + 4) | 10 to 21 | inside |
| Deaths, all seeds | 21 (7 on 8675309) | 17 (5 on 8675309) | 16 to 20 | overlaps |

Exactly 12 minutes is a tenth, which is not under; no seed sits on it in either batch.

**Deaths by kind:** batch 1 lava 4, fall 2, cornered 7, dark melee 7, other 1. Batch 2 drowning 2, cornered 6, dark
melee 6, other 3.

## Stuck spells by kind, all seeds

| Kind | 26 batch 1 | 26 batch 2 | 25 batch 1 | 25 batch 2 | Read |
| --- | --- | --- | --- | --- | --- |
| Busy-work loop | 5 | 12 | 20 | 23 | outside, fewer |
| No walkable route | 1 | 3 | 10 | 5 | outside, fewer |
| Water loop | 2 | 1 | 3 | 5 | outside, fewer |
| Hole or pit | 10 | 12 | 8 | 8 | outside, more |
| Hemmed in | 5 | 4 | 13 | 2 | inside |
| Mining for a find | 11 | 10 | 7 | 13 | inside |
| Refused target repeated | 6 | 2 | 0 | 3 | overlaps |
| Waiting for daylight or a teammate | 5 | 2 | 1 | 2 | overlaps |
| Other or unmatched | 0 | 0 | 1 | 0 | — |

25's hole-or-pit band has no width (8 and 8), so "outside" there means only that 26 is not 8; it is the weakest of these
reads.

## Stuck per seed, by kind (spells; three game minutes each)

Columns: water loop, no walkable route, refused target repeated, hole or pit, hemmed in, waiting, mining for a find,
busy-work loop. Other and unmatched are 0 on every seed of both batches.

**Batch 1, `e40a26-f8b7ffe`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 15 | 5 | 0 | 0 | 0 | 1 | 0 | 1 | 2 | 1 |
| 202 | 30 | 10 | 0 | 0 | 2 | 0 | 0 | 4 | 4 | 0 |
| 20261030 | 18 | 6 | 0 | 0 | 3 | 1 | 0 | 0 | 2 | 0 |
| 303 | 15 | 5 | 0 | 0 | 1 | 0 | 0 | 0 | 3 | 1 |
| 31102026 | 33 | 11 | 0 | 0 | 0 | 7 | 4 | 0 | 0 | 0 |
| 404 | 3 | 1 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 |
| 505 | 9 | 3 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 2 |
| 606 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 707 | 6 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 8675309 | 6 | 2 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 1 |

**Batch 2, `e40a26r-f8b7ffe`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 9 | 3 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | 1 |
| 202 | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 |
| 20261030 | 21 | 7 | 0 | 2 | 1 | 0 | 0 | 0 | 2 | 2 |
| 303 | 6 | 2 | 0 | 0 | 0 | 0 | 1 | 0 | 1 | 0 |
| 31102026 | 15 | 5 | 0 | 0 | 0 | 3 | 0 | 0 | 1 | 1 |
| 404 | 3 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 505 | 9 | 3 | 0 | 0 | 0 | 2 | 0 | 1 | 0 | 0 |
| 606 | 9 | 3 | 0 | 0 | 0 | 1 | 0 | 1 | 1 | 0 |
| 707 | 24 | 8 | 0 | 1 | 0 | 1 | 3 | 0 | 1 | 2 |
| 8675309 | 33 | 11 | 0 | 0 | 0 | 5 | 0 | 0 | 3 | 3 |

31102026 in batch 1 (a re-run seed) holds 11 of batch 1's 15 signature spells.

## Per seed against 25's band

Stuck game minutes. "Spread" is batch 2 minus batch 1, as in iteration 25's notes.

| Seed | 25 b1 | 25 b2 | 25 spread | 26 b1 | 26 b2 | 26 spread | 26 against 25's range |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 | 0 | 0 | 15 | 9 | −6 | both above |
| 202 | 60 | 18 | −42 | 30 | 9 | −21 | overlaps |
| 20261030 | 3 | 6 | +3 | 18 | 21 | +3 | both above |
| 303 | 12 | 15 | +3 | 15 | 6 | −9 | overlaps |
| 31102026 | 15 | 12 | −3 | 33 | 15 | −18 | overlaps |
| 404 | 3 | 33 | +30 | 3 | 3 | 0 | overlaps |
| 505 | 12 | 24 | +12 | 9 | 9 | 0 | both below |
| 606 | 39 | 18 | −21 | 0 | 9 | +9 | both below |
| 707 | 0 | 15 | +15 | 6 | 24 | +18 | overlaps |
| 8675309 | 45 | 42 | −3 | 6 | 33 | +27 | both below |
| Total | 189 | 183 | −6 | 135 | 138 | +3 | both below |

- **26's own spread, stuck minutes per seed:** median 0, range −21 to +27, median |Δ| 9 min. Iteration 25's: median
  +1.5, range −42 to +30, median |Δ| 7.5. The two pairs' per-seed spreads are alike.
- **Per seed:** 5 seeds overlap 25's range, 3 are below it on both batches (505, 606, 8675309), 2 above it on both
  (101, 20261030). Per seed, most of the drop in the total is not outside the band.

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Deaths an hour, batch 1 | 0.5 | 1.0 | 0.0 | 0.0 | 0.5 | 1.0 | 3.0 | 0.5 | 0.5 | 3.5 |
| Deaths an hour, batch 2 | 1.0 | 0.5 | 0.0 | 0.5 | 1.0 | 0.0 | 1.5 | 0.5 | 1.0 | 2.5 |
| Iron pickaxe, batch 1 (min) | 14.4 | 23.7 | 15.8 | 21.0 | 19.5 | 20.2 | 14.2 | 9.2 | 17.8 | 21.9 |
| Iron pickaxe, batch 2 (min) | 26.6 | 14.5 | 19.8 | 19.2 | 43.0 | 14.7 | 25.7 | 15.7 | 20.8 | 35.1 |
| Portal lit, batch 1 (min) | 114.0 | 94.5 | 53.7 | 89.3 | no | 55.2 | 31.2 | 37.4 | 60.6 | 56.3 |
| Portal lit, batch 2 (min) | 72.2 | 94.4 | 39.5 | 25.0 | 52.6 | 46.8 | 64.0 | 38.8 | 70.7 | no |

## The read
- **Outside 25's band:**
  - stuck total 135 and 138 against 183 to 189, and spells 45 and 46 against 61 to 63;
  - stuck under a tenth on 5 and 6 seeds against 2 to 4;
  - portal lit 9 and 9 against 6 to 8;
  - first iron pickaxe later, medians 18.65 and 20.3 against 16.6 to 17.15;
  - among kinds, busy-work loop (5 and 12 against 20 to 23), no walkable route and water loop fewer, hole or pit more.
- **Inside or overlapping:** deaths an hour (again either side of 0.5), deaths in all, the signature, hemmed in, mining
  for a find.
- **How firm:** each band is one pair of batches. The stuck-under-a-tenth and portal reads are outside by one and two
  seeds. The pickaxe band is 0.55 min wide while 25's per-seed pickaxe spread was a median |Δ| of 3.5 min, so the
  median's band is likely narrower than its noise. The stuck total is the widest margin (45 to 54 min against a band 6
  wide), but per seed only 3 seeds sit below 25's range on both batches.
- **The exit criteria:** stuck under a tenth is still not met by either batch (5 and 6 of 10, needing 7). The pickaxe
  and portal are met by both. Deaths an hour is met by batch 1 and not by batch 2, as in iteration 25 with the order
  reversed.

## Console
- **ERROR lines:** 0 in either batch.
- **Reconciliation:** no `reconciliationBlocked=true` line and no "reconcile before another request" line in either
  batch; the score's blocks for reconciliation are 0 on every seed.

## Not claimed
- No exit criterion is met by both batches except the iron pickaxe and the portal; stuck under a tenth is met by neither.
- No cause is named for any change against iteration 25; sixteen merges lie between the two jars.
- The cut runs of 303 and 31102026 are not scored and not filed.
