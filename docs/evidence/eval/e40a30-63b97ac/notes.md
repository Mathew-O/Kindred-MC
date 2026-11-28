# e40a30-63b97ac and e40a30r-63b97ac: runner notes

Date: 2026-11-23
Agent / branch: Claude RUNNER 2 / `claude/e40a30-63b97ac-1123`
Status: two evaluation batches. No code change, no release gate.

**The read:** two identical batches of one jar, on iteration 25's protocol, read against iteration 28's and 29's pairs.
"Outside" a pair's band means both of 30's batches fall outside the range that pair's two batches span. Each band is
one pair.

## Setup, both batches
- **Build:** main 63b97ac9, 2026-11-23, one jar passed by `--jar` (sha256 0ab2e931…).
- **What it carries over iteration 29 (9a0921b5):** twenty-odd merges, among them #498 (the #475 revert), #492
  (F-25c6d part 2), #411, #477, #479, #480, #482, #484, #485, #486, #488 and #495 (dm1's dig back up to the frame).
- **What it doesn't carry:** dm3 (REFACTOR's walk fix) and F-25b5. So 30 is not a read of the revert alone.
- **Harness and settings:** as for iterations 25 to 29. No `--keep-world`.
- **Order (UTC):** batch 1 14:44 to 17:39, batch 2 17:39 to 20:32, as four calls of five seeds. The calls ran
  detached from the runner session, so its 2-hour background limit could not cut them.
- **Completion:** 20 of 20 exit 0, 144000 ticks each. No re-runs.
- **Consoles:** on `claude/corpus-data-e40a30-consoles-1123` (788c3f53).

## Exit figures

| Exit criterion | Threshold | Batch 1 | Batch 2 | 29's band | 28's band | Read |
| --- | --- | --- | --- | --- | --- | --- |
| Stuck under a tenth (under 12 game minutes) | 7 of 10 | 3 of 10 (404, 606, 8675309): no | 6 of 10 (101, 20261030, 303, 404, 606, 8675309): no | 4 to 6 | 7 | outside 28's, fewer; overlaps 29's; not met |
| Deaths a game hour, median | 0.5 or less | 0.5 [0 to 2.0]: yes | 0.25 [0 to 3.0]: yes | 0.5 | 0.25 to 0.5 | overlaps both; met |
| First iron pickaxe, median | under 30 min | 18.7 (10 of 10): yes | 18.4 (10 of 10): yes | 19.5 to 23.05 | 17.5 to 19.35 | outside 29's, earlier; overlaps 28's; met |
| Portal lit | 7 of 10 | 9 of 10 (not 101): yes | 8 of 10 (not 101, 707): yes | 7 to 10 | 10 | outside 28's, fewer; overlaps 29's; met |

