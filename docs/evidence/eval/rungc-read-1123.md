# Rung c: a read of e40c4 and e40c3 (consoles only), 2026-11-23

**Asked by the lead (2026-11-23).** Iteration 28 met rung b. This read covers rung c: the Nether, a fortress, blaze
rods and a safe return, on 8 of 10. The read is offline from the runs' consoles and results, with no code changed.

**Sources:**
- e40c4-b23acc0 (main `b23acc0`, 2026-11-14/15) and e40c3-2c67d67, from `claude/corpus-data-e40c4-1115` and
  `claude/corpus-data-e40c3-1112`.
- Only what the players logged. No fortress position, block or structure was read from world data.

**The counts are from three scripts, added here:**
- `tools/eval/analysis/nethertime.py`: the time after entry, by the step each goal line names;
- `lastwait.py`: how long a player stayed at its last position;
- `searchreach.py`: how far the fortress search got.

Ticks come from the console clock, as `endidle.py` (e40c3's data branch) reads it.

## 1. Where the Nether time went, per seed (e40c4)

Shares of each player's game minutes from entry to the run's end. No player came back through a portal, and all 48
deaths were in the Overworld.

| Seed | Player | Nether min | Search | Idle (waiting) | Fights | Blaze | Pearls | Frozen at one spot to the end |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 20261030 | Echo | 169 | 92% | 5% | 2% (piglin 51) | 0 | 0 | no |
| 20261030 | Kindred | 49 | 94% | 4% | 0 | 0 | 0 | 37 min |
| 303 | Echo | 176 | 0 | 67% | 32% (piglin 46) | 1% | 0 | **83 min** |
| 31102026 | Sol | 204 | 4% | 13% | 56% (blaze 138, hoglin 107) | 4% | 20% | no |
| 31102026 | Echo | 141 | 3% | 63% | 1% | 33% | 0 | **88 min** |
| 404 | Echo | 162 | 35% | 63% | 1% | 0 | 0 | **98 min** |
| 606 | Kindred | 165 | 20% | 79% | 1% | 0 | 0 | **130 min** |
| 606 | Echo | 170 | 82% | 17% | 1% | 0 | 0 | no |
| 8675309 | Sol | 66 | 36% | 0 | 58% (piglin 44, hoglin 26) | 0 | 0 | no |

- **Retreats:** almost none ran as tasks: 0 to 5% of any player's time.
- **The "idle" column:** almost all of it is one state, the freeze below.

### The freeze

Five players stood at one spot for 37 to 130 game minutes, to the run's end. Together that is about 436 of the batch's
~1,300 Nether minutes, a third. Each freeze starts the same way, on 404 (−144 35 −132) and 606 (114 56 −25) alike:
- each task in turn is cancelled with `Hazard/threat detected: urgent retreat required` (goto, break, pillar, wait);
- then `End: Fortress: waiting for a feasible step`;
- then one report, `candidates=0 decision=WAIT No executable local task ... runner=CANCELLED ... blocker=Cancelled:
  Hazard/threat detected: urgent retreat required`, in the Nether;
- no retreat ever runs, and nothing reports again.

Urgent-retreat cancellations per player:

| Player | Cancellations |
| --- | --- |
| 404 Echo | 473 |
| 606 Kindred | 473 |
| 606 Echo | 474 |
| 31102026 Echo | 263 |
| 303 Echo | 23,325 |

In the code at b23acc0, and unchanged on main 9a0921b5:
- The interrupt aborts any task while `isHazardousSituation` holds: fire, lava, a berry bush or powder snow at the
  feet; magma, cactus or lava underfoot; or being alight.
- The only hazard candidates are:
  - a `native_retreat` one block N, S, E or W, onto a full solid floor that is not magma or cactus, with no fluid and
    room to stand;
  - `native_lava_escape` (`lavaBank`), and only when in lava.
- With no such neighbour there is no candidate at all, and the player waits on its spot.

The console does not say which block it was; the probe should log it (below).

e40c3 shows the same freezes:
- 303 Kindred, 88 min (401 cancellations, beside blazes);
- 404 Sol, 149 min (758);
- 606 Echo, 88% of 203 min within a block of its entry (1,405);
- 8675309 Echo, 161 min at one line, "Out of the hoglin's reach". Probably e40c3's reconciliation block; not checked.

### Why no fortress on 20261030, 404, 606 and 8675309

| Seed, player | Farthest from where it began searching | Walked | Heading changes |
| --- | --- | --- | --- |
| 20261030 Echo | 385 | 25,801 | 1,265 |
| 20261030 Kindred | 158 | 1,117 | 61 |
| 404 Echo | 200 | 6,708 | 1,109 |
| 606 Kindred | 145 | 4,275 | 277 |
| 606 Echo | **126** | **19,975** | **3,082** |
| 8675309 Sol | 543 | 3,869 | 68 |
| *found: 31102026 Sol* | *146* | *1,441* | *45* |
| *found: 303 Echo* | *25* | *38* | *3* |

- **20261030:** the search turns back on itself. Echo searched for 155 minutes and kept moving, but changed heading
  about every 16 lines (1,265 changes over 20,388 lines) and got no farther than 385 blocks from where it began.
  Kindred entered late, at tick 229,080.
- **404:** Echo searched within 200 blocks for 57 minutes, changing heading 1,109 times, then froze for 98 minutes.
- **606:** Kindred searched within 145 blocks for 33 minutes, then froze for 130. Echo walked about 20,000 blocks yet
  never got more than 126 from its start, with 3,082 heading changes.
- **8675309:** Sol entered at tick 209,300, with 66 minutes left. It is the one search that held its headings (68
  changes) and got out to 543 blocks, but it spent 58% of the time fighting piglins and hoglins and ran out of run.

The two fortresses that were reached were close: 25 and 146 blocks from where the search began.

## 2. The open rows on e40c4's blockers, and what has merged since 11-15

| Blocker | Rows | Merged since 11-15 |
| --- | --- | --- |
| No fortress: the freeze | none names it. F-25c6a (done 11-14) is in lava with no dry cell beside; F-25c4am is the retreat to a safe cell's middle | Only F-25c6b (#325, after b23acc0): putting out fire with water, and not in an ultrawarm dimension, so nothing in the Nether. `isHazardousSituation`, the interrupt's hazard test and the retreat candidates are unchanged |
| No fortress: the search turning back | F-10e ("following the last plan rather than planning each pass"), F-25c6 | Nothing. `StrongholdLeg` is unchanged since b23acc0 |
| Pearls after the rods | F-25c6 ("endermen in play"); E-39f and F-25c8 are pearl flight and the playbook, not the hunt | Nothing. `seekEnderman` goes to a warped forest it knows of, else the same explore. There is no bartering |
| The Overworld respawn loop (303) | F-25c4di1 (open), its children di1e and di1f3 | di (#353), di1a (#355), di1b (#362), di1c (#365), di1d (#373), di1f1/f2 (#405, #420), di1g (#460), on the bed and the kit. di1 still says it is not shown that the runs' loops end |
| The reconciliation block (101) | F-25c4df (done 11-15, #347) | #347: an attack's empty slot is accepted when the game counts the item broken. A block of any other kind is still never resolved during a run |

The table is from a read of the tracker and `git log` on main 9a0921b5. The "nothing merged" cells were checked by
searching commits since b23acc0 (`-S`/`-G`) for the hazard test, the interrupt, the retreat candidates, `StrongholdLeg`,
blazes and pearls.

## 3. Does the dragon goal's run gate anything rung c needs?

- **Portal travel:** allowed for the dragon goal only while `dragonFight` is on. It is off by default, and the
  evaluation turns it on for `goal=dragon` (`EvalBootstrap`); every e40c4 console says so. Travel is allowed only on the
  portal steps themselves ("Walk into the portal", "Wait in the portal"). So the run is not gated, but a server's
  default is.
- **The return** is built: the planner's RETURN stage, "Back through the portal with rods and pearls"
  (`StrongholdRunPlanner`, carried out by `StrongholdLeg.enterPortal`). It is reached only once `netherDone` holds:
  - the rods, plus 12 pearls or eyes;
  - or a stalled pearl hunt that could still make 6 eyes.
- **So, with rods and no pearl (31102026 Sol), no return ever comes.** Rung c's "safe return" is behind the pearl
  hunt, B above. A rung-c probe should count the return, or say that it was never asked for.

## 4. A first lever for the fortress search

**The freeze, before the search pattern.** It took a third of e40c4's Nether time. It took most of the time on two of
the four seeds with no fortress (404 Echo 98 of 162 minutes, 606 Kindred 130 of 165). It also took two players who
were already at a fortress (303 Echo 83, 31102026 Echo 88). It is the same in e40c3 (149 and 88 minutes). And no
merged change reaches it.

Proposed: while the hazard test holds, the player always has a way off, never `candidates=0`. A retreat would look
beyond the four same-level cells: up or down a step, and a block it can break or place. If no such cell exists, the
interrupt would not cancel a task that moves it. Which block it stands on has to be logged first, because the console
does not say. This is a proposal for the lead, not built.

**The search's turning is the second lever** (F-10e's "following the last plan"). Where it is the cause (20261030 Echo,
606 Echo) the search walks 67 to 158 times its farthest reach. In `StrongholdLeg.explore`, `tried` is fresh on each
pass and a stalled pass turns at right angles. This is read from the code, not tested.

## What e40c5 should count

For each Nether player, every count against the same player's entry tick:
- the minutes in the Nether, by the table above's columns;
- the minutes frozen at one spot, and each freeze's feet and floor blocks (to log);
- urgent-retreat cancellations, and whether a retreat or lava escape followed within 100 ticks;
- the search's farthest reach, distance walked and heading changes, as `searchreach.py`;
- the tick a fortress is first reached, and the first blaze rod;
- fights by mob;
- pearls, and whether the RETURN stage was reached and the portal walked back through.

The scripts here count all but the blocks from the consoles as they are.
