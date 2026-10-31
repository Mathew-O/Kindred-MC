# F-25c4as — A tree standing in water chopped from the water

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `00e98f5`; recorded in [validation](../validation.md)
Status: in progress (written; the fixture's runs before and after, and its neighbours', are queued; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player gathering logs or ore it can reach only from deep water, such as the trunk of a tree standing in a lake, now swims to it and breaks it from the water while its breath lasts, treading water to keep its head up. Whatever it digs in deep water, it treads water. Short of breath, it goes up for air first, as before.

Before, only a walk or a climb out went on in deep water. A player bobbing at a trunk began the log each time its feet came up out of the water, and gave it up each time it sank: "In deep water; up for air first". It then swam to dry ground and came back. Below its eyes it did not tread water, so it sank to the bottom with its eyes under.

Found in the live pair on `00e98f5` (seed 31102026, a desert, the End goal, strategist off): Echo's only tree in sight, an azalea, stood in a lake two deep at 148, 61, -93. A climb out of the water had cut its trunk above the water. Echo went between the log at the water's top and dry ground for twenty minutes, its watchdog moving it elsewhere twelve times.

Changed modules/files:
- `PortalRunCampaign.stopFor`: in deep water, a block it gathers ("Mine ... at") goes on while two thirds of its breath is left, as a climb out does.
- `NativeBreakAction`: in deep water, whatever it digs, it treads water.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| No wood, the only tree in sight a stump of two logs in a pool two deep, the top one level with the water's top: it swims out and chops both from the water, unhurt | `withNoWoodItChopsATreeStandingInWaterFromTheWater` | Pending: queued after the live pair on `00e98f5` | native |
| The water's and the climbs' fixtures beside it unchanged | The flooded pit, the swim up for air, under a lake, the shore passed over, the flooded shaft, water level with its floor, and the casts that take their water back, each alone | Pending: queued after the live pair on `00e98f5` | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the grass, the pool and the stump are staged; daylight; bread.
Analysis: the save round Echo's tree read with `rebuild.py` in the session's scratch space (not committed).

## Remaining limitations and handoff

- The logs of a tree held up by its leaves, such as an azalea's branches, are still not gathered: they would need a pillar.
