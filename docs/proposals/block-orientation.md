# Block orientation: every block placed the way it was meant to face

Status: proposal, 2026-11-14, by the owner's direction. E-72 and its children a to e are reserved in the
[tracker](../feature-spec.md); claim one in Active work before implementing it. From a read-only review of `main` at
`99063e9d`. Extends E-08a (blueprints in exact block states) and E-08b (each block verified in its state).

## Where it stands

`construction.Placement` models how vanilla decides a placed block's state, family by family, and the construction
planner picks a stance, a face, a hit point and a look to get the state a blueprint wants. A block in the wrong state
twice is left for the operator (`BuildRunner`).

| Placed correctly today | Not modelled: reported as unsupported, never built |
| --- | --- |
| Plain blocks; sand and gravel; torches, lanterns, carpets and plates on top; wall torches and ladders; logs and pillars by axis; slabs by half; stairs by facing and half; furnaces, single chests, pumpkins, looms, lecterns, glazed terracotta facing the player; fence gates; doors; beds; hoppers (not upward) | Trapdoors; signs, hanging signs and banners; buttons and levers; heads; rails; pistons, observers, dispensers, droppers; barrels and anything else with a facing and no rule; double chests; double slabs; hanging lanterns; anvils; bells; campfires; candles; redstone wire, repeaters, comparators; vines, saplings, flowers and other plants; scaffolding |

Other gaps:
- **Derived properties.** A door's `hinge` and whether a trapdoor, gate or door is `open` are treated as derived and
  ignored, so a door can hang on the wrong side and a shutter cannot be left open.
- **The rules are lists of block ids** (`FACING_PLAYER` names furnace, chest, pumpkin and ten more). A block not on a
  list, vanilla or modded, is unsupported even when it places exactly like a furnace.
- **Turning.** `BlockSpec.rotated` turns horizontal facings, axes, sixteenth rotations and side properties. It does
  not turn rail shapes or the twelve-way `orientation` of a crafter. There is no mirroring at all.
- **The click is taken on trust.** The body checks that the placed block's centre is within five blocks; it does not
  check that its look actually meets the clicked face at the hit point.

What this costs: builds without shutters, signs, banners, buttons or hanging lanterns; redstone limited to hoppers
(M-01d); imported schematics (M-05) that stop short at the first trapdoor; the expression work (E-59, E-68) and the
crafter (E-65a) blocked before they start.

## The idea: ask the game, do not model it

Vanilla already holds the answer to "what state would this block take if I clicked here, standing there, looking
that way": the block item's own placement logic. Instead of copying that logic rule by rule, the adapter **asks it**,
without placing anything, for each click the player could really make, and picks a click that gives the wanted state.
One mechanism then covers every vanilla family and every modded block, and can never disagree with the game.

---

## E-72a The placement oracle, the solver, and honest clicks

