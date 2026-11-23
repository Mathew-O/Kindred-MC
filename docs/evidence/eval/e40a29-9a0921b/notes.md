# e40a29-9a0921b and e40a29r-9a0921b: runner notes

Date: 2026-11-23
Agent / branch: Claude RUNNER 2 / `claude/e40a29-9a0921b-1123`
Status: two evaluation batches. No code change, no release gate.

**The read:** two identical batches of one jar, on iteration 25's protocol, read against iteration 27's and 28's pairs.
A figure is "outside" a pair's band when both of 29's batches fall outside the range that pair's two batches span.
"Overlaps" means at least one of 29's batches falls inside it, or one falls on each side. Each band is one pair.

## Setup, both batches
- **Build:** main 9a0921b5, 2026-11-23. One jar, built with `remapJar` and passed by `--jar` to both batches (sha256
  7c96cc87…). Over iteration 28 (7e2f9bff) it carries #475 (F-25c4bx1, the hemmed-in climb's bound), #471 (F-25c4dh4,
  fight start and end and meal-held-back lines, log only), #474 and #476 (tests) and docs. #479 and #480 are not in it.
- **Harness and settings:** as for iterations 25 to 28: run.py at 174e0b3, score.py from #399's branch with
  `--no-metrics`, the ten seeds, `goal=nether; ticks=144000`, earned, strategist off, three at a time, replays on. No
  `--keep-world`.
- **Order (UTC, 2026-11-23):** batch 1 (`e40a29-9a0921b`) 02:30 to 05:19, then batch 2 (`e40a29r-9a0921b`) 05:19 to
  07:49, each as two calls of five seeds into one build folder.
- **Completion:** 20 of 20 exit 0, 144000 ticks each. No re-runs.

## Exit figures

| Exit criterion | Threshold | Batch 1 | Batch 2 | 28's band | 27's band | Read |
| --- | --- | --- | --- | --- | --- | --- |
| Stuck under a tenth of the run (under 12 game minutes) | 7 of 10 | 4 of 10 (202, 303, 707, 8675309): no | 6 of 10 (101, 202, 303, 31102026, 404, 606): no | 7 to 7 | 4 to 6 | outside 28's, fewer; overlaps 27's; not met |
| Deaths a game hour, median | 0.5 or less | 0.5 [0 to 2.0]: yes | 0.5 [0 to 1.0]: yes | 0.25 to 0.5 | 0.75 to 1.0 | overlaps 28's; outside 27's, fewer; met |
| First iron pickaxe, median | under 30 min | 23.05 (10 of 10): yes | 19.5 (10 of 10): yes | 17.5 to 19.35 | 18.1 to 22.55 | outside 28's, later; overlaps 27's; met |
| Portal lit | 7 of 10 | 7 of 10 (not 202, 31102026, 404): yes | 10 of 10: yes | 10 | 7 to 8 | overlaps both; met |

| | Batch 1 | Batch 2 | 28's band | 27's band | Read |
| --- | --- | --- | --- | --- | --- |
| Stuck game minutes, all seeds | 180 | 195 | 99 to 138 | 102 to 144 | outside both, more |
| Stuck spells | 60 | 65 | 33 to 46 | 34 to 48 | outside both, more |
| Hemmed in, spells (min) | 21 (63) | 22 (66) | 7 to 15 | 9 to 16 | outside both, more |
| Hole or pit plus hemmed in (spells) | 28 (7 + 21) | 30 (8 + 22) | 12 to 18 | 14 to 23 | outside both, more |
| Deaths, all seeds | 15 | 8 | 10 to 10 | 16 to 18 | overlaps 28's (one each side); outside 27's, fewer |

**Deaths by kind:** batch 1 lava 2, drowning 1, cornered 6, dark melee 2, other 4. Batch 2 fall 1, starving with a mob
near 1, cornered 1, dark melee 3, other 2.

## Stuck spells by kind, all seeds

