# F-25c4ck — Buried under a column of gravel, it steps out from under the column

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-18-1108 (E-40's ladder, batch 18)
Build: on top of `6fd7dad` (batch 17); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player whose head room fills with gravel or sand, with more of it over its head, steps out from
under the column into a cell beside it with room to stand, and digs on from there. Before, it broke the block in its head
room; the next block of the column came down into it, and so on, while it suffocated. When the column only reaches into
its eyes from the cell beside its own, it steps back to the middle of its own cell. A single block that fell, with nothing
over it, is still broken where it is, as before.

Found: E-40's iteration 14 (`e40a14-80b7150`). Three of its first fifteen deaths were "suffocated in a wall": on 8675309
Kindred at 80, 60, 60 (tick 48,005) and at 81, 21, 161 (tick 69,866), and on 31102026 Echo at 163, 65, 242 (tick
120,452). Each had walked or stood where a column of gravel or sand came down, two of them hurt and out of food; each
time the goal's step was "Dig out of the wall", and it died within a second or two, at the first or second block. None
were recorded in iterations 10 to 13; iterations 6 and 9 had four each.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.digOut` (`fallingColumn`, `clearOfTheColumn`, `roomToStand`).
- gametest: `NativeFallingColumnGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Hurt, under a column of gravel, it gets out from under it alive | `NativeFallingColumnGameTests.buriedUnderAColumnOfGravelItStepsOutFromUnderIt`. Staged: a block of stone with a one-wide tunnel; the player in it with gravel in both its cells and seven blocks more over its head; six health, food twelve (no healing), a stone pickaxe and food; daylight held. Passes once the same body has been out of the wall and alive for forty ticks, within 600 | On `14cdf8a` (batch 17 does not touch it), without the fix: fails 3 of 3, "It died under the column": it dug at the gravel in its head room, ate between digs, and suffocated within seconds, the next block of the column down into its head room after each dig. With the fix: passes 3 of 3, "out from under the falling gravel" to the cell east of it, out of the wall about 13 ticks after the column came down, its health still six. |
| The rest | the classes that dig out of walls, tunnel and climb (`NativeFallingColumnGameTests`, `NativeGravelTunnelGameTests`, `NativePortalCampaignGameTests`, `NativeTripDownGameTests`, `NativeBranchMineGameTests`, `NativeBucketGameTests`, `NativeFrameReturnGameTests`, `NativePortalLightGameTests`, `ReplayGameTests`); `bash ./gradlew build` | 117 of 118 with this fix alone: the gravel tunnel's flint pickup, open from before and fixed as F-25c4cl; with both, 119 of 119. The build: see [validation](../validation.md) |

The fixture's first form looked at whichever body the player had: it died under the column, respawned, and the new body,
alive and out of any wall, passed it. Each of its before runs had died (eight health and a column of nine, one of one;
six health, three of three; four health and a column of fourteen, three of three). It now holds the body it buried and
fails on its death.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- With no cell beside it with room to stand (a one-wide shaft straight down), it digs the column out as before.
- How each of the three came to stand under a falling column (a walk into a cell under gravel its own digging had
  loosened, an opened ceiling beside it) is not changed.
