# F-25c4be — On an island in a lava lake under the hole it came down through, it pillars back up into it

Date: 2026-11-04
Agent / branch: Claude / claude/e40-ladder-5-1104 (E-40's ladder, rung a)
Build: on top of `9df95ac` (main with F-25c4bc and F-25c4bd); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player come down through a cave's ceiling onto ground it cannot climb or walk off, with a hole
one wide over it or over a cell beside it, pillars back up into the hole on blocks of its own, as a player does. Before,
with nothing beside it to climb onto, it walked "to the wall". Across a lava lake every such walk was refused, and it
asked for the same walk again and again.

Found: the second most common stuck place on the ten seeds of `05b58fd` (`e40a3-05b58fd`, E-40's iteration 3). Echo on
20261030, at 40 to 41, 70, 23, had 38 spells, from tick 4,500 to the end of the run.
- **How it got there:** its stair down for iron broke through the ceiling of a cave two high, at 41, 72, 23. The cave
  floor is a lava lake. Echo came down onto its only dry cells, stone at 40, 69, 23 and andesite at 41, 69, 23 (read
  offline from a fresh world on the seed, diagnosis only).
- **What it did there:** with every step beside it open over the lava, the climb found nothing to climb onto. Its walk
  "to the wall" found no route 40,419 times in the run, 21,382 toward 43, 70, 23 and 19,037 toward 41, 70, 21, each
  asked for again at once. The hole it came down through stood open over the island's andesite block.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.climb` handles a case before its walk "to the wall". With every step
  refused and the player on the ground and not swimming, `intoTheHoleOverhead` pillars up when over it is a hole one
  wide (`holeOver`: the head room clear, and the cell over its head walled in, or the one over that walled in with room
  over it). With the hole over a cell beside it instead, on the same ground, it steps over there first. It needs a block
  it seals and builds with.
- gametest: `NativeTripDownGameTests.onAnIslandInALavaLakeUnderTheHoleItCameDownThroughItPillarsBackUpIntoIt`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| On an island in a lava lake under its hole, back up into the hole, unhurt | the fixture, staged: a block of stone with open sky over it; a cave two high in it with a floor of lava but for an island of two blocks; a hole one wide from the cave's ceiling over the island's second block up to the surface; daylight held; the player on the island's first block, fed, with a stone pickaxe, cobblestone, torches and bread. Passes once it has been three or more above the island, with health 18 or more and not on fire. | On `9df95ac`: fails 3 of 3, still on the island 3,993 to 3,994 ticks on, under the hole; in the first run 1,183 refused walks "to the wall". With the fix: passes 3 of 3, up into its hole after 77 to 82 ticks, no walk to the wall |
| A second fixture, not kept | its stair dug up into a lake's bed one deep, the water coming down it onto the player (202's flooded stair, staged) | On `9df95ac` it passed 3 of 3, swimming out over the lake to dry ground in 192 to 199 ticks. It reproduced nothing of the evaluation's water loop, so it is not kept |
| The goal's other fixtures | see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- **The water loop is still open (F-25c4ay):** in a flooded stair, "Climb out of the water: to the wall" refused again
  and again. It was the most common stuck cause in `e40a3-05b58fd`: 65 spells, Echo on 101 and 202 and Sol on 404.
  - On 202, 9,792 refused walks toward one target and 5,076 cancelled, its stair's next step with the water falling
    down it.
  - Passing over a refused wall target for a minute, as the tracker asks, is not made here: no fixture reproduces the
    loop yet. The staged flooded stair above got out.
- **The from-beside step in water (F-25c4bd) is still open:** on 404, Sol in flood water stepped aside (F-25c4bd's
  `fromBeside`) and drifted back 546 times. The same walk "to the wall" kept it there. Keeping `fromBeside` out of water
  waits on a fixture of its own.
- **The goal may dig back down:** once back up its hole, the goal may dig down again where it came through.