**The oracle.** A `PlacementOracle` interface in `companion-core` (pure): given an item, a target cell, the face and
point clicked, the look (yaw and pitch) and whether the player sneaks, it returns the state the block would take, or
nothing if it cannot go there. The adapter implements it with vanilla's own code: an `ItemPlacementContext` built for
the candidate click (the look supplied the way vanilla's own automatic placement context supplies one), the block
item's placement state read through an invoker, and the item's own "can place" check. It reads the world and changes
nothing. The implementer confirms the names against the pinned mappings; the fixtures below are the proof.

**The solver.** For a wanted state at a cell, `Placement` no longer consults id lists. It enumerates the clicks the
player can really make:
- stances from the planner's existing search (including its scaffolds), the current stance first, so a block is set
  from where it stands when it can be (E-71b);
- each face of each solid neighbour that is in reach and in line of sight from the eyes at that stance;
- nine points on that face (a three-by-three grid, inset), which is what decides a half, a hinge or a part;
- the look is not chosen: it is the line from the eyes to that point, as a crosshair is;
- sneaking, as the body does now, and not sneaking where only that gives the state.

Each candidate goes to the oracle; the first whose result matches the wanted state in every property that is not
derived wins, cheapest first (no move, then a walk, then a scaffold). Sixteen-way blocks (signs, banners, heads) are
solved the same way: the stance is searched round the cell until the look falls in the wanted sixteenth.

When nothing matches: one temporary block of its own as a prop to click against (the planner already knows props),
then removed; then "no way to place it so", reported with how many clicks were tried, as today.

**Honest clicks.** Before any placement the body casts a ray from its eyes along its look, to vanilla's reach of four
and a half blocks. It must meet the clicked face of the clicked block at the hit point; otherwise the placement is
refused as "not looking at it" and the planner takes its next candidate. This replaces the five-block distance check.

**Core stays pure.** JUnit runs the solver against a recorded placement table: a GameTest dumps the oracle's answers
for one representative of each block class over the candidate grid into a resource, regenerated only when the
Minecraft version changes.

**Acceptance.**
- A GameTest walks the block registry and counts the blocks a survival player can hold whose wanted states the
  solver cannot reach from an open stance. It records today's count as the baseline and ends at zero, less a short
  written list (fluids, fire and portals, which are not placed as blocks).
- The existing build suites pass with every block exact and no build slower.
- A test-only block in the gametest mod, whose facing comes from where on the face it is clicked, is placed in each
  of its states with no code written for it.
- A placement whose look does not meet its face is refused (fixture).

## E-72b Every family, by fixture

With the oracle the families need tests rather than code. One fixture each, placing every state of one
representative from owned items and reading the state back:

| Family | What decides it |
| --- | --- |
| Trapdoors | the face clicked and the height on it; top or bottom half; its facing |
| Buttons, levers, grindstones | floor, wall or ceiling by the face; facing by the look |
| Signs, banners, heads (standing) | the look, to a sixteenth; (wall) the face clicked |
| Hanging signs, hanging lanterns, chains, end rods, lightning rods, bells | the face clicked, the block above |
| Pistons, observers, dispensers, droppers, barrels, the crafter | the look in six ways (twelve for the crafter), so the stance may be above or below |
| Anvils, campfires, repeaters, comparators | the look |
| Rails | their neighbours, so the order they are laid in |
| Amethyst clusters, pointed dripstone | the face clicked |
| Plants (saplings, flowers, cane, cactus, vines, mushrooms, bamboo, scaffolding) | what they stand on or hang from |

Pairs and neighbours, where the order and the second click matter:
- **double chests:** the second chest clicked, sneaking, onto the side of the first, so they join; two singles side by
  side when the plan says so;
- **a door's hinge:** by the side of the top face clicked when its neighbours do not decide it; a pair of doors
  hinged apart. `hinge` stops being ignored;
- **double slabs, candles, sea pickles, snow layers:** the same block placed again into the cell, counted;
- **stairs' corners, fences, walls and panes:** left to vanilla as now (they follow their neighbours), with a fixture
  that a roof's corners come out as planned whatever order the stairs go in.

**Acceptance.** Each fixture three of three. A cabin variant with shutters, a sign by the door, a banner, a hanging
lantern, a button, a pair of doors and a double chest built exact from owned blocks. A schematic that today stops at
its first unsupported block imports and builds.

## E-72c Finishing touches

Some states are reached only by using the block after it is placed. Each is a recorded use, takes what it takes, and
is verified like a placement:

| State | How |
| --- | --- |
| A trapdoor, gate or door left open | used once; `open` stops being ignored where the plan sets it |
| A repeater's delay, a comparator's mode, a note block's note, a lever thrown | used the number of times needed |
| Stripped logs, paths, farmland | its axe, shovel or hoe used on the block (their wear counted) |
| Waxed or scraped copper | honeycomb or an axe |
| Waterlogged stairs, slabs and fences | its water bucket emptied into the block, and refilled |
| Lit or unlit candles and campfires | flint and steel, or a shovel |
| Potted plants, a chiseled bookshelf's books, a lectern's book, a jukebox's disc | the item used on the block |
| An item frame's item and turn, a sign's words, glow ink and dye | the item, then the turns; the words through the voice or a fixed form (E-68a) |

**Acceptance.** A fixture per row with the item counts and tool wear exact. The auto-smelter grown into a machine
with a repeater at its set delay and a piston facing as planned (M-01d).

## E-72d Turning and mirroring

- `BlockSpec.rotated` completed: rail shapes, the twelve-way `orientation`, wall-mounted facings.
- `BlockSpec.mirrored` (new) across either horizontal axis: facings flip, a door's hinge and a chest's left and right
  swap, a stair's inner and outer corners swap, sixteenth rotations reflect, rail corners reflect.
- `Blueprint.mirrored()`, and `mirror` beside `turns` on the build command and in schematic imports; `Houses` uses it
  for its mirrored shapes.
- Core's own turning and mirroring are **checked against vanilla's** for every state of every block in the registry
  in one GameTest, so they cannot drift.

**Acceptance.** JUnit: four turns and two mirrors are the identity. The registry-wide agreement test. A cabin built
turned and mirrored, every block exact, its door opening the mirrored way.

## E-72e Reading orientation, and orienting by sense

**Reading.** `Orientation.of(state)` in core gives what a player sees in a block's state: its front, the side it
opens toward, which way is up a stair or a ladder, where a bed's head is, where a hopper or a piston points. Used by:
climbing a ladder from its open side; walking through and shutting doors, gates and trapdoors; sitting on a stair or
a chair facing forward (E-56b); following a hopper chain or a rail when it reads a machine or a line; keeping out
from in front of a piston.

**By sense.** Where no plan dictates a state (a furnace put down on a trip, a chest by a mine, a torch in a tunnel),
the player orients it as a person would:
- stations and chests face the open space and the player, never a wall;
- torches on a corridor's wall at head height, on the floor only in the open;
- a bed's head against a wall; a door hinged on the wall side, opening inward;
- logs laid along the beam they continue; stairs facing up the slope; a sign facing the path it is read from.

**Acceptance.** JUnit for the reading over the recorded states. Fixtures: a ladder climbed from its open side; a
furnace set down in a cave faces the open cell; a tunnel's torches go on its wall.

---

## Mods

Nothing here names a block. A modded block is placed in any state the game's own logic can give it, turned and
mirrored correctly if it follows vanilla's properties, with no profile entry (F-36). A block whose placement the
oracle cannot reach is listed by `/aiplayer mods` as unplaceable, with the state.

## Order and size

1. **E-72a** first: one and a half sessions. Everything else rests on it.
2. **E-72d**: half a session, independent, and it unblocks mirrored schematics.
3. **E-72b**: one to one and a half, mostly fixtures.
4. **E-72c**: one.
5. **E-72e**: half.

It touches `Placement`, `ConstructionPlanner`, `BuildRunner` and `NativePlayerBody.placeBlock`, so it meets E-71b
(working from one stance) in the stance search and E-71a (bridging) in the body; the build suites, exact to the
block, are its gate. No model is involved.
