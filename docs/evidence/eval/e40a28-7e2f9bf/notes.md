# e40a28-7e2f9bf and e40a28r-7e2f9bf: runner notes

Date: 2026-11-23
Agent / branch: Claude RUNNER 2 / `claude/e40a28-7e2f9bf-1123`
Status: two evaluation batches. No code change, no release gate.

**The read:** two identical batches of one jar, on iteration 25's protocol, read against iteration 26's and 27's pairs.
A figure is "outside" a pair's band when both of 28's batches fall outside the range that pair's two batches span.
"Overlaps" means at least one of 28's batches falls inside it, or one falls on each side. Each band is one pair of
batches; 27's pair showed that a pair's band can be narrower than its own same-commit noise.

## Setup, both batches
- **Build:** main 7e2f9bff (#469, F-25c4at1, merged on top of 9c8342fc), 2026-11-22. One jar, built from 7e2f9bff with
  `remapJar` and passed by `--jar` to both batches (sha256 61acba62…). It carries #455, #459, #460, #461, #463, #467
  (tests only) and #469.
- **Merges since iteration 27 (772dee37), first parent:** #453, #454, #456, #455, #459, #458, #460, #462, #461, #463,
  #466, #467, #469. No cause is assigned to any of them below.
- **Harness:** run.py at 174e0b3, as for iterations 24 to 27. Scoring: score.py from #399's branch, `--no-metrics`.
- **Settings:** iteration 25's protocol exactly: the ten seeds, `goal=nether; ticks=144000`, earned, strategist off,
  three at a time, replays on. No `--keep-world`.
- **Order (UTC):** batch 1 (`e40a28-7e2f9bf`) 2026-11-22 20:49 to 23:40, then batch 2 (`e40a28r-7e2f9bf`) 23:41 to
  2026-11-23 02:19. Each batch ran as two calls of five seeds (101, 202, 20261030, 303, 31102026, then 404, 505, 606,
  707, 8675309) into one build folder. Nothing else ran on the cores.
- **Completion:** 20 of 20 exit 0, 144000 ticks each. No re-runs.

## Exit figures

| Exit criterion | Threshold | Batch 1 | Batch 2 | 27's band | 26's band | Read |
| --- | --- | --- | --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) | 7 of 10 | 7 of 10 (101, 202, 31102026, 404, 505, 707, 8675309): yes | 7 of 10 (101, 20261030, 303, 404, 505, 606, 8675309): yes | 4 to 6 | 5 to 6 | outside both, more; met |
| Deaths a game hour, median | 0.5 or less | 0.5 [0 to 1.0]: yes | 0.25 [0 to 2.0]: yes | 0.75 to 1.0 | 0.5 to 0.75 | outside 27's, fewer; overlaps 26's; met |
| First iron pickaxe, median | under 30 min | 19.35 (10 of 10): yes | 17.5 (10 of 10): yes | 18.1 to 22.55 | 18.65 to 20.3 | overlaps both; met |
| Portal lit | 7 of 10 | 10 of 10: yes | 10 of 10: yes | 7 to 8 | 9 to 9 | outside both, more; met |

**Both batches meet all four exit criteria.** This is the first E-40 iteration where they do.

| | Batch 1 | Batch 2 | 27's band | 26's band | Read |
| --- | --- | --- | --- | --- | --- |
| Stuck game minutes, all seeds | 138 | 99 | 102 to 144 | 135 to 138 | overlaps both |
| Stuck spells | 46 | 33 | 34 to 48 | 45 to 46 | overlaps both |
| Hole or pit plus hemmed in (spells) | 18 (3 + 15) | 12 (5 + 7) | 14 to 23 | 15 to 16 | overlaps both |
| Deaths, all seeds | 10 | 10 | 16 to 18 | 17 to 21 | outside both, fewer |

Exactly 12 minutes is a tenth, which is not under; no seed sits on it in either batch.

**Deaths by kind:** batch 1 lava 1, fall 2, starving with a mob near 1, cornered 3, dark melee 2, other 1. Batch 2
lava 1, cornered 7, dark melee 2.

## Stuck spells by kind, all seeds

