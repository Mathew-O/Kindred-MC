# F-25c4by — Its flint and steel made where no walk leads back to its frame, it digs its way back and lights it

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-15-1107 (E-40's ladder)
Build: on top of `42d682c` (batches 13 and 14); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player whose frame stands and who has made its flint and steel somewhere it cannot walk back
from digs its way back to the frame, a staircase to the frame's level and a tunnel along it, and lights its portal.
Before, it asked for the walk back again and again, was refused each time ("No walkable route"), and the fallbacks
climbed it away; the frame stood unlit.

On the cast route the frame stands before the flint: the player casts it, then goes to dig gravel for flint. Its way
back to light the frame was a walk to the frame's stand, and only a walk. Now, once a walk there has been refused (the
stand set aside) or found to have no route from about here, it digs toward the stand as it already digs toward lava it
cannot walk to (`digToward`), and walks the last of the way if a walk opens up.

Found: E-40's iteration 11 (`e40a11-744c636`), Kindred on 404. It cast its frame at -23, -54, -45, tunnelled 23 blocks
east at the frame's level to gravel, set gravel down and dug it until it dropped flint; the gravel it set down lifted it
three blocks above its tunnel, and it made its flint and steel there. The walk back was refused 189 times, the fallbacks
climbed it about for the rest of the run, and the frame was never lit. 404 was one of the three seeds without a lit
portal.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.ignite` (out of reach of its frame's opening, with the walk to the
  stand refused or unreachable from here: `digToward` the stand, then the walk as before); `portalSiteForTest`, a
  fixture's hook that takes a frame standing at a site as the player's own.
- gametest: `NativeFrameReturnGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Its frame deep in stone, its flint and steel in hand in a pocket three blocks above the tunnel it came along, no walk back: it digs back and lights the frame | `NativeFrameReturnGameTests.itsFlintAndSteelMadeWhereNoWalkLeadsBackItDigsBackToItsFrameAndLightsIt`. Staged: a block of stone with a lit room in it, a complete unlit obsidian frame in the room taken as the player's own (as if it had cast it), a tunnel two high leading twelve blocks east from the room, the player walled into a lit pocket three blocks above the tunnel's far end with the cast route's kit and its flint and steel; daylight held | On `42d682c` with the fixture's hook only: fails 3 of 3, the walk back refused, then "Climb to find another way" up through the stone and "waiting for a feasible step" until the test's time ran out. With the fix: passes 3 of 3, the portal lit after 138 to 140 ticks from a stand at the tunnel's mouth. |
| The rest | `bash ./gradlew build`; the full native suite | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says: the frame and
the player's memory of it as its own are set by the fixture, not cast by the player.

## Remaining limitations and handoff

- The dig back is along the frame's level from the stand's side; a frame across a cave or a lava lake from the player
  is dug toward as lava is, which bridges a drop on the way at the frame's level and goes no further where neither way
  closing on it is open.
