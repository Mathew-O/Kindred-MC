# F-25c4cg — Attacked in deep water with a shore in reach, it gets out onto the shore

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `ef60cf6` (batch 16 with F-25c4cf); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player attacked while swimming, with dry ground in reach, swims out and climbs onto the shore,
and fights from there. Before, its walk out of the water was stopped "A hostile mob is attacking; fight back first" as
soon as it began. It was then given the walk out again, so it neither left the water nor fought, and was hit as it
swam.

In the water the goal gives the way out first, and a fight only when there is no way out (F-25c4cb). The runtime stops
any work but a fight while a mob attacks; the walk out of the water was spared that only when short of breath. Now it
is spared while the player is still in deep water, or wading two deep, whatever its breath. Once out of the deep water,
the walk is stopped for the fight as before.

Found: E-40's iteration 12 (`e40a12-66a7c0a`), Echo on 8675309 in a flooded passage near 30, 22, 17 with a drowned
about. Its walk out was stopped so 502 times, and a drowned killed it there (tick 20,095, "impaled by Drowned").

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.stopFor` (the walk out of the water is not stopped for a mob while still
  in deep water or wading two deep).
- gametest: `NativeWaterExitGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Swimming in a pool two deep with a drowned after it, dry ground two or three blocks off: it gets out onto the shore | `NativeWaterExitGameTests.attackedInAPoolWithAShoreInReachItGetsOut`. Staged: a pool five square and two deep in a block of stone, its shore level with the water's top; daylight held; the player swimming in the middle with a stone sword and the portal stage's kit, full health and breath; a drowned in the pool after it. Passes once it stands on dry ground out of the water, alive, within 200 ticks | On `ef60cf6`: fails 2 of 3, still in the water after 200 ticks. The third got out after 198 ticks with 5 health. In each run its walk out was stopped for the drowned 55 to 61 times. With the fix: passes 3 of 3, out after 115 to 124 ticks with 19 or more health, its walk out stopped once (on the shore, to fight). |
| The rest | `bash ./gradlew build`; the goal's fixtures with water and the fights' | See [validation](../validation.md) |

A first form of the fixture checked for the shore before the player had come into the water, and passed at once; one
run with a 600-tick limit passed after 316 ticks with 58 stops. The runs above are on the fixture as committed.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- A drowned with a trident still hits it from the water as it climbs out; the walk out does not take cover.
- In shallow water a mob attacking stops the walk out as before, and the fight comes first there.
