# F-25c4co — A finished frame's opening is cleared before the portal is lit

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-21-1108 (E-40's ladder, batch 21)
Build: on top of `f239a1d` (batch 20); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player about to light its finished frame first digs out anything lying in the opening, then
lights it. Before, a block in the opening made every try fail ("Portal opening position is obstructed") and nothing ever
cleared it.

Found: E-40's iteration 15 (`e40a15-dbf4e5c`), Kindred on 31102026:
- It finished its frame at 4, -54..-50, 114..117, dug gravel beside it for flint, and made its flint and steel.
- Two gravel lay in the opening's bottom row (4, -53, 116 and 117, read from the run's kept world).
- Each lighting was refused three times as obstructed. The watchdog moved it on, and it walked back and tried again:
  "Back to its portal frame" 604 times, 7 of the seed's stuck spells.
- No portal was lit on the seed, one of the three without one; rung b asks for 8 of 10.

Changed modules/files:
- `companion-server-adapter`: `PortalRunCampaign.ignite`. In reach of the opening, any cell of it that is neither air,
  fire, portal nor liquid is cleared first through `clearOrApproach` (the stand, then the row in front, then the site
  given up), labelled "Light" so a failed walk does not give up the finished frame as a "Portal" walk would.
- gametest: `NativeObstructedPortalGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| A finished frame with two gravel in its opening's bottom row is cleared and lit within 1,200 ticks | `NativeObstructedPortalGameTests.gravelInAFinishedFramesOpeningIsClearedAndThePortalLit`, staged as below | On `40d9e55` (fixture, no fix): fails 3 of 3, "No portal lit after 1,200 ticks", alternating "Back to its portal frame: dig the way" and "no way on from here - Climb to find another way" to the end, as Kindred did. On `7e9e03c` (the fix): passes 3 of 3, lit after 26 ticks each. |
| The rest | CI's build and full native suite on the pull request | See [validation](../validation.md) |

Staging: a block of stone; the frame along north and south, opening east, its ten cells obsidian and its corners
cobblestone, as if cast; gravel in the opening's two bottom cells; the row in front open five high and lit; the stand at
the end of a two-high tunnel; the frame taken as the player's own. The player on its stand with the cast route's kit and
its flint and steel made; daylight held.

Runs: the fixture's 3 + 3 were run alone in the CI-green session's container (this one's cores held the F-29e
comparison), its gametest entrypoints narrowed to the class and restored after.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- A liquid in the opening is not cleared; water left from the cast drains on its own, and lava there is not handled.
- Why gravel came to lie in the opening (a column dug for flint beside the frame) is not changed.
