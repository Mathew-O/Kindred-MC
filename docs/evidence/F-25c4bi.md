# F-25c4bi — Its surplus thrown out and picked straight back up: it lets it be rather than throw it again

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-9-1105 (E-40's ladder, rung a)
Build: on top of `f4bce7a` (main with batch 8); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player with a full inventory still throws out its surplus, as before. If the toss falls back
at its feet and is picked up again, as it does in a shelter a block wide, the player now lets it be. For a minute it
does not throw that item again from within three blocks of where it threw it. Before, it threw the same stack again
every couple of seconds.

Found: E-40's iteration 6 (`e40a6-cb4a545`), seed 20261030, 9 stuck spells.
- Sol was at Y=-26 with no pickaxe and no sticks, sealed in for the night and waiting for daylight to go up for logs.
- It threw a stack of cobbled deepslate at its shelter's wall and picked it up again when the pickup delay ran out,
  607 times.
- Waiting for daylight is not stuck time. Throwing is, so the watchdog fired again and again.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.makeRoom` notes each drop it asks for: the item, where, when, and
  how many of it the player should hold after.
  - When the player holds more than that again within the minute and within three blocks, the drop came back, and
    the item is not thrown again there.
  - With nothing else to throw, `makeRoom` gives nothing, and the plan goes on: here, the wait for daylight.
- gametest: `NativeTripDownGameTests.sealedInForTheNightWithItsInventoryFullItThrowsItsSurplusOnceAndWaits`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Sealed in for the night with a full inventory, wanting logs: surplus thrown at most twice in 2,000 ticks | The fixture, staged. A pocket two high in a block of stone, sealed, its roof three thick, night held. The player has no pickaxe and no wood; it has a crafting table, bread, torches, and 33 stacks of cobbled deepslate. | On `f4bce7a`: fails 3 of 3, its surplus thrown out 90 times in 3,000 ticks. A first form of the fix still threw three times: while the first stack lay at its feet, a second was thrown. With the fix: passes 3 of 3, its surplus thrown out once in 2,000 ticks each time. |
| The class's other fixtures | `NativeTripDownGameTests` | 7 of 7 in each of three runs |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- A full inventory is left full while it waits. A crafted tool or a find with no slot free still has nowhere to go until
  it moves on.
- A drop that comes back because something else gave the player more of the same item (a block it mined, say) is
  treated as come back too, for that minute and place.
