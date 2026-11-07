# F-25c4cb — Attacked in deep water with no way out of it, breath to spare, it fights back there

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `f2d93b7` (batch 15); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player swimming where it has no way out of the water to take, with a mob after it, fights back
from the water while two thirds or more of its breath is left. Before, each strike was stopped "up for air first" as soon
as it began and given out again at once, and every other step was stopped for the mob attacking it, so it neither fought
nor did anything else.

In the water the goal gives a player its way out first: up for air, to dry ground, or a step dug out of the side. Only
when there is none does a fight come next. The runtime's check stopped any task in deep water that would not take the
player out of it, except a walk, a climb out, a gather or a bucket, even with full breath; a fight was stopped every time.
Now a fight goes on in the water too while breath lasts; short of breath it is stopped for air as before.

Found: E-40's iteration 11 (`e40a11-744c636`), Sol on 404. In an aquifer's pool at Y=-50 by the lava it wanted for its cast,
with a creeper after it, it was given "Fight creeper" 2,101 times and had 2,097 of them stopped "In deep water; up for air
first"; between them its dig toward the lava was stopped "A hostile mob is attacking; fight back first". It stayed there
through eight stuck spells, the largest share of the watchdog's lines in the evaluation.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.stopFor` (a `native_fight` is not stopped in deep water while breath is
  two thirds or more).
- gametest: `NativeWaterFightGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Swimming in a pool it cannot leave, a drowned after it: it fights back from the water | `NativeWaterFightGameTests.attackedInAPoolItCannotLeaveItFightsBackFromTheWater`. Staged: a pool five square and three deep, walled with obsidian two blocks over the water, lit, daylight held; the player swimming in it with a stone sword, an iron chestplate and the portal stage's kit; a drowned in the pool after it | On `f2d93b7`: fails 3 of 3, its strikes stopped 20 or 21 times "up for air first" while the drowned took it from 20 health to 4 or 6 and killed it (it respawned in the Wood stage). With the fix: passes 3 of 3, the drowned killed after 181 to 194 ticks, the player at 13 health, no strike stopped. |
| The rest | `bash ./gradlew build`; the full native suite | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Short of breath a fight in the water is still stopped for air, and with no way up the goal gives it out again; a player
  under water with no air above it and a mob on it is not helped by this.
- On 404 Sol had no way out of its pool at all; that it had none (the climb out refused beside the lava it wanted) is not
  changed here.
