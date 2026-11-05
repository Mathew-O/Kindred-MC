# F-25c4bl — A bucket filled from a stack with no slot free: refused before use, not left to reconcile

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-9-1105 (E-40's ladder, rung a)
Build: on top of `f4bce7a` (main with batch 8); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player whose empty buckets are stacked, and whose inventory has no free slot, no longer fills one.
The skill refuses the fill before it uses the bucket ("No room for the filled bucket"), and the plan makes room or goes
on to other work.

Before, vanilla filled the bucket and, with no slot for the full one, dropped it at the player's feet. The skill read
that as the inventory changing without the outcome it expected. It marked the action uncertain and stopped the player
for reconciliation.

Found: in Echo's death replay from 404 (`echo-73670`, E-40's iteration 6), once F-25c4bk let it fight back and live.
- One replay of three on that code went on to fill a bucket with its inventory full.
- Its body was stopped there, which the replay test counts as dying again.

Changed modules/files:

- `companion-server-adapter`: `NativeBucketAction` refuses a fill when the source stack holds more than one bucket and
  the inventory has no empty slot. The refusal comes before any claim, write or use, so it is a plain precondition.
- gametest: `NativeBucketGameTests.aBucketFilledFromAStackWithNoSlotFreeIsRefusedNotLeftUncertain`. It is a new class,
  registered in the gametest `fabric.mod.json`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Two empty buckets, every other slot full, a water source in front: the fill is refused, the player still at work, no action uncertain, its two buckets held, nothing dropped | The fixture, staged. A water source walled into the floor; the fill asked for directly. | `NativeBucketAction` as on `f4bce7a`: fails 3 of 3, its body stopped for reconciliation. With the fix: passes 3 of 3. |
| Echo's death replay, with F-25c4bk | `ReplayGameTests`, `echo-73670` | With F-25c4bk alone, one replay of three was stopped this way. With both fixes, it survives in 9 of 9, three runs of three. |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Only the fill is refused. Whether the plan then makes room depends on `makeRoom` (F-25c4bi): if its last drop came
  back, it waits a minute before throwing that item again, and the fill is asked for and refused meanwhile.
- A lava bucket's cast fill is the same skill and is refused the same way.
