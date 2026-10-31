# F-25c4ap — Trees on the skyline: with no wood, it walks to the trees it sees far off

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `3c184b6`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player that needs logs and knows of none within 64 blocks, standing on open ground, looks out to 96 blocks for the tops of trees it can see. It remembers a log of the nearest one it sees and walks toward it a leg at a time, even past the bound its walks to look keep to, 192 blocks from home. A tree it has not got to in a minute (across water, up a cliff) is let be. Before, it noticed logs only 32 blocks round, and looked about only within 192 blocks of home.

Found in the live run on `91c1290` (seed 31102026, a desert spawn, the End goal, strategist off): no player had wood when it was stopped at about tick 175,000. Sol came on the only two trees found, the first an oak 152 blocks south of spawn, while chasing rabbits. Looking for trees took 2 to 3% of each player's time, its walks to look turning back at the bound.

Changed modules/files:
- `WorldKnowledge.treeInView`: every third loaded column out to the radius, past the sweep's 32 blocks. A column whose top block is leaves or a log is a tree's top. The eight nearest each get a sight line from the eyes, which counts if it meets the tree's own crown within three blocks of the top. The first one seen has a log of it found under its top and remembered.
- `PortalRunCampaign.treesInView`: looked for every five seconds of game time from open ground; the walk toward the tree in legs of 40; a tree not reached in a minute set aside by its four-block column. The log step uses it before the walks to look.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| No wood, its home where its goal began, then set down 170 blocks east on open ground with an oak 60 blocks further east, 230 from home and in plain view: it walks to the oak and cuts it | `withNoWoodItWalksToATreeItSeesFarOffOverOpenGround` | Before, the change set aside, it fails 3 of 3: "No wood: at -223, -1, -108, ... Explore east for logs", its walks to look kept within 192 blocks of home. In its first form the fixture moved the player before its first plan, so home was set at the new spot and the runs before failed only by the luck of their headings; now it waits for the first plan. In the first form of the change the look was never due (see the long-ago fix in [F-25c4ao](F-25c4ao.md)), and the sight line to a crown's top that met another leaf of the same crown did not count. After, 3 of 3 alone | native |
| The log, water and food searches and the climbs beside it unchanged | under a lake, under sand, the pocket's move elsewhere, wood first with no tree in sight, the chamber with no way to its trees, the logs at the top of a cut trunk, the trip's wood among the trees, water at night, the two hungry searches once up, the sapling where trees do not come back, and the night's iron, alone on the final code | 12 of 12 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the player's move, the open ground, the oak and its leaves are staged, and the oak's chunks are kept loaded.

## Remaining limitations and handoff

- Trees are seen only over loaded ground, about ten chunks round on a server with the default view distance.
- A crown behind a dune or a hill is not seen until the player is past it.
- A tree let be after a minute is looked at again only once the player has moved on.