| Kind | 29 batch 1 | 29 batch 2 | 28 batch 1 | 28 batch 2 | 27 batch 1 | 27 batch 2 | Read |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Hemmed in | 21 | 22 | 15 | 7 | 9 | 16 | outside both, more |
| No walkable route | 6 | 16 | 1 | 2 | 0 | 2 | outside both, more |
| Busy-work loop | 14 | 5 | 19 | 11 | 4 | 17 | overlaps both |
| Hole or pit | 7 | 8 | 3 | 5 | 5 | 7 | outside 28's, more; overlaps 27's |
| Water loop | 1 | 10 | 1 | 0 | 3 | 3 | overlaps both |
| Mining for a find | 6 | 4 | 6 | 8 | 10 | 3 | overlaps both |
| Waiting for daylight or a teammate | 4 | 0 | 0 | 0 | 0 | 0 | overlaps |
| Refused target repeated | 1 | 0 | 0 | 0 | 3 | 0 | overlaps |
| Other or unmatched | 0 | 0 | 1 | 0 | 0 | 0 | — |

## Stuck per seed, by kind (spells; three game minutes each)

Columns: water loop, no walkable route, refused target repeated, hole or pit, hemmed in, waiting, mining for a find,
busy-work loop, other.

**Batch 1, `e40a29-9a0921b`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 21 | 7 | 0 | 0 | 0 | 1 | 4 | 0 | 2 | 0 | 0 |
| 202 | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 20261030 | 15 | 5 | 0 | 0 | 1 | 1 | 2 | 0 | 0 | 1 | 0 |
| 303 | 9 | 3 | 0 | 0 | 0 | 1 | 2 | 0 | 0 | 0 | 0 |
| 31102026 | 15 | 5 | 0 | 1 | 0 | 4 | 0 | 0 | 0 | 0 | 0 |
| 404 | 39 | 13 | 0 | 1 | 0 | 0 | 0 | 2 | 1 | 9 | 0 |
| 505 | 42 | 14 | 0 | 4 | 0 | 0 | 7 | 0 | 2 | 1 | 0 |
| 606 | 27 | 9 | 1 | 0 | 0 | 0 | 6 | 0 | 1 | 1 | 0 |
| 707 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 8675309 | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 |

**Batch 2, `e40a29r-9a0921b`**

| Seed | Stuck min | Spells | water | route | refused | hole | hemmed | waiting | mining | busy | other |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |
| 202 | 3 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 |
| 20261030 | 51 | 17 | 0 | 0 | 0 | 2 | 12 | 0 | 1 | 2 | 0 |
| 303 | 3 | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 31102026 | 6 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 |
| 404 | 6 | 2 | 0 | 0 | 0 | 0 | 1 | 0 | 1 | 0 | 0 |
| 505 | 48 | 16 | 0 | 5 | 0 | 0 | 9 | 0 | 0 | 2 | 0 |
| 606 | 6 | 2 | 1 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 |
| 707 | 39 | 13 | 0 | 10 | 0 | 2 | 0 | 0 | 0 | 1 | 0 |
| 8675309 | 30 | 10 | 9 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 |

## Per seed against 27's and 28's ranges

Stuck game minutes. "Spread" is batch 2 minus batch 1.

| Seed | 27 b1 | 27 b2 | 28 b1 | 28 b2 | 29 b1 | 29 b2 | 29 spread | 29 against 27 | 29 against 28 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 101 | 9 | 18 | 0 | 0 | 21 | 3 | −18 | overlaps | both above |
| 202 | 21 | 12 | 3 | 18 | 6 | 3 | −3 | both below | overlaps |
| 20261030 | 0 | 30 | 18 | 3 | 15 | 51 | +36 | overlaps | overlaps |
| 303 | 0 | 6 | 45 | 3 | 9 | 3 | −6 | overlaps | overlaps |
| 31102026 | 18 | 21 | 9 | 21 | 15 | 6 | −9 | both below | overlaps |
| 404 | 30 | 21 | 6 | 9 | 39 | 6 | −33 | overlaps | overlaps |
| 505 | 3 | 9 | 9 | 9 | 42 | 48 | +6 | both above | both above |
| 606 | 12 | 3 | 39 | 6 | 27 | 6 | −21 | overlaps | overlaps |
| 707 | 0 | 9 | 3 | 21 | 0 | 39 | +39 | overlaps | overlaps |
| 8675309 | 9 | 15 | 6 | 9 | 6 | 30 | +24 | overlaps | overlaps |
| Total | 102 | 144 | 138 | 99 | 180 | 195 | +15 | both above | both above |

- **29's own spread:** median −4.5, range −33 to +39, median |Δ| 19.5 min. That is wider than 28's (13.5), 27's (9) and
  26's (9).
