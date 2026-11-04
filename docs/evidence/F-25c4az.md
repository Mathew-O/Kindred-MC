# F-25c4az — Walks that keep ending in deep water: the place set aside, its sighting let go

Date: 2026-11-04
Agent / branch: Claude / claude/e40-ladder-1104 (E-40's ladder, rung a)
Build: on top of `6ddb45d`; recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player whose walks toward one place keep taking it into deep water, where it turns up for air
or ashore, sets that place aside after the third time, and a sighting of ore there (an atlas lead) is let go. It goes
on looking for what it wants another way. Before, it walked out and swam back for as long as the run lasted.

Found: the most common stuck pattern on the ten seeds of main (`f25c4ax-84b331c`, E-40's starting build), in the
recorded stuck spells after branch mining.
- **505:** Echo and Kindred each walked back to iron ore seen across deep water. Each walk "completed" in the water, a
  partial route, so it counted as neither an approach nor a failure. Its swim to dry ground took it back to the near
  bank, and the plan sent it out again: Echo for 24 minutes, with 523 such walks for Kindred.
- Outside the End, any goal walk into deep water is turned up for air or ashore at once, wherever it is bound. So
  counting those turns against the walk's place costs no crossing.

Changed modules/files:

- `companion-server-adapter`:
  - `PortalRunCampaign`: `next` remembers the task it gave out (`lastGiven`). When the plan turns to getting out of the
    water straight after a goal walk, `wetDip` counts it against the place the walk names ("… at x, y, z"), or else its
    target. On the third, the place is set aside (`avoid`, as an out-of-reach target is).
  - `PortalRunCampaign.lead`: a set-aside or unreachable sighting is let go (`atlas.gone`), so the next lead is looked
    to.
- gametest: `NativeAtlasGameTests.ironItSawAcrossDeepWaterItCannotClimbOutOfIsLetGoAfterAWalkOrTwo` (new).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Iron seen across deep water it cannot climb out of is let go after a walk or two | the fixture: a causeway; a channel twelve deep cut across it, its far side a sheer wall three blocks over the causeway; iron ore seen past it (staged with a diamond-stage kit, no use for iron); the player moved 66 blocks off (staged), then a kit with no iron | On main's code: fails 3 of 3, back toward the ore 120, 121 and 122 times ("Walk to iron ore" or "Back to where it saw iron ore", then "Get out of the water: to dry ground"). With the fix: passes 3 of 3, 3 walks back, then "Explore the cave toward iron" |
| The water fixtures | `NativePortalCampaignGameTests` (the flooded pit, under a lake, the tree in water, the drownings), `ReplayGameTests`, `NativeSurvivalGameTests`, `NativeBoatGameTests`, `NativeFloodedShaftGameTests` together, with the fix | 110 of 110 |
| The atlas fixtures | `NativeAtlasGameTests` | 4 of 4 in each of three runs |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- The place is set aside for a while (two minutes, longer each time), not for good; a sighting let go comes back if
  seen again.
- A swim across to reach the ore is not attempted; the player looks for iron elsewhere.
