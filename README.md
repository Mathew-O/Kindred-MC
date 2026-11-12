# e40c3-2c67d67 data and the End:-stage idle count (RUNNER 2, 2026-11-12)

`runs/e40c3-2c67d67/<seed>/` holds `eval-result.json` and `console.txt.gz`, the ten seeds of E-40's rung c probe e40c3 (filed in PR #271). The kept worlds (`world-kept/`) stay in the runner's run folder for staging.

**Why:** no stuck spell fires in any `End:` stage, so rung c's stall time was invisible to the scorer.

`analysis/endidle.py <run dir>` is an offline count from the consoles alone, with no change to `score.py` or the product:
- **When it starts:** a player is in the End goal's stages from its first "goal at … : End:" line.
- **Progress:** a completed task (`native_* COMPLETED`), or a "goal at X Y Z" at a new position.
- **An idle spell:** each full 3600 ticks between two progress events, or between the last one and the run's end. That is the watchdog's threshold, three game minutes.
- **Nights left out:** a gap whose last step was the night's (waiting for daylight, a shelter, sleep or a bed) is not counted.
- **Ticks:** the console clock, interpolated between "/tick sprint", the "thinking at tick N" marks and "N ticks run".

Its output is `analysis/endidle-e40c3.txt`, and the table below is `analysis/endidle-table.md` (from `endidle_table.py`).

| Seed | Furthest | Scorer stuck min (all stages) | End: idle min (players) | Longest End: idle, ticks (player, from tick) |
| --- | --- | --- | --- | --- |
| 101 | obsidian | 60 | 0 (no player in End:) | - |
| 202 | portal_lit | 18 | 78 (Sol 78) | 13602 (Sol, 209814) |
| 20261030 | portal_lit | 57 | 42 (Echo 42) | 12679 (Echo, 191760) |
| 303 | nether_entered | 15 | 189 (Kindred 87, Sol 30, Echo 72) | 105690 (Kindred, 182310) |
| 31102026 | nether_entered | 42 | 111 (Echo 111) | 77532 (Echo, 210468) |
| 404 | nether_entered | 108 | 186 (Sol 156, Echo 30) | 179077 (Sol, 108923) |
| 505 | portal_lit | 24 | 120 (Kindred 108, Echo 12) | 57746 (Kindred, 230254) |
| 606 | nether_entered | 30 | 78 (Kindred 0, Sol 78, Echo 0) | 66771 (Sol, 221229) |
| 707 | portal_lit | 57 | 96 (Kindred 42, Sol 54) | 13068 (Sol, 191330) |
| 8675309 | nether_entered | 30 | 159 (Echo 159) | 193644 (Echo, 94356) |
| All | | 441 | 1059 | |

**What it shows:**
- **The size:** End:-stage idle time is 1059 game minutes over the ten seeds. The scorer's stuck time is 441 minutes over all stages, none of it in `End:`.
- **303 Kindred:** the longest stretch is 105690 ticks from tick 182310, the blaze fight cancelled 390 times.
- **8675309 Echo:** 193644 ticks from tick 94356, the reconciliation block.
- **404 Sol:** 179077 ticks from tick 108923, in the Nether, with no completed task and no new position.
- **Gaps of about 12,700 ticks** on other players are "candidates=0 decision=WAIT No executable local task" stretches, for example 202 Sol at 209814.

**Limits:**
- Positions are those printed on goal lines, so a player that moves without a new goal line is counted as still.
- The tick of a gap is good to about ±200 ticks.
- This is a diagnostic, not the watchdog's own measure.

The consoles scan to 0 for keys and model IDs.
