# F-25c4do — The watchdog's relocation: a read, and a diagnostic at "waiting for a feasible step"

Date: 2026-11-23
Agent / branch: REFACTOR, claude/f25c4do-relocation-read-1123, at the lead's word; takes over F-25c4ar's relocation side
Build: 2c2bd05e (the diagnostic), on main 399ea557 merged in (b36339de)
Status: In progress (this change is a log-only diagnostic, to be read on E-40 iteration 31)

## What iteration 29's consoles show (logs only)

The 20 seed-runs of `e40a29-9a0921b` have 89 watchdog spells with a move elsewhere in them. The scripts are in the author's scratch directory.

**The move itself mostly works, as in iteration 28:**
- 190 moves, counted by target. The median lasts 15 s, and none runs out its spell.
- 154 of the 190 targets are at the player's own Y, 24 off.
- 72 reach the target, 126 end 8 or more nearer, and 47 gain under 2.
- About 9.5 moves a seed-run, against iteration 28's 9.

**What carries the minutes: the plan has no step of its own.**
- 59 of the 89 spells (66%) come right after "waiting for a feasible step" (42) or the climb fallback (17). 30 of those 59 begin under 2 minutes after the previous spell ended.
- The rest come after a branch mine (9), inventory drops (8) and other steps (13).
- "Waiting for a feasible step (tried elsewhere N times)" reaches N = 9, so moving does not give the plan a step.
- 72 of the 89 spells are in the Iron stage, at every depth from Y −52 to 84.

**Just before "waiting", the plan's own walks were refused** ("No walkable route"):
- "Head back up toward home" 31;
- "Back off from zombie" 25;
- "Cast: step closer" 24;
- "Explore the cave toward iron" 15;
- the water exit ("Route … is blocked") 12.

**In the code** (`PortalRunCampaign`):
- `startRelocating` with somewhere else to go clears nothing it set aside.
- A set-aside lasts 1,200 ticks (60 s), so it cannot hold a 3-minute spell by itself.
- "Waiting for a feasible step" is `plan()`'s last fallback, after the climb and the jump down.

## The change: a diagnostic, logging only

- **`PortalRunCampaign.plan`:** once each watchdog spell (keyed on `stuckSpells`), at "waiting for a feasible step", one `WAITING` line gives:
  - the player, where it stands, the tick, the spell and the tick its clock last restarted, the stage, and how many times it was moved elsewhere;
  - the tasks set aside now, and the cells blocked and unreachable;
  - its buckets (lava, water, empty), its obsidian, the portal site and the chosen lava;
  - each of the stage's steps, and why it gave nothing: "no task" (with the dig's reason when the step ended in a dig), "night", or "set aside until" a tick, with the task.
  - It reads the cast's chosen lava field directly, not through `knownCastLava`, which would choose one.
- **`StrongholdLeg.next`:** on the End trip, the trip's own steps that gave nothing. The line gives them as well, since the trip's steps are tried there and the portal's `steps` are cleared.
- **`Digger` (core, pure):** `digDown` and `tunnel` record each heading's refusal, and `descendOrTunnel` the void, in `lastEmpty()`. The refusals are a drop ahead, set aside, liquid by, the island's edge, cannot break, a dug corridor with no fresh face, or no step.
  - No condition changed. The reasons repeat checks that only read state (`blocked`, `dangerBelow`, `islandEdge`, `liquidNear`, `passable`).
- **Not changed:** no saved key, event kind or schema. Nothing reads unseen blocks.

## What it showed on corpus scenes

**The six Iron scenes that waited most in F-25c4dm3's gate:**
- They are all deep water: five "death: drowned" captures (26778, 61176, 80467, 60192, 108001) and one "stuck: water loop" (116900).
- Each line reads `tunnel: NORTH a drop ahead; EAST a drop ahead; SOUTH a drop ahead; WEST a drop ahead`. Swimming, every way ahead reads as a 2+ drop, since water is passable.
- The plan there: "Get out of the water: up for air", then "in deep water with no way out it can reach", then waiting.
- Choosing the scenes by their count of waiting lines picked out the water scenes, so these are not the relocation spells' shape. A first read of them as three cast-route "shapes" was withdrawn.
- **What does carry over:** nothing was set aside in any of the 473 lines. So a 60-s set-aside re-armed within a spell does not explain them.

**The eight dry scenes that waited most:** six lines in all, each different.
- At the Wood and Stone stages, "gather logs" gave no task.
- At the Iron stage, twice the tunnel was refused on every side by cells set aside or liquid near: once with 21 cells blocked and 276 unreachable, once with one task set aside.
- Out of water, the corpus seldom reaches the state: its scenes are 3,600 ticks, which is one spell.

**So the read waits on iteration 31,** whose consoles will carry the line at no extra cost.

## The End trip

**Where the Nether's waiting comes from:**
- COMMS's read of `e40c5-b6f2d4b` found 303 Kindred (cut) spending 42% of its Nether time on "waiting for a feasible step". Its lines are "End: Blaze rods: waiting for a feasible step" ×60 and "End: Prepare: …" ×24.
- These come from the same fallback in `plan()`: `stageLabel` gives the leg's label, so the `WAITING` line fires there.
- It lists the End trip's own steps that gave nothing, from `StrongholdLeg.refusedSteps`, ahead of the portal's (which are cleared on the trip).

**On the End and Nether GameTests:**
- With the line on, on b36339de, they passed 16 of 16 (`NativeEndGameTests` 9, `NativeNetherGameTests` 7).
- None of them brings the trip to "waiting", so the line does not fire there (0 lines).
- So it is not yet shown firing on the End trip. Iteration 31's consoles are where it will be read.

## Validation

Environment: Linux, JDK 21, the manifest's Minecraft 1.21.1 and Fabric, through the wrapper.

**Builds and tests:**
- `:companion-core:test --tests '*Digger*' --tests '*Route*'` and the adapter's main and gametest compile pass. The full core suite is CI's.

**The dig- and walk-sensitive set, with the line on:**
- The set: Bridge, CastFloor, CastPool, DigFill, Navigation, PitPocket, PortalCampaign, RetraceLower, StrandedReturn, SwimUp, ToldLava; 134 tests, run once.
- Result: 133 of 134 passed.
- The one failure is `NativeToldLavaGameTests`' corner case, "Walker mined blind for lava (1 steps)". That is F-25c4dm3's intermittent: main fails it about 1 in 20 under load, and #501's fix is not on main yet.
- The line fired 7 times in that run, 326 to 533 characters a line.

**How long the line can get:**
- Once a watchdog spell, keyed on `stuckSpells`, so a 12-hour run logs at most about 240 lines a player.
- The steps text is cut at 1,200 characters, and the End trip's at 600.

## Remaining

- **Read iteration 31's `WAITING` lines:**
  - for the relocation spells, which steps give nothing, and why;
  - then one cause backed by counts, a fixture on the ground it met, and the smallest fix.
- **Deep water, a separate row:** in deep water with no way out it can reach, then waiting, the Digger reads every heading as a drop. 116900's water loop is M-06m3's (FACTIONS A), as its row reads.