| Kind | 28 batch 1 | 28 batch 2 | 27 batch 1 | 27 batch 2 | 26 batch 1 | 26 batch 2 | Read |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Busy-work loop | 19 | 11 | 4 | 17 | 5 | 12 | overlaps both |
| Hemmed in | 15 | 7 | 9 | 16 | 5 | 4 | overlaps 27's; outside 26's, more |
| Hole or pit | 3 | 5 | 5 | 7 | 10 | 12 | overlaps 27's; outside 26's, fewer |
| Mining for a find | 6 | 8 | 10 | 3 | 11 | 10 | overlaps 27's; outside 26's, fewer |
| No walkable route | 1 | 2 | 0 | 2 | 1 | 3 | overlaps both |
| Water loop | 1 | 0 | 3 | 3 | 2 | 1 | outside 27's, fewer; overlaps 26's |
| Refused target repeated | 0 | 0 | 3 | 0 | 6 | 2 | overlaps 27's; outside 26's, fewer |
| Waiting for daylight or a teammate | 0 | 0 | 0 | 0 | 5 | 2 | inside 27's (0); outside 26's |
| Other or unmatched | 1 | 0 | 0 | 0 | 0 | 0 | — |

## Stuck per seed, by kind (spells; three game minutes each)

Columns: water loop, no walkable route, refused target repeated, hole or pit, hemmed in, waiting, mining for a find,
busy-work loop, other.

**Batch 1, `e40a28-7e2f9bf`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 202 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |
| 20261030 | 18 | 6 | 0 | 0 | 0 | 0 | 5 | 0 | 0 | 1 | 0 |
| 303 | 45 | 15 | 0 | 0 | 0 | 1 | 7 | 0 | 2 | 5 | 0 |
| 31102026 | 9 | 3 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 1 | 1 |
| 404 | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 505 | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 |
| 606 | 39 | 13 | 1 | 1 | 0 | 1 | 3 | 0 | 2 | 5 | 0 |
| 707 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| 8675309 | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 0 |

**Batch 2, `e40a28r-7e2f9bf`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 202 | 18 | 6 | 0 | 1 | 0 | 1 | 3 | 0 | 0 | 1 | 0 |
| 20261030 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| 303 | 3 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 |
| 31102026 | 21 | 7 | 0 | 0 | 0 | 2 | 3 | 0 | 1 | 1 | 0 |
| 404 | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 | 0 |
| 505 | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 |
| 606 | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 0 |
| 707 | 21 | 7 | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 5 | 0 |
| 8675309 | 9 | 3 | 0 | 1 | 0 | 0 | 0 | 0 | 2 | 0 | 0 |

## Per seed against 26's and 27's ranges

Stuck game minutes. "Spread" is batch 2 minus batch 1.

| Seed | 26 b1 | 26 b2 | 27 b1 | 27 b2 | 28 b1 | 28 b2 | 28 spread | 28 against 26 | 28 against 27 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 15 | 9 | 9 | 18 | 0 | 0 | 0 | both below | both below |
| 202 | 30 | 9 | 21 | 12 | 3 | 18 | +15 | overlaps | overlaps |
| 20261030 | 18 | 21 | 0 | 30 | 18 | 3 | −15 | overlaps | overlaps |
| 303 | 15 | 6 | 0 | 6 | 45 | 3 | −42 | overlaps | overlaps |
| 31102026 | 33 | 15 | 18 | 21 | 9 | 21 | +12 | overlaps | overlaps |
| 404 | 3 | 3 | 30 | 21 | 6 | 9 | +3 | both above | both below |
| 505 | 9 | 9 | 3 | 9 | 9 | 9 | 0 | overlaps | overlaps |
| 606 | 0 | 9 | 12 | 3 | 39 | 6 | −33 | overlaps | overlaps |
| 707 | 6 | 24 | 0 | 9 | 3 | 21 | +18 | overlaps | overlaps |
| 8675309 | 6 | 33 | 9 | 15 | 6 | 9 | +3 | overlaps | overlaps |
| Total | 135 | 138 | 102 | 144 | 138 | 99 | −39 | overlaps | overlaps |

