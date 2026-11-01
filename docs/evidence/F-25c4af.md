# F-25c4af — Short of breath, the way out of the water is not stopped to fight

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `0c48f53`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player in water with less than half its breath left swims out or up for air even with a hostile mob on it, and does not stop to eat; it fights, and eats, once it can breathe. Before, two things stopped the swim up. Any step of the goal's was stopped while a hostile mob was attacking, to fight back first, the walk up for air too; the next plan chose the walk up for air again, as getting out of the water comes before a fight, and it was stopped again. And as its health fell, eating came first, and each start of it was cancelled for the threat, until the player drowned.

Found in the live pair on `41f9de9` (seed 8675309, strategist off): Echo drowned at 14, 8, 41 at 00:55:49. Its walk "Get out of the water: up for air" was cancelled ("A hostile mob is attacking; fight back first"), and in its last two seconds it began to eat a dozen times, each cancelled ("Hazard/threat detected: urgent retreat required").

Changed modules/files:
- `PortalRunCampaign` (the goal's interrupt of a running step): a walk out of the water is not stopped for a fight while the player's air is under half.
- `PlayerRuntime` (the survival candidates): no eating in deep water while the player's air is under half.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| At the bottom of a stone pool ten deep, air 40 of 300, health 19 and hunger 17 with bread, a drowned beside it after it, an iron sword and chestplate: it comes up for air and never drowns | `shortOfBreathWithADrownedAfterItItSwimsUpForAirRatherThanStopToFight` | Before, the change set aside, it fails 3 of 3, slain by the drowned while eating, its eating begun and cancelled for the threat 164 times. With only the fight part of the change it fails 3 of 3 the same way (166 cancels); with only the eat guard it drowns 3 of 3, its walk up for air stopped 130 times to fight back first. After, 3 of 3 alone | native |
| Fights and the other ways out of water unchanged | the goal's fight with a mob after it, the swim up a flooded shaft, the flooded pit (F-25c4t), the shore passed over (F-25c4aa), the fall broken with water before a fight (F-25c4p), and eating when hungry and while routine planning waits, alone on this code | 7 of 7 | native |
| In play | the live pair on `64c298d`, both seeds, strategist off | Not met yet: no player drowned or was short of breath with a mob after it in the log (one drowning on `41f9de9`) | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the pool, its light, the drowned and the player's air, health and hunger are staged; the kit is given as though made.

## Remaining limitations and handoff

- With half its breath or more it still stops to fight what attacks it in the water, and eats there, as before.
