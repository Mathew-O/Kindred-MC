# e40a27-772dee3 and e40a27r-772dee3: runner notes

Date: 2026-11-22
Agent / branch: Claude RUNNER 2 / `claude/e40a27-772dee3-1122`
Status: two evaluation batches. No code change, no release gate.

**The read:** two identical batches of one jar, on iteration 25's and 26's protocol. They are read against both earlier
pairs. A figure is "outside" a pair's band when both of 27's batches fall outside the range that pair's two batches
span. "Overlaps" means at least one of 27's batches falls inside it, or one falls on each side. Each band is one pair of
batches, so it is narrow.

## Setup, both batches
- **Build:** main 772dee37 (to #450), 2026-11-22. One jar, built from 772dee37 with `remapJar` and passed by `--jar`
  to both batches (sha256 60189a06…). It carries #441, #448 (E-40a1a) and #450 (F-25c4dl). #455 failed CI and is not in
  it, so it has no column here.
- **Merges since iteration 26 (f8b7ffea), first parent:** #444, #441, #445, #438, #446, #442, #449, #447, #448, #452,
  #450. No cause is assigned to any of them below.
- **Harness:** run.py at 174e0b3, as for iterations 24 to 26. Scoring: score.py from #399's branch, `--no-metrics`.
- **Settings:** iteration 25's protocol exactly: the ten seeds, `goal=nether; ticks=144000`, earned, strategist off,
  three at a time, replays on. No `--keep-world`, as for iterations 25 and 26.
- **Order (UTC, 2026-11-22):** batch 1 (`e40a27-772dee3`) 12:37 to 15:49, then batch 2 (`e40a27r-772dee3`) 15:49 to
  18:42. Each batch ran as two calls of five seeds (101, 202, 20261030, 303, 31102026, then 404, 505, 606, 707,
  8675309) into one build folder. Nothing else ran on the cores.
- **Completion:** 20 of 20 exit 0, 144000 ticks each. No re-runs.

## Exit figures

| Exit criterion | Threshold | Batch 1 | Batch 2 | 26's band | 25's band | Read |
| --- | --- | --- | --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) | 7 of 10 | 6 of 10 (101, 20261030, 303, 505, 707, 8675309): no | 4 of 10 (303, 505, 606, 707): no | 5 to 6 | 2 to 4 | overlaps both; not met |
| Deaths a game hour, median | 0.5 or less | 0.75 [0 to 2.5]: no | 1.0 [0 to 1.5]: no | 0.5 to 0.75 | 0.5 to 0.75 | overlaps both; not met |
| First iron pickaxe, median | under 30 min | 18.1 (10 of 10): yes | 22.55 (10 of 10): yes | 18.65 to 20.3 | 16.6 to 17.15 | overlaps 26's; outside 25's, later; met |
| Portal lit | 7 of 10 | 8 of 10 (not 202, 31102026): yes | 7 of 10 (not 202, 707, 8675309): yes | 9 to 9 | 6 to 8 | outside 26's, fewer; inside 25's; met |

| | Batch 1 | Batch 2 | 26's band | 25's band | Read |
| --- | --- | --- | --- | --- | --- |
| Stuck game minutes, all seeds | 102 | 144 | 135 to 138 | 183 to 189 | overlaps 26's (one each side); outside 25's, lower |
| Stuck spells | 34 | 48 | 45 to 46 | 61 to 63 | overlaps 26's (one each side); outside 25's, fewer |
| Hole or pit plus hemmed in (spells) | 14 (5 + 9) | 23 (7 + 16) | 15 to 16 | 10 to 21 | overlaps both |
| Deaths, all seeds | 16 | 18 | 17 to 21 | 16 to 20 | overlaps both |

Exactly 12 minutes is a tenth, which is not under. 606 in batch 1 and 202 in batch 2 sit on it.

**Deaths by kind:** batch 1 drowning 1, cornered 5, dark melee 5, other 5. Batch 2 starving with a mob near 1, cornered
5, dark melee 8, other 4.

## Stuck spells by kind, all seeds