- **Per seed:** 505 is above both earlier ranges in both batches (42 and 48 against 3 to 9). 101 is above 28's. 202 and
  31102026 are below 27's. The rest overlap.

| Seed | 101 | 202 | 20261030 | 303 | 31102026 | 404 | 505 | 606 | 707 | 8675309 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Deaths an hour, batch 1 | 0.5 | 2.0 | 0.5 | 0.5 | 1.5 | 1.5 | 0.0 | 0.0 | 0.5 | 0.5 |
| Deaths an hour, batch 2 | 0.0 | 0.5 | 1.0 | 0.5 | 0.5 | 0.0 | 0.5 | 0.0 | 1.0 | 0.0 |
| Iron pickaxe, batch 1 (min) | 23.3 | 61.4 | 23.5 | 22.2 | 28.1 | 19.7 | 25.6 | 11.6 | 11.2 | 22.8 |
| Iron pickaxe, batch 2 (min) | 23.7 | 19.7 | 25.7 | 13.3 | 19.5 | 19.5 | 17.7 | 15.0 | 12.8 | 20.3 |
| Portal lit, batch 1 (min) | 51.1 | no | 29.6 | 28.7 | no | no | 56.0 | 41.9 | 64.9 | 40.3 |
| Portal lit, batch 2 (min) | 81.9 | 38.9 | 72.0 | 18.6 | 41.6 | 51.7 | 50.2 | 39.2 | 81.6 | 33.1 |

## F-25c4bx1 (#475): hemmed in and "Climb to find another way"

"Climb to find another way" starts: a goal line with that label after a goal line of that player without it, counted
by `iter29-deaths.py` (filed beside these notes) on 27's, 28's and 29's consoles. The label existed before #475.

Hemmed-in spells (minutes), and climb starts, per seed:

| Seed | 27 b1 | 27 b2 | 28 b1 | 28 b2 | 29 b1 | 29 b2 |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 0 (0); 37 | 1 (3); 76 | 0 (0); 19 | 0 (0); 0 | 4 (12); 32 | 0 (0); 0 |
| 202 | 3 (9); 40 | 2 (6); 63 | 0 (0); 19 | 3 (9); 3 | 0 (0); 12 | 0 (0); 0 |
| 20261030 | 0 (0); 1 | 0 (0); 34 | 5 (15); 123 | 0 (0); 7 | 2 (6); 8 | 12 (36); 39 |
| 303 | 0 (0); 7 | 0 (0); 9 | 7 (21); 12 | 0 (0); 0 | 2 (6); 19 | 0 (0); 0 |
| 31102026 | 0 (0); 0 | 4 (12); 34 | 0 (0); 4 | 3 (9); 106 | 0 (0); 8 | 0 (0); 0 |
| 404 | 3 (9); 345 | 5 (15); 317 | 0 (0); 19 | 0 (0); 9 | 0 (0); 7 | 1 (3); 17 |
| 505 | 0 (0); 33 | 1 (3); 72 | 0 (0); 4 | 0 (0); 53 | 7 (21); 91 | 9 (27); 79 |
| 606 | 3 (9); 142 | 0 (0); 6 | 3 (9); 653 | 0 (0); 0 | 6 (18); 47 | 0 (0); 11 |
| 707 | 0 (0); 1 | 0 (0); 14 | 0 (0); 0 | 1 (3); 2 | 0 (0); 0 | 0 (0); 0 |
| 8675309 | 0 (0); 17 | 3 (9); 247 | 0 (0); 3 | 0 (0); 0 | 0 (0); 17 | 0 (0); 12 |
| Total | 9 (27); 623 | 16 (48); 872 | 15 (45); 856 | 7 (21); 180 | 21 (63); 241 | 22 (66); 158 |

Each cell is "hemmed-in spells (minutes); climb starts".
- **Climb starts:** 241 and 158 against 180 to 856 (28) and 623 to 872 (27). That overlaps 28's band and is outside
  27's, fewer.
- **Hemmed in:** 21 and 22 spells against 7 to 15 (28) and 9 to 16 (27), outside both, more. In batch 2, 20261030
  (12) and 505 (9) carry 21 of the 22. In batch 1 they are spread over five seeds.
- **No walkable route:** 6 and 16 spells against 0 to 2 in both earlier pairs, outside both, more. In batch 2, 505 and
  707 carry most of it.
- **No cause is named.** #475 is the one change aimed at the hemmed-in climb, but hemmed in rose rather than fell, and
  six merges lie between the jars.

