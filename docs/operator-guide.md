# AI-player development operator guide
[Contract](../minecraft_player_spec.md) · [Tracker](feature-spec.md) · [Manifest](../release-manifest.json)

Use a disposable world and back up native saves together with Kindred SQLite metadata. This build is not qualified for unattended production survival.

Use the pinned Java 21/Fabric stack and server JAR. Ordinary clients remain the target; live compatibility requires evidence. No model key or external bot account is needed for current controls.

Use /aiplayer for command help; /companion redirects to the same tree. Creation, status, follow/stay/stop/resume, setup, movement, gather/deposit fixtures, personality and journal retain their existing restrictions. Commands do not imply planned skills are enabled.

## Quick start: the K panel and the Nether-portal goal
Press **K** in game (rebindable under Controls → Key Binds → Kindred AI Players) to open the AI player panel. It lists the canonical roster (Kindred, Sol, Vesp) with their own skins, spawns a roster player at your feet, and for a selected player shows health, food, position, goal and progress, with Start Nether run, Stop, Resume, mode, team, activity cap, Connect/Disconnect and the server's World edits switch. Operator-only buttons need permission level 2. Commands behind the buttons:
```text
/aiplayer roster spawn Kindred       spawn a roster player beside you
/aiplayer edits on|off               server-wide switch for AI block breaking and placing (default off)
/aiplayer goal nether [bot]          earn a lit Nether portal from what the player owns
/aiplayer goal none [bot]            stop pursuing the goal
/aiplayer native goto <x y z> [bot]  walk a pathfound route (no teleport)
/aiplayer native break <x y z> [bot] one recorded survival break with the best owned tool
```
World edits stay off until an operator turns them on; a fresh world has no `edits.properties`, which means off (decision D4). With edits on and no assigned work area, the player follows the roaming policy: it may break and place where a survival player could, never containers or block entities other than its own empty furnace, never in spawn protection, never the block under its own feet, and never a break that would let lava out (obsidian guarded by water is the one exception).

The Nether goal is a local plan, not a script: logs, crafting table and wooden pickaxe, cobblestone and stone tools, iron smelted in its own furnace, then the frame's obsidian, then a 4×5 frame lit with its own flint and steel (flint from gravel). For the obsidian it picks the cheaper way from what it holds and has seen, and re-plans as that changes: usually it makes a second bucket and casts the frame in place, as speedrunners do (a lava bucket filled at a pool it has seen, a cobblestone mould behind each frame block, water poured over the block from its other bucket, lava poured in, where it hardens at once, and the water taken back); with diamonds in hand or diamond ore in sight it makes a diamond pickaxe and mines obsidian made by pouring water over lava instead. It sees only blocks it could see (exposed blocks nearby; ores, fluids and caves also need a line of sight). It looks for ore the way players do: ore in view first, then into caves it has seen toward the depth where that ore is common, lighting the dark with torches it crafts from spare coal; it digs (a staircase down, then branch mining) only when it has no ore or cave in view. Every step is one bounded skill through the same activity, ledger and body checks as the commands above. It takes the crafting table and an emptied furnace along when it leaves to gather, makes and wears an iron chestplate before the deep descent, fights back against a mob that is after it without chasing it into lava or off a drop (takes cover behind its own block from a skeleton it cannot reach, and backs off when badly hurt, breaking off a fight that has worn it down; with no sword or axe, as after a respawn, it keeps away from mobs and takes cover from skeletons rather than fight with its fists), never stands where lava beside or above it could flow in, digs itself out at once if gravel falls into its head space (or a block beside it reaches into its eyes), walls itself in when a dig breaks into a cave of mobs it is no match for and waits there to heal, holds a tunnel mouth against a mob on foot instead of chasing it into the open, stays away for a day from the cave where a mob killed it, smelts its first iron straight into a shield when it kept wood for one (raised while closing in on a skeleton, and toward a creeper about to blow), fights a creeper hit and run or sprints away from it, digs in on the surface at night and seals the way in with its own dirt or stone while it waits for daylight, loads a furnace with fuel matched to the ore (one coal for every eight items, and no more ore than its fuel can smelt), and drops surplus stone when its inventory fills (the items stay in the world). If it makes no progress for about three minutes of game time (no new stage, nothing new gained, no new ground), it gives up on the spot and tries somewhere else; the panel shows "tried elsewhere N times" and the log records each attempt. Progress shows in the panel and in the server log as `<name> goal at x y z: …`.

