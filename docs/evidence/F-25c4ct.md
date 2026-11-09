# F-25c4ct — A frame's mould or corner refused with nothing placed is tried again, not set aside

Date: 2026-11-09
Agent / branch: Claude / claude/cast-refusal-1109 (CI fully green, F-25c4), on main `feb4f82`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its CI record shows over the next day's runs.

## Scope and implementation

User-visible behaviour: on the cast route, a mould or corner block whose placement is refused with nothing placed is
placed on the next try. Before, the refused cell was set aside for 2,400 ticks. The cast passed over it meanwhile and
stepped from one stand to another to reach it, about 250 times, before it placed it at last.

Found: `NativePortalCampaignGameTests.underThickRockTheFramesTopIsCastWithItsWaterTakenBack` failed on the first CI attempt
for #51 (run 42543371799, job 128923100460, 9 November). Its log, read in full:
- Deepcaster cleared the frame site's cells, set its first corner, and then had its first mould's placement refused:
  "Placement preconditions, claim, or policy no longer valid; no block placed". That refusal comes from the place action's
  last check before placing. The inventory recorded with the placement no longer matched; most likely a drop of the cells
  it had just cleared had come in.
- The failure handler gives every failed task's position to `avoid`, which sets that cell aside for 2,400 ticks.
  `placeBlock` passes over a cell set aside, so `within` found nothing to do from where it stood. It took that as out of
  reach and asked `stepCloser` for another stand. From there it was the same, and back. "Cast: step closer" completed 267
  times until the 2,400 ticks were up and the mould was placed.
- The next mould's placement was refused too; 108 more steps. The watchdog then found no progress and moved it away from the
  frame, off the fixture's plot, where it found no way back for the rest of the 12,000 ticks.
- In local runs that passed, no placement was refused, and the cast stepped closer 26 times. One local run with two
  refusals stepped closer 415 times.

Changed file: `companion-server-adapter` `PortalRunCampaign` (the task failure handler). A failed `native_place` labelled
"Cast: mould at" or "Cast: corner at" is not given to `avoid`. A refusal that lasts is still set aside, as any task failing
three times in a row is (for 1,200 ticks), so a cell that cannot be placed does not hold the cast.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| A mould refused once with nothing placed is placed within 300 ticks of the refusal | `NativeCastRefusalGameTests.aMouldRefusedWithNothingPlacedIsPlacedOnTheNextTry`, staged as below | On `feb4f82`, without the fix: fails 3 of 3, each with one refusal and 244 to 246 steps closer. With the fix: passes 3 of 3, the mould placed 4 to 5 ticks after its refusal, with no step closer |
| The cast's own classes | `NativePortalCampaignGameTests` and the cast fixtures' classes | See [validation](../validation.md) |

The fixture's staging: the thick-rock fixture's room and kit. While its first mould's placement is being recorded (the
action's admission still pending), the fixture moves the bread to another slot of its inventory. Nothing is given or taken.
The placement's last check then finds the inventory changed, as a drop coming in changes it, and refuses it with nothing
placed. Should the placement go through first, the next mould's is staged.

The benches ran beside E-40's iteration 16, at the lowest priority, on the cores it left idle.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- The refusal in #51's run is read from its log as an inventory change; the log does not say which check failed.
- `within` still steps to another stand whenever its action finds nothing to do from where it stands, whatever the reason.
  With a cell set aside for another reason, it would step about in the same way.
