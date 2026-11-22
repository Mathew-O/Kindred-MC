# F-25c4di1g — The bed it carries is taken back up in the morning without a refusal repeated every tick

Date: 2026-11-22
Agent / branch: Claude, LOOPS / `claude/bed-takeup-refused-1122`, from main `6749ed7e`, at the lead's word
Build: the claim `2071cdca`; the fixture `38869860`; the change `489468e6`, then `f1db771f` (gated there, with a temporary
marker); the marker removed `01fd838c`; main `8d4d30c2` merged in as `59a3017f`; the doorway's race fixed `a09fec51`
Design parent: F-25c4di1 (what it does on waking, its bed)
Status: complete (2026-11-22). The stuck-time gain is read in E-40 iteration 28.

## Scope and implementation

**Found:** in E-40 iteration 26's consoles (`claude/corpus-data-e40a26-consoles-1122` at `1b99f522`), "Take its bed back
up in the morning" was refused every tick in 7 windows: 25,926 refusals and 7 stuck spells, all "refused target repeated",
21 stuck minutes. 9 of those minutes were on batch 1's 20261030 Echo, which was refused 10,958 times in ten game minutes.

**The cause:** the morning take-up (`carriedBedNight`) was offered whenever the bed was within 4.5 of its eyes, without
the break's own rules, and a refused take-up changed nothing in `bedTaskEnded`. Two cases:
- **Woken on top of its bed** in a sealed pocket, as vanilla leaves a player with nowhere beside the bed to stand: "Would
  remove the block the player stands on". Batch 1's 20261030 Echo and 303 Kindred.
- **At a stand cell outside its sealed pocket,** the pocket's own wall between it and its bed: "out of reach or not
  visible … the line to its middle meets Block{minecraft:stone}". Batch 1's 202 Kindred and Sol and 101 Echo; batch 2's
  101 Kindred and 20261030 Kindred.

**User-visible behavior:**
- **Woken on its own bed:** it takes the bed up from the half it isn't standing on, and drops the bed's height onto the
  floor.
- **Walled from its bed by day:** it opens its pocket's doorway, at most two blocks, and takes the bed up.
- **A take-up it can't make:** set aside a while, as a walk to the bed that gets no nearer is; set aside three times, the
  bed is left where it stands.

**Changed files:**
- `PortalRunCampaign`, the morning branch of `carriedBedNight`:
  - a take-up set aside is not offered;
  - within reach, `takeUp`;
  - else `toBedStand` with sight required.
- `takeUp` (new):
  - each half is offered only once `NativePlayerBody.miningEligible` says it is in reach and in sight. That is the
    break's own rule, read in one place, not a copy.
  - Standing on one half, it offers the other. This is bounded so that `RoamingEdits.breakDenial`'s rule against
    breaking the block underfoot still holds in purpose:
    - only when the half under it is the bed it set down itself and lay in tonight (its spawn is set at one of the
      bed's halves);
    - only when the block under that half has a solid top and no fluid.
  - Vanilla's loot drops the bed from its head (`data/minecraft/loot_table/blocks/white_bed.json`, `part=head`). A
    break of the foot takes the head with it, by neighbour update, drops and all. So either half returns the one bed.
- `toBedStand` gains `inSight`. To take the bed up, only a cell from which a half can be seen, and none of the bed's own
  cells. With none, by day, `openWay`.
- `openWay` (new):
  - by day only: at night F-25c4dl's rule keeps its seal, and this doesn't reopen it;
  - the block the line from its eyes to a half's middle meets, only when that block is beside the bed's room (a bed
    cell, or the air over one), is plain ground (no block entity, no fluid, breakable), and only within two a morning;
  - a `native_break` under all of `breakDenial`'s rules;
  - logged once a block: "{player} opens the way to its bed at …".
- `openDoorway` (new): the doorway's other half, the other block of the same two-high cell, below or above the first.
  - It is not a block `miningRefusal` named. With only the named block open, the player saw its bed and broke it, but
    couldn't walk in after the drop through a one-high gap.
  - The same conditions apply: beside the bed's room, plain ground, by day, under `breakDenial`, within the two.
- `bedTaskEnded`:
  - a morning take-up, or a block opened toward the bed, that didn't go is set aside through `setBedAside` (new, shared
    with the walk: 2,400 ticks, and the bed left where it stands at the third);
  - a block opened is counted toward the two.