## F-25c4at1's hold, the tightened count

As in iteration 28, by `iter28-counts.py`:

| Batch | Walk-back tasks (completed) | Ended lower, completed | Taken again in the hold | Raw proxy, taken again |
| --- | --- | --- | --- | --- |
| 29 batch 1 | 140 (107) | 27 | 24 | — |
| 29 batch 2 | 83 (60) | 14 | 9 | — |
| 28 batch 1 | 74 (52) | 6 | 0 | 52 |
| 28 batch 2 | 75 (62) | 8 | 1 | 86 |
| 27 batch 1 | 103 (87) | 24 | 22 | 173 |
| 27 batch 2 | 140 (119) | 33 | 47 | 363 |

- 29 raw proxy (an upper bound): walks back 749, ended lower 235, taken again in the hold 167
- 29 raw proxy (an upper bound): walks back 394, ended lower 126, taken again in the hold 87
- **Taken again in the hold:** 24 and 9, against 0 and 1 in 28. That is outside 28's band, more, and overlaps 27's.
- **Where:** 31102026 batch 1, 20 (Kindred, about 03:29); 707 batch 1, 4; 101 batch 2, 9.
- **A check of the read:** 4 of the 33 have a drop-surplus goal or a drop outcome among the player's 12 lines before
  them, which would reset the count's "begin". So drops do not explain them.
- **What it may mean:** either the hold does not act in these cases, or the count's goal-line positions misplace the
  walk. #469's hold was not changed between 28 and 29. This needs a look at 31102026 batch 1's console before it is
  read as the hold failing.

## Deaths, read with #471's fight lines

`iter29-deaths.py` reads the logged lines:
- "`<P>` fight starts with `<target>`: health …, food …, saturation …, armour …, weapon …; mobs within 8 …; light … at
  Y …";
- "`<P>` fight ends …";
- "`<P>` meal held back: …".

A death is "in a fight" if the player's fight had started and not ended, or ended within 40 ticks before the death.
Every one of the 8 here was the second kind. The state is the one logged at the fight's start.

| Build | Seed | Player | Tick | Death | Fight | Target | State at the fight's start | Meal held back in the 400 ticks before |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| e40a29-9a0921b | 101 | Sol | 16756 | was shot by Skeleton | fight ended just before | zombie | health 4.5, food 16, saturation 0.0, armour 0, weapon stone_sword; mobs within 8 {skeleton=1, zombie=2}; light 0 at Y 31 | none |
| e40a29-9a0921b | 202 | Sol | 76766 | was slain by Zombie | fight ended just before | zombie | health 3.5, food 20, saturation 0.2, armour 0, weapon cobblestone; mobs within 8 {spider=2, zombie=3}; light 0 at Y 29 | none |
| e40a29-9a0921b | 303 | Kindred | 31108 | was shot by Skeleton | fight ended just before | skeleton | health 2.8, food 16, saturation 0.0, armour 0, weapon stone_sword; mobs within 8 {skeleton=2, zombie=1}; light 0 at Y 75 | none |
| e40a29r-9a0921b | 202 | Sol | 139406 | was slain by Zombie | fight ended just before | zombie | health 3.9, food 14, saturation 0.6, armour 6, weapon iron_sword; mobs within 8 {skeleton=1, zombie=1}; light 0 at Y -12 | none |
| e40a29r-9a0921b | 20261030 | Sol | 56481 | was slain by Zombie | fight ended just before | creeper | health 4.0, food 6, saturation 0.0, armour 6, weapon stone_pickaxe; mobs within 8 {zombie=1}; light 0 at Y 85 | none |
| e40a29r-9a0921b | 31102026 | Echo | 56733 | was shot by Skeleton | fight ended just before | zombie | health 1.3, food 8, saturation 0.0, armour 6, weapon stone_sword; mobs within 8 {skeleton=1, zombie=1}; light 0 at Y 33 | none |
| e40a29r-9a0921b | 505 | Sol | 9642 | was slain by Zombie | fight ended just before | zombie | health 0.5, food 16, saturation 0.0, armour 0, weapon dirt; mobs within 8 {zombie=1}; light 0 at Y 32 | zombie 0.8 blocks off can get at it (health 3.0, food 16, saturation 0.0, armour 0, weapon dirt; mobs within 8 {zombie=1}; light 0 at Y 32) |
| e40a29r-9a0921b | 707 | Echo | 24674 | was slain by Cave Spider | fight ended just before | cave_spider | health 1.2, food 9, saturation 0.0, armour 6, weapon stone_pickaxe; mobs within 8 {cave_spider=4}; light 0 at Y 25 | none |

