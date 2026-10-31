# F-25c4ae — A walk home beyond one route's reach goes a leg at a time

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `0c48f53`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player that wants logs, with no way up from where it is, heads back toward home. Home more than 96 blocks off is now headed for a leg at a time, as lava seen far off is (F-25c4j). Before, a home beyond one route's reach (160 blocks) was asked for as one walk, refused as it was made, and asked for again, so nothing started.

Found in the live pair on `41f9de9` (seed 20261030, strategist off): Echo, at 190, 69, 15, its home more than 160 blocks off, had "Head back up toward home" refused nine times, and two stall lines ("candidates=0", "Goto target is too far for one route").

Changed modules/files:
- `PortalRunCampaign` (the plan's step for logs): the walk home a leg at a time when home is beyond `LAVA_LEG`.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| The goal begun where the player stands, then the player moved 200 blocks off, sealed in obsidian in a cell its own size, nothing in hand: in twenty seconds it is never held by a walk home refused as it was made | `homeFarOffIsHeadedForALegAtATimeNotByAWalkRefusedAsItIsMade` | Before, the change set aside, it fails 3 of 3: "Held by a walk home refused as it was made: Goto target is too far for one route". After, 3 of 3 alone | native |
| The way to logs unchanged | `woodFirstWithNoTreeInSightLetsTheRouteGoOn` alone on this code | 1 of 1 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the obsidian cell and the glowstone under it are staged, and so is the move away from home.

## Remaining limitations and handoff

- Other walks to far targets are not all split into legs yet; this one and the lava's are.
