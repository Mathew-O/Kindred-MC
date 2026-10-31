# F-25c4aq — A night shelter sealed with sand or gravel where it rests on solid ground

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `00e98f5`; recorded in [validation](../validation.md)
Status: in progress (written; the fixture's runs before and after, and its neighbours', are queued; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player sealing the way into where it waits out the night, or setting any block of its own (cover from a skeleton, a cast's corner, holing up), uses sand, red sand or gravel when it has nothing else to build with. It puts them only into a cell over solid ground, where the block rests. Before, it built only with stone, cobblestone, dirt and the like. A player that had dug in by hand in a desert held only sand, so its seal was refused and it waited out the night with the way in open. Over an open cell, such as the cell over its own head, a falling block is still never placed.

Found in the live run on `91c1290` (seed 31102026, a desert spawn, the End goal, strategist off): two of the run's four deaths came this way. Both players had dug in for the night by hand, two steps down through sand and sandstone, and both held only sand. Kindred was killed by a husk come down its staircase, and Echo by a drowned.

Changed modules/files:
- `PortalRunCampaign.placeBlock`: with no stone, cobblestone, dirt or the like, sand, red sand or gravel (`RESTING`) for a cell whose cell below is solid.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| At night below ground with no wood, holding only sand, beside a corridor into its pocket: it seals the way in, the block at its feet and the one over it, unhurt and never buried | `atNightBelowGroundWithOnlySandItSealsTheWayInWithTheSand` | Pending: queued after the live pair on `00e98f5` | native |
| The shelters, cover and placing beside it unchanged | The night shelters, the cover from skeletons, the walls against caves, the cast and the bridged lava beside it, each alone | Pending: queued after the live pair on `00e98f5` | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the sandstone, the pocket, the corridor and the torch are staged; the sand and bread are given; night is set.

## Remaining limitations and handoff

- A way in whose cell below is open (the step over a drop) is not sealed with sand; with nothing else, it stays open.
- An opening over the player's head is not sealed with sand.
- Sandstone, which a pickaxe takes from a desert, is not among its building blocks; with a pickaxe it soon has cobblestone from the stone below.
