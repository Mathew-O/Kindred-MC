# Veteran mechanics: the hands of a player who has done this for years

Status: proposal, 2026-11-12, by the owner's direction ("Kindred should have veteran player mechanics; when bridging
it should use the timed crouch method"). E-71 and its children a to j are reserved in the
[tracker](../feature-spec.md); claim one in Active work before implementing it. From a read-only review of `main` at
`8b85b5a`. Every slice is body behaviour, so each goes through the failure corpus and its merge gate.

## What the review found

| Mechanic | Today | A veteran |
| --- | --- | --- |
| Bridging | A `native_place` against the side of the block it stands on, then a separate walk onto it, block after block; the sneak is set only for the click itself | Walks backward looking down at the edge, crouches only as it reaches the edge, places, releases, keeps moving |
| Working a spot | It walks to stand within 0.45 of a block's middle for each block. An instrumented hideout build made 2,783 walks for about 400 breaks, with over half its ticks spent walking (F-22c7) | Stands once and breaks or places everything in reach |
| Mining a tunnel | One recorded break at a time; a branch mine keeps "at most 56 ticks a step" (F-26a) | Holds forward and the pickaxe: a two-high step through stone with an iron pickaxe is about 16 ticks of breaking and a step |
| Hands and feet | One skill at a time: it stops to eat, stops to swap, stops to place a torch | Eats on the move, has the next tool out on arrival, places torches from the off hand as it mines |
| Melee against mobs | The duel model strikes, strafes, steps out and raises the shield; nothing uses the knockback of a sprinting hit. Zombies are the largest single cause of death | Sprint-hit, step back through the mob's reach, hit again: the mob never touches it |
| Hearing | None. Nothing reads sound | A hiss behind, a bow drawn, groans through a wall, lava popping ahead: half of what keeps a veteran alive |
| Water | Paddles at the surface; no sprint-swimming anywhere in the code | Sprint-swims, drops a water bucket to descend a cliff, makes an air pocket with a door |
| The kit | Three hotbar slots are managed; a bridge can fail with "no block in its hotbar to bridge with" | A fixed hotbar, blocks, a bucket and torches always to hand, a shield in the off hand |
| Falling | A water-bucket clutch (E-07d) | Also a block against the wall, a swerve to water, hay, slime or vines, and a crouch at any edge it works beside |
| Waiting | Stands by the furnace and watches it | Loads it and leaves, and comes back when it is done |

Already veteran-grade and left alone: sprint-jumping with committed jumps (F-10d), jumping criticals timed to the fall
and the five-tick shield delay (E-41b), gap jumps (F-35a), the jump-and-place pillar and the staircase built on the
move (F-10h), casting obsidian with buckets (F-25c5), bow ballistics and pearl arcs (E-39f).

Measures added to the evaluation engine for all of this: blocks broken and placed per game minute of work, walks
per block worked, bridge blocks per second, and hits taken per mob fought.

---

## E-71a Timed-crouch bridging (the owner's requirement)

One skill, `native_bridge`, for a run of up to sixteen blocks, built like `NativeStairAction` (many blocks in one
skill, each with its own verified record).

**The move.**
1. **Set up.** Blocks selected in the main hand and counted against the gap. Its back to the gap, eyes down at the
   far top edge of the block underfoot, where the next block's face will be.
2. **Back.** Walk backward, not sneaking.
3. **Crouch at the edge.** As its feet come within a step of the edge (from its own position and speed that tick),
   it sneaks. Vanilla's own edge rule then holds it on the block.
4. **Place.** The moment its look meets the side face, it places. The block's record was admitted while it walked,
   so the click is not held up; if the record is not yet saved, it simply stays crouched at the edge until it is.
5. **Release.** Sneak off as the new block lands; back onto it; repeat. The head stays down for the whole run.

**When it uses it, and when it does not.**
- Timed crouch when a fall would not kill: a drop it survives, water below, or a water bucket in its hotbar for
  the clutch.
