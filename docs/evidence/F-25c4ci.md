# F-25c4ci — The corners set in its frame are not cobblestone in hand

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `0a62732` (batch 16); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player with no pickaxe, whose frame has its corner blocks set, makes a wooden pickaxe from its
logs. Before, it set out to make a stone pickaxe "with cobblestone already in hand" that it did not have, and its
crafting table refused it again and again.

The goal counts the corner blocks already set in its own frame toward the four corners it needs, so that it does not mine
them again. They were counted in with the cobblestone it carries, and that count also decides what it can craft: a stone
pickaxe, a sword, a furnace, the casting mould's blocks. Now the corners set are counted on their own. Toward the frame's
corners and the stock of cobblestone, they count as before; toward any tool, only what it carries counts.

Found: E-40's iteration 13 (`e40a13-fe2ba3b`), Kindred on 8675309. It died in lava (tick 62,802) with its frame begun, and
came back with nothing. With no pickaxe and "3 cobblestone" from its frame's corners, the goal sent it to make a stone
pickaxe at its table: "cannot make stone_pickaxe at the table in reach: not the makings", 52 times, with seven crafting
tables made and seven stuck spells "waiting for a feasible step".

Changed modules/files:

- `companion-core`: `PortalRunSnapshot` (`cornersPlaced`, `cornerBlocks()`; the old constructors kept, with none set);
  `PortalRunPlanner` (the frame's corners and the cobblestone stock count `cornerBlocks()`; tools count what is carried).
- `companion-server-adapter`: `PortalRunCampaign.snapshot` (the frame's corners counted apart from the cobblestone carried).
- tests: `PortalRunPlannerTest.theCornersSetInItsFrameMakeNoPickaxeButCountTowardTheFour`; gametest:
  `NativeCornersInHandGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| With no pickaxe and its frame's corners set, it makes a wooden pickaxe from its logs | `NativeCornersInHandGameTests.theCornersSetInItsFrameAreNotCobblestoneInHand`. Staged: a block of stone, the frame's four corners of cobblestone set two blocks east of the player and its site taken as the player's own; four oak logs and food, no pickaxe, no crafting table and no cobblestone; daylight held. Passes once it holds a pickaxe, within 600 ticks | On `0a62732`: fails 3 of 3. It makes its own table, then "cannot make stone_pickaxe at the table in reach: not the makings", then "waiting for a feasible step" for the 600 ticks. With the fix: passes 3 of 3, a wooden pickaxe after 39 to 42 ticks (planks, sticks, its own table, the pickaxe). |
| Placed corners make no pickaxe, but still count toward the four | `PortalRunPlannerTest.theCornersSetInItsFrameMakeNoPickaxeButCountTowardTheFour`: logs, a table and four corners set lead to planks and a wooden pickaxe; walked from nothing to the frame, with its corners set and none carried, the frame is built; with none set nor carried, corner blocks are mined | Pass (the planner's 43 tests) |
| The rest | `bash ./gradlew build`; the classes that build or light frames | See [validation](../validation.md) |

A first form of the fixture set a crafting table down beside the player. The goal takes only tables it set down itself as
its own, so with the fix that player went for more logs to make one; the fixture now gives it a table's worth of logs, as
Kindred had. It failed 3 of 3 before the fix in both forms; the runs above are on the fixture as committed.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Why Kindred's death came about (into lava, backing off from a skeleton at night) is a death of its own, not this fix's.
- The frame's corners are counted wherever a solid block stands at them, as before; a natural block there counts too.
