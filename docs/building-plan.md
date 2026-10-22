# Building: review of the world-design prototype, and the plan taken over

Date: 22 October 2026. Agent: Claude, branch `claude/modest-faraday-r3spro`. This is the design for E-08 (build approved blueprints) and M-05 (schematic library). The [tracker](feature-spec.md) remains the only checklist; [the building contract](architectural-design.md) still holds.

## The prototype, as found

The world-design work by another agent is on `codex/world-design-foundation` and `codex/world-design-handoff` (`55eea9c`). It is a separate Gradle module, `kindred-world-design`. Its parts are a 575-line specification, pure planning code (about 1,350 lines with tests), three draft SQLite migrations (V9–V11) and a handoff review. It was not merged and is not merged here; the branches stay on the remote as reference.

**Will it enable dynamic building for Kindred? Not as designed.** The specification's goals are sound: functional interiors and entrances, paths, persistent style, phases with useful stopping points, verification from the world rather than from a preview. But the code puts its weight on governance and almost none on what makes a survival player able to build:

- **Stale base.** It is built on `main` at `4cb2b33` (19 October), 104 commits behind this branch. It has none of the native body, recorded actions, navigation with pillars and bridges, the needs queue or the End work, and none of it could call them.
- **No real blocks.** It has two materials (cobblestone, oak planks), and the world is seen as AIR, SOLID or HAZARD. Builds need any block in its exact state: stairs' facing and half, slabs, logs' axis, panes, doors, torches. Schematics carry exactly that.
- **The player is missing from the plan.** The order is support adjacency only. Where the player stands for each block, whether it can reach (4.5 blocks from the eyes), which face it clicks, where it looks (that decides a stair's facing), scaffolding for a roof, and not walling itself in are all marked "missing capability". `executionQualified()` is always false.
- **A second action system.** Its in-memory `ExecutionLedger` duplicates the runtime's `ActionAdmission`. The prototype's own review says it must not reach production.
- **Approval before ability.** A design lifecycle, revision graphs, novelty fingerprints, proposal envelopes and design-artifact tables (migrations 9–11) come before any block can be placed. They are worth having only once building works.
- **Static.** It plans once from a snapshot, while a survival world changes under a build (a mob, a human's block, terrain the player digs). It also counts direct items only, with no link to gathering or crafting.

**Kept** (as ideas; the code is re-done in core):
- the pure boundary (planning sees immutable facts, never Minecraft objects);
- validation of targets against what is known (unknown, occupied, protected);
- clearance kept free;
- support before what rests on it;
- a bill of materials;
- bounded sizes;
- "a preview never proves a build".

**Dropped for now:** the separate module, the migrations, the semantic graph and lifecycle, novelty, proposals, the in-memory ledger. Taste and novelty stay tracked under E-29 and E-30, for after the build loop works.

## The plan

Building is a survival skill on the runtime that exists. It is re-planned from the world at every step, so it is dynamic.

1. **Blueprint** (`dev.kindred.core.construction`, pure). A bounded list of blocks with relative positions and exact states (`minecraft:oak_stairs[facing=north,half=bottom]`), with attribution kept (D8). It turns by quarter turns, carrying direction-valued properties along. Sources: vanilla structure `.nbt` and Sponge `.schem` (M-05; `.litematic` later), the user's schematic database, and local generators.
2. **What the world holds.** An immutable snapshot the adapter takes on the server thread, for the build's box and a margin. For each cell: air, the target state already, something replaceable, something else solid, fluid, or protected (another's claim or a container). Unknown cells are not built against.
3. **The next step.** The construction planner, pure, given the blueprint where it is placed, the snapshot and where the player is, returns the next step, re-planned every pass:
   - The remaining blocks are those whose cell does not hold the target state.
   - Lowest layer first, then nearest.
   - Only blocks with support now: a solid face beside them to click; gravity blocks need ground under them.
   - A stance for each: ground it can stand on, within reach of the clicked face, not inside the build's future blocks and not where it would seal itself in.
   - The placement context: which face is clicked, where on it, and which way the player looks. That is what gives a stair its facing and half, a log its axis and a slab its half.
   - With no stance, a scaffold. A pillar of its own temporary blocks, or a staircase (F-10h), recorded so it is taken down after.
   - A block another placed is never broken without the build's leave.
4. **Execution** (adapter). Walk to the stance with the navigator. Place through `native_place`, extended to take the look and hit from the plan. Verify the exact state after: a wrong state is broken and placed again, once, then reported. Everything goes through one action ledger, claims, the edit policy and conservation. Progress is read from the world (what matches), so a restart needs no saved plan beyond the project itself: the blueprint, where, the rotation and who.
5. **Materials.** The bill of materials, less what is already in place, goes into the needs queue (F-25c7). It is gathered and crafted as other needs are: cobblestone, planks, logs, glass from sand, stone bricks from stone. Blocks survival cannot get are reported, never made. The first slice builds from owned blocks, as M-05's fixture asks.
6. **Teams** (E-11, E-40a). Phases are shared out as tasks, and materials are reserved from the team's stock.

Later, on a working loop: interiors and functions (a workshop with a table and storage); generators and the model proposing designs from the user's schematic database (search by size, style, materials); taste and novelty (E-29, E-30); repair (E-31).

## Status (22 October 2026)

- **E-08a is done** ([evidence](evidence/E-08a.md)). It is the pure core in `dev.kindred.core.construction`. Pure tests simulate whole huts (3×4, 5×5, 7×9, each turned four ways) through the planner step by step, as the runner plays them. Every block ends in its exact state, every spot stood on is one it can walk to, and the scaffold all comes down.
- **E-08b is in progress** ([evidence](evidence/E-08b.md)). A native player builds a 5×5 hut of 112 blocks, in six kinds, from its own blocks with `/aiplayer build hut`. Every block is verified in its state, and the fixture passed five runs of five. It is not yet kept across a restart.
- **M-05 is in progress** ([evidence](evidence/M-05.md)). Vanilla structure files and Sponge schematics are read into blueprints with their attribution, from `config/kindred/schematics`. A structure saved by vanilla's own writer is built turned a quarter, every block as vanilla itself turns it. Blocks with nothing to be set against are propped.
- **E-08c** is next: gathering and crafting the bill.

Simulating whole builds and running the fixture again and again showed what a design on paper missed. A roof block can have nothing to be placed against. A scaffold must be judged from its top, by whether the placement works there with the facing it needs. A spot to stand is no use unless the player can walk to it. The server's own reach and sight checks differ a hair from any model of them, so a refused spot has to be let go.

## Stages and evidence

| Stage | Tracker | Done when |
| --- | --- | --- |
| Blueprint, snapshot cells, the next-step planner, the bill of materials, rotation | E-08a | Pure tests: order, support, stance and reach, placement contexts for stairs, slabs and logs, scaffold when out of reach, never sealing itself in, rotation of states |
| Placement with an exact look, the build runner, `/aiplayer build` | E-08b | A native fixture building a small house of several block kinds, stairs included, from owned blocks, every block verified in its state, restart-safe |
| Schematic import (`.nbt`, `.schem`) with attribution | M-05 | Parser tests; a fixture building an imported structure |
| Materials through the needs queue | E-08c | A fixture where the bill is gathered and crafted before building |