| Kind | 27 batch 1 | 27 batch 2 | 26 batch 1 | 26 batch 2 | 25 batch 1 | 25 batch 2 | Read |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Busy-work loop | 4 | 17 | 5 | 12 | 20 | 23 | overlaps 26's; outside 25's, fewer |
| Hemmed in | 9 | 16 | 5 | 4 | 13 | 2 | outside 26's, more; overlaps 25's |
| Hole or pit | 5 | 7 | 10 | 12 | 8 | 8 | outside both, fewer |
| Mining for a find | 10 | 3 | 11 | 10 | 7 | 13 | overlaps both |
| No walkable route | 0 | 2 | 1 | 3 | 10 | 5 | overlaps 26's; outside 25's, fewer |
| Water loop | 3 | 3 | 2 | 1 | 3 | 5 | outside 26's, more; overlaps 25's |
| Refused target repeated | 3 | 0 | 6 | 2 | 0 | 3 | overlaps both |
| Waiting for daylight or a teammate | 0 | 0 | 5 | 2 | 1 | 2 | outside both, fewer |
| Other or unmatched | 0 | 0 | 0 | 0 | 1 | 0 | — |

Batch 2's busy-work loop is 17 spells, and 7 of them are on 20261030.

## Stuck per seed, by kind (spells; three game minutes each)

Columns: water loop, no walkable route, refused target repeated, hole or pit, hemmed in, waiting, mining for a find,
busy-work loop, other.

**Batch 1, `e40a27-772dee3`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 9 | 3 | 1 | 0 | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| 202 | 21 | 7 | 0 | 0 | 2 | 0 | 3 | 0 | 2 | 0 | 0 |
| 20261030 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 303 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 31102026 | 18 | 6 | 0 | 0 | 0 | 5 | 0 | 0 | 0 | 1 | 0 |
| 404 | 30 | 10 | 1 | 0 | 0 | 0 | 3 | 0 | 5 | 1 | 0 |
| 505 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |
| 606 | 12 | 4 | 0 | 0 | 0 | 0 | 3 | 0 | 0 | 1 | 0 |
| 707 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 8675309 | 9 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 |

**Batch 2, `e40a27r-772dee3`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 18 | 6 | 2 | 1 | 0 | 1 | 1 | 0 | 0 | 1 | 0 |
| 202 | 12 | 4 | 0 | 0 | 0 | 0 | 2 | 0 | 1 | 1 | 0 |
| 20261030 | 30 | 10 | 0 | 0 | 0 | 3 | 0 | 0 | 0 | 7 | 0 |
| 303 | 6 | 2 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | 0 |
| 31102026 | 21 | 7 | 0 | 0 | 0 | 0 | 4 | 0 | 0 | 3 | 0 |
| 404 | 21 | 7 | 0 | 0 | 0 | 0 | 5 | 0 | 1 | 1 | 0 |
| 505 | 9 | 3 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 0 |
| 606 | 3 | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 707 | 9 | 3 | 0 | 0 | 0 | 1 | 0 | 0 | 1 | 1 | 0 |
| 8675309 | 15 | 5 | 0 | 0 | 0 | 1 | 3 | 0 | 0 | 1 | 0 |

## Per seed against 25's and 26's ranges

Stuck game minutes. "Spread" is batch 2 minus batch 1.

| Seed | 25 b1 | 25 b2 | 26 b1 | 26 b2 | 27 b1 | 27 b2 | 27 spread | 27 against 25 | 27 against 26 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 | 0 | 15 | 9 | 9 | 18 | +9 | both above | overlaps |
| 202 | 60 | 18 | 30 | 9 | 21 | 12 | −9 | overlaps | overlaps |
| 20261030 | 3 | 6 | 18 | 21 | 0 | 30 | +30 | overlaps | overlaps |
| 303 | 12 | 15 | 15 | 6 | 0 | 6 | +6 | both below | overlaps |
| 31102026 | 15 | 12 | 33 | 15 | 18 | 21 | +3 | both above | overlaps |
| 404 | 3 | 33 | 3 | 3 | 30 | 21 | −9 | overlaps | both above |
| 505 | 12 | 24 | 9 | 9 | 3 | 9 | +6 | both below | overlaps |
| 606 | 39 | 18 | 0 | 9 | 12 | 3 | −9 | both below | overlaps |
| 707 | 0 | 15 | 6 | 24 | 0 | 9 | +9 | overlaps | overlaps |
| 8675309 | 45 | 42 | 6 | 33 | 9 | 15 | +6 | both below | overlaps |
| Total | 189 | 183 | 135 | 138 | 102 | 144 | +42 | both below | overlaps |

