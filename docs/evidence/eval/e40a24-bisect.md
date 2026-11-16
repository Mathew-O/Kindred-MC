# Iteration 24's stuck rise: the bisect, and the first same-commit noise band

Date: 2026-11-16
Agent / branch: Claude RUNNER 2 / `claude/e40a24-bisect-1116`
Status: a diagnostic. It changes no code. The bisect is stopped (the lead's decision, below).

**The question:** iteration 24 (`e40a24-83321ac`) had more stuck minutes than iteration 23 (`e40a23-3115b1c`) on all ten
seeds: 249 against 108, sign test p=0.002. Which of the 30 merges between them caused it?

## The harness, for every batch here
- **run.py:** from #376's branch (`claude/eval-keep-level-1115` at 174e0b3), as for iteration 24. It differs from
  3115b1c's run.py only in #371's stop path and #376's extra copies in `finish()`, both of which act after a server
  ends. Neither touches the server's command line, its environment, the scenario or the policy.
- **The jar:** each batch's own ref. `e40a24r` used iteration 24's own build, passed by `--jar` (sha256 418709e0…).
- **Settings:** iteration 22's command: the ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three at a
  time, replays on, `--keep-world`. The scenario line in each summary is identical to iterations 22, 23 and 24.
- **Scoring:** score.py and compare.py from main 711f5a6 (`--no-metrics`).

## Step 1: `e40a24b-8e05ec4`, the midpoint (#356's merge)
**Pre-stated read (the lead's):**
- worse than 3115b1c on ≥9 of 10 means the cause is in the first half;
- not worse than 3115b1c (p≥0.10), with 83321ac worse than it on ≥9 of 10, means the cause is in the second half;
- anything else is a split cause or noise: stop and report.

**Result: "anything else".**
- **8e05ec4 against 3115b1c:** worse on 6, better on 4. Median +4.5 [−6.0, +13.5], one-sided p=0.377.
- **83321ac against 8e05ec4:** worse on 6, better on 3, 1 tied. Median +13.5 [−6.0, +25.5], one-sided p=0.254 with
  the tie dropped.
- **Totals (3115b1c, 8e05ec4, 83321ac):**
  - stuck minutes 108, 141, 249;
  - stuck spells 36, 47, 83;
  - busy-work loop 13, 16, 30;
  - hole or pit 7, 9, 24;
  - hemmed in 4, 4, 15.
- **Exit figures at the midpoint:** stuck under a tenth 6 of 10, deaths median 0.75 an hour, iron pickaxe 17.6 min,
  portal 8 of 10. 23 deaths, 0 ERROR lines.

## Step 2: `e40a24r-83321ac`, a replicate of iteration 24
**Pre-stated read (the lead's):**
- **Real:** the replicate's total is ≥200 and it is worse than 3115b1c on ≥8 of 10.
- **Noise:** the total is ≤160, or it is worse than 3115b1c on ≤6 of 10.
- **Between the two:** report it.

**Result:**
- **Total:** 255.
- **Against 3115b1c:** worse on 6, better on 1, tied on 3.
- **Read:** the "Real" rule fails on its 8-of-10 half.

**The decision (the lead's, in those words):** "I read step 2 by the rule as stated, and it is not Real. The 8-of-10
half fails at 6 worse, 1 better, 3 tied. So the bisect stops here and does not go on to 569256e. I won't change the
rule after seeing the result."

**What the rule did not foresee, stated plainly:** the total replicated. 83321ac gave 249, then 255, against 3115b1c's
single 108. The excess sits on 505, 606 and 707. That does not show a per-seed regression. Nor does it show the
140-minute gap is noise: 3115b1c has only one run, so its noise is not known. The next batch (`e40a23r`, a
replicate of 3115b1c) asks whether 108 reproduces.

**Iteration 24's 10-of-10 sign pattern did not repeat.** From now on, a single batch's per-seed sign test on stuck
minutes needs the noise band below beside it.

**The replicate's exit figures:**
- stuck under a tenth 2 of 10 (20261030, 303);
- deaths median 1.0 an hour [0 to 3.0];
- iron pickaxe median 17.7 min;
- portal lit 7 of 10 (not 202, 31102026, 505);
- 23 deaths; stuck spells 85.

**Harness note for step 2:** seed 707's first run was stopped partway by the runner session's 2-hour limit on
background work. run.py logged `seed 707: server stopped (pid 1396) on run.py's SIGTERM`, and no server was left
running. It was run again alone (one at a time, not three; in sprint that changes wall time, not game minutes),
with the same jar, run.py and settings, into the same build folder.

**One ERROR line, in that seed-707 re-run:**
- Sol was blocked at 07:53:31: "Native break outcome requires reconciliation (The block changed before an
  attributable break; inventory changed without an attributable break)", while digging down toward lava for the
  portal cast. The server logged "no replacement inventory will be created".
- Sol completed no action after it, about 94,000 ticks to the run's end. A blocked player raises no stuck spell, so
  it does not count in the stuck total.
- It is a reconciliation block, as on e40c4's seed 101, but from a different check: e40c4's was an attack's item conservation ("Attack item conservation check failed; reconcile", Kindred, about tick 223,500), this one a break's.

## The first same-commit noise band: the two 83321ac runs, side by side

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `e40a24-83321ac` | 18 | 33 | 24 | 9 | 24 | 21 | 24 | 21 | 36 | 39 | 249 |
| `e40a24r-83321ac` | 24 | 12 | 3 | 6 | 21 | 24 | 63 | 48 | 39 | 15 | 255 |
| Replicate minus first | +6 | −21 | −21 | −3 | −3 | +3 | +39 | +27 | +3 | −24 | +6 |
| `e40a23-3115b1c`, for reference | 9 | 21 | 0 | 6 | 21 | 12 | 9 | 12 | 3 | 15 | 108 |
| `e40a24b-8e05ec4`, for reference | 18 | 39 | 6 | 0 | 30 | 0 | 33 | 0 | 6 | 9 | 141 |

- **Per seed, stuck minutes, same commit:** median difference 0, range −24 to +39, median |Δ| 13.5 min.
  compare.py: helped on 5, hurt on 5, sign test p=1.0.
- **Per run, stuck minutes in total, same commit:** 249 and 255.
- **So:** a per-seed stuck difference within about ±24 minutes cannot be told from a second run of the same commit.

## Not separated
- Whether 3115b1c's 108 was a low draw, that is, whether the regression is real in total. `e40a23r` answers it, by the
  lead's read stated before it runs:
  - total ≤160: the regression is real in total, and the bisect resumes at 569256e on totals;
  - total ≥200: 108 was a low draw, and the bisect closes;
  - anything between: the lead decides.
