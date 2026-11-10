# companion-sim: the headless simulator (F-28)

The players' decisions and plans played on a made-up world, with no Minecraft in it: a hundred games take seconds.
The planner, the choice between staying alive and getting on, the lessons, the atlas, the route planners (F-35d), the
campaign's own climb, dig down, tunnel and giving up of a target (F-35e), and the game's perception and the cast's dig
toward lava (F-35f) are the core's real ones; its ore is placed as vanilla's world generation places it (F-35f). What each step
does in the world, and how long it takes, is the simulator's own approximation of vanilla. It tests decisions and
plans, not execution; the GameTests and the evaluation runs say whether a body carries a plan out on a server.

```
./gradlew :companion-sim:runPacks                          # the three packs of 100 seeds, into run/sim/
./gradlew :companion-sim:run --args="portal 100 run/sim"   # one pack
tools/eval/score.py run/sim/portal --no-metrics            # scored as a server's runs are
./gradlew :companion-sim:test                              # its tests, the staged scenes among them
```

See [the operator guide](../docs/operator-guide.md#the-simulator-f-28) for the packs, and the evidence in
[F-28](../docs/evidence/F-28.md), [F-35d](../docs/evidence/F-35d.md), [F-35e](../docs/evidence/F-35e.md) and
[F-35f](../docs/evidence/F-35f.md).

## Known gaps

What the simulator lacks for movement fidelity, most often seen first. The rank is how many of the 225 corpus scenes
(`kindred_corpus/gametest/corpus/*.json` on `main`, with the ground captured round each in `src/corpus/structures`)
show the gap. A scene shows it if its record mentions it, or its ground holds the blocks for it:
- The record: the class and staging, the cause, the stage and task, and the 20 log lines before.
- The ground: 146 of the scenes have it captured.
- The words and blocks matched for each gap are listed with it.

Counted 2026-11-10, F-35e.

| Rank | Gap | Scenes | How it was counted |
| --- | --- | --- | --- |
| 1 | Mobs do not plan routes: they walk straight at the player and step up one | 210 | A hostile mob named, or "mob" or "hostile" |
| 2 | No knockback: a hit cuts a walk short where it got to, and never moves the body off its route | 168 | "slain by", "shot by", "blown up", "knock", "hit by" |
| 3 | Gravel and sand never fall, so nothing is dug out from under them and nothing buries the player. The dig decisions refuse what vanilla's would, but nothing comes down | 148 | The ground holds gravel, sand or concrete powder (140), or the record names gravel, sand, a fall or suffocation (17) |
| 4 | Water does not flow: an opening beside water stays dry, and a lake's place is fixed | 108 | The ground holds water (81), or the record names water, swimming, an aquifer, a lake, a river or a flood (54) |
| 5 | No replans mid-walk: the world never changes under a walk, so the goto's "Route to ... is blocked" never comes up | 47 | The goto's "Route to x, y, z is blocked", or "replan" |
| 6 | Swimming is paced as walking, and a route may swim under water as long as its breath lasts (breath, drowning, lava and burning came in F-35f) | 20 | The player's air below full, or "drown", "under water", "breath" |
| 7 | No doors, slabs, stairs or portal blocks in its world, so those facts of the terrain are never exercised | 1 | The ground holds a door, slab, stairs, trapdoor or portal block |
| 8 | Edits are always on: no server policy, protection or spawn area refuses a dig or a placing | 0 | A refusal for policy, protection or spawn |

Other gaps the movement port showed (F-35e), and what F-35f did about them:
- Fixed (F-35f): the look saw treetops for 48 blocks from anywhere, below ground too. It now sees by the game's own
  perception, the core's `Sight` (ported from the adapter's `WorldKnowledge`): a sweep of the chunk sections round it,
  blocks with a face open to the air, ores, fluids and cave floors only in a sight line, trees on the skyline only under
  open sky, and a log only if it is a tree's to chop from the ground at most five below it (the campaign's `trunkFoot`).
- Fixed (F-35f): the cast walked to its lava; it now digs toward it (the core's `Digger.toward`, the campaign's
  `digToward`) once a walk there finds no way.
- Fixed (F-35f): iron was sparse (200 veins on the square, by the atlas's priors). Coal, iron and diamond ore are now
  placed as vanilla 1.21.1 places them, read from the mapped game jar's `OrePlacedFeatures` and
  `OreConfiguredFeatures`: iron 10 veins of 9 a chunk on a triangle from Y -24 to 56 (peak 16), 10 of 4 uniform from
  -64 to 72, and the high-mountain band, 90 of 9 a chunk on a triangle from 80 to 384; coal 20 of 17 on a triangle from
  0 to 192 (half dropped where air touches) and 30 of 17 from 136 up; diamond 7 of 4, 4 of 8 and one in nine chunks of
  12 on a triangle from -144 to 16, and 2 of 8 uniform from -64 to -4. The vein's shape is the simulator's own.
- Still in the simulator's own way: combat. Against the corpus's mob deaths it survives fights the game lost.
  There are no phantoms, and a fall does no more than its blocks' worth (no momentum).
- Still not in the simulator: the campaign's way up (`wayUp`: its trail back, a pillar out of a hole, its own seal
  opened), its `lead`, `cave` and `lookAround` steps, and its cast's walk to lava a leg at a time beyond 96 blocks.
- There are no danger zones to keep a staircase from.
- Its sight sweeps all at once where the game's is spread over its ticks, a few thousand block reads and two dozen sight
  lines a tick; light is not spread, so the sky's is 15 open to the sky, 14 under leaves only and 0 under anything else,
  and an overhang at a cave's mouth is dark.
- Its hills top out near Y 78, so iron's high-mountain band lands in almost no stone; the game's mountains hold much of
  the iron near the surface.

## The corpus in the simulator (F-35f)

`tools/sim/corpus_scenes.py` writes the corpus's replays as simulator scenes; `:companion-sim:run --args="corpus <scenes>
<out>"` plays them and sets each outcome beside the game's (`tools/corpus/baseline.json`):

```
git archive origin/main companion-server-adapter/src/corpus tools/corpus | tar x -C /tmp/corpus
tools/sim/corpus_scenes.py /tmp/corpus /tmp/scenes
./gradlew :companion-sim:run --args="corpus /tmp/scenes run/sim/corpus"   # run/sim/corpus/agreement.txt
```

A scene loads when it is a replay (not a staged fixture), its ground was captured, and every block of it maps to one the
simulator knows (the table in the script: rock of every kind and the ores it has none of as stone, plants and layers with
no collision box as air). The player's state is its health, food, saturation, the items the simulator has a use for, the
time of day, the corpus's ticks, and its place. The ground stands alone in an empty square over bedrock, at its own
heights, as a GameTest's structure stands alone; tools' wear and items the simulator has no use for are not carried; a
dragon goal plays as the portal run. A scene passes, as the game's replay does, when the player is alive at its end.

Played 2026-11-10 (F-35f): 110 of the corpus's 224 replays load (78 have no ground captured; 36 hold a block the
simulator has no form of, magma most often). Of the 72 the game passed or failed (the other 38 it passed on some runs
and not others), the simulator agrees on 55, 76.4%. It survives 12 the game died in (6 zombie fights, 2 skeletons, a
creeper, a phantom, a suffocation, a fall hit too hard) and dies in 5 the game came through.