- **28's own spread, stuck minutes per seed:** median +1.5, range −42 to +18, median |Δ| 13.5 min. 27's was median +6,
  range −9 to +30, median |Δ| 9; 26's median 0, range −21 to +27, median |Δ| 9. Per seed, 28's pair spreads more than
  either earlier pair: 303 (45, then 3) and 606 (39, then 6) carry most of batch 1's total.
- **Per seed:** 101 is 0 in both batches, below both earlier ranges. 404 is below 27's range and above 26's. The other
  eight overlap both.

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Deaths an hour, batch 1 | 0.5 | 1.0 | 0.5 | 0.5 | 0.0 | 0.0 | 1.0 | 0.5 | 0.5 | 0.5 |
| Deaths an hour, batch 2 | 0.0 | 0.0 | 1.0 | 0.0 | 0.5 | 0.0 | 2.0 | 0.0 | 1.0 | 0.5 |
| Iron pickaxe, batch 1 (min) | 14.8 | 22.5 | 12.0 | 24.9 | 21.7 | 14.5 | 16.1 | 27.3 | 25.4 | 17.0 |
| Iron pickaxe, batch 2 (min) | 13.3 | 16.9 | 14.1 | 46.6 | 17.6 | 18.9 | 24.3 | 17.3 | 17.4 | 27.4 |
| Portal lit, batch 1 (min) | 35.5 | 67.2 | 88.8 | 62.7 | 59.8 | 43.9 | 55.5 | 104.3 | 34.2 | 49.4 |
| Portal lit, batch 2 (min) | 37.3 | 40.5 | 56.5 | 66.1 | 79.2 | 37.2 | 40.4 | 36.7 | 66.7 | 39.0 |

## F-25c4at1's hold (#469)

