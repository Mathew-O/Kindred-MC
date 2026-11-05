# F-25c4bp — A block the world changes without breaking it, while something is picked up, is no break to reconcile

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-11-1105 (E-40's ladder)
Build: on top of `c9de40f` (batch 10, merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player mining a block that the world changes under its pickaxe without breaking it (redstone
ore lighting up as mining begins, dirt grown over with grass) goes on playing when it picks up a loose item at the same
moment. Before, it was stopped for reconciliation and stayed stopped for the rest of the run.

A break that ends without its block broken is settled by what the inventory shows. Where the inventory only gained,
and the block stood as it was, the gain was put down to a pickup from the ground, and the break was a break with no
effect. The check compared the whole block state, though, so a block that had only lit up or grown grass counted as
changed. With the block changed and the inventory changed, the break could not tell what it had done, and the runtime
stopped the player to reconcile.

Now the same settlement also applies where the target still holds a block (not air, no fluid) and no break of that cell
has been received from the player since the action began: the block was not broken, whatever the world did to its
state.

Found: E-40's iteration 8 (`e40a8-aed3e82`), on two seeds:
- 20261030, Echo, branch mining for lava at Y=-53 (17:40:36 in its console: "Native break outcome requires
  reconciliation"), just before tick 48,000, 40 game minutes in. It was stopped for the remaining 80 minutes of the run.
- 404, Kindred, digging for stone at Y=61 under grass, within the run's first five game minutes. It never made a stone
  tool.

Also in this batch, as diagnostics (no behaviour changed): the reason a break or a placement gives when its outcome
cannot be told now carries what was seen (for a placement: whether it was placed, the block at the cell, and the slots
changed). The runtime logs that reason when it stops the player, so the next such stop shows its cause in the console.
Iteration 8's third stop (Echo on 303, at a cast mould) logged only "Native placement effect cannot be verified", and
its world kept no record of why.

Changed modules/files:

- `companion-server-adapter`:
  - `NativeBreakAction`: `unbroken()`; `settle` treats "intact or unbroken, and the inventory only gained" as no effect.
    The reason for an unattributable outcome now names the step and what was seen.
  - `NativePlaceAction`: the reason for an unverifiable placement carries its detail.
- gametest: `NativeBreakOutcomeGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Mining redstone ore that lights as mining begins, with a cobblestone picked up as it lights: no uncertain action, the player not stopped, the ore still standing | `NativeBreakOutcomeGameTests.aBlockTheWorldChangesUnbrokenWhileSomethingIsPickedUpLeavesNoBreakToReconcile`. Staged: deepslate redstone ore set in stone two blocks in front of the player, which holds only an iron pickaxe; the cobblestone put into its inventory as the ore lights. | On `c9de40f`: fails 3 of 3, each time the player stopped and its body taken out of the world ("Its body still in the world"). With the fix: passes 3 of 3. |
| The rest | `bash ./gradlew build` | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- A block the world changes into another solid block while the player breaks something else nearby, with the inventory
  losing an item at the same time, is still left to reconciliation: nothing can tell a loss from a use there.
- Placement's unverifiable outcome (Echo on 303) is not fixed; the next stop of its kind logs its detail.