- Held crouch (sneaking the whole way, today's safe pace) over lava or the void without a bucket, with a hostile mob
  within eight blocks, or while being shot at. A hit while it stands uncrouched is the one way this goes wrong.
- A race (speedrun, a manhunt runner) may take the timed crouch over a deadly drop when its pace says the risk is
  worth it; cautious personalities never do.

**Where it is used.** The route planner's bridge steps (whose cost drops, so re-tune `BRIDGE_COST`), the rescue
`bridge` verb, bridges as builds (E-62b), the End's islands and towers, and both match modes.

**Acceptance.** A twelve-block gap over a three-deep pit: bridged with twelve blocks and twelve records, no fall,
sneaking for under half of the ticks, and at least 1.7 times as fast as the held crouch measured on the same
fixture. A placement refused mid-run (a claim) leaves it crouched at the edge, not in the pit. Over lava with no
bucket it holds the crouch throughout. A zombie brought within eight blocks mid-run switches it to the held crouch.
The corpus's bridge scenes pass.

## E-71b Work from where it stands

Every block in reach from one stance is worked before it moves: the build runner, the tunnel and the clearing of a
site choose a stance that covers the most remaining blocks (the construction planner already reckons stance and reach
for placing), work them nearest first, and move only when nothing is left in reach. No re-centring between blocks.

**Acceptance.** The hideout fixture: walks per break under 1.5 (2,783 walks to about 400 breaks today), and the build
finished in under 60% of its present ticks, every block exact. The build and bunker suites unchanged.

## E-71c Continuous mining and placing

A tunnel, a strip of ore, a wall or a floor is one skill for the run: the pickaxe stays swinging and the feet keep
moving, with each block's record admitted one ahead (as the stairs do). Mining order is a veteran's: top block
first, so nothing falls on it; gravel and sand taken from the top, or a torch set under the column to break it as it
falls.

**Acceptance.** The branch-mine fixture (F-26a's slab): a two-high step through stone with an iron pickaxe in 30
ticks or under, where the limit today is 56; every block recorded and collected. A gravel column brought down with
one torch.

## E-71d Hands and feet together

Still one arbiter and one skill at a time. The movement and mining skills gain what a player's other hand does:
- **eat on the move:** a walk given food eats as it goes, at vanilla's slowed pace while chewing, as one recorded
  eat;
- **the tool for the job out on arrival:** the slot is selected during the last steps of the walk;
- **torches from the off hand:** while mining, torches sit in the off hand and one is placed without stopping
  whenever the light at its feet falls under 8 (this is E-46's torch habit, made fast).

**Acceptance.** A sixty-block walk begun hungry ends fed with no stop. A tunnel of thirty blocks stays lit with no
pause longer than a place. A break begins on the tick of arrival.

## E-71e The rhythm against mobs

Added to `Melee` and used by the fights with mobs, not only duels:
- **the sprinting hit:** the first blow of each exchange is made sprinting, for its knockback, and the sprint is
  taken up again before the next (the reset players call the W-tap);
- **in and out:** hit, step back out of the mob's reach while its own swing recovers, in again with a critical on
  the way down. Zombies are fought so they never land a blow;
- **creepers:** a sprinting hit and straight back out of its range so the fuse stops, or the shield raised toward
  it if it cannot get clear (a raised shield takes the whole blast);
- **skeletons:** shield up while closing, lowered only to strike;
- **ground:** a doorway or a one-wide gap so one mob at a time can reach it; two blocks up when it must eat or heal;
- **the shield lives in the off hand** from the moment it owns one.

**Acceptance.** Fixtures: a zombie killed with no damage taken in at least 8 of 10 fights with an iron sword and no
armour; a creeper struck and survived with no blast, and a blast taken on the shield with no damage; a skeleton
closed on and killed with arrows blocked. The duel benchmark no worse. The corpus's melee death scenes pass. The
engine's hits taken per mob fought, before and after.

## E-71f Playing by ear

A player hears what the server sends its client. The body's connection already receives those sound packets; the
adapter reads them for the body instead of discarding them, so it hears exactly what a person's client would be sent
and nothing more (the implementer confirms the packets reach a native body's handler). A `Heard` record gives
the kind, the rough direction and the rough distance, never a position behind a wall.

What it listens for and does:
- **a creeper's hiss:** turn and act at once (E-71e), about a second and a half of warning it does not have today;
- **a bow drawn, a groan, a rattle, a spider:** something is near and roughly where; shield toward it, back to a wall;
- **mob sounds through stone:** a cave is close; a cautious player digs elsewhere, a bold one goes to look;
- **lava popping or water running ahead while it digs:** a block ready in hand, no digging upward, dig round it;
- **in a match:** footsteps, eating, a chest, a door. Sneaking is silent in vanilla, so runners crouch near hunters.

**Acceptance.** JUnit for direction and distance from staged packets. Fixtures: a creeper primed behind it is turned
to and escaped where the deaf control is blown up; lava one block beyond a tunnel's face is heard and the tunnel
turns; a sneaking mock player is not heard and a walking one is. Nothing is ever known from a sound beyond its kind,
direction and rough distance (asserted).

## E-71g Water

- **Sprint-swimming** wherever the water is two deep or more: far faster than paddling at the surface, and the way
  out of a flooded shaft (verify vanilla's speeds for 1.21.1 in the fixture).
- **The bucket as a tool:** poured down a cliff face and ridden to the bottom, then taken back; poured at its feet
  to put itself out; poured to push mobs back in a tunnel; poured over lava to walk on the obsidian.
- **Air:** a door set on the sea floor for a pocket of air on a long job underwater; a breath taken there.

**Acceptance.** A forty-block channel crossed sprint-swimming in under half the paddling time; a twenty-block cliff
descended by water with no damage and the bucket refilled; a door's air pocket used to finish an underwater job that
drowns the control.

## E-71h The kit and the hotbar

- **A fixed hotbar,** so every change of item is one selection and never a search: sword, pickaxe, axe, shovel,
  blocks, torches, food, bow or spare, bucket; the shield in the off hand, torches there while mining, the totem
  there when it owns one and is in danger (the swap rule exists, E-07c).
- **The caving kit as a standing want** before any trip below ground: 32 blocks, 16 torches, a water bucket, food,
  a spare pickaxe. "No block in its hotbar to bridge with" stops being a way to fail.
- Restocked from storage on each return home.

**Acceptance.** The layout held through a mining trip, a fight and a build; a trip below refused until the kit is
whole, with the missing piece raised as a want; a bridge started from any state finds its blocks.

## E-71i Falling and landing

Beside the water-bucket clutch:
- **a block against the wall** as it falls beside one, landed on (no bucket needed);
- **a swerve in the air** toward water, hay, slime, powder snow, vines or a ladder when one is within reach;
- **a crouch at every edge it works beside:** while building, roofing or mining within a block of a drop of four or
  more, it sneaks. Travel is unaffected.

**Acceptance.** A knock off a wall with no bucket ends on a placed block; a fall beside a pool ends in it; a roof
laid along an eight-block drop with no fall in twenty runs, sneaking only by the edge.

## E-71j Load it and leave

A furnace is loaded with a full stack and the right fuel, and the player goes back to work; the smelt's end is a
time it knows, and collecting it is a task for then. The same for crops, a brewing stand and a villager restocking.
Waiting beside something is for when there is nothing else to do.

**Acceptance.** Sixty-four ore smelted while forty logs are cut, with the ingots collected within a minute of the
last one finishing; nothing watched.

---

## Order

By what the measurements say costs most:
1. **E-71b and E-71c** (the cadence of work): more than half of a build's ticks are walks that a stance would save.
2. **E-71e and E-71f** (the rhythm and the ear): aimed at the largest causes of death.
3. **E-71a** (the bridge) and **E-71h** (the kit), together: the bridge needs its blocks to hand.
4. **E-71d, E-71g, E-71i, E-71j.**

Sizes: a, b, c, e and f about one session each; d, g, h, i and j about a half each. None needs a model.
