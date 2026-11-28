# F-25c4dn — A craft is offered only when its result has room in the pack; otherwise room is made first

Date: 2026-11-23
Agent / branch: Claude, LOOPS / `claude/craft-room-1122`, from main `d23c4c13`, at the lead's word
Build: the claim `21b14080`; the fixture `c09f6cfd`, `28ec2e3b` (the freed slot asserted, MODES) and `2b3ed7a2` (its table
staged as its own); the change `8c69b35f`; the second change `ce208334`; main `150eb1f5`+ merged as `4716627a`; gated on
`60e5a7c2`, with a temporary marker; the marker removed `1eb9b3b6`; main `285de9df` merged in as `98249fce`
Design parent: F-25c4 (the portal run's steps)
Status: complete (2026-11-23). The live read, E-40 iteration 30's 404: the crafting-table spells gone.

## Scope and implementation

**Found:** a read of E-40 iteration 27's consoles (`claude/corpus-data-e40a27-consoles-1122` at `7b930d48`).
- **The loop:** 1,495 "Climb to find another way" starts came right after a plan step. "Walk to the crafting table" came
  before 371 of them, 319 inside stuck spells.
- **Where:** batch 1's 404 Kindred (136 walks) and Echo (40); batch 2's 404 Echo (140) and Sol (6).
- **Stuck minutes:** 6 of batch 1's 404's 30 and 9 of batch 2's 404's 21, at three a spell. Without those 9, batch 2's 404
  would be at 12, within the bar.

**The cause:** the planner and the craft disagreed about room, not the makings.
- `NativePlayerBody.planCraft` refuses when there is no room for the result once the ingredients are reserved. A stone
  pickaxe needs an empty slot.
- `PortalRunSnapshot` carried no free-slot count, so the planner offered the craft from counts alone. `craft` logged the
  refusal as "not the makings".
- 20 of the four bodies' 23 "cannot make … at the table in reach" lines came within 15 s after an "inventory full" goal.
- At the table it had nothing to do, climbed to find another way out of reach, and was sent back to the table.
- **Not the stone variants:** the snapshot counts `ItemTags.STONE_TOOL_MATERIALS` and the recipe tests the same tag.
- **The read in one case:** batch 1's 404 Kindred at 14:32:19–20 made two stone pickaxes, the wood goal's and the iron
  stage's spare, into its last free slots; the third had none.

**A second loop, a note only:** batch 2's 8675309 Sol was offered "Fish for food" 187 times in one spell with no fishing
skill run. No cause is visible in the console.

**User-visible behavior:** with its pack full and the makings of a craft, an AI player drops a stack of surplus first,
then makes the thing. It no longer walks back and forth to its table finding no room. A refused craft now says why.

**Changed files, the first change (`8c69b35f`), the room rule:**
- `PortalRunSnapshot` (core): two new canonical components.
  - `freeSlots`: the main inventory's empty slots.
  - `roomyStacks`: the recipe names of which a stack carried has room for more.
  - `roomFor(item)` reads them.
  - The previous canonical signature remains as an overload with `ROOM_UNKNOWN` (36) and no stacks: room assumed, so
    existing callers, the simulator and core tests are unchanged in meaning.
- `PortalRunPlanner` (core): `plan` maps each `CRAFT` whose result has no room to a new `MAKE_ROOM` step for that result,
  at its single exit (`roomFirst`).
- `PortalRunCampaign` (adapter):
  - the snapshot's build counts `freeSlots` and `roomyStacks` ("planks" for any planks);
  - `MAKE_ROOM` realizes as `makeRoomFor`: the existing "Drop surplus" discard with one slot wanted, not three, stone kept
    down to 16 (a furnace's eight and a stone pickaxe's three), and the label "… to make room for <item>";
  - not while a mob attacks, as the inventory-full drop;
  - nothing to drop, nothing offered: the plan's next step has its turn, and the craft is not offered meanwhile.
- `NativePlayerBody.checkCraft` (new): `planCraft`'s plan or why there is none.
  - The reasons: "no room for the result", "the crafting grid or cursor not empty", "a screen open", "not the makings",
    "no crafting table in reach", "no such recipe", "no grid it fits", "no result".
  - `planCraft` returns its plan, unchanged in what it decides.
  - `craft`'s "cannot make … at the table in reach: …" now gives the reason.

**Changed files, the second change (`ce208334`), the room drop recorded when done:**
- **The fault:** `makeRoom` marked a stack as thrown when it offered the drop, not when the drop ran.
  - Offered and not yet dispatched, the next look marked the next stack too. With nothing left to offer, the plan fell to
    its next step and climbed.
  - The fixture failed 1 run of 3 so on `2b3ed7a2`.
- **The fix:** the room drop is recorded as thrown once its discard completes (`roomDropEnded`, from `taskFinished`).
- **The inventory-full drop:** it shares the refactored `makeRoom(player, wantFree, stoneKeep, forWhat)`, and its own path
  is unchanged: three slots wanted, 64 stone kept, recorded as thrown on offer. That loop is MODES's.

Nothing is given or moved: the player's own discard drops its own items in the world, and its own craft makes the result.

**Schema/configuration changes:** none. No saved keys.

**Gametest:** `NativeCraftRoomGameTests` (new, registered in `fabric.mod.json`). **Unit:** `PortalRunPlannerTest
.aCraftWithNoRoomForItsResultMakesRoomFirst`.

## Acceptance evidence

**Fixture (staged):** `NativeCraftRoomGameTests.withItsPackFullItMakesRoomThenMakesTheStonePickaxe`, staged from 404
Kindred.
- STAGED: stone round it, no sky; a corridor two high and nine long, lit by torches set in its walls.
- STAGED: a crafting table at its far end, six blocks off, its own: set down and known as the runtime records one it
  places. The plan counts it (within sixteen), and the craft can't reach it (4.4 from its eyes).
- STAGED: the player at the near end with no pickaxe, four sticks, sixteen cobblestone, twenty-four cobbled deepslate and
  eight bread. Every other slot holds a full stack of bricks, which nothing uses or drops as surplus.
- The portal goal; daylight held.
- Fails unless, within 600 ticks, a slot of its pack is seen freed and then it holds a stone pickaxe.

| Check | Build | Result |
| --- | --- | --- |
| Unit `PortalRunPlannerTest` | `8c69b35f` | 46 of 46 |
| Fixture before the change | main's source (`21b14080`) with the final fixture | 3 of 3 fail with iteration 27's own pattern: "Walk to the crafting table", "cannot make stone_pickaxe at the table in reach: not the makings", "Climb to find another way" |
| Fixture after the first change | `2b3ed7a2` | 2 of 3 pass; the third, the room drop offered and marked thrown before it ran, then the climb |
| Fixture after the second change | `ce208334` | 3 of 3 pass: a slot freed at 5, 7 and 163 ticks; the stone pickaxe 28 to 30 ticks after |
| Related classes ×1 (26) | `ce208334` | 213 of 213 |
| Fixture and 3 close classes ×3 | `98249fce` (main `285de9df` merged in) | 3 of 3 pass, 6 of 6 each |

**The related classes:** NativeBring, Bucket, CastTable, CavernLedge, Chronicle, CobwebHeadRoom, CornersInHand, Craft,
CraftHelper, CraftRoom, FactionBase, FactionPlay, FollowUp, Footing, Gold, House, KitName, Netherite, OwnRanch,
PortalCampaign, Smelter, SwimUp, TableLoop, TeamShare, TradingHall, TripDown.

**The 3 close classes:** NativeCraftHelper, NativeTableLoop, NativeCornersInHand.

**Log of the run:** the first staging of the fixture failed on main for the wrong reason. The plan sought logs for a
table, because it counts only a table of its own. The table is now staged as its own, and said so.

## Corpus gate (`60e5a7c2`; baseline `16cb7a2`)

**The marker, stated before the gate:** "F-25c4dn-marker: room made for <item> in place of the craft at <pos>: <the drop
offered, or nothing to drop>".
- It was logged the first time the plan's `MAKE_ROOM` step was reached, and re-armed after 1,200 ticks without it.
- `roomFirst` changes the plan only by putting `MAKE_ROOM` where a `CRAFT` with no room stood. So every decision the
  change alters passes through that step's realize: the room drop, nothing offered, and the drop recorded on completion.
- The snapshot's new fields are read only by `roomFirst`; `craft`'s new wording changes a log line, not a decision.
- The fixture fired it once, the positive control. It was removed after the gate (`1eb9b3b6`).

**The corpus cannot exercise the room rule.** Its newest scenes are iteration 24's (e40a24); none after. No run, on the
branch or on the base, has a "cannot make … at the table in reach" line. The gate shows only that the change breaks
nothing; its effect rests on the fixture, and the live read is iteration 30's 404.

| Run | Minutes | Peak | Required failed | Marker | "Make room for" steps |
| --- | --- | --- | --- | --- | --- |
| 1 | 61 | 3,056 MB | 130 | 0 | 0 |
| 2 | 61 | 3,109 MB | 114 | 0 | 0 |
| 3 | 58 | 3,100 MB | 127 | 0 | 0 |

- **Against the baseline:** 80 scenes newly pass, and 6 are flagged.

| Scene | Body | Full runs | Failure |
| --- | --- | --- | --- |
| diag-holepit-079bdc2-8675309-echo-51440 | R452817_1 | F F P | still in its stuck spell |
| e40a13-fe2ba3b-505-echo-5394 | Rc48a157_1 | F P F | doomed to fall by a skeleton |
| e40a14-80b7150-101-echo-75842 | R52fdb63_1 | F F F | shot by a skeleton |
| e40a15-dbf4e5c-303-kindred-66804 | R515926_1 | F F F | slain by a zombie |
| f29e-on-1530142077-echo-83228 | Rb527f07_1 | F F F | slain by a zombie |
| factions-e40a16seeds-b7b4bec-8675309-echo-106722 | R6fb1641_1 | F F P | slain by a zombie |

**Result: 0 confirmed. The gate passes.** The lead cleared all six by the marker rule: the marker read 0 and no room step
came in any run. Three of them (`…75842`, `…66804`, `…83228`) have been flagged in three or four gates running with the
same deaths. The lead has asked for a new baseline on current main.

**The drop counts,** against bx1's three gate consoles (main `d56dfb17` plus F-25c4bx1):

| | Branch 1 / 2 / 3 | Base 1 / 2 / 3 |
| --- | --- | --- |
| "Drop surplus" goal lines | 1,104 / 1,146 / 1,297 | 1,211 / 1,352 / 1,325 |
| A drop then "Climb to find another way" | 1 / 1 / 1 | 1 / 3 / 0 |
| Inventory-full discards completed / blocked / cancelled | 203/4/14, 198/5/15, 204/11/16 | 206/5/10, 234/5/18, 222/3/17 |
| Room drops offered / completed | 0 / 0 in every run | none (not on the base) |

- No rise, so no scene to name.
- **The base differs from the branch by more than this change:** main has since taken #463, #464, #467, #469, #471 and
  #474. A rise would be this change's only where its marker fired for that body in that run, and it fired in none.

**Deaths across the full runs** (a corpus observation, not an exit-criterion claim):

| | Per run | Mean |
| --- | --- | --- |
| Baseline `16cb7a2`, its 422 scenes | 88, 81, 90, 89, 87, 86 | 86.8 |
| Branch, the same 422 scenes | 72, 65, 70 | 69.0 |
| Branch, all 472 scenes | 89, 81, 90 | 86.7 |

**Kept:** the runs are on `claude/spacer-runs-1112`, under `gate25c4dn/`.

## The live read (E-40 iteration 30, 2026-11-23)

Iteration 30 ran main `63b97ac9`, which has #486; iteration 29 ran `9a0921b5`, which does not. The consoles are on
`claude/corpus-data-e40a30-consoles-1123` at `788c3f53`. Seed 404, both batches each:

| Iteration | Stuck spells | Goal lines walking to a crafting table | "no room for the result" |
| --- | --- | --- | --- |
| 29 (`9a0921b5`) | 13 + 2 = 15 | 1 + 1 | 0 |
| 30 (`63b97ac9`) | 1 + 1 = 2 | 0 + 1 | 0 |

- 29's batch 1 404 also had 829 "Gather logs for a crafting table - Climb toward the surface for logs" lines; 30 has 0 and 2.
- The crafting-table spells are gone, and no craft was refused for room. The row stays Done.

## Remaining limitations and handoff

- **The live read, E-40 iteration 30's 404:** read above; the crafting-table spells are gone.
- **Room by counts:** `roomFor` counts an empty slot or a stack with room. The craft's own check also counts a slot its
  ingredients empty. Where only that makes room, room is made first, one drop more than needed.
- **Nothing to drop:** with a full pack of nothing surplus (stone at or under 16, no junk), `MAKE_ROOM` offers nothing and
  the craft waits. The plan's other steps go on.
- **The inventory-full drop's own loop** (MODES's) is unchanged.
- **The fish loop** of batch 2's 8675309 Sol is a note only.
