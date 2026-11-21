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

## Addendum, 2026-11-21: `e40a23r`, the decision, and step 3

### `e40a23r-3115b1c`, a replicate of iteration 23
- **Harness:** 3115b1c's own jar, rebuilt from its source tree and passed by `--jar` (sha256 76f7d576…). run.py at
  174e0b3, as above. Run in two calls of five seeds, three at a time, so neither nears the runner's 2-hour limit.
  10 of 10 exit 0, 144,000 ticks each.
- **Result:** a total of **171**, between the lead's pre-stated lines (≤160 real in total; ≥200 a low draw).
- **Per seed against the first 3115b1c run:** median +7.5, range −12 to +21, median |Δ| 12.0. That is inside the
  83321ac band.
- **Its exit figures:** stuck under a tenth 3 of 10; deaths median **0.5** an hour [0 to 3.5]; iron pickaxe 23.7 min;
  portal 9 of 10; 20 deaths; 0 blocks for reconciliation.
  - The deaths median of 0.5 meets that criterion. It is **3115b1c's code**, not main's.

### The decision on `e40a23r` (the lead's)
**The regression is real; the bisect resumes at 569256e.** The reasons given:
- Both 83321ac runs (249, 255) sit about 80 above even the higher 3115b1c run (171), and about 110 above its mean.
  That is well outside 3115b1c's own spread of 63.
- **The spell kinds carry a signature the totals hide:** hole or pit plus hemmed in is 11 and 10 in the 3115b1c runs,
  and 39 and 34 in the 83321ac runs.
- 8e05ec4 (141; signature 13) sits with 3115b1c, so the cause is in 8e05ec4..83321ac.

From here each step is read on the signature first and the total second:
- **Bad** (the cause at or before the step): signature ≥28 and total ≥200.
- **Good** (the cause after it): signature ≤16 and total ≤180.
- **Anything else:** the lead decides.

### Step 3: `e40a24c-569256e` (#369's merge)
- **Harness:** its own jar by `--jar` (sha256 68e139b0…), run.py at 174e0b3, two calls of five seeds, three at a time.
  10 of 10 exit 0, 144,000 ticks each (2026-11-16, 10:43 to 12:54 UTC).
- **Result:** total **123**, signature **18** (hole or pit 11, hemmed in 7). Neither line is met: the total is Good,
  and the signature is 2 over Good and 10 under Bad.