- "{player} left its bed where it stands at …: {why}" is now logged to the console too: a bed it no longer carries.
- **Gametest:** `NativeBedTakeUpGameTests` (new, registered in `fabric.mod.json`).

Nothing is given or moved by the code: it changes which block a break targets, when the take-up is offered, and which
stand is walked to. The player's own break and pickup return the bed. The world it reads is what it can see from where it
stands.

**Schema/configuration changes:** none.

## Acceptance evidence

**Fixture (staged):** `NativeBedTakeUpGameTests`, four cases.
- **The night, every case:**
  - STAGED: rock round it, no sky; a one-cell pocket sealed all round, with dirt beside it, where it digs its bed's room
    itself;
  - STAGED: its bed in its pack, as one it made, and nothing else; no home bed;
  - the portal goal; night held until it has set its bed down and lain in it.
- **Then morning, staged on the tick it wakes:**
  - `wokenOnItsBedsHeadItTakesItsBedBackUp`: put on top of its bed's head.
  - `wokenOnItsBedsFootItTakesItsBedBackUp`: put on top of its foot. This was taken up before the change too; it checks
    the head's own drop.
  - `walledFromItsBedItOpensItsPocketAndTakesItsBedBackUp`: the pocket's own cell beyond its bed's foot walled up with
    stone (two blocks), a cell dug beyond that, a stone pickaxe in its pack, and it put there. A bare hand harvests no
    stone, and iteration 26's players all had a pickaxe by then.
  - `walledFromItsBedByLavaItSetsTheChoreAside`: as walled, with a lava source shut in the rock beside the wall's top
    block. `breakDenial` refuses a break that would release lava; it doesn't refuse one beside water, so the refused
    variant is staged with lava.
- **Each fails unless, within 600 ticks of morning:**
  - on its bed: the bed is back in its pack with no bed block left, and it stands on the stone floor under its bed,
    unhurt and in no liquid;
  - walled: the same, with at most two blocks broken besides the bed;
  - with lava: its chore set aside, the wall's top block still stone and the bed still down.

| Check | Build | Result |
| --- | --- | --- |
| Fixture before the change | main `6749ed7e` with the final fixture | 3 of 3 fail. On its head, walled and walled with lava fail every run, with iteration 26's own refusals, 2,884 to 2,968 each in the 600-tick morning ("Would remove the block the player stands on"; "out of sight … the line to its middle meets Block{minecraft:stone}"). On its foot passes, as before. |
| Fixture after | `489468e6` | 3 of 3 pass, 4 of 4 each. On its head or foot: the bed back 21–22 ticks into the morning. Walled: 77, 78 and 137 ticks, two blocks opened. Lava: set aside after 1 refusal ("Breaking it would release lava"). |
| Related classes ×1 | `f1db771f` | 250 of 250 pass |
| Fixture and 4 close classes ×3 | `59a3017f` (main `8d4d30c2` merged in) | 2 of 3 pass; 1 fails: walled, its bed broken from outside, the drop out of reach (the race below) |
| Fixture and 4 close classes ×3, then the fixture ×6 | `a09fec51` | 3 of 3 (12 of 12 each), then 6 of 6: walled done in 78–139 ticks, both blocks opened every run |

**The related classes:**
- the 29 of [F-25c4dk](F-25c4dk.md);
- NativeTeamShare;
- NativeBedTakeUp, NativeBedRetry, NativeWakeAtBed, NativeNights, NativeLifeFirst, NativeFollowUp and NativeBase.