When a native player dies, its current work settles first, vanilla drops its real inventory, and the same identity respawns about a second later at its bed or the world spawn. Unless a mob killed it (the mob is likely still there), it tries once to pick its items back up. Nothing is restored. Restart while dead is not yet covered and needs reconciliation.

Roster skins come from the bundled textures. To show the same skins to clients without the mod, put signed Mojang texture properties in `config/kindred_skins.properties` as `<key>.value` and `<key>.signature` (keys `kindred`, `sol`, `vesp`).

## Development native bodies
Explicit development native bodies are available:
```text
/aiplayer native create Ada
/aiplayer native create Ben
/aiplayer native create Cy
/aiplayer native list
/aiplayer native status|stop|resume|connect|disconnect <UUID>
/aiplayer native wait <1..4 seconds> <UUID>
/aiplayer native eat <0..8 hotbar slot> <UUID>
/aiplayer native walk <north|south|east|west> <1..3 blocks> <UUID>
/aiplayer native equip <0..35 inventory slot> <UUID>
/aiplayer native patch <from x y z> <to x y z> <resource block ID> <UUID>
/aiplayer native mine <target x y z> <0..8 hotbar slot> <UUID>
```
Only operators create them, on solid ground in an already loaded controlled area. Controllers/operators may use lifecycle controls. The cap is three, including pending/incomplete records. New bodies are established after verifying a native player-file receipt. Disconnect/shutdown preserve native items and stop. Restart leaves them offline/stopped; connect explicitly while the saved area is loaded, then resume explicitly. Resume enables no autonomous work yet.

Wait/eat are explicit bounded development tasks. A request reserves at most five seconds through the existing activity writer before acquiring a lease. Eating uses one actual owned hotbar food with native timing, requires hunger, rejects chorus fruit and never retries an uncertain outcome. Stop clears use immediately. A failed/expired allowance starts no work; status distinguishes charged and observed milliseconds. Settlement must finish before another request; shutdown settles accounting without additional game ticks. No automatic renewal or unattended work is admitted. Walking is limited to one short flat loaded corridor, checked again during movement; unsafe or changed routes block. No jumping, digging or general pathfinding.

Equip moves one owned armor/shield into an empty native equipment slot using ordinary inventory-handler rules. Slots 0..8 are the hotbar; 9..35 are main inventory. Occupied gear/cursor, invalid or changed stacks reject. Durable intent acknowledgements precede the transfer; exact items/components/counts and durable outcome verification precede reported completion. Pending action IO is flushed before store shutdown. Unknown outcomes block the native record without replay; preserve its actual native save for reconciliation. No borrowing, gear replacement or automatic equipment selection.

A resource-work patch is explicit bounded task scope: at most nine loaded chunks and 4096 blocks, in the native player dimension and world bounds. No home or owner-presence requirement. Changing it stops current/pending work immediately; after the save acknowledgement, resume explicitly. Status shows its bounds/resource. Configuration does not install mining or grant server edit permission.

Mine executes a single scoped log break using owned hotbar contents, native survival timing, and tool wear. It requires explicit server edit policy opt-in, active paid activity, an unexpired resource claim, and a matching work patch. A matching native break receipt completes the action; drop collection is not claimed. Stop cancels digging immediately; conflicting world changes block for reconciliation without replay.

Missing, foreign, wrong-version or unverified state blocks for reconciliation; it is never replaced with an empty inventory. A saved dead body (a crash during the one-second respawn window) also blocks. Blocked/incomplete records keep their slot until recovery/removal is implemented. Autonomous work beyond the Nether-portal goal above remains disabled, and the goal itself is not qualified for unattended worlds. Default /aiplayer create still uses the legacy body; no saves are converted. Full client and restart recovery qualification remains open.

Legacy owner/mode/scope fields preserve access and saves; they are not the new product interface. Native creation has its development cap; team management and legacy migration remain unfinished. Combat/duels reject until qualified native skills use the arbiter. Timezone changes reject until safe accounting transitions exist; spent activity is not reset.

Inference is optional and currently disconnected. Planned Claude/OpenAI keys/budgets belong in private operator configuration, never memory/logs/source. No Jev.

Report exact build, seed/difficulty, IDs, commands and native before/after evidence. Stop and snapshot ambiguous actions; do not recreate missing items.
