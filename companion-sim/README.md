# companion-sim: the headless simulator (F-28)

The players' decisions and plans played on a made-up world, with no Minecraft in it: a hundred games take seconds.
The planner, the choice between staying alive and getting on, the lessons, the atlas, the route planners (F-35d) and
the campaign's own climb, dig down, tunnel and giving up of a target (F-35e) are the core's real ones. What each step
does in the world, and how long it takes, is the simulator's own approximation of vanilla. It tests decisions and
plans, not execution; the GameTests and the evaluation runs say whether a body carries a plan out on a server.

```
./gradlew :companion-sim:runPacks                          # the three packs of 100 seeds, into run/sim/
./gradlew :companion-sim:run --args="portal 100 run/sim"   # one pack
tools/eval/score.py run/sim/portal --no-metrics            # scored as a server's runs are
./gradlew :companion-sim:test                              # its tests, the staged scenes among them
```

See [the operator guide](../docs/operator-guide.md#the-simulator-f-28) for the packs, and the evidence in
[F-28](../docs/evidence/F-28.md), [F-35d](../docs/evidence/F-35d.md) and [F-35e](../docs/evidence/F-35e.md).

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
| 6 | No breath or drowning: a route may swim under water as long as it likes, and swimming is paced as walking | 20 | The player's air below full, or "drown", "under water", "breath" |
| 7 | No doors, slabs, stairs or portal blocks in its world, so those facts of the terrain are never exercised | 1 | The ground holds a door, slab, stairs, trapdoor or portal block |
| 8 | Edits are always on: no server policy, protection or spawn area refuses a dig or a placing | 0 | A refusal for policy, protection or spawn |

Other gaps the movement port showed (F-35e):
- The look sees treetops for 48 blocks from anywhere, below ground too, so logs are asked for through the rock.
- The campaign's way up (`wayUp`: its trail back, a pillar out of a hole, its own seal opened), its cast's dig toward
  lava (`digToward`), and its `lead`, `cave` and `lookAround` steps are not in the simulator.
- There are no danger zones to keep a staircase from.
- Iron is sparse: 200 veins on its 128-block square.
