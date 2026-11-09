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
- It came up under the bed of a frozen lake, 18 across.
- At 29, 55, -131 the block over its head room, 29, 57, -131, is dirt with the lake's water on it, so that head room was
  refused. It stepped aside to 29, 55, -132, whose head room is open.
- There every way up and along was refused, for the water or the sand under it. Its nearest wall was the cell it had
  just left, and it walked "to the wall" back into it.
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
| Under the lake's bed where Echo stood, with no pickaxe, it stands on dry ground under the open sky within 12,000 ticks, alive, having stepped aside and back to the wall fewer than 100 times | `NativeLakeBedClimbGameTests.underALakesBedWithNoPickaxeItGetsOutFromUnderTheLake`. Staged as described below | On `84fde42`, without the fix: fails 3 of 3. Each run steps aside and back some 400 times until the watchdog moves it on, then loses its body ("No body") digging toward elsewhere by hand. With the fix (`305c9fd`): passes 3 of 3, out under the sky after 6,653 to 7,326 ticks, with 9 to 53 steps aside and back on the way. |
| The rest | the classes that climb, tunnel, dig out and jump down; `bash ./gradlew build` | See [validation](../validation.md) |

The fixture's staging:
- The block of the run's kept world round the spot, 28 by 17 by 23 (18, 50, -142 to 45, 66, -120), read offline for
  diagnosis only. The liquid on the block's faces is made stone. The cells Echo dug are open in it, as the kept world has
  them.
- Daylight is held.
- The player stands where Echo stood, 29, 55, -132, with what Echo had left: a stone sword, seven raw iron, cobblestone,
  coal and bread. It has no pickaxe and no wood.

The fixture was settled in three steps, and the runs above are on it as committed.
- It first gave 6,000 ticks. On `e0b77f3`, without the fix, it failed 3 of 3, with 404 steps aside and back in each run,
  as in the run. With the fix it stepped down, tunnelled and climbed most of the way out, but did not reach the surface
  in time: by hand, stone takes it some seven seconds a block. The limit became 12,000 ticks.
- At 12,000 ticks without the fix, the watchdog's moves after some 3,600 ticks of the loop dug it out once in 3 runs; the
  other two lost their body. So reaching the sky alone did not tell the loop apart. The criterion gained the cap of fewer
  than 100 steps aside and back, which is the loop itself.
- In one set with the fix on that form, 2 of 3 runs lost their body on the way out, before the fixture named the cause;
  it now names the damage source and place. In all, the fix passed 11 of 13 runs over the forms, with 8 to 57 steps aside
  and back. Without it, all 12 runs over the forms looped 403 to 404 times. Of the 9 at 12,000 ticks, the watchdog's
  moves dug one out and 8 lost their body.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- What starts it is upstream: a pickaxe worn out deep underground with no sticks for the next. In iteration 15, ten
  players on six seeds went "Gather logs for sticks" from deep underground (`sticksReserved`, F-25c4cj, did not cover
  them). That is the next lever, and a bigger one.
- By hand the way out is slow, minutes of stone. A player would more often have kept sticks for its next pickaxe.
