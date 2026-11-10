# F-25c4cy — Cover from a mob is kept while the mob is about, not dug out by the next climb

Date: 2026-11-09
Agent / branch: Claude / claude/cover-climb-1109 (E-40 ladder, batch 26), on main (`66788a8`); the benches below ran on
`claude/cast-outcome-1109` (`4361a52`, main with #96 and #98), the fixture again on main
Build: recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is the next evaluation's to show.

## Scope and implementation

User-visible behaviour: a player hurt and out of food below ground that puts a block up between itself and a mob after it,
with nowhere to back off to, keeps that block standing while the mob is about. Before, the goal's next step, the climb for
food, dug the same block out at once, and the player put it back and dug it out again until the mob killed it.

Found: `ReplayGameTests` with the kept replay `echo-73670`, failing #83's CI (run 42583923691; it died 1 of 12 in batch 25's
benches).
- The player, at four health with no food, a zombie on it and no way back, took cover ("Take cover from zombie", a block
  at the cell east of and one above its feet). With the zombie behind that block, the fight step chose nothing, and the
  goal's hungry branch chose the climb: "Climb toward the surface for food: clear" that same cell, the landing of its only
  step up. Placed and dug three times over in one second; the zombie killed it during the third dig.
- `defend` takes cover through `coverFromHunters` when `backOff` finds nowhere; `cover` gives nothing once the cell toward
  the mob is filled, so behind its own block the fight step is empty and the goal goes on. Nothing marked the block as
  cover: `climb` (and `tunnel` and the other digs) saw an ordinary breakable block in the way.

Changed file: `companion-server-adapter` `PortalRunCampaign`.
- `ownCover`: a "Take cover from …" placement that completes is recorded by its cell and tick.
- `holdCover`, at each plan: of those cells, the ones still standing with a living hostile mob within six blocks are held,
  and `blocked` answers true for them, as for a cell set aside, so the climb, the tunnel and every other dig that asks
  `blocked` leave them. A cell is let go once no such mob is near, once it is air, or two minutes after it was put up.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Hurt, out of food and with no way back below ground, it keeps its cover from a zombie while the zombie is near, and climbs on for food | `NativeCoverClimbGameTests.itKeepsItsCoverFromAZombieAndClimbsForFoodOnceItIsGone`, staged as below | On `4361a52`, without the fix: fails 3 of 3, "Its cover … dug out with the zombie 4.0 blocks off; … Climb toward the surface for food: clear" the cover's cell, the cover placed and dug out again and again as in the replay. With the fix: passes 3 of 3; the cover stood while the zombie was there, and each time the climb went up from the niche instead. On main (`66788a8`) with the fix: 3 of 3 |
| The death it came from | `ReplayGameTests` with `echo-73670` (each run plays every kept replay three times), 4 runs alone and within the 3 class runs below | With the fix, 20 of 21 lived. On `4361a52` without it, 3 runs: 8 of 9. The one death with the fix was not this one: badly hurt between a creeper and a zombie villager, no cover taken, it backed off "the way it came" and was killed |
| The classes that fight, take cover and climb | `NativeCoverClimbGameTests`, `ReplayGameTests`, `NativeWeaponToHandGameTests`, `NativeCombatGameTests`, `NativeTacticalGameTests`, `NativeEncounterGameTests`, `NativeFallGameTests`, `NativeInitiativeGameTests`, `NativeSpireGameTests`, `NativeEdgeFightGameTests`, `NativeWaterFightGameTests`, `NativeNightShelterGameTests`, `NativeShelterSealGameTests`, `NativeStairGameTests`, `NativeCavernLedgeGameTests`, `NativeLakeBedClimbGameTests`, `NativeFallingColumnGameTests`, `NativeWaterEdgeGameTests`, `NativePortalCampaignGameTests`, 3 runs | 138, 139 and 138 of 139. Each failure was `kindred-43985-707` (F-25c4cv's replay) dying again, backing off "the way it came" from a zombie with no cover taken, so this change's code was not in play; over 7 runs with the fix it lived 18 of 21, and on `4361a52` without it 9 of 9 |

The fixture's staging: on the flat test plot, the end of a cut (the player's cell, two high) with a step up east into a
passage two high where the zombie is; the cut's other sides obsidian, which its stone pickaxe cannot break, but for a niche
one deep, stone over the player's head and obsidian over that, so that its only staircase up is the step toward the zombie,
whose landing is where its cover goes, as the replay's was. The player is moved into the cut after its body is made, its
inventory set to a stone pickaxe and 32 cobblestone, its health to 4 and its food to 8, in daylight; a zombie is spawned
five blocks off along the passage and set on it. Once the cover has held for half a minute the zombie is removed, as
though it gave up. Nothing else is staged.

Three earlier forms of the scene were not kept. All in stone, it failed 1 of 3: the climb or a cut-through mostly went round the
cover. With obsidian walls and roof round the player's cell, it failed 3 of 3 for another reason: the climb had no step it
could clear, and the cover was never dug. With obsidian walls, stone overhead and the passage at the player's own level, it
failed 1 of 3 (a cut-through dug the cover) and otherwise pillared up out of the cut: the climb never steps toward a cell it
can walk into, so the cover, at the player's own level, was never the landing of its step. The kept scene puts the cover where
the replay's was, on the landing of the one step up.

The benches ran with nothing else on the cores but the worktree builds.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- Only cover the goal puts up itself ("Take cover from …") is held; a seal or a wall it did not place for a mob is not.
- The held block is let go after two minutes whatever is near; a mob that waits longer at the cover is then dug toward.
- Both kept replays still die now and then backing off "the way it came" while badly hurt (`echo-73670` 1 of 21 with this fix, `kindred-43985-707` 3 of 21). That is a separate cause, open for the next batch; until it is fixed `ReplayGameTests` can fail a CI run.
- A player behind cover with no food still has nothing to do but wait for the mob to leave; it does not fight from behind
  the block.
