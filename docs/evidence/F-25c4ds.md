# F-25c4ds — The cast's approach never leans out over lava to bridge it

Date: 2026-11-28
Agent / branch: REFACTOR, claude/cast-approach-lava-1128, at the lead's word; a child of F-25c4 (the cast route's approach), split with COMMS B: the approach's own walk and bridge are this row's, and the hazard spell and the retreat after its cancel are COMMS's
Build: the branch head in the PR, on main
Status: in progress: the staged fixture and the corpus gate pass; the live read is to come

## What iteration 30 showed (E-40, `e40a30-63b97ac`, batch 1)

**Five of the seven lava deaths in iterations 28 to 30 were the cast route's own approach,** each ending on footing that was not solid over lava. Two shapes were read in iteration 30.

**The bridge (8675309 Echo, 17:19:55):**
- On "Climb to the cave toward lava: bridge 1", the hazard spell found Echo at the gap with "feet air, floor lava" before its block was in. The bridge was cancelled.
- It happened twice in a row, and Echo died in that lava at -127, -4, 82.
- The bridge's cell was the lava itself, flush with the floor.

**The cave vines (404 Kindred, 16:36:50):**
- Kindred filled a bucket at its cast's lava. At once, on "Cast: step closer", the hazard spell found it in cave vines at 57, -54, -67 over a lava floor. It died two below.
- This row's change covers the bridge. The vines shape stays open (see below).

## The cause (the bridge)

**How `native_bridge` places:** it leans out past the edge of the block the player stands on, to click that block's side facing the gap. So the body is over the gap's cell before any block is in it.

**Why the crouch was timed:** `NativeBridgeAction.danger()` reads the column below the cell it fills, starting one down. Lava in the cell itself was not seen, so the run kept the timed crouch, not the held one.

**The cancel:** with the body's centre over the lava cell, COMMS's hazard spell fires on "feet air, floor lava" and cancels the run.

## The change (adapter)

**`PortalRunCampaign.bridges`:**
- When the route's first bridge cell holds lava, still or flowing, its block goes in from where the player stands, through `routeEdit` (one `native_place` against any solid face in reach, the block under the lava included), and no run is started.
- A run of bridge steps stops short of any lava cell. The next plan then reaches it as a first step.
- Marker, once a cell: `F-25c4ds: <name> fills the lava at <cell> from where it stands, not bridging out over it`.

**`NativeBridgeAction.danger`:** lava in the cell to be filled counts, as lava below it does, so the crouch is held.

**Not changed:**
- The hazard spell and its retreat (COMMS's).
- `native_bridge` over a gap whose lava is below it, as `NativeBridgeGameTests`' two lava cases have it.
- #520's `besideItsLava`, LOOPS's bx paths, and no saved key, event kind or schema.
- Nothing is read from unseen blocks: the cell is the route's own next cell, at the player's feet level.

## Fixture (staged), `NativeCastApproachLavaGameTests` (new)

**`aGapFilledWithLavaToItsBrimIsCoveredFromTheEdgeNotLeanedOver`:**
- A course twelve up, one wide. Its gap at 1 has a floor cell that is a lava source in a stone basin, with walls either side of the gap, so there is no way round.
- The player is at the start with 16 cobblestone, then a route to the far end.
- Passes if it reaches the far end and is never in the lava nor below the course.

| Build | Result |
| --- | --- |
| main e99ee812 (the branch before the change) | Fails 3 of 3: `native_bridge` cancelled by the hazard spell ("feet air, floor lava"), then the player at the gap cell, its tasks cancelled one after another, never across |
| This change | Passes 3 of 3: one `native_place` into the lava cell from the edge, the marker once, no hazard spell, across at the course's height (lowest 0.0) |

**A first fixture, dropped:** cave vines in a one-block gap with lava two below, and a gap jump through them.
- On main it passed 3 of 3: the jump clears the vines.
- So it is no fixture for a failure. It is not kept.

## The cave vines, still open

**From RUNNER 2's death replay** (`claude/corpus-data-e40a30-404-death-replay-1128`, 889f9da7, Kindred-52093):
- At death the player was at x 57.3, y -56, in a lava source two below the vine cell.
- 57.3 is the furthest a body's box reaches past the edge of the block under it (56's) while still held up.
- The filled cell, 57, -55, -67, was flowing lava again, from its sources.
- The step closer was going to a stand near 60, -54, -60. Every cell at y -54 east of x 56 is over the pool.

**The navigator cannot have ended the step on the vine cell:** `standable` needs a supporting top below, and vines are only passable. COMMS agreed.

**What fits:** the body's centre was already past its block's edge as the step began. Then the step's first steering tick, or the cancel, carried it off the last 0.3.

**Not shown,** and so not changed here. COMMS has the edge-cancel and airborne-retreat questions on its side, after F-25c6h.

## Validation

Environment: Linux, JDK 21, the manifest's Minecraft 1.21.1 and Fabric; GameTests through the wrapper.

- The adapter's main and gametest compile pass.
- **The bridge- and cast-sensitive set,** run once with the change: 155 of 155.
  - The classes: Bridge, Navigation, RescueRounds, CastFloor, CastPool, CastWalkUp, ToldLava, SwimUp, PortalCampaign, HalfCastFrame, CastWaterLost, CastRefusal, StageA, Nether, CastApproachLava, HazardEdge.
  - The marker fired once, at the fixture's own lava cell.
- **The corpus gate** (`tools/corpus/gate.sh 3`, on f1a4a6cb with main 79f80bd4 merged; baseline 3354cb33): passes.
  - 307 of 472 scenes pass over 3 runs (65.0%).
  - 7 flagged, all on the known-intermittent list; 0 confirmed, 7 cleared:
    - Five failed 0 or 1 of 3 on the branch alone: diag-factions303 sol-58760, 8675309 kindred-112700 and kindred-123600, 505-echo-43893, 891567637 kindred-93075.
    - Two failed 3 of 3 on the branch alone and 3 of 3 on main alone: e40c1 8675309 sol-114191 and e40c4 303 kindred-200600.
  - 24 scenes newly pass against the baseline.
  - The marker fired once, in run 3, in the shape it is for: `Ra957361_1` on "Climb to the cave toward lava: bridge", `native_place COMPLETED` into the lava cell, then "walk on" over it and the next bridge steps. No hazard spell and no death followed for that player in the scene.
    - Its scene is `e40a20-79a9aad-8675309-kindred-26400` (class "death: lava"), by `ReplayGameTests`' naming rule. It is not one of the 7 flagged.
    - It passes 3 of 3 on the branch, as on the baseline.
  - **Lava deaths, branch against baseline:** the 18 "death: lava" scenes failed 9, 9 and 10 times over the branch's three runs, 28 in all. The baseline 3354cb33's three runs (`claude/spacer-runs-1112`, `baseline-3354cb3/`) are also 9, 9 and 10, 28 in all. It is not above the baseline.
    - Three scenes moved, none of them where the marker fired:
      - `e40a22-d2389c9-505-echo-129201`: 2 of 3 on the baseline, 0 on the branch.
      - `e40a17-7bc5b42-707-echo-36256`: 0 of 3 on the baseline, 1 on the branch.
      - `e40a20-79a9aad-505-kindred-60851`: 2 of 3 on the baseline, 3 on the branch.

## Remaining

- The live read on E-40's next iteration: no bridge over a lava cell, and the marker where one was filled.
- The cave-vines shape (open, above).
