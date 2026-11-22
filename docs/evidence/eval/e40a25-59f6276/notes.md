# e40a25-59f6276 and e40a25r-59f6276: runner notes

Date: 2026-11-22
Agent / branch: Claude RUNNER 2 / `claude/e40a25-59f6276-1122`
Status: two evaluation batches. No code change, no release gate.

**The question (lead), stated before the runs:** what are current main's exit figures, and how far apart are two
identical batches of it?

## Setup, both batches
- **Build:** main 59f62762 (with #426 and #428). One jar, passed by `--jar` to both batches (sha256 9102bd2b…).
- **Harness:** run.py from #376's branch at 174e0b3, as for iteration 24 and its bisect.
- **Settings:** iteration 22's command: the ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three at a
  time, replays on (the default). The scenario line in both summaries is iteration 24's.
- **One difference from iteration 24:** no `--keep-world`. The container had 2.7 GB free. `--keep-world` acts only
  after a server ends, so it does not move these figures, but no world from these batches is kept.
- **Order:** batch 1 (`e40a25-59f6276`) 21:05 to 00:04 UTC, then batch 2 (`e40a25r-59f6276`) 00:04 to 02:35 UTC,
  2026-11-21 to 22. Each batch ran as two calls of five seeds (101, 202, 20261030, 303, 31102026, then 404, 505, 606,
  707, 8675309) into one build folder. Nothing else ran on the cores.
- **Completion:** 20 of 20 exit 0, 144000 ticks each.
- **Scoring:** score.py from #399's branch (`--no-metrics`), with its blocked-for-reconciliation fields. The exit
  figures are recomputed from the summaries by the rule used for iterations 22 to 24.

## Exit figures

| Exit criterion | Threshold | Batch 1, `e40a25-59f6276` | Batch 2, `e40a25r-59f6276` |
| --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) | 7 of 10 | 4 of 10 (101, 20261030, 404, 707): no | 2 of 10 (101, 20261030): no |
| Deaths a game hour, median | 0.5 or less | 0.75 [0 to 4.0]: no | 0.5 [0 to 2.5]: yes |
| First iron pickaxe, median | under 30 min | 16.6 (10 of 10): yes | 17.15 (10 of 10): yes |
| Portal lit | 7 of 10 | 8 of 10 (not 202, 8675309): yes | 6 of 10 (not 101, 202, 31102026, 707): no |

| | Batch 1 | Batch 2 |
| --- | --- | --- |
| Stuck game minutes, all seeds | 189 | 183 |
| Stuck spells | 63 | 61 |
| Hole or pit plus hemmed in (spells) | 21 (8 + 13) | 10 (8 + 2) |
| Deaths, all seeds | 20 (8 on 8675309) | 16 (5 on 8675309) |

Exactly 12 minutes is a tenth, which is not under: 303 and 505 in batch 1, 31102026 in batch 2.

## The spread: batch 2 minus batch 1, per seed

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Stuck minutes, batch 1 | 0 | 60 | 3 | 12 | 15 | 3 | 12 | 39 | 0 | 45 | 189 |
| Stuck minutes, batch 2 | 0 | 18 | 6 | 15 | 12 | 33 | 24 | 18 | 15 | 42 | 183 |
| Difference | 0 | −42 | +3 | +3 | −3 | +30 | +12 | −21 | +15 | −3 | −6 |
| Signature, batch 1 | 0 | 7 | 0 | 1 | 2 | 0 | 2 | 4 | 0 | 5 | 21 |
| Signature, batch 2 | 0 | 1 | 0 | 2 | 3 | 1 | 0 | 1 | 1 | 1 | 10 |
| Deaths an hour, batch 1 | 0.5 | 0.5 | 1.5 | 0.5 | 1.0 | 0.0 | 1.0 | 0.0 | 1.0 | 4.0 | |
| Deaths an hour, batch 2 | 0.0 | 1.5 | 1.0 | 1.0 | 0.5 | 0.5 | 0.5 | 0.0 | 0.5 | 2.5 | |
| Iron pickaxe, batch 1 (min) | 10.1 | 10.0 | 16.8 | 35.5 | 30.2 | 15.1 | 16.4 | 24.6 | 21.6 | 10.8 | |
| Iron pickaxe, batch 2 (min) | 14.3 | 15.6 | 16.5 | 19.4 | 27.4 | 21.7 | 17.8 | 24.0 | 14.0 | 11.1 | |
| Portal lit, batch 1 (min) | 43.1 | no | 77.9 | 93.5 | 63.4 | 44.0 | 41.6 | 65.6 | 69.4 | no | 8 |
| Portal lit, batch 2 (min) | no | no | 36.8 | 42.1 | no | 53.8 | 50.5 | 62.6 | no | 104.3 | 6 |

"Signature" is hole or pit plus hemmed in, the kinds the bisect was read by.

- **Stuck minutes:** median difference +1.5, range −42 to +30, median |Δ| 7.5 min. Totals 189 and 183.
- **Signature:** totals 21 and 10. Hemmed in alone 13 and 2.
- **Deaths an hour:** median difference −0.5, range −1.5 to +1.0, median |Δ| 0.5.
- **Iron pickaxe:** median difference 0.0, range −16.1 to +6.6 min, median |Δ| 3.5.
- **Portal lit:** the same on 6 seeds (5 lit in both, 202 in neither); different on 4 (101, 31102026 and 707 lit only
  in batch 1, 8675309 only in batch 2). Where both lit: −41.1, −51.4, +9.8, +8.9, −3.0 min.

**Against iteration 24's same-commit band** (two runs of 83321ac, [e40a24-bisect.md](../e40a24-bisect.md)): stuck
minutes per seed differed by a median of 0, range −24 to +39, median |Δ| 13.5, totals 249 and 255; signature 39 and
34. Here the range is about as wide (−42 to +30), and the median |Δ| is smaller.

## What two identical batches show
- **The exit criteria move between identical batches:**
  - stuck under a tenth 4 against 2 of 10;
  - portal 8 against 6;
  - deaths 0.75 against 0.5, either side of the threshold.

  So one batch on one side of a threshold does not show that main meets it, or fails it.
- **The totals are close:** stuck 189 and 183, pickaxe medians 16.6 and 17.15.
- **Hemmed in moves a lot between identical runs:** 13 against 2 on one jar with the same settings, and the signature
  21 against 10. This bears on the bisect's ruling ([step 8](../e40a24-bisect.md#addendum-2026-11-21-step-8-does-not-confirm-the-tie)):
  - steps 6 and 7 were read on hemmed in, 15 to 19 against 4 to 7;
  - step 8 gave 9 with the capture on, 23 with it off.

  Both gaps are within this pair's.

## Console
- **ERROR lines:** 0 in either batch.
- **Reconciliation:** no `reconciliationBlocked=true` line in either batch; the score's blocks for reconciliation are
  0 on every seed. Batch 1 has two `native_eat BLOCKED: Food changed during use; reconcile before another request`
  lines:
  - 202, Sol, 21:37:46: Sol completed 2,621 actions after it;
  - 20261030, Kindred, 21:52:14: 15 s before that seed's end.

  Batch 2 has none.

## Not claimed
- No exit criterion is met by both batches together except the iron pickaxe. No batch is batch 1 of 2.
- No cause is named for any difference; this is one jar run twice.
