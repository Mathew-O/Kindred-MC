# F-25c4aa — Dry ground a walk out of the water failed to get to is passed over for the next nearest

Date: 2026-10-30
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `a4df9b4`; recorded in [validation](../validation.md)
Status: complete 2026-10-31 (native passed; met in the live pair on `41f9de9`); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player swimming in water it cannot stand in heads for the nearest dry ground it can get to. If a walk out fails to get there, the current carrying it past say, that spot is passed over for the next nearest for a while, as a drop it cannot reach is (F-25c4s). Before, the same spot was chosen again at once, and a player in a stream could set out for it for minutes.

Found in the live runs:
- On `a4df9b4` (seed 20261030), from 22:53 Echo, in a stream running down a hillside at -12, 78, 23, set out for the same spot on its bank four blocks down 173 times ("Route to -11, 74, 24 is blocked"), until 23:04. The world save shows the stream's water flowing down the slope there.
- On `458d192` (seed 8675309), Sol did so 375 times for one spot, and on `a4df9b4` (seed 8675309) Kindred 57 times.

Changed modules/files:
- `NativeNavigator.nearestReachableDryStand`: a variant that passes over dry ground a predicate refuses.
- `PortalRunCampaign.surface`: not dry ground set aside after a walk to it failed, both for the climb out at the surface and for the swim to the nearest dry ground.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| In a pool five deep in a closed stone room, a notch of shore one block across near it and a ledge along the far wall, one walk out to the notch reported failed: it gets out on the far ledge | `dryGroundAWalkOutOfTheWaterFailedToReachIsPassedOverForTheNextNearest` | Fails on the old campaign, 3 of 3: "Out on the far ledge, not the notch it failed to reach: 6, 0, 0", the notch chosen again first. Passes after, 3 of 3 alone. Asserting only where it ended up passed on the old code too: from the notch it moved on later | native |
| The other ways out of water unchanged | the full native suite on this code, the water fixtures among it | 421 of 422; the one failure, the life-first fixture that hunts before building its base, passed 3 of 3 alone after | native |
| In play | the live pair on `41f9de9`, both seeds, strategist off | No spot was set out for more than four times: Sol's walks out of a pocket of flowing water on the first seed failed at one spot after another, each passed over. On `a4df9b4` one spot was set out for 57 times and another 173 | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the room, its pool, the notch, the ledge and the sea lantern are staged, and so is the failed walk out: one walk to the notch is reported to the goal as failed, as its own walks report, since a current that carries a player past its mark is not easily made in a fixture. The kit is given as though made.

## Remaining limitations and handoff

- Each spot is passed over only once a walk to it has failed: along a stream's bank, one spot after another may be tried.
