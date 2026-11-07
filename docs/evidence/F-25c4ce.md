# F-25c4ce — Stranded by walks toward places out of reach, with no pickaxe, it still walks back the way it came

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-15-1107 (E-40's ladder; a CI failure on PR #35)
Build: on top of `f2d93b7` (batch 15); recorded in [validation](../validation.md)
Status: complete (native).

## Scope and implementation

User-visible behaviour: a player without a pickaxe, below ground, walks back up the way it came before it punches a
stair up by hand, even when its last few walks from there were refused. Before, three refused walks from about one
place left it "stranded", and stranded with no pickaxe it left the way it came out and dug its stair by hand, though
that way was walkable ground it had just stood on.

Three refused walks from one place are taken as a sign that the player is shut in where it stands. They can as well be
walks toward places it cannot reach from anywhere near (the top of a wall, a ledge over a drop), with its own way back
open. The walk back was left out while stranded when a refused walk back was asked for again and again. Since F-25c4at
a refused walk back is not asked for again from there for half a minute, so the stair by hand still comes next for a
player that is shut in.

Found: PR #35's pull-request build on CI (`f2d93b7`, attempt 2 of run 42414346740),
`NativePortalCampaignGameTests.goalWalksBackUpTheWayItCameWhenItsPickaxeBreaksBelow`: "Nothing dug on the way back up:
440 of 448 stone left". From the job's log: the iron went out of its view on the first step down the staircase, and the
goal went back to where it saw it, then explored toward it. Deep below sea level the top of the fixture's wall reads as a
cave floor (open ravine floors count there; c539f15). Three walks to it were refused, and stranded by them, with its
pickaxe gone on the next block it mined, it dug a stair up by hand beside the staircase. In attempt 1 of the same run,
and in every earlier run of the fixture, the iron stayed in view, the pickaxe broke on it, and it walked back.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.wayUpBy` (with no pickaxe: a pillar if boxed in, then the way it
  came, then the stair by hand, stranded or not).
- gametest: `NativeStrandedReturnGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| The staircase down into rock, three of its walks refused as it starts on the ore at the foot, its pickaxe one block from worn out: it walks back up the way it came, breaking nothing | `NativeStrandedReturnGameTests.goalWalksBackUpTheWayItCameWhenStrandedByWalksElsewhere`. Staged: the scene of the fixture above; three refused walks toward the wall's top, given to the goal as the runtime gives them, as it starts on the ore | On `f2d93b7`: fails 3 of 3, eight blocks cleared by hand beside the staircase ("489 of 497 stone left") before it walked back the way it came, once it stood more than three blocks from where it was stranded. With the fix: passes 3 of 3, back the way it came at once after picking up the raw iron, no block cleared by hand. |
| The rest | `bash ./gradlew build`; the affected native fixtures | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Why the iron went out of view on the first step in that run is not established; the fixture's neighbours in that batch
  were working and fighting close by. The goal now copes with what followed.
- The top of a wall deep below sea level still reads as a cave floor, as an open ravine's floor does.
