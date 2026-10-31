# F-25c4ah — Under water, the climb tunnels out from under it rather than going round

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `64c298d`; recorded in [validation](../validation.md)
Status: in progress (written and compiling; native pending; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player climbing toward the surface (for water, food, logs or anything else) whose every step up is refused by water over its ceiling, a lake's or a river's, no longer walks over to a wall, whose steps the same water refuses, nor back the way it came, which went under the same water. It tunnels on, one way, until the ground over its head is dry, then climbs out there, at the shore. Before, under a lake, the climb, the walk to the wall and the walk back took turns.

Found in the live pair on `41f9de9` (seed 8675309, strategist off): Kindred, wanting water for its cast, spent eighteen minutes at Y=45 to 49 under a lake. The lake's floor of gravel lay on its corridor's ceiling, the water three blocks over its head. Every step up was refused: the gravel over its head (F-25c4g) or the water over the cells it would dig. It went round once a second between a step up with no way on, the walk to the wall and the walk back the way it came: 1,417 walks to the wall on that seed, against 20 to 176 on the others of P15 to P18. Its watchdog then dug it along under the lake.

Changed modules/files:
- `PortalRunCampaign.climb`: water over a refused cell, or over the gravel or sand over its head, marks the climb as under water; with every way up refused so, it tunnels on one way (`underWaterHeading`) instead of going to a wall.
- `PortalRunCampaign.wayUp`: within half a minute and 24 blocks of that, the climb comes before the walk back.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| In a corridor under a lake three deep on a floor of gravel over the corridor's stone ceiling, the cast route's kit with its two buckets empty, by day: its bucket is filled with water, and it is unhurt | `underALakeItTunnelsOutFromUnderTheWaterClimbsOutAndFillsItsBucket` | Pending | native |
| The other climbs unchanged | the climb fixtures alone on this code | Pending | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the block of stone, the lake, its floor of gravel, the corridor and its glowstone are staged; the kit is given as though made.

## Remaining limitations and handoff

- It does not dig up into the water to fill its bucket from below; it goes out from under it to the shore.
- The way it tunnels is the way it last faced; a wide lake is crossed under, a narrow one under its width only by chance.
