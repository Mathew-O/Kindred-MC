# F-25c4ar — The watchdog's move to a place farther than one route, a leg at a time

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `2bceb1f`; recorded in [validation](../validation.md)
Status: in progress (the fixture and its neighbours pass on `2bceb1f`, the fixture failing before; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: when the watchdog moves a player elsewhere after three minutes with no progress, and the new place is farther than one walk reaches (home, on every third such move, from far off), the player now walks there a leg of 96 blocks at a time, as its plan's walk home goes (F-25c4ae). Before, the walk was aimed at the place itself, and a walk farther than 160 blocks is refused as made. Its digging toward the place then went only as far as water or a drop on the way, and with water beside it the move was lost.

Each walk of the move is judged once it has ended, by how much closer it ended. Before, it was judged at the next look at the plan, which can come before the walk has run: another step still had the turn when the walk was given out, or the plan was looked at again meanwhile. Found no closer, the move dug toward the place instead, or ended, before it had walked a step.

Found in the live pair on `00e98f5` (seed 31102026, a desert, the End goal, strategist off): Echo, by a river at 148, 63, -93 with the only tree it knew of standing in the water, had its moves toward home, 200 blocks off, refused: "Goto target is too far for one route".

Changed modules/files:
- `PortalRunCampaign.relocateStep`: a new place farther than 96 blocks is walked to by the point 96 blocks toward it. The walk given out is given out again until it has ended (`taskFinished`), for up to 1,200 ticks; only then is it judged.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| Moved elsewhere toward a place 200 blocks off, a pool in front of it: it walks round the pool and on toward the place, still on the move | `aMoveElsewhereFartherThanOneRouteGoesALegAtATime` | Before (the walk aimed at the place, judged at the next look), 0 of 3: "Not on its way: at -108, -1, -23, Wood: ... Explore north for logs (tried elsewhere 1 time)". With the leg alone it failed 3 of 3 too: the move's first walk came while a climb was still running, the next look found it no closer, and the move ended. After, with both parts, 3 of 3, each alone in its batch (2026-10-31) | native |
| The watchdog's other moves unchanged | The watchdog's move elsewhere after no progress, the pocket's move elsewhere, home far off a leg at a time, and the water's fixtures beside them | 11 of 11, each alone in its batch, on the final code (2026-10-31) | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the strip of grass and the pool are staged, the player set down on it, and the move elsewhere begun by the fixture; daylight; bread.

## Remaining limitations and handoff

- Each leg is aimed along the straight line; a leg's end in a lake or a cliff is walked toward as far as the way goes.
