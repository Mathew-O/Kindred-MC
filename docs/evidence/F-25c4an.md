# F-25c4an — A move elsewhere keeps to the ways that close on its new place, and the plan has a turn after it

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `91c1290`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: when a player's goal has made no progress for three minutes of game time, the watchdog moves it elsewhere, as before. It walks toward the new place, and once walking stalls it digs toward it. The dig now goes up first if the new place is well above, then along the two ways that close on it. It never steps back down below a level it has climbed to, and never below the new place. When neither way is open it climbs out, but not with the climb's walk over to a wall. The plan's own slow work by hand no longer takes turns with it. However a move elsewhere ends, the plan then has three minutes of its own before the next. Separately, the cut-through a player makes when its walks to look keep ending where they started no longer steps down from the ground it stands on.

Before, the dig's tunnel turned any way, so it went back the way it came and stepped down where the climb had just stepped up. The climb then stepped up again, a block either side of the line between them. A move that ended on its time had used up the next one's three minutes, so the next began at once and the plan's own steps never ran.

Found in the live pairs:
- On `c432fba`, seed 8675309, strategist off: Sol's pickaxe wore out in a pocket at about 50, 60, 3. For its last eight minutes it went between three cells: "dig toward elsewhere: advance", "step down", and "climb away from here: step up". Its spells came back to back.
- On `64c298d`, seed 8675309: Echo spent 29 minutes in back-to-back spells between the ends of a corridor beside water (Q28).
- On `91c1290`, seed 31102026: Kindred went the same way at -75, 63..64, 155, and between the climb and the tunnel at -72, 68, 156, where its feet crossed two blocks below the new place. It spent 13% of the run in spells.
- In the same pair on `c432fba` Echo spent 59% of seed 20261030 in spells. Most of that was the retreat refused at a lava pool's edge, its goal's work stopped meanwhile, fixed in F-25c4am.

Changed modules/files:
- `PortalRunCampaign.relocateStep`: up first while the new place is more than two above (`relocateLevel`), then `tunnelStraight` along the major then the minor way that close on it, never below the higher of the new place's level and the level it climbed to (`relocateFloor`). The fallback climb is refused a walk to a wall.
- `PortalRunCampaign.endRelocating`: every end of a move elsewhere clears it and the stall clock.
- `PortalRunCampaign.next`: a move under way goes on while the plan's own step is work by hand.
- `PortalRunCampaign.explore`: the cut-through holds its floor at the player's feet.
- `PortalRunCampaign.relocateForTest`: begins a move elsewhere toward a given place, for the fixture.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| No pickaxe, in a pocket: a cell one up and east of its own, open over its own, obsidian round both and a stair's width of stone east, the only way out; a move elsewhere begun toward a place 24 east at its level: it climbs out, punching the stone by hand, then its own plan runs for five seconds with no move begun again | `withNoPickaxeAMoveElsewhereFromAPocketClimbsOutRatherThanSteppingBackDown` | Before, the change set aside, it fails 3 of 3: "Still in the pocket ... dig toward elsewhere: step down", or its plan's climb by hand. In its first form, with neither the work-by-hand nor the cut-through part, it passed 2 of 3: in the third the plan's climb by hand took turns with the move's, and then the plan's cut-through stepped back down into the pocket. After, 3 of 3 alone, and 3 of 3 again on the final code | native |
| The watchdog, the climbs and the tunnels beside it unchanged | the watchdog's own fixture, the chamber, the gravel over its head, the flooded pit, under a lake, lava across a cave, the ledge and the pit with no pickaxe, alone on the final code | 8 of 8 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the obsidian, the stone, the pocket and the torch are staged; the move elsewhere is begun by the fixture toward a staged place, as the watchdog begins one.

## Remaining limitations and handoff

- A move with neither closing way open and nothing to climb ends at once; the next one, three minutes on, takes another heading.
- With moves and the plan taking turns, a plan stuck for good spends half its time in moves.