| | Batch 1 | Batch 2 | 29's band | 28's band | Read |
| --- | --- | --- | --- | --- | --- |
| Stuck game minutes, all seeds | 258 | 111 | 180 to 195 | 99 to 138 | overlaps both (one each side of 29's) |
| Stuck spells | 86 | 37 | 60 to 65 | 33 to 46 | overlaps both |
| Hemmed in, spells (min) | 21 (63) | 4 (12) | 21 to 22 | 7 to 15 | overlaps both |
| Deaths, all seeds | 14 | 15 | 8 to 15 | 10 | outside 28's, more; overlaps 29's |
| F-25c4at1's hold: taken again in the hold (tightened) | 19 | 1 | 9 to 24 | 0 to 1 | overlaps both |
| "Climb to find another way" starts | 208 | 208 | 158 to 241 | 180 to 856 | inside both |

- **The same-commit spread is the widest yet.** The two batches differ by 147 stuck minutes on one jar: per seed,
  median −12, range −51 to +6, median |Δ| 12. 505 went 63 then 12, 202 54 then 12, 31102026 39 then 24.
- **Deaths by kind:** batch 1 lava 3, fall 1, cornered 3, dark melee 4, other 3. Batch 2 cornered 6, dark melee 6,
  other 3.

## Stuck spells by kind
- **Batch 1:** water 8, route 9, refused 2, hole 13, hemmed 21, waiting 2, mining 6, busy 24, other 1.
- **Batch 2:** water 4, route 1, hole 5, hemmed 4, waiting 1, mining 9, busy 13.

Per seed:

**Batch 1, `e40a30-63b97ac`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 15 | 5 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |
| 202 | 54 | 18 | 0 | 2 | 0 | 3 | 6 | 0 | 1 | 6 | 0 |
| 20261030 | 24 | 8 | 0 | 4 | 0 | 0 | 0 | 1 | 0 | 3 | 0 |
| 303 | 12 | 4 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | 0 |
| 31102026 | 39 | 13 | 1 | 3 | 0 | 4 | 1 | 0 | 0 | 3 | 1 |
| 404 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 |
| 505 | 63 | 21 | 2 | 0 | 2 | 2 | 14 | 0 | 0 | 1 | 0 |
| 606 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |
| 707 | 36 | 12 | 0 | 0 | 0 | 4 | 0 | 0 | 3 | 5 | 0 |
| 8675309 | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 1 | 0 |

**Batch 2, `e40a30r-63b97ac`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 202 | 12 | 4 | 0 | 0 | 0 | 1 | 0 | 1 | 1 | 1 | 0 |
| 20261030 | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 0 |
| 303 | 3 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 |
| 31102026 | 24 | 8 | 1 | 1 | 0 | 2 | 0 | 0 | 3 | 1 | 0 |
| 404 | 3 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 505 | 12 | 4 | 2 | 0 | 0 | 0 | 0 | 0 | 1 | 1 | 0 |
| 606 | 6 | 2 | 0 | 0 | 0 | 0 | 1 | 0 | 1 | 0 | 0 |
| 707 | 42 | 14 | 0 | 0 | 0 | 1 | 3 | 0 | 2 | 8 | 0 |
| 8675309 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 |

## Per seed against 28's and 29's ranges

| Seed | 28 b1 | 28 b2 | 29 b1 | 29 b2 | 30 b1 | 30 b2 | 30 spread | 30 against 28 | 30 against 29 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 | 0 | 21 | 3 | 15 | 0 | −15 | overlaps | overlaps |
| 202 | 3 | 18 | 6 | 3 | 54 | 12 | −42 | overlaps | both above |
| 20261030 | 18 | 3 | 15 | 51 | 24 | 6 | −18 | overlaps | overlaps |
| 303 | 45 | 3 | 9 | 3 | 12 | 3 | −9 | overlaps | overlaps |
| 31102026 | 9 | 21 | 15 | 6 | 39 | 24 | −15 | both above | both above |
| 404 | 6 | 9 | 39 | 6 | 3 | 3 | 0 | both below | both below |
| 505 | 9 | 9 | 42 | 48 | 63 | 12 | −51 | both above | overlaps |
| 606 | 39 | 6 | 27 | 6 | 3 | 6 | +3 | overlaps | overlaps |
| 707 | 3 | 21 | 0 | 39 | 36 | 42 | +6 | both above | overlaps |
| 8675309 | 6 | 9 | 6 | 30 | 9 | 3 | −6 | overlaps | overlaps |
| Total | 138 | 99 | 180 | 195 | 258 | 111 | −147 | overlaps | overlaps |

## The watches
- **Seed 606 (#411):**
  - Stuck 3 and 6 min, against 39 and 6 (28) and 27 and 6 (29).
  - Its cast lines are "walk up to the lava" 46 and 52, and "step closer" 254 and 143, across the three players.
  - 29 batch 1's 606 had a walk-up loop, Sol, 323 instant completions. 30's has none.
- **Portal lit (#485):** 9 and 8 of 10, against 10 and 10 (28) and 7 and 10 (29).

## The "walk up to the lava" loop

`iter30-cast.py` and `iter30-loop.py` (filed beside these notes):

| | "Cast: walk up to the lava" lines, all / in stuck windows | "Cast: step closer" lines, all / in stuck windows | Stuck minutes in walk-up loops, of matched spells |
| --- | --- | --- | --- |
| 30 batch 1 | 3883 / 3659 | 915 / 327 | 12 of 225 (202 Sol, 31102026 Echo, 707 Sol, 20261030 Echo) |
| 30 batch 2 | 2581 / 2365 | 495 / 40 | 6 of 84 (202 Sol, 20261030 Sol) |
| 29 batch 1 | 406 / 330 | 693 / 307 | 3 of 156 (606 Sol) |
| 29 batch 2 | 83 / 5 | 550 / 55 | 0 of 174 |

- **What the loop is:** a stuck spell is in it when the player's window holds 100 or more "native_goto COMPLETED [Cast:
  walk up to the lava]" lines. The walk completes at once and is re-issued with no new goal line, about 1,200 times
  in under 3 minutes, at one spot, with the lava's BLOCKED targets within 2 blocks.
- **202 Sol, batch 1:** 1219 completions against 4 goal lines, 14:59:06 to 15:02:01, standing at 33 −54 −53.
- **31102026 Echo, batch 1:** 1053 completions against 3 goal lines, at 113 −54 275.
- **Its cost in stuck time is small:** one 3-minute spell each, after which the watchdog gives the spot up.
- **"Matched spells":** spells whose stuck line lies within 400 ticks of an eval-result entry. The rest are
  unmatched, so these totals fall short of the summary's.

## Which merged changes' lines fired in the stuck spells

`iter30-spells.py`, run with `iter30-new-fragments.tsv`. A change's lines are the string fragments its merge added to
the main sources, absent from 9a0921b5's sources and from every iteration 29 batch 1 console. #492 is also counted
by a hazard-spell line naming a diagonal cell, or a native_retreat with a diagonal label. No "F-…-marker" line occurs.
#411 and #477 add no string, so they are invisible to this method.

| Change | Batch 1, stuck minutes in spells where it fired | Batch 2 |
| --- | --- | --- |
| #495 | 6 (505 Sol; 606 Sol) | 6 |
| #492 | 3 (8675309 Sol) | 3 |
| #484 | 3 (the same spell) | 3 |
| No change's line | 216 | 78 |

**The three big seeds in batch 1** (63, 54 and 39 minutes):
- **505:** top steps "dig toward elsewhere: clear" (79), "Climb to find another way: clear" (73) and "dig toward
  elsewhere: advance" (69).
- **202:** "Cast: walk up to the lava" (1188), "dig toward elsewhere: clear" (255) and "Cast: step closer" (248).
- **31102026:** "Cast: walk up to the lava" (1054), "Dig down toward iron: clear" (292) and "Dig down toward iron: step
  down" (92). Its four route spells are Sol on "Head back up toward home".

## Deaths with #471's fight lines (`iter29-deaths.py`)
- **In or just after a fight:** batch 1, 3 of 14 deaths; batch 2, 2 of 15. The per-death fight-start state is in
  `iter29-deaths.py`'s table.

## Console
- **e40a30-63b97ac:** 1 ERROR line, on 303 at 16:09:16: "Native player … blocked: Native action outcome requires
  reconciliation", caused by `IllegalStateException: Native placement effect cannot be verified; reconcile (placed=true,
  at -28, -52, 95: air; slots changed: 4: 33 cobblestone -> 32 cobblestone)` from `PlayerRuntime.observeRunner`. It is
  followed by "no replacement inventory will be created". The score counts Echo on 303 blocked for reconciliation once,
  37.2 minutes, to the run's end; blocked minutes are counted apart from stuck minutes. It is a different check from 27's
  clutch conservation error.
- **e40a30r-63b97ac:** no ERROR line and no reconciliation line.

## The read
- **Stuck under a tenth is not met** by either batch (3 and 6 of 10). The other three criteria are met by both.
- **The pair's spread is the widest yet,** 258 against 111 stuck minutes on one jar. So the stuck total overlaps both
  earlier bands, and neither batch alone says whether the #475 revert recovered anything.
- **The walk-up loop is new in scale** (about 2,500 to 3,900 lines a batch against 83 to 406) but small in stuck time
  (12 and 6 minutes).
- **No cause is named.** Twenty-odd merges lie between 29's jar and 30's.

## Not claimed
- No release gate. The change-line read shows which lines fired, not that a change caused or cured a spell.
