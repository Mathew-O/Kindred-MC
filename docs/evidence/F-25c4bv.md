# F-25c4bv — A tunnel never steps in under sand or gravel still coming down

Date: 2026-11-06
Agent / branch: Claude / claude/e40-ladder-13-1106 (E-40's ladder)
Build: on top of `d585600` (batch 12 with main merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player tunnelling into gravel or sand that rests over the cells it opens waits a moment for
what falls to come to rest, and digs that out too, before it steps in. It is no longer buried in its own tunnel.

A tunnel clears the cell ahead at head height, then at its feet, and steps in once both are open. Where gravel rested on
the head-level cell, clearing that cell let the gravel over it fall. For the few ticks it was in the air the cell looked
open, so the tunnel cleared the feet cell, stepped in, and the gravel landed on its head. Digging out of the wall let the
next block of the column down onto it, and so on to the top of the column, at two health a second.

Now, before it steps into the cell ahead (or down into the cell below it), the tunnel checks for sand or gravel coming
down into that cell or onto the head there: a falling block over it, or sand or gravel resting over an open head-level
cell, which falls within two ticks. If there is, it waits a second for it to settle. What lands is then dug out in turn
until the column is gone.

Found: E-40's iteration 9 (`e40a9-dd9b037`). Four of its 48 deaths were suffocations, all this way:

| Seed | Player | Where | Health in its death replay | Food held | Doing |
| --- | --- | --- | --- | --- | --- |
| 20261030 | Kindred | 201, -54, 101 | 1.4 | none | Branch mine for lava, through a pocket of gravel in the deepslate |
| 606 | Sol | 291, -54, -195 | 1.8 | none | Dig toward elsewhere (the stuck watchdog's tunnel) |
| 8675309 | Sol | -151, -54, 206 | 8.4 | none | Branch mine for lava |
| 8675309 | Echo | -183, 19, 153 | 3.0 | none | Branch mine for iron |

Iteration 8 (`e40a8-aed3e82`) had a fifth, Kindred on 20261030 at 11, 19, -65, branch mining for iron, starving.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign`: `comingDown`, `settle`; the tunnel's advance and step down wait
  while something is coming down into the cell.
- gametest: `NativeGravelTunnelGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| A branch mine for iron through stone with gravel over the head-level cells two to four blocks out, all round: never in a wall, through the gravel, alive | `NativeGravelTunnelGameTests.tunnellingUnderGravelItWaitsForWhatFallsBeforeItStepsIn`. Staged: the stone, a pocket in it, the gravel from head height up five blocks, the morning held, the branch mine fixture's kit | On `d585600`: fails 3 of 3, each time buried two blocks in, at full health, where the gravel came down onto its head. With the fix: passes 3 of 3, through the gravel, each column dug out with a wait while it fell (`let what falls settle`) and never in a wall. |
| The deaths' own replays: the four of iteration 9 and Kindred's on 20261030 in iteration 8 | `ReplayGameTests`, the five staged in turn (not kept in the suite). A replay starts where the player died, with its health ten seconds before: here inside the wall, gravel on its head | The same on `d585600` and with the fix. Kindred on 20261030 (iteration 8, 1.0 health), Sol on 606 (1.8) and Echo on 8675309 (3.0) die again in 3 of 3, buried before the replay begins; Kindred on 20261030 (iteration 9, 1.4) and Sol on 8675309 (8.4) live in 3 of 3, the last block over them dug out. None dies before the fix and lives after it, so none is kept (F-27's rule); the staged fixture is the check. |
| The rest | `bash ./gradlew build` | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Buried all the same (by gravel it did not dig under, say), it still digs out of the wall where stepping back out of it
  would be quicker.
- All four of iteration 9's players were mining at low health with no food. Out of food at night underground, it works
  on rather than climbs (the climb for food waits for daylight): that is open under E-40's deaths.