- **27's own spread, stuck minutes per seed:** median +6, range −9 to +30, median |Δ| 9 min. 26's was median 0, range
  −21 to +27, median |Δ| 9; 25's was median +1.5, range −42 to +30, median |Δ| 7.5. The three pairs' per-seed spreads
  are alike.
- **The total:** 102 and 144 differ by 42 minutes on one jar, against 3 for 26's pair and 6 for 25's. Per seed, 30 of
  the 42 are 20261030 (0, then 30).
- **Per seed against 26's range:** 9 seeds overlap it and one is above it on both batches (404: 30 and 21 against 3
  and 3).
- **Per seed against 25's range:** 4 overlap it, 4 are below it on both batches (303, 505, 606, 8675309) and 2 above
  it on both (101, 31102026).

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Deaths an hour, batch 1 | 1.0 | 1.5 | 0.0 | 1.5 | 0.0 | 0.0 | 2.5 | 0.0 | 0.5 | 1.0 |
| Deaths an hour, batch 2 | 1.5 | 1.0 | 1.5 | 0.5 | 0.0 | 0.0 | 1.0 | 0.5 | 1.5 | 1.5 |
| Iron pickaxe, batch 1 (min) | 19.1 | 11.6 | 13.0 | 31.9 | 17.1 | 25.6 | 14.5 | 23.7 | 10.7 | 35.9 |
| Iron pickaxe, batch 2 (min) | 12.2 | 18.8 | 14.1 | 33.2 | 26.3 | 29.2 | 67.9 | 14.1 | 9.5 | 29.0 |
| Portal lit, batch 1 (min) | 97.8 | no | 56.8 | 34.0 | no | 33.7 | 31.3 | 49.5 | 40.8 | 78.4 |
| Portal lit, batch 2 (min) | 115.0 | no | 67.2 | 92.2 | 43.9 | 59.9 | 73.8 | 37.9 | no | no |

## E-40a1a and F-25c4dl columns (#448 and #450)

