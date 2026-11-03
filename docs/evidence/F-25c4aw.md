# F-25c4aw — A walk out to look for logs ends once a log is in view

Date: 2026-11-03
Agent / branch: Claude / claude/fixtures-1103
Build: `373bb90`; recorded in [validation](../validation.md)
Status: native fix complete (fails before, passes after); open until an evaluation or live run.

## Scope and implementation

User-visible behavior: a player with no wood that sets out to look for trees stops as soon as it knows of a log it would
go and chop, and goes for it, instead of walking on to the end of its 28-block walk out. A log it would not go for (out
of its reach, given up, with no ground to stand on under the trunk) does not stop the walk.

Found: the tree-in-water fixture (F-25c4as) failed in every full suite since the merge of 2 November and passed alone.
- **The full suite's trace:**
  1. The first step was "Explore west for logs", before the player's look about had reached the stump.
  2. The next plan was "Walk to logs at" the stump, but the walk out was already under way and ran its full length, off
     the fixture's ground and two blocks down.
  3. From there the stump had "No walkable route", and the player explored ever farther (156 blocks east by the end).
- **Why alone differs:** the look about reads a few chunk sections a tick. In a test world full of other fixtures' logs
  and ore it takes longer to come round to the stump than in a world with only this fixture in it.

Changed modules/files:

- `companion-server-adapter`:
  - `PortalRunCampaign`: `gatherable`, the gathering rule shared by `gather` and the new check.
  - `PortalRunCampaign`: `stopFor(NativePlayerBody, Task)`, which stops an "Explore … for logs" walk once a gatherable
    log is known.
  - `PlayerRuntime`: the stop check given the body.
- gametest: `NativePortalCampaignGameTests.aTreeThatComesIntoViewOnItsWayOutToLookEndsTheWalk` (new).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| A tree that comes into view stops the walk out | the fixture: a 60-block field; once the player has set out, three logs placed five blocks behind it; it may go no more than 3 blocks further out along its heading after it first knows of the tree | On main's code: fails 3 of 3, "25 blocks from where it set off, 0 (twice) or 21 when it first knew of the tree". With the fix: passes 5 of 5 alone (the log: known at 19 or 20 blocks out, 20 at the farthest; or at 0 and 0) |
| The tree-in-water fixture | alone, with the fix; the full suite on `373bb90` | 5 of 5 alone; passed in the full suite (522 of 525, its failures elsewhere) |
| The rest of the goal's fixtures | `NativePortalCampaignGameTests` alone with the fix | 95 of 97. One failure was the new fixture's first measure, distance in any direction, which counted going back for the tree as going out; it now measures along the heading. The other, `badlyHurtAndCorneredByAZombie…`, passed 8 of 8 alone before and after the fix |

Environment: Linux x64, OpenJDK 21, the manifest's versions. Staging, said in the fixture: the field, the tree once the
player has set out, daylight, bread.

## Remaining limitations and handoff

The rule covers walks out for logs only. Walks out for animals, stone or lava keep their full length. Next: the
evaluation of the next build, to see it in the open.
