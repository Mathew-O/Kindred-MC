# e40c3-2c67d67: runner notes

**Question (lead):** on main as it stands, where does the End-goal run stop now, and what are the top three blockers for rung c?

**Setup:**
- Build: main `2c67d67` (#255's merge), run 15:15 to 20:33 UTC on 2026-11-12.
- Settings: the same as `e40c2-d640518` — the ten seeds, `goal=dragon; ticks=288000`, two at a time, worlds kept (`--keep-world`), earned, strategist off, nothing else on the cores. As in e40c2, the evaluation's bootstrap turns the dragon fight on for the dragon goal (F-26b): each of the ten consoles logs "Evaluation run: the dragon fight on, for the dragon goal". No player reached the End, so it never came into play.
- Completion: 10 of 10 exit 0, 288,000 ticks each. The runner's container did not restart: it was up throughout, checked hourly.
- Differences from e40c2: the build, and that e40c2's container restarted twice, so five of its seeds were re-run there.
- Scoring: this build's score.py (`--no-metrics`), paired with e40c2 in [`compare-e40c3.md`](../compare-e40c3.md).
- Method: per E-40's "Reading a batch" note (PR #261), same-build runs differ, so per-seed differences are not evidence of a change. This is a measure of where rung c stands.

| Seeds reaching | `e40c2-d640518` | `e40c3-2c67d67` |
| --- | --- | --- |
| The iron pickaxe | 10 of 10 (median 26.65 min) | 10 of 10 (median 22.6 min) |
| Obsidian | 10 of 10 (median 64.85 min) | 10 of 10 (median 49.85 min) |
| A portal lit | 7 of 10 (median 81.0 min) | 9 of 10 (all but 101; median 71.2 min) |
| The Nether | 3 of 10 (101, 404, 505) | 5 of 10 (303, 31102026, 404, 606, 8675309; one player each) |
| A fortress reached | 1 seen (404), not reached | 1 of 10 (303, Kindred) |
| Blaze rods | 0 of 10 | 0 of 10 |
| Deaths a game hour, median [range]; all | 0.62 [0.25 to 1.75]; 34 | 0.62 [0.0 to 1.75]; 28, none in the Nether |
| Stuck spells, median; all | 15.5; 154 | 12.0 [5 to 36]; 147 |
| Stuck game minutes, median [range] | 46.5 [9 to 105] | 36.0 [15 to 108] |

Paired by seed against e40c2:
- the portal: helped on 3, hurt on 1, p=0.625;
- the Nether: helped on 4, hurt on 2, p=0.688;
- stuck minutes: helped on 5, hurt on 5, p=1.0;
- iron pickaxe: helped on 6, hurt on 4, p=0.754;
- first portal: helped on 6, hurt on 4, median −44.55 min, p=0.754.

Nothing passes the sign test.

**Per seed, the team's furthest stage and its time:**

| Seed | Furthest stage, time, player |
| --- | --- |
| 101 | obsidian 85.3 min (Kindred) |
| 202 | portal_lit 35.2 (Sol) |
| 20261030 | portal_lit 48.1 (Echo) |
| 303 | nether_entered 132.7 (Kindred) |
| 31102026 | nether_entered 96.6 (Echo) |
| 404 | nether_entered 70.9 (Sol) |
| 505 | portal_lit 36.5 (Kindred) |
| 606 | nether_entered 37.0 (Echo) |
| 707 | portal_lit 108.8 (Sol) |
| 8675309 | nether_entered 75.6 (Echo) |

**Deaths by cause:**

| | zombie | skeleton | spider | fall | suffocation | phantom | creeper | pillager | witch | lava | drowned |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Deaths | 8 | 4 | 4 | 3 | 2 | 2 | 1 | 1 | 1 | 1 | 1 |

By kind: cornered 12, starving with a mob near 5, dark melee 4, fall 3, other 3, lava 1. All 28 deaths were in the Overworld.

**Stuck spells by kind (147):**

| | busy-work loop | hole or pit | hemmed in | mining for a find | no walkable route | water loop | waiting | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Spells | 52 | 51 | 15 | 11 | 9 | 6 | 2 | 1 |

11 of the hole-or-pit spells are iteration 21's water-bucket loop. All 147 spells are in Overworld stages; none is in an `End:` stage.

## The furthest seed's final stall (303)

Kindred entered the Nether at tick 159240, reached a fortress by about tick 159500 ("End: Blaze rods: Look for blazes about the fortress - Walk to the fortress"), and saw a blaze.

From about tick 182300 to the run's end at 288000 (about 88 game minutes), it looped:
1. "End: Blaze rods: A blaze in sight: its rod - Fight blaze";
2. `native_fight CANCELLED: Hazard/threat detected: urgent retreat required [Fight blaze]`;
3. "End: Blaze rods: waiting for a feasible step".

That is 390 fights cancelled and 0 completed. No retreat ran in that time; the last `native_retreat` was before it. The first step of the stall is "Fight blaze", cancelled by the hazard check.

## The top three blockers for rung c

1. **The blaze fight is cancelled by its own hazard check (303).** On the only seed that reached a fortress, every "Fight blaze" was cancelled by "urgent retreat required", 390 times, with no blaze killed and no rod. A blaze in sight is apparently treated as the threat that ends the fight against it.
2. **The fortress is not found on the other Nether seeds, and none of it counts as stuck.**
   - "Find a Nether fortress for blazes" steps: 31102026 2872, 404 1476, 606 341, 8675309 128. Each comes with "waiting for a feasible step" (404 212, 606 1202, 31102026 44) and full inventories (404 135, 31102026 239, 8675309 135).
   - 606's Echo spent 1401 steps on "An enderman in sight: its pearl".
   - Not one stuck spell fired in an `End:` stage on any seed. So stuck time understates rung c's stalls, and the watchdog's progress test is passing a search that finds nothing.
3. **Half the lit portals are never walked into (202, 20261030, 505, 707).**
   - 202 and 707: "Gather N food for the Nether trip" (398 and 408 steps) holds the team.
   - 505: 254 "Walk into the portal" steps, and 187 "No walkable route" refusals that name the portal.
   - 20261030: "too far for one route" (30), lit at 48.1 min.
   - 101 never lit one.

Also on 8675309, Echo entered the Nether at 75.6 min, and at about tick 94356 a pillar step was "cancelled with uncertain outcome". Its body was blocked: "Durable native action requires reconciliation", the batch's only ERROR line. Echo then had no goal or task line for the rest of the run, about 194,000 ticks. By design an ambiguous outcome blocks for reconciliation, but nothing reconciled it during the run.

The worlds are kept (`world-kept/`) in the runner's run folder for staging.

## Idle time in End: stages, counted offline

No stuck spell fires in any `End:` stage, so the scorer cannot see rung c's stalls. An offline count from the consoles does: `endidle.py` on the data branch `claude/corpus-data-e40c3-1112`, with no change to `score.py`.

- **When it counts:** from a player's first "goal at … : End:" line.
- **Idle spell:** each full 3600 ticks (the watchdog's threshold) with no completed task and no new goal position.
- **Left out:** gaps whose last step was the night's (daylight, a shelter, sleep, a bed).
- **Ticks:** from the console clock, about ±200 ticks.

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

- **The total:** 1059 idle game minutes in `End:` stages, against the scorer's 441 stuck minutes over all stages.
- **303 Kindred:** the longest stretch is the blaze fight cancelled 390 times.
- **8675309 Echo:** the reconciliation block.
- **404 Sol:** idle in the Nether for 179,077 ticks from tick 108,923, with no completed task and no new position.
- **Other stretches of about 12,700 ticks:** "candidates=0 decision=WAIT No executable local task".
- **The limit:** a player moving without a new goal line counts as still.