From each seed's console, by `iter27-counts.py` (filed beside these notes), run unchanged on 27's and 26's consoles so
the two compare like for like.
- **Let go:** "`<P>` lets go of handing over `<piece>`", split by its reason ("stayed N blocks off" or "six of its
  steps failed").
- **Handed over:** "`<P>` handed over `<piece>`".
- **Taken up again:** "`<P>` takes up handing over `<piece>` again", split by its reason (the teammate nearer, the
  teammate's kit changed, or it holds more of the material).
- **Never taken up again:** let-gos with no later take-up for the same player and piece.
- **Shelter sealed:** goal lines containing ": its shelter sealed for the night - ".
- **Night iron:** raw iron picked up while tick mod 24000 is in [13000, 23000). Console times are turned into ticks
  between the "thinking at tick N" lines, with the "Done (" line as tick 0. That window is 42% of a day.
- **First iron pickaxe:** the earliest player's, from summary.json.

The take-up and shelter-sealed lines are new in 772dee37 (#448 and #450). Their 0 on 26 means the line did not exist
then, not that the behaviour was absent.

| | 27 batch 1 | 27 batch 2 | 26 batch 1 | 26 batch 2 |
| --- | --- | --- | --- | --- |
| Let go | 8 | 32 | 21 | 13 |
| Handed over | 0 | 5 | 0 | 0 |
| Taken up again | 4 | 22 | 0 (no such line) | 0 (no such line) |
| Never taken up again | 4 | 10 | 21 | 13 |
| Shelter-sealed lines | 76 | 77 | 0 (no such line) | 0 (no such line) |
| Raw iron at night, of all | 362 of 714 (51%) | 374 of 758 (49%) | 399 of 791 (50%) | 334 of 726 (46%) |

- **Hand-overs:** 5 in 27, all in batch 2 (303 1, 707 3, 8675309 1), against 0 in 26's two batches. "Handed over" is
  an existing line, so this one compares.
- **Let-gos:** 8 and 32 against 21 and 13, which overlaps.
- **Never taken up again:** 4 and 10 against 21 and 13. But 26 had no take-up line, so every let-go there counts as
  never taken up; this is not a like-for-like drop.
- **Night iron share:** 51% and 49% against 50% and 46%, which overlaps. Both are above the 42% the night takes of a
  day.
- **Shelter sealed:** 76 and 77 lines, on 5 seeds in batch 1 and 4 in batch 2. Seeds 20261030, 31102026, 606 and 707 have
  none in either batch.

**27, per seed**

**`e40a27-772dee3`**

| Seed | Let go (stayed off / six steps failed) | Handed over | Taken up again (by why) | Never taken up again | Shelter sealed for the night | Raw iron at night | Raw iron, all | Night share | First iron pickaxe (min) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 (0 / 0) | 0 | 0 (-) | 0 | 23 | 45 | 79 | 57% | 19.1 |
| 202 | 2 (1 / 1) | 0 | 2 (nearer 1, kit changed 1) | 0 | 16 | 29 | 88 | 33% | 11.6 |
| 20261030 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 55 | 70 | 79% | 13 |
| 303 | 0 (0 / 0) | 0 | 0 (-) | 0 | 19 | 31 | 108 | 29% | 31.9 |
| 31102026 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 29 | 55 | 53% | 17.1 |
| 404 | 0 (0 / 0) | 0 | 0 (-) | 0 | 16 | 31 | 62 | 50% | 25.6 |
| 505 | 0 (0 / 0) | 0 | 0 (-) | 0 | 2 | 32 | 68 | 47% | 14.5 |
| 606 | 5 (5 / 0) | 0 | 2 (kit changed 2) | 3 | 0 | 35 | 64 | 55% | 23.7 |
| 707 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 34 | 60 | 57% | 10.7 |
| 8675309 | 1 (1 / 0) | 0 | 0 (-) | 1 | 0 | 41 | 60 | 68% | 35.9 |

**`e40a27r-772dee3`**

| Seed | Let go (stayed off / six steps failed) | Handed over | Taken up again (by why) | Never taken up again | Shelter sealed for the night | Raw iron at night | Raw iron, all | Night share | First iron pickaxe (min) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 7 (7 / 0) | 0 | 6 (kit changed 6) | 1 | 13 | 50 | 109 | 46% | 12.2 |
| 202 | 0 (0 / 0) | 0 | 0 (-) | 0 | 18 | 36 | 51 | 71% | 18.8 |
| 20261030 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 50 | 96 | 52% | 14.1 |
| 303 | 7 (4 / 3) | 1 | 7 (kit changed 4, nearer 3) | 0 | 44 | 24 | 63 | 38% | 33.2 |
| 31102026 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 34 | 57 | 60% | 26.3 |
| 404 | 4 (4 / 0) | 0 | 0 (-) | 4 | 0 | 27 | 68 | 40% | 29.2 |
| 505 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 37 | 46 | 80% | 67.9 |
| 606 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 49 | 84 | 58% | 14.1 |
| 707 | 6 (6 / 0) | 3 | 2 (kit changed 2) | 4 | 0 | 40 | 98 | 41% | 9.5 |
| 8675309 | 8 (2 / 6) | 1 | 7 (nearer 4, kit changed 3) | 1 | 2 | 27 | 86 | 31% | 29 |

**26, per seed** (the same script, run on 26's consoles)

**`e40a26-f8b7ffe`**

| Seed | Let go (stayed off / six steps failed) | Handed over | Taken up again (by why) | Never taken up again | Shelter sealed for the night | Raw iron at night | Raw iron, all | Night share | First iron pickaxe (min) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 15 (14 / 1) | 0 | 0 (-) | 15 | 0 | 45 | 71 | 63% | 14.4 |
| 202 | 4 (2 / 2) | 0 | 0 (-) | 4 | 0 | 61 | 115 | 53% | 23.7 |
| 20261030 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 44 | 62 | 71% | 15.8 |
| 303 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 38 | 77 | 49% | 21 |
| 31102026 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 32 | 58 | 55% | 19.5 |
| 404 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 31 | 80 | 39% | 20.2 |
| 505 | 1 (0 / 1) | 0 | 0 (-) | 1 | 0 | 41 | 94 | 44% | 14.2 |
| 606 | 1 (0 / 1) | 0 | 0 (-) | 1 | 0 | 13 | 65 | 20% | 9.2 |
| 707 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 49 | 91 | 54% | 17.8 |
| 8675309 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 45 | 78 | 58% | 21.9 |

**`e40a26r-f8b7ffe`**

| Seed | Let go (stayed off / six steps failed) | Handed over | Taken up again (by why) | Never taken up again | Shelter sealed for the night | Raw iron at night | Raw iron, all | Night share | First iron pickaxe (min) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 9 (8 / 1) | 0 | 0 (-) | 9 | 0 | 42 | 88 | 48% | 26.6 |
| 202 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 42 | 70 | 60% | 14.5 |
| 20261030 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 23 | 59 | 39% | 19.8 |
| 303 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 22 | 38 | 58% | 19.2 |
| 31102026 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 26 | 48 | 54% | 43 |
| 404 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 23 | 60 | 38% | 14.7 |
| 505 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 31 | 93 | 33% | 25.7 |
| 606 | 3 (3 / 0) | 0 | 0 (-) | 3 | 0 | 50 | 91 | 55% | 15.7 |
| 707 | 0 (0 / 0) | 0 | 0 (-) | 0 | 0 | 42 | 85 | 49% | 20.8 |
| 8675309 | 1 (0 / 1) | 0 | 0 (-) | 1 | 0 | 33 | 94 | 35% | 35.1 |

## The read
- **Against 26's band:** only the portal (8 and 7 against 9 and 9) is outside it, fewer lit. Among kinds, hemmed in is
  outside it (more), and hole or pit and waiting are outside it (fewer). Everything else overlaps, including the stuck
  total (102 and 144 lie either side of 135 to 138).
- **Against 25's band:** the stuck total and spells are still outside it (lower), as in 26. The pickaxe is outside it
  (later). Busy-work loop, no walkable route, hole or pit and waiting are fewer than 25's band. The rest overlaps or is
  inside.
- **How firm:** each band is one pair of batches. 27's own pair differs by 42 stuck minutes, far more than 26's 3 or
  25's 6, so the pair bands for the total were narrower than its same-commit noise. The portal read is outside 26's
  band by one and two seeds.
- **The exit criteria:** stuck under a tenth is met by neither batch (6 and 4 of 10, needing 7). Deaths an hour is met
  by neither (0.75 and 1.0). The pickaxe and the portal are met by both.
- **No cause is named** for any change; eleven merges lie between 26's jar and 27's.

## Console
- **ERROR lines:** 0 in batch 1. 1 in batch 2, on 31102026 at 17:19:00 (about tick 131900): "Native player … blocked:
  Native action outcome requires reconciliation". Its cause is `IllegalStateException: Clutch conservation check
  failed; reconcile`, from `PlayerRuntime.observeRunner`. It is followed by "Native action outcome requires
  reconciliation; no replacement inventory will be created." The lines before it are Sol's "inventory full - Drop
  surplus …" and "Get out of the water: to dry ground" goals. The score counts Sol blocked for reconciliation once,
  10.1 minutes, to the end of the run. The run's item-sources line reads "8032 gained, 8032 paid by a source, 0 with
  none". No "Clutch conservation" line appears in any console of iterations 20 to 26.
- **Other reconciliation lines:** 2 in batch 2, both "native_eat BLOCKED: Food changed during use; reconcile before
  another request" (Sol on 20261030, Echo on 8675309). Each was followed by a completed `native_eat` within a second.
  The score counts no block for either.

## Not claimed
- No exit criterion is met by both batches except the iron pickaxe and the portal.
- No cause is named for any change against iteration 25 or 26.
- The hand-over and take-up counts show the lines fire. They do not show that more pieces reached the player who
  needed them. That would need the kit at the end, which these columns do not read.
- The iteration 26 consoles' census (beds, wool and sheep; `iter26-consoles/`) is filed here as asked. It reads 26's
  consoles, not 27's.
