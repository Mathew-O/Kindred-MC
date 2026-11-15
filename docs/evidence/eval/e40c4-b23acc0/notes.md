# e40c4-b23acc0: runner notes

**Question (lead):** a real rung c run on main tip, with the last rung c probe's settings. Is the Nether entered on more seeds than before?

**Setup:**
- **Build:** main `b23acc0` (#310's merge). Run 21:43 on 2026-11-14 to 04:13 UTC on 2026-11-15.
- **Settings:** those of `e40c3-2c67d67`: the ten seeds, `goal=dragon; ticks=288000`, two at a time, worlds kept (`--keep-world`), earned, the strategist off, nothing else on the cores.
- **The dragon fight:** as in e40c3, the evaluation's bootstrap turns it on for the dragon goal (F-26b). Each of the ten consoles logs "the dragon fight on, for the dragon goal". No player reached the End, so it never came into play.
- **Completion:** 10 of 10 exit 0, 288,000 ticks each. The runner's container did not restart.
- **Differences from e40c3:** the build only. There are 49 merges into main between `2c67d67` and `b23acc0`, among them #274 (the table loop), #261, #269 and #288.
- **Scoring:** this build's score.py (`--no-metrics`), paired with e40c3 in [`compare-e40c4.md`](../compare-e40c4.md).
- **Method:** per E-40's "Reading a batch" note, runs of one build differ from the first animal on (vanilla's per-run mob randomness; iteration 22's method note). So per-seed differences are not by themselves evidence of a change.

**Answer: on one more seed, 6 of 10 against 5 of 10, which is not significant.** 20261030 is the new one; the other five are e40c3's five. Paired, the Nether is helped on 1 seed and hurt on 0, p=1.0. For the first time in an E-40 batch, a player got blaze rods: 31102026's Sol, 7 of them.

| Seeds reaching | `e40c3-2c67d67` | `e40c4-b23acc0` |
| --- | --- | --- |
| The iron pickaxe | 10 of 10 (median 22.6 min) | 10 of 10 (median 18.35 min) |
| Obsidian | 10 of 10 (median 49.85 min) | 10 of 10 (median 52.6 min) |
| A portal lit | 9 of 10 (median 71.2 min) | 9 of 10 (all but 101; median 56.8 min) |
| The Nether | 5 of 10 (303, 31102026, 404, 606, 8675309) | 6 of 10 (20261030, 303, 31102026, 404, 606, 8675309) |
| A fortress reached | 1 of 10 (303, Kindred) | 2 of 10 (303 Echo; 31102026 Sol) |
| Blaze rods | 0 of 10 | **1 of 10 (31102026, Sol, 57.3 min, 7 rods)** |
| Deaths a game hour, median [range]; all | 0.62 [0.0 to 1.75]; 28 | 0.62 [0.0 to 6.75]; 48, none in the Nether |
| Stuck spells, median [range]; all | 12.0 [5 to 36]; 147 | 9.0 [2 to 25]; 97 |
| Stuck game minutes, median [range] | 36.0 [15 to 108] | 27.0 [6 to 75] |

Paired by seed against e40c3:

| Measure | Helped | Hurt | Median | Sign test |
| --- | --- | --- | --- | --- |
| The Nether | 1 | 0 | | p=1.0 |
| The portal | 0 | 0 | | p=1.0 |
| Stuck minutes | 6 | 4 | −15.0 [−30.0, +18.0] | p=0.754 |
| Deaths an hour | 4 | 3 | | p=1.0 |
| Iron pickaxe | 5 | 4 | | p=1.0 |
| First portal | 5 | 4 | −1.55 min | p=1.0 |

Nothing passes the sign test.

**Per seed, the team's furthest stage and its time:**

| Seed | Furthest stage, time, player |
| --- | --- |
| 101 | obsidian 107.4 min (Kindred) |
| 202 | portal_lit 76.6 (Kindred) |
| 20261030 | nether_entered 70.9 (Echo) |
| 303 | nether_entered 64.2 (Echo) |
| 31102026 | blaze_rods 57.3 (Sol) |
| 404 | nether_entered 78.4 (Echo) |
| 505 | portal_lit 33.4 (Kindred) |
| 606 | nether_entered 70.2 (Echo) |
| 707 | portal_lit 48.4 (Echo) |
| 8675309 | nether_entered 174.4 (Sol) |

**Deaths by cause (48):**

| | zombie | skeleton | creeper | spider | suffocation | enderman | lava | fall | drowning | drowned | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Deaths | 20 | 12 | 4 | 3 | 2 | 2 | 1 | 1 | 1 | 1 | 1 |

By kind: cornered 27, dark melee 9, other 8, and one each of lava, drowning, fall, and starving with a mob near. All 48 deaths were in the Overworld. **23 of the 48 are one player on one seed** (see 303 below).

**Stuck spells by kind (97):**

| | no walkable route | busy-work loop | mining for a find | hole or pit | water loop | hemmed in | refused target repeated | other | waiting |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Spells | 27 | 23 | 18 | 14 | 5 | 4 | 3 | 2 | 1 |

## The blaze rods (31102026, Sol)
- **Into the Nether:** Sol lit the portal at tick 42,903 and entered at 43,600.
- **The fortress and the rods:** it searched for a fortress ("Find a Nether fortress for blazes", 1,770 steps), reached one, and got its first rod at tick 68,740 (57.3 min).
  - "Fight blaze": 9 completed and 2 cancelled ("Hazard/threat detected: urgent retreat required").
  - 7 rods picked up, from 23:56:18 to 00:14:42.
- **Iteration e40c3's blocker #1 does not hold here.** There, 303's every blaze fight was cancelled by that check, 390 times. Here 9 of 11 completed.
- **After the rods:** "Pearls: Look for endermen for pearls" for the rest of the run. That is 4,202 pearl steps, mostly exploring and cutting west, with no ender pearl picked up.
- **Deaths:** none on this seed.

## Seed 303: one player, 23 deaths at its bed's spawn
- **The loop:** Kindred died 23 times between ticks 194,201 and 237,417, about 36 game minutes. Every death was in the Overworld, under cover, in one cave region near 280–360, 6–48, 130–193: zombie 11, skeleton 5, creeper 4, spider 3.
- **The respawns:** every one of the 23 was at 314, 51, 172 ("Kindred respawned at 314, 51, 172"). That is where Kindred had slept once, underground, while tunnelling toward lava for the portal cast. Its bed's spawn point was in that cave.
- **What it did after each:** its first goal after the first respawn was "Take its bed back up in the morning". After that first death its armour was 0 at every death.
- **Seed 303's 6.75 deaths an hour** is all but this. Echo reached the Nether (64.2 min) and a fortress, but saw no blaze: 0 "Fight blaze", with 245 "waiting for a feasible step" and 46 piglin fights in `End: Blaze rods`.

## Seed 101: Kindred blocked for reconciliation by an attack's conservation check
- **When:** at about tick 223,500, Kindred was hunting sheep for wool ("on the way, for what comes later").
- **The block:** "Native player … blocked: Native action outcome requires reconciliation". The cause was `IllegalStateException: Attack item conservation check failed; reconcile` at `PlayerRuntime.observeRunner` (PlayerRuntime.java:8370), with "no replacement inventory will be created".
- **After it:** Kindred had no goal line for the last ~64,500 ticks. This is the batch's only ERROR line.
- **By design:** an ambiguous outcome blocks for reconciliation, but nothing reconciled it during the run. This is like e40c3's 8675309 Echo block, but from an attack's conservation check, not a pillar.

## Idle time in End: stages, counted offline
e40c3's `endidle.py` (from `claude/corpus-data-e40c3-1112`), unchanged, run on this batch; the output is in [`endidle.txt`](endidle.txt).

| Seed | End: idle min (players) | Longest End: idle, ticks (player, from tick) |
| --- | --- | --- |
| 101 | 0 (no player in End:) | - |
| 202 | 180 (Kindred 150, Sol 12, Echo 18) | 182328 (Kindred, 105672) |
| 20261030 | 0 (Kindred 0, Echo 0) | 1614 (Echo, 136466) |
| 303 | 171 (Echo 171) | 110016 (Echo, 78491) |
| 31102026 | 126 (Sol 39, Echo 87) | 105398 (Echo, 182602) |
| 404 | 225 (Kindred 78, Sol 51, Echo 96) | 117660 (Echo, 170340) |
| 505 | 126 (Kindred 84, Sol 42, Echo 0) | 13187 (Sol, 167268) |
| 606 | 129 (Kindred 129, Sol 0, Echo 0) | 155726 (Kindred, 132274) |
| 707 | 102 (Kindred 27, Echo 75) | 13794 (Echo, 95395) |
| 8675309 | 36 (Sol 27, Echo 9) | 19566 (Sol, 252077) |
| All | 1095 | |

- **The total** is 1,095 idle game minutes in `End:` stages, against e40c3's 1,059.
- **20261030:** its Echo and Kindred searched for a fortress (21,414 steps) without ever being idle by this count. They kept moving and found none.
- **The limit:** a player moving without a new goal line counts as still, as in e40c3.

## The top blockers for rung c, now
1. **No fortress found on four of the six Nether seeds** (20261030, 404, 606, 8675309). "Find a Nether fortress for blazes" steps: 20261030 21,414, 404 9,285, 606 24,650, 8675309 3,045. Long End-stage idles on 404 and 606.
2. **Pearls after the rods.** The one seed with rods found no enderman pearl in its remaining ~180 game minutes.
3. **Overworld deaths in a respawn loop** (303), and a **reconciliation block** (101) that nothing resolves during a run.

The worlds are kept (`world-kept/`) in the runner's run folder for staging.
