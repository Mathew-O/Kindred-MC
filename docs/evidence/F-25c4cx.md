# F-25c4cx — A cast whose water is not taken back is settled by where the water went

Date: 2026-11-09
Agent / branch: Claude / claude/cast-outcome-1109 (E-40 ladder, batch 25), on main `e7e0b57`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is the next evaluation's to show.

## Scope and implementation

User-visible behaviour: a player casting its portal frame from lava and water, whose obsidian forms but whose poured water
does not come back to its bucket, goes on with its frame. Before, that cast's outcome was left uncertain, and the player was
stopped for reconciliation for the rest of its run.

Found: E-40's iteration 16 (`e40a16-922f4fd`). Two players were stopped for good this way:
- **31102026, Kindred, 03:32:** "Cast outcome requires reconciliation: Obsidian cast". The cast at (-2, -49, 68) hardened.
  In the kept world (read offline, for diagnosis only) the target holds obsidian. The cell above it, where the water was
  poured, holds gravel, and so does the cell above that. The cast had cleared that cell just before ("Cast: clear -2, -48,
  68"), and the gravel came down into the poured water. The water bucket came back empty.
- **404, Sol:** "Taking the water back was denied". The obsidian formed and the water was left standing; the take-back
  could not be aimed.
- `NativeCastAction.settle` verified a cast only when the target was obsidian and both buckets came back exactly: one lava
  bucket emptied and the water bucket filled again. Anything else that had changed the buckets was uncertain.

In both cases nothing is in doubt. The obsidian is at the target, one lava bucket is empty, and the water bucket is empty.
The world shows where the water went: it is still standing in its cell, or a block that came down into that cell holds it.
No item is unaccounted for, and nothing is made again: water is not an item.

Changed file: `companion-server-adapter` `NativeCastAction.settle`. With the target obsidian, one lava bucket and the water
bucket poured both empty and the other buckets as they were, the cast is verified when the water's cell shows where the
water went: water standing still there ("its water left standing at ..."), or a solid block in it ("its water lost under
gravel at ..."). Air or flowing water in that cell does not show what became of the water, and stays uncertain.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| A frame cell cast, its water then lost under gravel: the cast is verified, nothing is left uncertain, and the player casts another frame cell | `NativeCastWaterLostGameTests.aCastWhoseWaterIsLostUnderGravelCountsAndTheFrameGoesOn`, staged as below | On `e7e0b57`, without the fix: fails 3 of 3, the cast left uncertain and the player blocked with "Cast outcome requires reconciliation: Obsidian cast", the message 31102026 stopped on. With the fix: passes 3 of 3: the cast completed, the player filled a bucket at the room's water ("Fill a bucket with water while it is close") and cast the next frame cell |
| The cast route's classes | Eight classes, 105 fixtures, three runs | See [validation](../validation.md) |

The fixture's staging: the scene of `NativePortalCampaignGameTests.underThickRockTheFramesTopIsCastWithItsWaterTakenBack` (a
frame's room under rock five thick, lava in its floor, the cast route's kit), with a water source set into the floor of the
room's open end for its buckets to be filled again. On the tick the first frame cell is obsidian with its poured water still
standing over it, the fixture sets the water's cell to gravel, as the gravel came down into it on 31102026.

The benches ran with the batch's other benches on the cores, three at a time.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- The cast still clears the water's cell under gravel without waiting for the gravel to come down first; the water is then
  lost and fetched again. Waiting for it, as the tunnel does (F-25c4bv), would save the water.
- A cast whose water's cell holds air or flowing water is still uncertain.
