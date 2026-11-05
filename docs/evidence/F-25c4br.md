# F-25c4br — A tree it came to and could take nothing from is not walked back to from afar

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-11-1105 (E-40's ladder)
Build: on top of `c9de40f` (batch 10, merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player with no wood that walks to a tree it saw far off, and finds there no log it can take,
lets that tree be and looks elsewhere, rather than walk back to it later from another tree.

The look for trees on the skyline (F-25c4ap) sees past the player's 32-block sweep only, and a tree it has not got to
in a minute is let be. At a tree whose logs it could not reach, it saw another tree far off and set out for it; there
it saw the first again. Each switch began the minute again, so neither was ever let be.

Now, when it looks again within 32 blocks of the tree it walked toward, with still no log there that it can take (the
gather of a known log comes before the look), that tree is let be as one not got to in a minute is.

Found: E-40's iteration 8 (`e40a8-aed3e82`), seed 101, Kindred, with no wood. Two oaks 60 blocks apart had had their
lower trunks cut; what was left of each was a few logs in the crown, the lowest six blocks over the ground (from the
run's kept world). Kindred walked from one to the other and back, nine stuck spells of "Walk toward the trees seen".

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.treesInView`: at a look, the tree walked toward is given up if the
  player is within its sweep's 32 blocks of it.
- gametest: `NativeTreeSightGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Two crowns 54 blocks apart, each with two logs six and seven blocks up: in 1,800 ticks it walks toward the far one and never starts a walk toward one it has walked to and come within 32 blocks of | `NativeTreeSightGameTests.withNoWoodItDoesNotWalkBackToATreeItCameToWhoseLogsItCouldNotReach`. Staged: open ground, the crowns with persistent leaves, the player beside the first; daylight; bread | On `c9de40f`: fails 3 of 3, each time walking back toward the far oak after coming to it, at ticks 501, 519 and 501. With the fix: passes 3 of 3. |
| F-25c4ap's two fixtures: a tree seen far off walked to and cut; one behind a bank walked round to its foot | The two fixtures from `NativePortalCampaignGameTests`, run alone in a class of their own for this bench | Both pass with the fix (one run, the two alone) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- The logs left high in a crown are not reached by pillaring up to them.
- A tree given up is not walked toward again while its goal runs.