- **The decision (the lead's): Good.** In the lead's words, the total of 123 is "well inside the good line of 180, and
  below even 3115b1c's 171"; the signature of 18 is "2 over the good line and 10 under the bad one", near the good
  commits' 10 to 13 and far from the bad commits' 34 to 39.
- **So the regression lies in the five merges after it:** #371, #374, #357, #375, #377. #371 (run.py's stop) and #374
  (a corpus note) are harness and docs.
- **Step 4** runs at 3360ef28 (#375's merge, before #377), by the same read; it splits #377 from #357 and #375. If it
  is Good, the cause is #377; if it is Bad, step 5 runs at d9c7b13c to split #357 from #375. (A first start at
  d9c7b13c was stopped after two minutes when the lead chose 3360ef28; run.py stopped its three servers cleanly, and
  it is not scored.)
- **Its exit figures:** stuck under a tenth 5 of 10; deaths median 0.5 an hour [0 to 2.0], on main's code at 569256e;
  iron pickaxe 20.8 min; portal 8 of 10; 13 deaths; 41 spells; 0 blocks for reconciliation.

### Every run so far, stuck minutes by seed

| Run | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 | Total | Signature |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `e40a23-3115b1c` | 9 | 21 | 0 | 6 | 21 | 12 | 9 | 12 | 3 | 15 | 108 | 11 |
| `e40a23r-3115b1c` | 24 | 18 | 12 | 9 | 24 | 0 | 21 | 3 | 24 | 36 | 171 | 10 |
| `e40a24b-8e05ec4` | 18 | 39 | 6 | 0 | 30 | 0 | 33 | 0 | 6 | 9 | 141 | 13 |
| `e40a24c-569256e` | 3 | 6 | 3 | 18 | 27 | 6 | 12 | 21 | 6 | 21 | 123 | 18 |
| `e40a24-83321ac` | 18 | 33 | 24 | 9 | 24 | 21 | 24 | 21 | 36 | 39 | 249 | 39 |
| `e40a24r-83321ac` | 24 | 12 | 3 | 6 | 21 | 24 | 63 | 48 | 39 | 15 | 255 | 34 |

The signature is hole or pit plus hemmed in, by score.py's own kinds.

## Addendum, 2026-11-21: steps 4 and 5 name #375

### Step 4: `e40a24e-3360ef2` (#375's merge, before #377)
- **Harness:** its own jar by `--jar` (sha256 797c6dd5…), run.py at 174e0b3, two calls of five seeds, three at a time.
  10 of 10 exit 0, 144,000 ticks each (02:25 to 04:56 UTC).
- **Result: Bad.** Total **243** (≥200), signature **30** (≥28: hole or pit 12, hemmed in 18). So the cause is at or
  before 3360ef2: #357 or #375 (#371 and #374 are harness and docs).
- **Its exit figures:** stuck under a tenth 1 of 10; deaths median 0.25 an hour; iron pickaxe 19.3 min; portal lit
  10 of 10; 8 deaths; 81 spells; 0 blocks for reconciliation.
- A first start of step 4 at d9c7b13c was stopped after two minutes when the lead chose 3360ef28; it is not scored.

### Step 5: `e40a24f-d9c7b13` (#357's merge)
- **Harness:** its own jar by `--jar` (sha256 df4e1fce…), the same jar built for that stopped start, run.py at
  174e0b3, two calls of five seeds. 10 of 10 exit 0, 144,000 ticks each (04:58 to 07:25 UTC).
- **Result:** signature **13** (≤16: hole or pit 7, hemmed in 6), total **192** (over 180, under 200). Neither line
  is met on both measures.
- **The decision (the lead's): Good**, on the signature-first read: its 13 sits inside the good builds' 10 to 13, and
  its total of 192 is close to 3115b1c's own spread (108 and 171).
- **So the cause is #375** (F-27a4, the waking-scene cutter), the one merge between d9c7b13 and 3360ef2. The
  signature goes from 13 to 30 across it.
- **Its exit figures:** stuck under a tenth 2 of 10; deaths median 1.25 an hour; iron pickaxe 21.2 min; portal 9 of 10;
  20 deaths; 64 spells; 0 blocks for reconciliation.

| Run | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 | Total | Signature |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `e40a24f-d9c7b13` (Good) | 18 | 30 | 0 | 18 | 6 | 27 | 39 | 15 | 18 | 21 | 192 | 13 |
| `e40a24e-3360ef2` (Bad) | 9 | 18 | 18 | 18 | 21 | 33 | 27 | 39 | 18 | 42 | 243 | 30 |

### What #375 runs in play, and what was checked
- **In play, #375 adds:** three fields set at each death (place, dimension, the death message), and at each respawn,
  with replays on, `captureWaking`. That reads the blocks and entities of a box round the waking (up to 64 × 34 × 64
  when the death is within reach) and the campaign's memory of it, on the server thread.
- **Read in the code:** nothing in it writes to the player, its memory or the world. `memoryIn` only reads and builds
  JSON. The death-time capture keeps its box and order. One side effect stands: reading a block in a chunk that is not
  loaded loads it, or generates it, synchronously.
- **Checked in the logs:** in the 5 s after each "respawned at", step 4 had 0 WARN, 0 ERROR and 0 exception lines over
  8 respawns, and step 5 the same over 20. Step 4's 8 respawn captures took a median of 6.0 ms and at most 24.4 ms on
  the server thread. So nothing throws, and the respawn's handling after it runs.
- **Checked offline, in step 4's kept worlds:** every chunk in the 8 capture boxes (83 chunks) had been near a player at
  some point (`InhabitedTime` above 0), and none was missing. So no capture reached into chunks it alone loaded or
  generated; the lead accepted this as ruling out cold loads.

### Step 6: `e40a24g`, 3360ef2 without the respawn capture (a diagnostic, not for merge)
- **The build:** 3360ef2 with only `captureWaking(entry, body);` removed from `respawnNative`
  ([the diff](e40a24g-3360ef2-nowake/diagnostic.diff)), built and reverted; jar sha256 2a76aeb5…. Same harness.
  10 of 10 exit 0, 144,000 ticks each. No "respawn replay" line in any console, so the edit took.
- **Result:** signature **10** (hole or pit 6, hemmed in 4), total **201**. The lead's call: **Good** on the signature,
  provisionally: the one call takes the signature from 30 to 10, and 201 sits in the good builds' spread.
- **Its exit figures:** stuck under a tenth 2 of 10; deaths median 0.5 an hour; iron pickaxe 17.85 min; portal 6 of 10;
  22 deaths.

### Step 7: `e40a24h`, a replicate of 3360ef2 with the capture on
- **The build:** step 4's own jar (sha256 797c6dd5…), the same harness. 10 of 10 exit 0, 144,000 ticks each; 23
  respawn captures.
- **Result:** signature **22** (hole or pit 4, hemmed in 18), total **207**: between the lines.
- **The decision (the lead's), on hemmed in alone:** "With the capture on, hemmed-in is 18, 18, 15 and 19 (3360ef2 ×2,
  83321ac ×2). In every capture-off run (the good builds and step 6) it's 4 to 7. That's complete separation, 4
  against 6, about p ≈ 0.005. So the capture is tied to hemmed-in. Hole-or-pit is noise between runs."
- **Its exit figures:** stuck under a tenth 4 of 10; deaths median 1.0 an hour; iron pickaxe 18.55 min; portal 10 of
  10; 23 deaths.

| Run | Capture | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 | Total | Hole or pit | Hemmed in |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `e40a24e-3360ef2` (step 4) | on | 9 | 18 | 18 | 18 | 21 | 33 | 27 | 39 | 18 | 42 | 243 | 12 | 18 |
| `e40a24h-3360ef2` (step 7) | on | 12 | 60 | 6 | 3 | 30 | 30 | 33 | 24 | 0 | 9 | 207 | 4 | 18 |
| `e40a24g-3360ef2-nowake` (step 6) | off | 12 | 18 | 18 | 27 | 18 | 9 | 45 | 12 | 6 | 36 | 201 | 6 | 4 |

### The mechanism, checked offline (steps 4, 6 and 7, with the 83321ac runs)
- **Timing:** each hemmed-in spell's start (its watchdog tick less 3,600) against the same player's last death.
  - 3360ef2 step 4: 18 hemmed in, only 3 after a death of that player (14.5, 18.5 and 27.5 game minutes after it).
  - 3360ef2 step 7: 18, only 2 after its own death (8.4, 28.4).
  - 83321ac: 15, 5 after its own death (41 to 93 min). Its replicate: 19, 7 after (62 to 104 min).
  - In none of the four did a hemmed-in spell start within 5 minutes of its player waking.
  - **So most hemmed-in spells with the capture on are by players that had not yet died**, whose own capture never ran.
- **Accounting:** the hemmed-in bucket is score.py's `stuck_kind`, read from the player's console lines before its
  watchdog line. No capture output matches its words (0 of step 7's "replay" lines). The spells are the game's
  watchdog's, in `eval-result.json`; report.py does not feed it. **So it is not a measurement artifact.**
- **Not separated:** how the capture reaches players that have not died. In play, #375 adds only the three death fields
  (read by nobody but `captureWaking`) and `captureWaking`, run at another player's respawn on the shared server thread:
  6 to 24 ms, a box read, and `getOtherEntities` and `saveNbt` on the entities in the box.
