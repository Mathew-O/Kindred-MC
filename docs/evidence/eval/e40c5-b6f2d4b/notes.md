# e40c5-b6f2d4b: runner notes (partial probe, not a rung c result)

Date: 2026-11-23
Agent / branch: Claude RUNNER 2 / `claude/e40c5-b6f2d4b-1123`
Status: a partial rung c probe. No code change, no release gate. **Not a rung c result.**

## Why partial
- **Stopped at six seeds** (lead, 11:01): on this jar "safe return" is 0 by construction (below), and #492 is not in it.
  A full rung c probe has to be re-run once F-25b5 (the portal return) and F-25c6e land, so the cores went to
  iteration 30 instead.
- **303 was cut** at about tick 240000 of 288000 by the runner session's 2-hour background limit (13:00:52). It is not
  scored; its partial facts are given separately below. Its console and unfinished server folder are kept in place.
- **So 5 complete seeds:** 101, 202, 20261030, 31102026 and 404.

## Setup
- **Build:** main b6f2d4ba, 2026-11-23, one jar passed by `--jar` (sha256 2998a546…). **It does not carry #492
  (F-25c6d part 2, the Nether lava-edge freeze fix)**, whose CI had not finished; e40c5 started without it, at the lead's
  word (09:00).
- **Protocol:** e40c4's: `goal=dragon; ticks=288000`, earned, strategist off, two at a time, replays on, run.py at
  174e0b3. The one other change: no `--keep-world`.
- **Order (UTC):** part 1 (101, 202) 09:03 to 11:00; part 2 (20261030, 303) 11:00 to 13:00, 303 cut; part 3
  (31102026, 404) 13:01 to 14:42. Parts 4 and 5 (505, 606, 707, 8675309) were not run.
- **Completion:** 5 of 5 complete seeds exit 0, 288000 ticks each.

## Two things true by construction on this jar (COMMS's findings)
- **Safe return is 0 by construction (F-25b1):** a native body can never cross a portal a second time. Its teleport is
  never accepted, so its portal cooldown never runs down, and the return home is impossible.
- **Nether deaths and damage are "invulnerable by construction (F-25b5)":** a native body that has crossed a portal
  keeps vanilla's teleport state, which makes the player immune, and never clears it. So survival after the crossing
  is not earned. Reaching the Nether, a fortress and rods still count.

## Rung c against its bar, beside e40c4's same seeds

The bar is each of the Nether, a fortress, rods and a safe return on 8 of 10 seeds. Here are the 5 complete seeds,
and e40c4 on the same five and on the six that included 303:

| Seeds reaching | e40c5, 5 complete seeds | e40c4, the same 5 | e40c4, the same 6 (with 303) | e40c4, all 10 |
| --- | --- | --- | --- | --- |
| A portal lit | 5 of 5 | 4 of 5 (not 101) | 5 of 6 | 9 of 10 |
| The Nether | 2 of 5 (202 Echo; 31102026 Echo) | 3 of 5 (20261030, 31102026, 404) | 4 of 6 | 6 of 10 |
| A fortress reached | 1 of 5 (202 Echo) | 1 of 5 (31102026 Sol) | 2 of 6 | 2 of 10 |
| Blaze rods | 0 of 5 | 1 of 5 (31102026 Sol, 7 rods) | 1 of 6 | 1 of 10 |
| A safe return | 0 of 5 (0 by construction) | 0 of 5 | 0 of 6 | 0 of 10 |

- **A fortress reached** is counted as e40c4 counted it: a player's goal line "Walk to the fortress" or "… about the
  fortress", or a "sees a fortress" line.
- **Against the bar:** none of the four is near 8 of 10. This probe is not a rung c result, and five seeds can't
  support a comparison with e40c4; the table shows what happened, not a change.

## Per player in the Nether

By `e40c5-counts.py` (filed beside these notes). Each figure is timed from the player's own Nether entry tick (the
"nether_entered" stage in its eval-result). The script's docstring gives the exact definitions:
- **The columns:** search, fights, retreats and idle are game minutes from the player's goal lines after entry. Each
  line counts until the next, capped at 200 ticks, and time past the cap counts as idle. Retreats are the time inside
  a hazard spell.
- **Freeze:** the player's last 600 or more ticks of goal lines within 1 block of one spot to the run's end, with
  the feet and floor of its last hazard-spell line.

| Seed | Player | Nether entry tick | Search (min) | Fights (min) | Retreats (min) | Idle (min) | Other (min) | Freeze to the end | Urgent-retreat cancels (retreat within 100) | Farthest reach | Distance walked | Heading changes | First fortress (ticks after entry) | First rod (ticks after entry) | Fights by mob | Pearls picked up | RETURN reached / walk-back attempts by outcome |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 202 | Echo | 77560 | 140.4 | 16.5 | 0.0 | 12.6 | 5.8 | from 202794 after entry at -27 64 84; feet ?, floor ? | 0 (0 followed) | 86 | 24830 | 585 | 853 | no | hoglin 24, piglin 22, wither_skeleton 3, blaze 1 | 0 | no / none |
| 31102026 | Echo | 94940 | 35.1 | 15.1 | 0.2 | 108.7 | 1.8 | from 178961 after entry at 69 37 235; feet fire, floor nether_wart_block | 143 (1 followed) | 283 | 5739 | 174 | no | no | piglin 37, hoglin 34 | 0 | no / none |

The other 13 players of the five seeds did not enter the Nether.

- **202 Echo:**
  - Searched for 140 game minutes, with a farthest reach of 86 blocks, 24,830 blocks walked and 585 heading changes.
  - Reached a fortress 853 ticks after entry, but got no rod.
  - Fought hoglins (24), piglins (22), wither skeletons (3) and one blaze.
  - Stood at one spot from about 202,800 ticks after entry to the end, with no hazard spell logged.
- **31102026 Echo:**
  - Idle for 109 of its game minutes in the Nether.
  - Had 143 urgent-retreat cancels, with a retreat following within 100 ticks only once.
  - Froze from about 179,000 ticks after entry to the end. Its last hazard spell's feet were fire and its floor
    nether_wart_block.
- **Neither player** picked up a pearl or reached RETURN, and neither tried to walk back through the portal.
- **Deaths and damage after the crossing** are not read: invulnerable by construction (F-25b5).

## 303, partial (cut at about tick 240000; not scored)
- **No eval-result was written,** so there are no stage ticks and the per-player counts above can't be timed for it.
- **From its console:**
  - Kindred was on the Nether stages: "End: Fortress" from 11:22:07 and "End: Blaze rods" from 11:22:25, when its
    first "Walk to the fortress" goal line came. 3078 "End: Blaze rods" goal lines follow.
  - No blaze rod was picked up, and there is no "Back through the portal" line.
  - Partial: at most, "the Nether and a fortress on 303 by Kindred", with no rods, by the cut.

## Console
- **Scoring:** score.py (`--no-metrics`) scores the 5 complete seeds and notes "No result from: 303".
- **ERROR lines:** 0 on the 5 complete seeds.
- **Reconciliation:** one "Food changed during use" eat block (101, Kindred, 09:40:49); no other reconciliation line.

## Not claimed
- Not a rung c result. Not a comparison with e40c4: 5 seeds, without #492, and with the return and Nether survival
  broken by construction.
- No cause is named for any difference from e40c4.
