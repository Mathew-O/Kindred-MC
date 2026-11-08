# F-25c4cj — The trip's sticks are sized to the digging that wears its pickaxes out

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-17-1108 (E-40's ladder, batch 17)
Build: on top of `14cdf8a` (batch 16); recorded in [validation](../validation.md)
Status: complete (planner and native classes); its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: before the work below ground, among the trees, a player packs sticks for the stone pickaxes the
digging ahead will wear out, measured from its own play: about 90 blocks dug for each iron still to find, and, where the
frame is cast and no lava is yet in hand, about 700 more looking for lava. It packs a stick more for each 310 blocks of
that digging, for its torches. Before, it packed for 50 blocks an iron, no search for lava and no torches: sticks for
half the pickaxes it wore out. Underground with its last pickaxe worn out and no sticks or wood left, it climbed to the
surface for logs, by hand when no pickaxe was left.

In the dark below ground it still makes torches from its own coal and a stick, now from any sticks over those for the
pickaxes still to make (the iron one's two) and four spare pairs. Before, it made them only from sticks over the whole
trip's reserve; with the larger reserve, a player that had packed less than it would have left its tunnels dark.

Rock is dug with a stone pickaxe first, even with an iron one in hand: a stone pickaxe is three cobblestone and two sticks,
an iron one's iron is about 270 blocks of digging to replace. That stays; only the sticks it takes are packed for.

Found: E-40's iteration 13 (`e40a13-fe2ba3b`). On the six seeds that lit no portal the players had 842 to 2,192 goal steps
each climbing toward the surface for logs; on the four that lit one, 1 to 1,105, mostly under 100. Each player on the
unlit seeds made 15 to 24 stone pickaxes (8675309: Sol 24, Echo 18), from 8 to 22 logs. Over all 30 players the median dug
1,853 blocks for 19 iron ores, about 90 an ore; those casting a frame dug a median of about 700 more in staircases down
and branch mines toward lava. The players crafted torches 195 times over 61,143 blocks dug, a stick every 314 blocks
(median 4.5 crafts a player, at most 25).

Changed modules/files:

- `companion-core`: `PortalRunPlanner` (`BLOCKS_PER_IRON` 50 to 90; `LAVA_SEARCH_BLOCKS`, 700, counted in
  `sticksReserved` where the frame is cast and no lava, obsidian or frame is yet had; `BLOCKS_PER_TORCH_STICK`, 310, a
  stick for the torches each 310 blocks of that digging lights; `sticksKeptFromTorches`, the pickaxes' sticks and four
  spare pairs).
- `companion-server-adapter`: `PortalRunCampaign.light` (torches made from sticks over `sticksKeptFromTorches`, not over
  the trip's reserve).
- tests: `PortalRunPlannerTest.theTripsSticksCoverTheStonePickaxesItsDiggingWearsOut` (new); five planner tests whose
  scenarios were stocked to the old reserve now stock to the reserve as the plan works it out, and state the reserve by
  the calibration's own sum.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| At the stone stage on the cast route, with the route's iron still to find and no lava, the sticks packed cover the stone pickaxes the measured digging wears out | `PortalRunPlannerTest.theTripsSticksCoverTheStonePickaxesItsDiggingWearsOut` | Before the change: fails, 18 sticks for 18 stone pickaxes' 36. After: passes. |
| The rest of the planner as it was, stocked to the new reserve | `PortalRunPlannerTest` (44) and `companion-core`'s tests | Pass: 44 of 44; `companion-core` 787 of 787 |
| Torches still light the dark below ground from the sticks over the pickaxes' and four spare pairs | `NativePortalCampaignGameTests.goalExploresACaveAndMinesIronItCouldNotSeeAtFirst` (its player staged with the old reserve's sticks and one for a torch), with the classes that pack for the trip and craft | With the larger reserve as the torches' threshold: fails, "It lit the dark passage with its own torch" (121 of 122). With `sticksKeptFromTorches`: the classes 122 of 122. |
| On the ten seeds | E-40's next evaluation | Pending |

The five tests changed: `castingKeepsNoSticksForADiamondPickaxe`, `spareSticksAreToppedUpNearTreesButNeverFetchedFromBelow`
(their reserve numbers, now by the sum), `aShieldIsMadeFromWoodKeptNearTheTreesAndTheFirstIron`,
`beforeTheWorkBelowItPacksASparePickaxeAndATablesWorthOfWood` and `aPlayerThatLearnedOneSpareIsNotEnoughPacksTwo` (with
the shared `ironWork` scenario: stocked to the reserve). Each asserts what it did before, as strictly; with the old
stock, the plan first went for the logs the larger reserve asks for.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- The figures are medians of one evaluation; a seed with a long lava search still digs past them.
- The tables made and left below (two to nine a player) are not sized to the digging.
- Torches may now take sticks the reserve packed for stone pickaxes, down to the iron pickaxe's two and four spare pairs;
  the reserve's torch share is iteration 13's rate, when torches were made only from sticks over the old reserve or from
  planks.
