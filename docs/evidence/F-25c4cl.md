# F-25c4cl — The walk to a drop waits for the gravel coming down on its way

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-18-1108 (E-40's ladder, batch 18)
Build: on top of `6fd7dad` (batch 17), with F-25c4ck; recorded in [validation](../validation.md)
Status: complete (native).

## Scope and implementation

User-visible behaviour: a player going to pick up a drop a few blocks off, with sand or gravel coming down into a cell on
the way to it or into its own cell, waits a moment for it to come to rest, as its tunnel does before it steps into a cell
it has just opened (F-25c4bv). Before, it walked in, and the next block came down onto it.

Found: the gravel tunnel fixture (`NativeGravelTunnelGameTests.tunnellingUnderGravelItWaitsForWhatFallsBeforeItStepsIn`),
failing now and then in the native classes and CI with "Buried in its tunnel": in batch 16's classes (130 of 131), and in
batch 18's (117 of 118, with F-25c4ck). Each time the gravel it broke had dropped flint, which gravel does one time in ten,
and the walk to the flint went into the cell it had just cleared, where the gravel over it was still coming down; once
there, the drop's own "wait for the drop to settle" held it in the cell.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.pickUpLoose` (`comingDownOnTheWay`: the cells on the straight way to the
  drop at its own height and the drop's, and the drop's cell, by F-25c4bv's `comingDown`).
- gametest: `NativeGravelTunnelGameTests.tunnellingUnderGravelThatDropsFlintItWaitsForWhatFallsOnTheWayToTheDrop` (new;
  the tunnel fixture's body made a helper shared by both, the first as it was).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Tunnelling under gravel that drops flint, it is never buried | `NativeGravelTunnelGameTests.tunnellingUnderGravelThatDropsFlintItWaitsForWhatFallsOnTheWayToTheDrop`. Staged: the gravel tunnel fixture's scene, with a stone shovel of Fortune III among its tools so that each block of gravel it digs drops flint | Without the fix (with F-25c4ck): fails 3 of 3, "Buried in its tunnel"; in each, "Pick up flint" walked into the cell the tunnel had just cleared while the gravel over it came down. With the fix: passes 3 of 3, never in a wall; over the three runs it waited "Pick up flint: let what falls settle" 66 times and picked the flint up 7 times. |
| The tunnel fixture as it was | `NativeGravelTunnelGameTests.tunnellingUnderGravelItWaitsForWhatFallsBeforeItStepsIn` | Passes, with the batch's classes: 119 of 119 |
| The rest | the classes that dig out of walls, tunnel and climb; `bash ./gradlew build` | See [validation](../validation.md) |

The cell it stands in is left out of the way checked: what comes down there is the wall it digs out of (F-25c4ck), and
waiting under it would not help. With that, the flint variant passed 3 of 3 again, and with the tunnel, falling column and
portal campaign classes 101 of 101.

Three scenes staged first, a flint set down a cell or two along a tunnel with gravel let fall over its cell or the one
between, did not reproduce it: 9 of 9 passed without the fix, the flint taken in passing before the gravel came down, or
the walk made before it fell. The shovel of Fortune III gives the drops where the tunnel's own digging leaves them.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- The way to a drop is taken as the straight line to it; a walk round a corner to one is checked only along that line.