**The 4 close classes:** NativeBedPocket, NativeBedRetry, NativeWakeAtBed, NativeNightShelter.

**Log of the run:**
- An earlier version of the walled case passed on main for the wrong reason: the player had no pickaxe, so the opened
  block's break was refused. A stone pickaxe is now staged, and said so.
- **A race in the doorway, found on the merged head:** `openDoorway` cleared its pending block when it offered it. So
  while that break ran, the next plan could offer the take-up through the gap one high. In 1 of 3 runs the take-up
  replaced the second opening, the bed was broken from outside, and the drop lay out of reach ("No walkable route toward
  … [Pick up white bed]").
  - Fixed in `a09fec51`: the doorway's other block stays pending until its own break has ended, and no take-up is
    offered while it is.
  - The gate ran on `f1db771f`, before the fix. Its "open the way" marker was 0 in all three runs, so this path never
    ran in the corpus, and the gate's result stands for it.
- An interrupted local run on another branch (F-25c4at1's) left that branch's gametest registry narrowed. It was pushed
  in `919d0583` and restored in `0ea76275` without a force-push. This branch was not touched.

## Corpus gate (`f1db771f`; base main `6749ed7e`)

**The marker, stated before the gate:** a temporary line wherever the change acts:
- the take-up from the other half;
- a block opened toward the bed;
- a take-up set aside;
- a bed left where it stands.

The sighted-stand filter has no marker of its own; its only entry is the walk "Back to its bed to take it up", counted
from the console. The marker was removed after the gate (`01fd838c`).

| Run | Minutes | Peak game-JVM VmHWM | Required tests failed | Marker lines | "Back to its bed" walks | Take-ups offered / completed / refused | Beds left where they stand |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 62 | 3,220 MB | 123 | 0 | 0 | 1 / 1 / 0 | 0 |
| 2 | 60 | 3,118 MB | 122 | 0 | 0 | 1 / 1 / 0 | 0 |
| 3 | 59 | 3,115 MB | 128 | 0 | 0 | 1 / 1 / 0 | 0 |

- **Coverage:** the corpus offers one morning take-up a run. It was taken from the head, as before, and never refused.
- **Against the baseline** (`16cb7a2`, six runs): 71 scenes newly pass, and 2 are flagged.

| Scene | Full runs | Failure | Its body's bed lines |
| --- | --- | --- | --- |
| e40a14-80b7150-101-echo-75842 | F F F | shot by a skeleton | 0 in every run |
| e40c1-eed2dc5-8675309-sol-114191 | P F F | slain by a zombie | 0 in every run |

**Result: 0 confirmed. The gate passes.** The lead cleared both by the marker rule, without solo confirms: all markers and
the walk's entry are 0 in all three runs, and no changed line acts in either flagged scene.

**Deaths across the full runs:** counted as in [F-25c4dk](F-25c4dk.md).

| | Per run | Mean |
| --- | --- | --- |
| Baseline `16cb7a2`, its 422 scenes | 88, 81, 90, 89, 87, 86 | 86.8 |
| Branch, the same 422 scenes | 74, 67, 75 | 72.0 |
| Branch, all 472 scenes | 91, 86, 94 | 90.3 |

**Kept:** the runs are on `claude/spacer-runs-1112`, under `gate25c4di1g/`.

## Remaining limitations and handoff

- **The live read, iteration 28:** the 7 spells of iteration 26 are expected to go, −21 stuck minutes, 9 of them on
  batch 1's 20261030. If they don't, this row reopens.
- **The cost to watch in 28:** beds left where they stand, against beds taken up, per seed.
- **A take-up from the other half needs its spawn at the bed.** A bed set down but never lain in (its spawn elsewhere)
  is not broken from beneath it; it is set aside.
- **Openings:** at most two blocks a morning, each beside the bed's room. A bed whose way in is longer is set aside, and
  left at the third set-aside.
