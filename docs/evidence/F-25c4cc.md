# F-25c4cc — Told where a teammate found trees, a player with no wood walks there for its logs

Date: 2026-11-07
Agent / branch: Claude / claude/e40-ladder-16-1107 (E-40's ladder)
Build: on top of `31f53c5` (batch 16, F-25c4cb); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: when a player cuts its first logs at a stand of trees it tells its teammates, aloud, that there
are trees there. A teammate that needs logs and has no tree in view walks to the nearest trees it was told of, a leg at a
time, and cuts its own. Before, each player looked for trees on its own, no farther round where it started than its
explore reaches; on a desert seed a player could look all run while its teammates had wood.

- **Telling.** Logs cut at trees more than 48 blocks from any it has told of are told to its teammates once: the other
  native players in the same world with a goal, none in a match. The runtime says it aloud, its place to whoever may
  control the player, as it says a lit portal.
- **Walking.** A player short of logs takes, in order: logs it knows of; trees on its skyline (F-25c4ap); trees it was
  told of; then its own exploring. The walk to told trees is a leg of 40 blocks at a time. It is given up once the player
  comes within 16 blocks of them and its gather finds no log there, or after two minutes with no ground gained.
- **Honesty.** What a teammate is told is what the teller saw and cut; nothing is read from the world.

Found: E-40's iteration 12 (`e40a12-66a7c0a`), the desert seed 31102026. Echo cut its first logs at 28, 66, 197, about
150 blocks from spawn, in the first minute. Kindred looked for trees all run, 1,270 of its goal's lines "Gather logs for
a table and a wooden pickaxe", and never made a table. In iteration 11 the same seed's team had the iron pickaxe; in 12 one
player had nothing, and rung a asks for the iron pickaxe on 10 of 10.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign` (`treesFound`, `takeTreesFound`, `toldOfTrees`, `toldTrees`,
  `TREES_APART`; the log gather's fallbacks); `PlayerRuntime.tellOfTrees`.
- gametest: `NativeToldTreesGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Two players 300 blocks apart along a desert strip, one by a stand of oaks: the one with no wood is told where and walks there for its logs | `NativeToldTreesGameTests.toldWhereATeammateFoundTreesAPlayerWithNoWoodWalksThereForItsLogs`. Staged: a strip of sand nine wide on stone, open to the sky, daylight held; eight oaks at its far end; Cutter beside them, Dune at the far end; both with bread and nothing else, on the Nether portal goal | On `31f53c5`: fails 3 of 3, Dune exploring east and south to about 176 blocks from where it started and turning back, no wood in 3,900 ticks. With the fix: passes 3 of 3, Cutter telling of the trees after its first logs and Dune holding logs it cut there after 1,184 to 1,722 ticks. |
| The rest | `bash ./gradlew build`; the full native suite | See [validation](../validation.md) |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Only trees whose logs a teammate has cut are told of; trees a teammate saw and passed by are not.
- Told trees are taken only when no tree is in view; a player that sees a far tree walks to it first.
