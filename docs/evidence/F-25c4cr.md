# F-25c4cr — The trip's wood from the next trees in the packing range, its own tree cut, before the first stone

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-24-1108 (E-40's ladder, batch 24)
Build: on top of `41c4d62` (main with batch 22); recorded in [validation](../validation.md)
Status: complete (native and planner). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player with its wooden pickaxe made, short of the wood a trip below takes, now walks to the
next trees it has seen within 64 blocks for that wood before its first cobblestone. Before, it did so only from a tree
within 24 blocks, and otherwise dug for stone where it stood. By then the tree it had made its pickaxe from was often
cut, or a teammate was cutting it.

Found: E-40's iteration 15 (`e40a15-dbf4e5c`). The run's largest family of stuck spells left after batches 22 and 23 is
"Gather logs for sticks" climbing from deep down: some 17 spells, on 101, 202, 707 and 31102026.
- Ten players on six seeds climbed for logs for sticks. Six of them had packed at most five steps of the trip's wood:
  on 101 Sol (none) and Echo (one), on 31102026 Sol and Echo (none) and Kindred (three), on 707 Echo (five). Of the
  seven players who packed at most five, six climbed; of the 21 who packed ten or more, three did.
- On 101 Sol cut the one tree it had walked to and made its wooden pickaxe at its foot. The next trees it had seen were
  40 to 60 blocks off. It went "Mine cobblestone for a stone pickaxe" from there and dug down to Y=19. Branch mining
  for iron there, it made three more stone pickaxes one after another as each wore out, and then had no sticks for the
  next.
- On 707 Echo's "Gather logs for the trip below" was cut short. Kindred was cutting the same tree ("Another task holds
  this block"), and with that tree gone there was none within 24 blocks.
- The digging's rate is not the cause: over the ten seeds the players dug 41 blocks for each iron ore (13 to 63 a seed),
  under the 90 the stick reserve is sized for (F-25c4cj).

Changed modules/files:

- `companion-core`: `PortalRunPlanner.primary`. At the wooden pickaxe the trip's wood is gathered from a tree within
  `TREE_PACK_RANGE` (64), the range the stick reserve is already topped up from before work below, instead of
  `TREE_RANGE` (24). A tree farther than 64 still sends it on to stone with what it has.
- `companion-core` test: `PortalRunPlannerTest.theTripsWoodIsTakenAmongTheTreesBeforeTheFirstStone` asked for no
  packing from a tree forty blocks off; it now asks for the trip's wood from there, and for none from beyond 64.
- gametest: `NativeTripWoodGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| At its wooden pickaxe, the next tree forty blocks off, the trip's wood is gathered; from beyond 64, the stone | `PortalRunPlannerTest.theTripsWoodIsTakenAmongTheTreesBeforeTheFirstStone` | On `c1c401e`, without the fix: fails ("the trip's wood from forty blocks off": `GATHER_STONE`). With it: the class's 44 pass. |
| Its own tree cut and the next trees forty blocks off, its first cobblestone comes with the trip's wood in hand, 28 planks' worth or more | `NativeTripWoodGameTests.itsOwnTreeCutTheNextFortyBlocksOffItTakesTheTripsWoodBeforeItsFirstStone`. Staged as described below | On `ec0695f`, without the fix: fails 3 of 3, its first cobblestone mined where it stood with the 3 planks it had. With the fix (`3f80bac`): passes 3 of 3, with 55 planks' worth from the trees forty blocks off at its first cobblestone, after 1,314 to 1,474 ticks. |
| The rest | `companion-core`'s tests, the classes that pack, mine and climb, `bash ./gradlew build` | See [validation](../validation.md) |

The fixture's staging:
- A flat, open stone plot, 55 by 17, with four oak trunks of five forty blocks off: as many as the sibling fixture
  (`withItsWoodenPickaxeAmongTheTreesItTakesTheTripsWoodBeforeItsFirstStone`) has beside the player.
- The trees are told to the campaign as seen before (`WorldKnowledge.remember`), as Sol had seen its trees on its way to
  the one it cut, after a first sweep of its own.
- The player with a wooden pickaxe, three planks and bread, a crafting table beside it, as though made from the tree it
  cut. Morning held.
- The bench ran beside the Factions evaluation, at the lowest priority, on the cores it left idle.

The fixture was settled in two forms, and the runs above are on it as committed.
- In its first form (`c1c401e`) the trees were not told to the campaign. Without the fix it failed 3 of 3, as above.
  With it, it passed 1 of 3: in each run its first step, taken before its first sweep had reached the trees forty blocks
  off, was "Mine cobblestone for a stone pickaxe", and the next was "Gather logs for the trip below". In two runs the
  first cobblestone was in hand by then.
- In the first run without the fix, after its first cobblestone, it walked to the trees for a crafting table and packed
  once among them.
  The check at the first cobblestone is what tells the two apart.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Trees farther than 64 blocks are still not walked to for the trip's wood: on the desert seed, 31102026, the trees may
  be farther. A player there sets out short.
- Four players who packed more still climbed for logs for sticks: Kindred and Sol on 202 and Sol on 8675309 (ten or
  more steps), and Sol on 303 (seven). Their stick reserve ran out below for other reasons, not yet traced.
- A teammate cutting the same tree is not waited for or passed over at once: the claim refusal sets the log aside, and
  with this fix the next tree within 64 is gathered from.
