# F-25c4cd — Dug in for the night, a step that would walk it out under the sky waits for daylight

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `a86796a` (batch 16, F-25c4cb and F-25c4cc); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player that has dug in for the night stays in its shelter until morning when its next step of
work would walk it back out under the sky. It waits for daylight, its way in sealed, and does any work it can do where
it is meanwhile (crafting, say). The watchdog's move elsewhere also waits for the morning. Before, the work step walked
it out (to ore it had seen on the surface, into a cave open to the sky), the night's dig-in came first again once it was
out, and it went up and down by turns all night.

The night's dig-in is ordered before the route's steps, and it only applies while the player is not sheltered. Once it
is below, the route's next step was taken as by day, and a walk up to the surface undid the shelter. F-25c4bx made the
fallback climb wait for daylight when no step could be taken; this covers a step that can be taken and leads out.

Now, at night in the shelter it dug tonight, a walk the goal gives out whose end is under the open sky, or on the
ground's top, is replaced with sealing in and waiting for daylight. This holds for its work steps only: a walk up for air,
a back-off, a run from a creeper and the other safety steps go as before. While it is in that shelter at night the
watchdog's clock does not run and a move elsewhere under way ends.

Found: E-40's iteration 11 (`e40a11-744c636`). Once that iteration's other causes were fixed, the night's dig-in was the
largest stuck cause left, 56 of the watchdog's lines:
- Kindred on 606, dug in, was sent out by its cast's next steps (into a cave open to the sky toward lava, to coal in
  view) and dug in again.
- Echo on 707, dug in and waiting for the team's portal, was stepped out by the watchdog's move elsewhere and stepped
  back in by the dig-in, step for step.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.nextTask` (the night's work walks out under the sky wait in the
  shelter; the watchdog holds while it is in it), `outUnderTheSky`.
- gametest: `NativeNightWalkGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Night on open stone, iron ore seen on the surface ten blocks off: dug in, it stays below until morning, then goes for the ore | `NativeNightWalkGameTests.dugInForTheNightAWalkOutUnderTheSkyWaitsForDaylight`. Staged: a block of stone open to the sky, the night held for 1,500 ticks, three iron ore in its top ten blocks off; the player with stone tools, cobblestone, torches, bread, a table, a furnace and logs, on the Nether portal goal | On `a86796a`: fails 3 of 3, out from its shelter under the sky and dug in again 14 to 17 times, "Explore the cave toward iron" taking it up each time. With the fix: passes 3 of 3, dug in four down, its spare sticks made in the shelter, daylight waited for; in the morning on to the iron. |
| The rest | `bash ./gradlew build`; the night and campaign fixtures; the full native suite | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Deeper than a night shelter at night, its work goes on below as before, walks included.
- The team's wait for its portal is still not counted as passive by day; the watchdog moves a waiting player in daylight.
