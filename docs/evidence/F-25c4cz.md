# F-25c4cz — A pocket of lava under the rock is followed once the lava it saw is taken

Date: 2026-11-09
Agent / branch: Claude / claude/lava-pocket-1109 (E-40 ladder, batch 26), on main (`66788a8`)
Build: recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is the next evaluation's to show.

## Scope and implementation

User-visible behaviour: looking for lava for its cast, a player whose tunnel found a pocket of lava under the rock takes
the pocket's lava a source at a time, opening the rock over each, instead of taking the one or two it can see and branch
mining on, away from it.

Found: E-40's iteration 17 (`e40a17-7bc5b42`), seed 707, the one cast-route seed with no portal.
- Kindred branch-mined for lava at Y=-54 for 54% of the run (827 of 1,539 s). At 09:02 its tunnel opened one cell over
  a pocket (F-25c4ac) and filled its bucket twice. It cast two frame blocks, then went back to legs 48 long, leading away.
- The kept world, read offline for diagnosis only: that pocket held 208 sources, 27 of them at its top (Y=-55), most of
  them under solid rock beside the tunnel. A lake of 1,591 sources lay 100 blocks on; its tunnel opened a cell over it at
  tick ~131,000 of 144,000, too late.
- Of the 707 world's deep lava, pockets with their top at Y=-55, one block under the branch tunnels' floor, hold 95% of
  the sources. The depth is right; the search let go of what it found.

Cause: once the source under the cell it opened was taken, nothing told the search where the rest of the pocket lay. The
lava flowing into the emptied cell comes from the still sources beside it, under the rock; nothing was made of that.

Changed file: `companion-server-adapter` `PortalRunCampaign`.
- `lavaHoles`: each source its cast's bucket was filled from, kept (the last 24).
- `followPocket`, first in `lavaForCast` when it knows of no lava:
  - The cells over still sources beside those holes, within 16 blocks, with lava directly under them and no other liquid
    round them (F-25c4ac's `onlyLavaBelow`), nearest first.
  - One in reach and in view is opened from where it stands, as the tunnel opens the cell ahead over lava.
  - One out of reach or of view is dug toward, as lava seen that no walk leads to is (`digToward`, F-25c4ad): it stands a
    block above the lava and its tunnel opens the cell when it comes to it.
  - A cell it could not open, or could not dig toward, is set aside for a minute.
- Opened, the lava is remembered as seen (F-25c4ac's existing step), filled, and is the next hole.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| The pocket is followed past what it can see from the first cell | `NativePortalCampaignGameTests.aPocketOfLavaUnderTheRockIsFollowedOnceTheLavaItSawIsTaken`, staged as below, 3 runs each | On main without the fix: fails 3 of 3, taking 1, 1 and 3 of the 9 sources, then branch mining away. With the fix: passes 3 of 3, 6 or more taken |
| The classes that cast and make obsidian | `NativePortalCampaignGameTests`, `NativeCastRefusalGameTests`, `NativeObsidianPickupGameTests`, `NativeFrameReturnGameTests`, `NativeHalfCastFrameGameTests`, `NativeObstructedPortalGameTests`, `NativeTeamPortalGameTests`, `NativeBucketGameTests`, `NativeRescueRecipesGameTests`, 3 runs | 107 of 107 each |

The fixture's staging: stone walled in obsidian. The player stands in a short room at its south end, facing north, the
way its tunnel goes. The pocket is a line of nine lava sources one block under the tunnel's floor. It runs west from the
one source under the tunnel's path, so the tunnel opens one cell over the pocket and nothing past the second source is in
view from it. There is glowstone in the rock. The kit is an iron pickaxe, a stone sword, an iron chestplate, bread, 64
cobblestone, sticks, flint and steel, a water bucket, an empty bucket and a crafting table, given as though made. The
check counts each source once it is seen gone, whatever flows in after.

Forms of the fixture not kept:
- A pocket four by four beside the tunnel failed only 2 of 3 without the fix. A neighbour seen through a freshly emptied
  cell let the old search chain on by chance.
- Counting the sources left at the end undercounted, because a cell refilled by flowing lava was counted again.

Forms of the fix not kept:
- Walking back to the solid cell itself: the walk ended at once, short of it.
- Walking to a stand beside the opened cell: the only stand beside the second hole's opening was the first hole's
  opening, which is over lava.
- Opening the rock over each opened cell to see across it: it worked in some geometries but not others.
- In all three cases, 1 or 2 of 3 passed. The dig-toward form reuses F-25c4ad's tested path.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- Only sources beside a hole it filled from are followed. A pocket's lava seen nowhere near one is still found only by
  the tunnel, a cave or a sighting.
- 707's later lake, found at tick ~131,000, is a matter of finding lava sooner. That is not this change.
- The kept world was read offline for diagnosis only. Nothing in the change reads unseen blocks beyond the single block
  under a cell beside lava it has seen flow, which F-25c4ac's opening already reads.
