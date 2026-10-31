# F-25c4al — The Nether goal done, its portal lit, the player stays by it

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `c432fba`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player whose goal is the Nether portal, once its portal is lit, does nothing more for the goal. Its plan reads done, it starts no climb, dig or walk for the goal, and the stall watchdog stays off. It still eats, fights back and gets out of danger as ever. On the dragon or End goals the lit portal is a step, and the leg toward the End portal goes on as before. Before, with the plan's last step done, the plan's fallbacks ran (underground, "no way on from here", a climb), and three minutes later the watchdog's spell to try elsewhere dug it away from its portal.

Found in the live pair on `c432fba` (strategist off), the first pair with a portal lit:
- On seed 8675309 Kindred lit its portal at Y=-54 at about tick 236,000. A second later its goal read "Done: no way on from here - Climb to find another way", and later "Done: no progress here for a while - dig toward elsewhere".
- On seed 20261030 Sol lit its portal at about tick 150,000, and its goal went the same way.

Changed modules/files:
- `PortalRunCampaign.plan`: on a goal that ends at the portal, a plan whose first step is done returns nothing, before the fallbacks.
- `PortalRunCampaign.next`: on such a goal at the done stage, the stall watchdog's clock and spell are cleared.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| The owned-block frame built and lit under a roof on the Nether goal, then four minutes watched, past the watchdog's three: it stays within two blocks of where it lit it, its goal done the while, and the portal stands lit | `itsPortalLitTheGoalIsDoneAndItStaysByItNothingDug` | Before, the change set aside, it fails 3 of 3: "0, 1, -3 from where it lit it, Done: no progress here for a while - dig toward elsewhere". In its first form the fixture failed before for another reason: its first look came before the panel's line read done. After, 3 of 3 alone | native |
| The portal goals unchanged | the owned-block frame, the whole cast and the walled-in site, alone on this code | 3 of 3 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the roof is staged; the obsidian, corners, flint and steel and the rest of the kit are given as though made.

## Remaining limitations and handoff

- Done means idle: on the Nether goal the player waits by its portal for a new goal; going through it is the dragon and End goals' leg.
