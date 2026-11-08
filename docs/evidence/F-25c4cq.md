# F-25c4cq — Out from under a lake's bed with no pickaxe, a level lower, not to and fro beneath it

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-23-1108 (E-40's ladder, batch 23)
Build: on top of `00cddc5` (batch 22); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player climbing toward the surface comes up under a lake's bed, with the water or sand under
water over every way along its own level. It now takes a step down along its heading and tunnels on from there, under a
block more of cover, by hand if it has no pickaxe. Before, it stepped aside into the cell beside it and walked back to
the wall, between the same two cells, for good. The fix has two parts:
- Under the water, with every way along its own level refused: a step down, then along from there.
- After a step aside, no walk "to the wall" straight back into the cell it stepped aside out of, for a minute.

Found: E-40's iteration 15 (`e40a15-dbf4e5c`), Echo on 707: 10 stuck spells, the run's second most frequent stuck cause
(filed as "climb for logs").
- Its stone pickaxe wore out branch mining for iron at Y=19, with no sticks for another. It climbed by hand toward the
  surface for logs.
- It came up under the bed of a frozen lake, 18 across, at 29, 55, -132.
- The cell over the next one, 29, 57, -131, is dirt under the lake's water, so its head room there was refused. It stepped
  aside to 29, 55, -132, whose head room is open.
- There every way up and along was refused for the water or the sand under it. Its nearest wall was the cell it had just
  left, and it walked "to the wall" back into it.
- It went to and fro between the two cells, 10,564 such lines in its console, and later did the same at a second spot
  under the lake, to the end of the run.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.climb`.
  - Under water, when the tunnel "out from under the water" along its own level is refused every way, it digs a step
    down along the same heading (`digDown`, allowed by hand with `digDownByHand`), labelled "out from under the water, a
    level lower". The cell it leaves is set aside for 2,400 ticks so that it is not stepped back up into at once.
  - `fromBeside` remembers where it stepped aside from and to. The walk to the wall is refused for 1,200 ticks when it
    would go straight back into the cell it stepped aside out of.
- gametest: `NativeLakeBedClimbGameTests` (new class, one fixture). Its ground, `lakebed-707.snbt`, is cut from the run's
  kept world.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Under the lake's bed where Echo stood, with no pickaxe, it stands on dry ground under the open sky within 12,000 ticks, alive | `NativeLakeBedClimbGameTests.underALakesBedWithNoPickaxeItGetsOutFromUnderTheLake`. Staged as described below | BEFORE AFTER |
| The rest | the classes that climb, tunnel, dig out and jump down; `bash ./gradlew build` | See [validation](../validation.md) |

The fixture's staging:
- The block of the run's kept world round the spot, 28 by 17 by 23 (18, 50, -142 to 45, 66, -120), read offline for
  diagnosis only. The liquid on the block's faces is made stone. The cells Echo dug are open in it, as the kept world has
  them.
- Daylight is held.
- The player stands where Echo stood, 29, 55, -132, with what Echo had left: a stone sword, seven raw iron, cobblestone,
  coal and bread. It has no pickaxe and no wood.

The fixture first gave it 6,000 ticks. On `e0b77f3`, without the fix, it failed 3 of 3, with 404 steps aside and back to
the wall in each run, as in the run. With the fix it stepped down, tunnelled and climbed most of the way out, but did not
reach the surface in time: by hand, stone takes it some seven seconds a block. The limit is now 12,000 ticks, and the
runs above are on the fixture as committed.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- What starts it is upstream: a pickaxe worn out deep underground with no sticks for the next. In iteration 15, ten
  players on six seeds went "Gather logs for sticks" from deep underground (`sticksReserved`, F-25c4cj, did not cover
  them). That is the next lever, and a bigger one.
- By hand the way out is slow, minutes of stone. A player would more often have kept sticks for its next pickaxe.
