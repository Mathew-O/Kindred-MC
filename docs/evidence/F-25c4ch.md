# F-25c4ch — A staircase step it will not take is not dug, so its tunnel's fill is not dug out again and again

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `fe2ba3b` (batch 16 with iteration 12 filed); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation; iteration 13 (`e40a13-fe2ba3b`)
runs on the build before it.

## Scope and implementation

User-visible behaviour: digging a staircase down, a player does not dig a step it would not then take. That is a step
whose landing it has set aside, or one by water or lava. It turns to another way down, or tunnels on past. Before, it dug
the step, then would not step down onto the landing. Its tunnel filled the open step as a pit over a drop, and the
staircase dug it out again, hundreds of times in a few minutes, until it waited "for a feasible step".

The staircase checks each way down for water or lava under the step, a drop too deep and danger below before digging
anything. It checked the landing (set aside, or by liquid) only after the step was open. A landing is set aside for two
minutes or more when a walk there did not go, for instance a step down cancelled for a mob. Now the landing is checked
with the rest, before the step is dug.

Found: E-40's iteration 12 (`e40a12-66a7c0a`), Kindred on 505 at 16, 46, -45. It had "Dig down toward iron: clear 16, 45,
-44" and "Tunnel past liquid toward iron: fill 16, 45, -44" by turns hundreds of times, then 29 stuck spells waiting "for
a feasible step". That was the evaluation's largest stuck cause: 29 of its 92 spells.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.digDown` (the landing checked, set aside or by liquid, before the step
  is dug).
- gametest: `NativeDigFillGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Digging down toward lava for its cast, its one way down onto ground it has set aside: the step is not dug out again and again | `NativeDigFillGameTests.aStepItWillNotTakeIsNotDugSoItsTunnelsFillStays`. Staged: a block of stone above lava's depth, the player in a cell two high; under each other way down a pocket of water; the way down south onto a pocket of air, set aside by a step down there that did not go, given to the goal as the runtime gives it; daylight held; the cast route's kit. Passes if in 1,500 ticks the step south is dug out at most twice | On `fe2ba3b`: fails 3 of 3, the step dug out 110 or 111 times in the first 150 or so ticks, filled again each time (110 to 111 fills). With the fix: passes 3 of 3, the step dug out 0 times, no fill; it tunnels on and digs down another way to 7 below where it stood. |
| The rest | `bash ./gradlew build`; the fixtures that dig down | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Why the landing on 505 was set aside is not read from the run; the fixture sets it aside as a step down that did not go
  sets one aside.
- The tunnel still fills a pit it dug itself over a drop; that is what it is for.
