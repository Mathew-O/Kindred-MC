# F-25c4ar — The watchdog's move to a place farther than one route, a leg at a time

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `00e98f5`; recorded in [validation](../validation.md)
Status: in progress (written; the fixture's runs before and after, and its neighbours', are queued; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: when the watchdog moves a player elsewhere after three minutes with no progress, and the new place is farther than one walk reaches (home, on every third such move, from far off), the player now walks there a leg of 96 blocks at a time, as its plan's walk home goes (F-25c4ae). Before, the walk was aimed at the place itself, and a walk farther than 160 blocks is refused as made. Its digging toward the place then went only as far as water or a drop on the way, and with water beside it the move was lost.

Found in the live pair on `00e98f5` (seed 31102026, a desert, the End goal, strategist off): Echo, by a river at 148, 63, -93 with the only tree it knew of standing in the water, had its moves toward home, 200 blocks off, refused: "Goto target is too far for one route".

Changed modules/files:
- `PortalRunCampaign.relocateStep`: a new place farther than 96 blocks is walked to by the point 96 blocks toward it.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| Moved elsewhere toward a place 200 blocks off, a pool in front of it: it walks round the pool and on toward the place, still on the move | `aMoveElsewhereFartherThanOneRouteGoesALegAtATime` | Pending: queued after the live pair on `00e98f5` | native |
| The watchdog's other moves unchanged | The watchdog's move elsewhere after no progress, the pocket's move elsewhere, and home far off a leg at a time, each alone | Pending: queued after the live pair on `00e98f5` | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the strip of grass and the pool are staged, the player set down on it, and the move elsewhere begun by the fixture; daylight; bread.

## Remaining limitations and handoff

- Each leg is aimed along the straight line; a leg's end in a lake or a cliff is walked toward as far as the way goes.