Read from the consoles by `iter28-counts.py` (filed beside these notes), run unchanged on 26's, 27's and 28's
consoles. #469's marker was removed after its corpus gate, so the hold is read from the walk-back lines.
- **The tightened count (the lead's, 2026-11-22 20:01):**
  - A walk back is one `native_goto` outcome line (COMPLETED, BLOCKED, CANCELLED or FAILED) labelled "…: back the way
    it came". The consoles print no task start line and no position on the outcome line.
  - Its chosen point is the first walk-back goal line of that player after its previous native outcome. Its end is
    the player's next goal line after a COMPLETED outcome. "Ended lower" needs COMPLETED and that end below the chosen
    point.
  - "Taken again in the hold" is a later walk back whose first goal line is within 600 ticks of that completion,
    within 3 blocks horizontally of the earlier chosen point, and no higher. That is the walk back the hold refuses.
- **The raw proxy, an upper bound:** walks back counted from goal lines alone (a walk back's goal line after one
  without it, ending at the next goal line without it). Goal lines repeat at every re-plan, so this overcounts.

| Batch | Walk-back tasks (completed) | Ended lower, completed | Taken again in the hold | Raw proxy: walks back / ended lower / taken again |
| --- | --- | --- | --- | --- |
| 28 batch 1 | 74 (52) | 6 | 0 | 445 / 119 / 52 |
| 28 batch 2 | 75 (62) | 8 | 1 | 316 / 92 / 86 |
| 27 batch 1 | 103 (87) | 24 | 22 | 585 / 201 / 173 |
| 27 batch 2 | 140 (119) | 33 | 47 | 844 / 428 / 363 |
| 26 batch 1 | 274 (234) | 82 | 97 | 2105 / 1196 / 1176 |
| 26 batch 2 | 136 (96) | 9 | 3 | 682 / 232 / 163 |

- **Taken again in the hold:** 0 and 1 against 22 and 47 (27) and 97 and 3 (26). Outside both bands, fewer.
- **The one in 28 batch 2** (707, Echo, 01:50:25): its walk-back goal line was printed in the same second that its
  step up completed, at the step's lower block. That walk back was then BLOCKED ("No walkable route"), not completed.
  It is most likely the goal line's position lagging the step, not the hold failing; it is counted as it reads.
- **Ended lower, completed:** 6 and 8 against 24 to 33 (27) and 9 to 82 (26). Walk-back tasks: 74 and 75 against 103
  to 140 and 136 to 274.
- **The raw proxy's "taken again"** stays at 52 and 86. It counts re-printed goal lines, which the hold does not see.

**What the player does after each hold is armed** (`iter28-hold.py`), on the seeds where the tightened count was above
5 in 26 or 27 (101, 31102026, 707). Each completed walk back that ended lower arms a hold. The first of these in the
next 600 ticks decides the outcome: a death, or a new walk back inside the hold's bounds (a new loop). Otherwise the
outcome is where its last goal line in the window stands: out (higher than the chosen point, or more than 3 blocks
from it) or still there. Each is split by whether a "step up" or "clear …" climb goal came in the window.

| Batch | Seed | Hemmed in, spells (min) | Holds armed | Death | Out, after a climb step | Out, directly | New loop | Still there, climbing | Still there |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e40a28-7e2f9bf | 101 | 0 (0) | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| e40a28-7e2f9bf | 31102026 | 0 (0) | 1 | 0 | 0 | 1 | 0 | 0 | 0 |
| e40a28-7e2f9bf | 707 | 0 (0) | 1 | 0 | 0 | 1 | 0 | 0 | 0 |
| e40a28r-7e2f9bf | 101 | 0 (0) | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| e40a28r-7e2f9bf | 31102026 | 3 (9) | 3 | 0 | 0 | 2 | 0 | 0 | 1 |
| e40a28r-7e2f9bf | 707 | 1 (3) | 3 | 0 | 0 | 2 | 1 | 0 | 0 |
| e40a27-772dee3 | 101 | 0 (0) | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| e40a27-772dee3 | 31102026 | 0 (0) | 4 | 0 | 0 | 2 | 0 | 1 | 1 |
| e40a27-772dee3 | 707 | 0 (0) | 12 | 0 | 2 | 0 | 10 | 0 | 0 |
| e40a27r-772dee3 | 101 | 1 (3) | 30 | 0 | 9 | 2 | 17 | 2 | 0 |
| e40a27r-772dee3 | 31102026 | 4 (12) | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| e40a27r-772dee3 | 707 | 0 (0) | 3 | 0 | 1 | 1 | 1 | 0 | 0 |
| e40a26-f8b7ffe | 101 | 0 (0) | 31 | 0 | 1 | 0 | 30 | 0 | 0 |
| e40a26-f8b7ffe | 31102026 | 4 (12) | 39 | 0 | 2 | 1 | 32 | 3 | 1 |
| e40a26-f8b7ffe | 707 | 0 (0) | 2 | 0 | 1 | 1 | 0 | 0 | 0 |
| e40a26r-f8b7ffe | 101 | 0 (0) | 2 | 0 | 0 | 1 | 0 | 1 | 0 |
| e40a26r-f8b7ffe | 31102026 | 0 (0) | 1 | 0 | 0 | 0 | 1 | 0 | 0 |
| e40a26r-f8b7ffe | 707 | 3 (9) | 1 | 0 | 0 | 1 | 0 | 0 | 0 |

- **On these three seeds**, 28 arms 8 holds in all against 49 (27) and 76 (26). New loops: 1 against 28 and 63.
- **No death** follows an armed hold on any of these seeds in any of the six batches.
- **In 28**, 6 of the 8 armed holds end out directly and 1 still there. Too few to read a benefit or cost per hold;
  the change is that the holds are rarely armed at all.
- **Hemmed in on these seeds:** 0, 0, 0 (batch 1) and 0, 3, 1 spells (batch 2), against 0 to 4 per seed in 26 and 27.

## The other columns

`iter27-counts.py` (E-40a1a and F-25c4dl, as in iteration 27) and `iter28-counts.py`'s raw line counts:

| | 28 batch 1 | 28 batch 2 | 27 batch 1 | 27 batch 2 | 26 batch 1 | 26 batch 2 |
| --- | --- | --- | --- | --- | --- | --- |
| Hand-overs let go | 26 | 23 | 8 | 32 | 21 | 13 |
| Handed over | 0 | 0 | 0 | 5 | 0 | 0 |
| Taken up again | 12 | 16 | 4 | 22 | no such line | no such line |
| Shelter sealed for the night (lines) | 67 | 31 | 76 | 77 | no such line | no such line |
| Raw iron at night, of all | 359 of 710 (51%) | 394 of 735 (54%) | 362 of 714 (51%) | 374 of 758 (49%) | 399 of 791 (50%) | 334 of 726 (46%) |
| Hemmed in, spells (min) | 15 (45) | 7 (21) | 9 (27) | 16 (48) | 5 (15) | 4 (12) |
| Reserve turn lines, "Up for wood while its last pickaxe still digs" (#455) | 300 | 212 | no such line | no such line | no such line | no such line |
| Reserve set aside, "found no wood for its reserve in N ticks" (#455) | 1 | 2 | no such line | no such line | no such line | no such line |
| Lava told of, "Cast: walk to the lava `<P>` found at" goal lines (#459) | 76 | 44 | no such line | no such line | no such line | no such line |
| "Lava here, if anyone needs it for the cast." (#459) | 7 | 9 | no such line | no such line | no such line | no such line |
| Bed take-up goal lines (#460) | 19 | 15 | 18 | 24 | 26 | 12 |
| Bed left where it stands (#460) | 0 | 0 | no such line | no such line | no such line | no such line |
| Take-up refused (#460) | 0 | 0 | no such line | no such line | no such line | no such line |

- **Goal-line counts are raw lines,** repeated at every re-plan, not spells or events.
- **#455's reserve turn** fires on few seeds: batch 1 202 (119 lines) and 31102026 (181); batch 2 202 (104) and
  31102026 (108). Its 2400-tick bound was set aside 1 and 2 times.
- **#459's told-of lava:** goal lines naming the teammate who found it appear on 10 seeds in batch 1 and 9 in batch 2 (not 31102026).
  The goal line reads "found at", not "told of at" as the code's other string does; the count uses "found at".
- **#460:** "Take its bed back up in the morning" and "Back to its bed to take it up" existed before #460 (26 and 27
  have them). Its own lines, the bed left where it stands and the take-up refused, are 0 in both batches.
- **The take-up and shelter-sealed lines** did not exist in 26's code, nor #455's and #459's in 26's or 27's. Their
  "no such line" is not a comparison.

## The read
- **All four exit criteria are met by both batches,** for the first time in E-40: stuck under a tenth 7 and 7 of 10,
  deaths an hour 0.5 and 0.25, iron pickaxe 19.35 and 17.5 min, portal 10 and 10.
- **Outside both earlier bands:** stuck under a tenth (more), portal (more), deaths in all (10 and 10 against 16 to 21),
  and the hold's tightened count (0 and 1 against 3 to 97).
- **Overlapping:** the stuck total, spells, the signature, the pickaxe, busy-work loop and hemmed in (against 27).
- **How firm:**
  - Each band is one pair of batches. Stuck under a tenth is met by 7 of 10 in both batches, the threshold exactly;
    one seed fewer in either batch would miss it.
  - Deaths an hour, 0.5 in batch 1, sits on its threshold.
  - 28's per-seed spread (median |Δ| 13.5 min) is wider than 26's or 27's.
  - The stuck total did not fall outside either band, so the exit figures moved without the total doing so.
- **No cause is named** for any change; thirteen merges lie between 27's jar and 28's. The hold's count is the one
  figure tied to its change by its own definition.

## Console
- **ERROR lines:** 0 in either batch.
- **Reconciliation:** 1 line in each batch, "native_eat BLOCKED: Food changed during use; reconcile before another
  request" (Kindred in batch 1, Echo in batch 2). Each was followed by a completed `native_eat`. No "Clutch conservation"
  line and no "requires reconciliation" line in either batch.

## Not claimed
- No release gate. The exit criteria are E-40's ladder figures on one protocol, met by one pair of batches; the gate's
  own conditions are not read here.
- The hold's counts are read from goal and outcome lines, with positions from goal lines; they are not the hold's own
  record.
- The reserve, told-of-lava and bed counts show the lines fire. They do not show that the pickaxe, the lava or the bed
  reached anyone sooner.
