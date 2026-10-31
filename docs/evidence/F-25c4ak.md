# F-25c4ak — Lava across a cave at its level is bridged toward, not tunnelled past

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `c432fba`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player digging toward lava it can see but not walk to (F-25c4ad), once it stands a block above the lava's level, tunnels only the two ways that bring it nearer the lava, and lays a block of its own over a drop on the way, as a player bridges a cave to lava it sees across it. With neither way open it digs nothing there, and the lava's sources are set aside as before. Before, the tunnel at that level turned wherever a cell was refused or the floor ahead was open, and could go on in any direction.

Found in the live pair on `64c298d` (seed 8675309, strategist off): Kindred saw lava at 0, -18, 48 across a cave from a tunnel at Y=-17, and no walk led to it. It dug a staircase down to the lava's level and tunnelled 81 blocks west, past the lava. In the world save, rebuilt as it stood then, the cave opened south of the tunnel along its whole length, its floor two or more blocks down. At every step south the floor beyond was open, so the tunnel turned west.

Changed modules/files:
- `PortalRunCampaign.digToward`: at the lava's level, the tunnel tries the way along the larger distance to the lava, then the other, one turn each, with its floor at that level (`tunnelFloor`) and drops bridged (`bridgeDrops`).
- `PortalRunCampaign.tunnel`: with `bridgeDrops`, a drop ahead of any depth is filled with a block of its own, as a pit it dug itself already was.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| At the mouth of its tunnel over a cave four deep that runs sixty blocks west, a pool at the tunnel's level on the far side in plain view, no walk to it, a water bucket and an empty one: its bucket is filled at the pool within a minute, and it is unhurt | `lavaAcrossACaveAtItsLevelIsBridgedTowardNotTunnelledPast` | Before, the change set aside, it fails 3 of 3: "now -33, 0, 0 … dig toward the lava", tunnelling west along the cave's side as Kindred did. In its first form, a cave eleven wide, the old tunnel went round its west end in time and the fixture passed before; the cave now runs sixty blocks west, as Kindred's ran on past x=-57. After, 3 of 3 alone | native |
| The other lava searches unchanged | the pit's pool dug toward (F-25c4ad), the lava under a corridor (F-25c4ac), the lava legs (F-25c4j), the night's lava (F-25c4n) and the whole cast, alone on this code | 5 of 5 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the block of stone, the tunnel, the cave, the pool behind its lip and the glowstone are staged, and so is its look across the cave from the tunnel's mouth; the kit is given as though made.
Analysis: `rebuild.py` in the session's scratch space (not committed) read the save's blocks and undid the logged edits.

## Remaining limitations and handoff

- A bridge over a deep cave is walked on as any other block; a fall from it is broken only as any fall is.
- Lava with no way to it along either axis is still given up source by source.
