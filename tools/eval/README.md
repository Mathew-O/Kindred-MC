# Scoring a build: the evaluation engine (F-26)

Three scripts, Python 3 standard library only, run a build of Kindred MC unattended on many seeds and score it.

| Script | What it does |
| --- | --- |
| `run.py` | Builds a git ref (or takes a built jar), and for each seed starts a fresh Fabric server in `run/eval/<build>/<seed>/` with the jar and the pinned Fabric API, its world from the seed and `KINDRED_EVAL` set to the scenario. The mod plays the scenario with no one at the console and stops the server; the script keeps the server's whole console (`console.txt`), `latest.log`, `eval-result.json` and the jar's name (in `run.json`), and removes the world. With `--keep-world` it keeps the world's region files as the run left them (`world-kept/`), so a stuck place can be staged from its own ground; they are for diagnosis only. |
| `score.py` | Turns a build's results into `summary.md` and `summary.json`: per seed and per player the stage reached, the time to each stage, deaths a game hour by cause, stuck spells and stuck game minutes (three for each spell, the watchdog's minutes of no progress before it gives a spot up), rescue plans and how many got the player out, `RunMetrics`' measures (from `console.txt`, the whole run: `latest.log` starts again at midnight), tokens, and the social lanes' lines (between AI players, said first to people, a meeting noted with nobody to hear) with the lines a game minute (E-51, E-53); then medians and ranges across the seeds. A time to a stage is over every seed, a seed that never reached the stage counted as later than any that did, so its median or the top of its range can read "not reached". A run some of whose starters never came into the world is named and left out, and a seed run on another jar than its build's is said so. |
| `compare.py` | Puts two builds' summaries side by side on the seeds both ran, and calls a difference worth noting only when the two ranges do not overlap; times to a stage (iron, diamonds, the portal) are compared the same way, over every seed. |

```
tools/eval/run.py --ref HEAD --seeds 101,102,103,104,105,106,107,108,109,110 --scenario "goal=nether; ticks=144000" --parallel 2
tools/eval/score.py run/eval/<build>
tools/eval/compare.py run/eval/<old>/summary.json run/eval/<new>/summary.json --out compare.md
```

## A simulated run (F-28)

`companion-sim` writes each of its runs as `run.py` keeps a server's: `<pack>/<seed>/eval-result.json` and
`latest.log`. So `score.py run/sim/<pack>` scores a scenario pack as it scores a build, `RunMetrics` included, and the
summary says the build is `sim`. A simulated run tests the players' decisions and plans, not their execution; see the
operator guide.

## The scenario

`KINDRED_EVAL` is `key=value` pairs separated by `;`. Anything else is not an evaluation run; a person runs it by hand.

| Key | Values | Default |
| --- | --- | --- |
| `starters` | Kindred, Sol, Echo, comma-separated | all three |
| `goal` | `nether`, `end`, `dragon`, `none` | `nether` |
| `edits` | `on`, `off` (the players may break and place blocks) | `on` |
| `ticks` | 1 to 10,000,000 (144,000 is two game hours) | 144000 |
| `sprint` | `on`, `off` (`/tick sprint` the ticks) | `on` |
| `strategist` | `on`, `off` | `off` |
| `voice` | `on`, `off` | `off` |
| `keep` | `inventory`, `none` (the `keepInventory` rule) | `none` |
| `lessons` | `on`, `off` (the players' own lessons from their deaths, E-46) | `on` |
| `atlas` | `on`, `off` (the players' atlas of the world, F-30) | `on` |
| `rescue` | `on`, `off` (a stuck player asks the strategist for a way out, F-29; only with `strategist=on`) | `on` |
| `advice` | `on`, `off` (the strategist's advice; with `strategist=on`, `advice=off` asks it for rescue plans alone) | `on` |

- **Earned or staged.** A run is earned only when nothing in it is handed out or kept: the starters spawn with empty
  inventories, edits are on under the roaming policy, and `keep=none`. Every other run is staged, and the summary says
  so at the top. A staged run is not earned progress.
- **The model players.** `strategist=on` needs the strategist configured as in play (`KINDRED_STRATEGIST=on` and a key
  in your environment); `run.py` passes the key and the `KINDRED_STRATEGIST*` settings to a server only when its
  scenario says `strategist=on` or `voice=on`, and never writes them to a file. The voice has no switch of its own: it
  speaks only when spoken to, through the strategist, so in a run with no people it is rarely asked. A run that asks
  for either with none configured says so in its result's `build` field.
- **The dragon.** `goal=dragon` is refused, as in play, unless the dragon fight is on in the server's edit policy
  (`/aiplayer dragonfight on`); the run does not turn it on.

## What a run writes

`logs/eval-result.json`, per player (every tick counted from the run's start, when the goals were set): the first tick it reached each stage (first wooden tool, stone tools, iron ingot,
iron pickaxe, diamond, obsidian, portal lit, Nether entered, blaze rods, eyes, stronghold, End, dragon), every death
(the tick, the cause as the game records it, x y z, light level, time of day, dimension, hunger, under the sky or not,
the goal's stage, the stance and habits in force, the task it was on), every stuck spell (when the campaign's watchdog
gave a spot up), every rescue plan (F-29: when, and its line, `rescue: <steps> -> unstuck`, `-> failed: <why>` or
`-> refused: <why>`), and the model tokens it used; and the scenario, earned or staged, the seed, the ticks run, and whether
the run ran all its ticks. Stages are seen once a game second from the player's inventory and dimension, and from the
cast and the portal's lighting as they complete; a frame's obsidian is cast where it stands and never held.

## Conventions

- **Ten seeds or more.** The same seed never plays the same way twice. A comparison needs ten seeds at the least, and
  every summary prints medians and ranges, never a single number; a difference is worth noting only when the ranges do
  not overlap.
- **Summaries only.** Results go under `docs/evidence/eval/` as `summary.md` files, no logs; the tracker cites them as
  it cites any evidence.
- **Cores and memory.** Each server wants about 3 GB of heap and a core or more. On four cores and 15 GB, up to three
  at a time (`--parallel 3`). Watch the pace in the run's own lines ("ticks run N, T s" for each seed): if a server falls under 20 ticks a second, go back to two.
  Nothing else should run on the cores meanwhile, or the runs take longer.
- **The server.** `run.py` fetches the Fabric server launcher once into `run/eval/cache/` at the pinned loader version,
  and uses the vanilla server jar from Loom's cache (or Mojang's, fetched once). It needs the network the first time.
  Loom's `runServer` is the fallback with no network: copy the scenario into its environment by hand.
- **What `RunMetrics` measures.** Its windows (ten minutes for a repeated failure, five for work resumed) are read from
  the log's clock, so on a sprinted run they cover more game time than on a played one; compare sprinted runs with
  sprinted runs.

## Rescue recipes (F-29d)

Each run keeps its server's rescue recipes beside its result (`rescue-recipes.json`), with its `stuck` replays under
`replays/`. `--recipes <run folder>` starts each seed's world with the file that seed kept in that folder, and
`--recipes <file>` starts every world with one file: the way to measure a second run with the recipes carried over.
`tools/eval/seeds-20.txt` is F-29e's twenty seeds.
