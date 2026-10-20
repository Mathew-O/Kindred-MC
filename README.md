# Kindred MC
Autonomous Minecraft AI players that earn progress and cooperate through survival.

Production goal: three AI players start empty-handed, establish a shared base, progress through the Nether and defeat the Ender Dragon. Humans may join or administer the world. Local code handles gameplay; optional Claude/OpenAI APIs add difficult planning and conversation. No Jev or mandatory hosted AI service.

**Development build:** control/persistence/skill foundations and a player decision lifecycle exist. Qualified native player mechanics and automatic survival progression do not. The current villager-like mob body is a compatibility backend.

The native server-player backend implements survival inventory/equipment, timed food use, physics and timed mining under the shared arbiter. Native fixtures cover three registered players, one tick path and real player-file reconnect. Schema 7 supports distinct AI identities under one administrator. Operators can create three development native bodies with /aiplayer native create <name>, inspect native list and use native status/stop/resume/connect/disconnect <UUID>. Explicit native wait <1..4> <UUID> and native eat <0..8> <UUID> use acknowledged prepaid activity and the shared runner. Native walk <north|south|east|west> <1..3> <UUID> adds short flat traversal through ordinary movement inputs. Native equip <0..35> <UUID> moves owned armor/shields through normal inventory rules with exact conservation checks. Native patch <from> <to> <block> <UUID> saves bounded work scope and stops current work. Native mine <target> <slot> <UUID> breaks a single scoped log using owned tools and vanilla survival timing under explicit server policy, without claiming drop collection. Default creation stays legacy compatibility.

**Try it:** press **K** in game for the AI player panel (roster with their own skins, spawn, status, Start Nether run, stop/resume, world edits). Or by command: `/aiplayer edits on`, `/aiplayer roster spawn Kindred`, `/aiplayer goal nether Kindred`. The player then earns its way from an empty inventory toward a lit Nether portal: logs, tools, iron smelted in its own furnace, diamonds, obsidian made from lava, flint, frame, flint and steel. It plans only from what it has seen, every step passes the same admission, ledger and body checks as the commands, it respawns after death, and nothing is granted or teleported. This goal is in progress (F-25c4) and not qualified for unattended worlds. See the [operator guide](docs/operator-guide.md).

Start with [the player contract](minecraft_player_spec.md), [architecture](docs/player-architecture.md), [tracker](docs/feature-spec.md), [operator guide](docs/operator-guide.md), [validation](docs/validation.md) and [AGENTS.md](AGENTS.md).

`PlayerRuntime` hosts `PlayerDecisionLoop`, existing task ranking and the sole arbiter/skill runner. Native saves own items; ordered SQLite owns metadata. Inference stays in optional `companion-model`. Unqualified parallel survival/duel controllers are disconnected.

`/aiplayer` is primary; `/companion` redirects to the same command tree. Existing controls retain their restrictions. Legacy mode/scope, module names and SQL/NBT keys preserve saves; they do not define the new product. Timezone changes safely reject until period transitions are implemented.

Use JDK 21 and pinned dependencies:
```powershell
.\gradlew.bat build
.\gradlew.bat :companion-testkit:run
.\gradlew.bat :companion-testkit:runPersonalityDemo
.\gradlew.bat :companion-server-adapter:runGametest
```
Development JARs: `companion-server-adapter/build/libs/`. No complete production release is claimed.