- **In a fight:** 8 of 23 deaths (3 of 15 and 5 of 8).
- **At those 8 fights' starts:**
  - light 0 every time;
  - health 0.5 to 4.5;
  - armour 0 four times and 6 four times;
  - in hand: a stone sword three times, an iron sword once, a stone pickaxe twice, cobblestone once and dirt once.
- **Meal held back:** one case, Sol on 505 batch 2 ("zombie 0.8 blocks off can get at it"). The run has 23
  meal-held-back lines in all.
- **The other 15 deaths:** not in a fight by this rule. The time since the player's last fight ended is in the table
  below.

| Build | Seed | Player | Tick | Death | Fight |
| --- | --- | --- | --- | --- | --- |
| e40a29-9a0921b | 202 | Kindred | 16473 | was shot by Skeleton | not in a fight (last fight ended 679 ticks before) |
| e40a29-9a0921b | 202 | Echo | 63742 | was shot by Skeleton | not in a fight (last fight ended 17415 ticks before) |
| e40a29-9a0921b | 202 | Kindred | 93299 | drowned | not in a fight (last fight ended 5818 ticks before) |
| e40a29-9a0921b | 20261030 | Kindred | 16039 | was shot by Skeleton | not in a fight (last fight ended 2751 ticks before) |
| e40a29-9a0921b | 31102026 | Sol | 19971 | was shot by Skeleton | not in a fight (last fight ended 350 ticks before) |
| e40a29-9a0921b | 31102026 | Sol | 30300 | was shot by Skeleton | not in a fight (last fight ended 360 ticks before) |
| e40a29-9a0921b | 31102026 | Sol | 122426 | was slain by Husk | not in a fight (last fight ended 1085 ticks before) |
| e40a29-9a0921b | 404 | Sol | 34303 | tried to swim in lava | not in a fight (last fight ended 6000 ticks before) |
| e40a29-9a0921b | 404 | Sol | 48106 | was shot by Skeleton | not in a fight (last fight ended 446 ticks before) |
| e40a29-9a0921b | 404 | Sol | 101413 | was slain by Panda | not in a fight (last fight ended 2543 ticks before) |
| e40a29-9a0921b | 707 | Kindred | 31957 | tried to swim in lava | not in a fight (last fight ended 1925 ticks before) |
| e40a29-9a0921b | 8675309 | Kindred | 86400 | was slain by Enderman | not in a fight (last fight ended 514 ticks before) |
| e40a29r-9a0921b | 20261030 | Sol | 142154 | suffocated in a wall | not in a fight (last fight ended 434 ticks before) |
| e40a29r-9a0921b | 303 | Kindred | 24279 | fell from a high place | not in a fight (last fight ended 4647 ticks before) |
| e40a29r-9a0921b | 707 | Sol | 15714 | was slain by Cave Spider | not in a fight (last fight ended 629 ticks before) |

## Console
- **ERROR lines:** 0 in either batch.
- **Reconciliation:** 1 line in batch 1, a "Food changed during use" eat block followed by a completed eat; 0 in batch 2.
  No "Clutch conservation" line.

## The read
- **Not all four exit criteria are met:** stuck under a tenth is 4 and 6 of 10, against 7 and 7 in 28. The other three
  are met by both batches.
- **Outside both earlier bands, worse:** the stuck total (180 and 195), spells, hemmed in, the signature and no
  walkable route.
- **Outside 28's band:** the pickaxe is later (23.05 and 19.5), and the hold's tightened count is higher (24 and 9).
- **How firm:**
  - Each band is one pair, and 29's per-seed spread (median |Δ| 19.5 min) is the widest of the four pairs since 26.
  - 28's met criteria were at their thresholds. 29's miss is by three and one seeds.
- **No cause is named.** Six merges lie between 28's jar and 29's. #475 touches the hemmed-in climb, and #471 adds log
  lines only.

## Not claimed
- No release gate.
- The hold's counts are read from goal and outcome lines; 29's rise is not shown to be the hold failing.
- The fight state is the one logged at the fight's start, not at the death.
