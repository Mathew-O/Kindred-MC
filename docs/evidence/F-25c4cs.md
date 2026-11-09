# F-25c4cs — The obsidian site is kept until its water is taken back

Date: 2026-11-09
Agent / branch: Claude / claude/cast-refusal-1109 (CI fully green, F-25c4), on main `feb4f82`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its CI record shows over the next day's runs.

## Scope and implementation

User-visible behaviour: a player making obsidian from a lava source with poured water keeps to that source until the water
is back in its bucket, and then picks up the obsidian from the draining water. Before, it gave the site up as soon as it
chose to take the water back. When that choice was not acted on, it left the water standing and the obsidian in its flow,
refilled its bucket from it later, and went exploring without the obsidian.

Found: a class run of `NativePortalCampaignGameTests` in batch 23's bench (8 November).
`goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater` failed once there: the obsidian was mined and never picked
up. Smith's log:
- It mined the obsidian from the shore and walked back to its stand. As that walk ended, the goal chose "Obsidian: recover
  the water". The runtime was still on the walk and did not take that step.
- `obsidian()` sets the site aside as it chooses that step ("Done with this lava source"). With no site, the next choice was
  `pickUpLoose`'s wait for a drop in moving water: "Wait for the drop to settle" five times, a second each. The poured water
  was still there, so the drop could not settle.
- With the wait spent, it filled its bucket from the poured water ("Fill a bucket with water while it is close"). The drop
  was still in the draining water, and a drop gets that wait only once, so it went "Explore the cave toward lava" and branch
  mined out of the room.
- In 24 copies of the fixture run unforced, it passed 24 times: the race needs the choice to fall on the walk's last tick.

Changed file: `companion-server-adapter` `PortalRunCampaign`.
- `obsidian()`: choosing to take the water back no longer gives up the site. The site is given up once its water is gone
  (`siteStillUseful`, already called at the top of `obsidian()`). A refused fill still gives it up, as any failed
  "Obsidian:" step does.
- `pickUpLoose`: the wait for a drop in moving water is held off while the site is still in use, instead of while it is
  merely set. Its water taken back, the wait starts then.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| With a choice to take the water back not acted on, the site is kept: its water is back in the bucket with no wait for the drop and no refill by the bucket's own upkeep first; and the obsidian is in hand before any walk to explore | `NativeObsidianPickupGameTests.anUntakenStepToTakeTheWaterBackLeavesNoObsidianBehind`, staged as below | On `feb4f82`, without the fix: fails 3 of 3, "The site given up with its water still standing", the bucket refilled by its upkeep. With the fix: passes 3 of 3, its water taken back by the obsidian step; in two of the three it had not swum through the water, and picked the obsidian up once the water was back |
| The classes that cast and make obsidian | `NativePortalCampaignGameTests` and the cast and obsidian classes | See [validation](../validation.md) |

The fixture's staging: the scene of the sibling fixture (a lava source recessed below its shore, a diamond pickaxe and a
water bucket). On the first tick the player stands on its stand after the mining, the fixture asks the runtime for the
player's next tasks once (`PlayerRuntime.nativeCandidates`) and runs none of them. That is what its own decision does each
tick, before it ticks the task under way. The fixture checks that the step it asked for and did not run was to take the
water back.

The site's check is there because the obsidian alone does not tell the two apart. The obsidian is often in hand before the
water matters: a player that swims through the poured water after mining picks it up as it passes, and one may pick it up
from the shore as it is mined, whether the site was kept or not. Run with the obsidian check alone (`obs2`), the fixture
failed 1 of 3 without the fix; in the other two Smith had the obsidian at once, once by swimming through and once from the
shore. With the fix it passed 3 of 3, each run having swum through, so those runs did not show the fix.

A first form of the fixture closed the stand with a block once the player stood on the shore, to make its walk back fail.
It is not kept: the block cancelled the mining under way, so the obsidian was never mined, with the fix or without.

The benches ran beside E-40's iteration 16, at the lowest priority, on the cores it left idle.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- Other steps of the goal still change its state as they are chosen. One whose choice is not acted on could leave it
  the same way; none other is known to.
