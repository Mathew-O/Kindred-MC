# F-25c4ad — Lava it can see but no walk leads to is dug toward

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `0c48f53`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player that wants lava for its cast and sees a pool that no walk leads to digs its way toward it: a staircase up or down until its feet are a block above the pool's top, then a tunnel along that level, which opens a cell with lava under it to show the lava (F-25c4ac). It then fills its bucket there. Before, its walk to the pool was refused, that source was set aside, and the next source of the same pool tried, one after another, until none was left and the pool was forgotten.

Found in the live pair on `41f9de9` (seed 20261030, strategist off):
- Echo saw lava for the first time in two pairs at 02:01, a pool in a pocket inside the mountain at Y=78, some 46 blocks off, while it waited for daylight below for its water.
- "No walkable route" to each of its sources, twenty in four seconds; each was set aside, and the pool with them.

Changed modules/files:
- `PortalRunCampaign`: a walk to the lava that fails marks that pool (`lavaNoWalk`) to be dug toward (`digToward`) rather than walked for; a filled bucket ends it.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| On the rim of a pit five deep, a lava pool at its bottom in plain view and no way down it could walk, a water bucket and an empty one: its bucket is filled at the pool, and it is unhurt | `lavaItCanSeeButNotWalkToIsDugTowardAndItsBucketFilled` | Before, the change set aside, it fails 3 of 3: "Its bucket filled at the pit's pool: now 13, -2, 41, Obsidian: Cast the portal frame from lava and water - Branch mine for lava: advance", its walks to the pool refused 35 times. After, 10 of 10 alone. In the fixture's first form one run in three never saw the pool, its sweep having passed the pit's section before the pit was laid; the fixture now gives it its look from the rim | native |
| The other lava searches and casts unchanged | the lava under a corridor (F-25c4ac), the lava legs (F-25c4j), the night's lava (F-25c4n) and the whole cast, alone on this code | 4 of 4 | native |
| In play | the live pair on `64c298d`, both seeds, strategist off | Met in part. On the second seed Sol's walk to a pool at Y=-55 was refused; it dug toward it, opened cells over it and filled its buckets. On the first seed Kindred dug toward lava it saw at 0, -18, 48 and, at the lava's level, tunnelled 81 blocks west past it: each step south the floor beyond was open, and the tunnel turned | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the block of stone, the pit, its pool and the glowstone are staged, and so is the moment of its look over the pit from the rim (a sweep, then a look whose every sighting is a line of sight from its eyes): the sweep alone may already have passed the pit before it was laid, and one run so never saw the pool. The kit is given as though made.

## Remaining limitations and handoff

- Only the pool a walk failed to: lava it has never tried to walk to is still walked to first.
- A staircase toward lava turns where a cell is refused, as every staircase does; lava level with the way it digs, or above it, is still refused unseen.
