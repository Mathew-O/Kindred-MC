# AI-player development operator guide
[Contract](../minecraft_player_spec.md) · [Tracker](feature-spec.md) · [Manifest](../release-manifest.json)

Use a disposable world and back up native saves together with Kindred SQLite metadata. This build is not qualified for unattended production survival.

Use the pinned Java 21/Fabric stack and server JAR. Ordinary clients remain the target; live compatibility requires evidence. No model key or external bot account is needed for current controls.

Use /aiplayer for command help; /companion redirects to the same tree. Creation, status, follow/stay/stop/resume, setup, movement, gather/deposit fixtures, personality and journal retain their existing restrictions. Commands do not imply planned skills are enabled.

## Quick start: the K panel and the Nether-portal goal
Press **K** in game (rebindable under Controls → Key Binds → Kindred AI Players) to open the AI player panel. It lists the canonical roster (Kindred, Sol, Vesp) with their own skins, spawns a roster player at your feet, and for a selected player shows health, food, position, goal and progress, with Start Nether run, Stop, Resume, mode, team, activity cap, Connect/Disconnect, Skin... and the server's world edits switch (Edits). Operator-only buttons need permission level 2. Commands behind the buttons:
```text
/aiplayer roster spawn Kindred       spawn a roster player beside you
/aiplayer edits on|off               server-wide switch for AI block breaking and placing (default off)
/aiplayer goal nether [bot]          earn a lit Nether portal from what the player owns
/aiplayer goal end [bot]             on from a lit portal to the End portal (earns the portal first)
/aiplayer goal none [bot]            stop pursuing the goal
/aiplayer skin <name|default> [bot]  dress an AI player in a skin from config/kindred/skins (or its own again)
/aiplayer skin list                  the skins on offer
/aiplayer native goto <x y z> [bot]  walk a pathfound route (no teleport)
/aiplayer native break <x y z> [bot] one recorded survival break with the best owned tool
```
**Skins.** Put Minecraft skin PNGs in `config/kindred/skins` on the server (the folder and a read-me appear on first start; in single player it is your own game folder):
- Files are 64×64, or the old 64×32, at most 64 KiB, up to 64 of them.
- The file name is the skin's name: `Red Knight.png` is chosen as `red_knight`.
- End a name in `_slim` or `_wide` to set the arm model; otherwise it is worked out from the image.
- In game, press K, select an AI player and press Skin... to see each skin from the front, and click one to put it on. The first card brings back the player's own skin.
- Players with the Kindred client see it at once; vanilla clients keep the default skin, as they accept only Mojang-signed textures.
- Choices are kept in `config/kindred/skin-choices.properties`.
- Files that are not usable skins are named in the server log with the reason.
- After adding files, press Reload in the selector.

World edits stay off until an operator turns them on; a fresh world has no `edits.properties`, which means off (decision D4). With edits on and no assigned work area, the player follows the roaming policy: it may break and place where a survival player could, never containers or block entities other than its own empty furnace, never in spawn protection, never the block under its own feet, and never a break that would let lava out (obsidian guarded by water is the one exception).

Every walk moves as a player does (F-10d): straight across open ground to the furthest point it can reach in a line, sprinting when its food allows (7 or more) and sprint-jumping along clear straight stretches (10 or more, as a sprint-jump costs twice the hunger), turning smoothly with its head, slowing for sharp turns, steps and drops, and easing to a walk near where it is going.

Where walking will not do, the End goal makes a way as a player would (F-10e, in development): across a gap or a lava inlet by bridging with its own blocks, up a cliff by pillaring, through a wall or a hill by digging, whichever costs least against walking round. Digging is cheap in soft blocks and dearer in hard ones; a placed block costs more, and far more over lava, so it builds only where going round is much longer. Each change is one ordinary recorded break or place under the server's edit policy, and the way is planned again after each. It does so in the Nether (where a walk on fails, and to reach a fortress it has seen), and in the Overworld when every straight line toward the stronghold is refused. In the Nether it keeps above the lava sea (Y 40) while it explores.

Across open water the End goal goes by boat (F-10f, in development), as players do: where most of the way ahead is water and it carries a boat, it puts the boat on the water (wading out where the shore leaves no room), rows across at a boat's speed, gets out at the far shore and breaks the boat to pick it up again. A drowned does not stop it rowing, and nothing on land is done from the boat. It makes a boat from wood it carries while it prepares for the Nether. Launching from deep water does not work yet.

What a player learns about a world on the way to the End (its portals, the fortress, each eye's flight and where they cross) is kept across restarts in the world's `kindred/memory` folder, one file per player. Delete a player's file to make it forget.

The Nether goal is a local plan, not a script. Like a player it pillars up out of a hole it cannot walk out of (jumping and placing one of its own blocks under its feet), and packs sticks from a tree up to 64 blocks off before going below ground: logs, crafting table and wooden pickaxe, cobblestone and stone tools, iron smelted in its own furnace, then the frame's obsidian, then a 4×5 frame lit with its own flint and steel (flint from gravel). For the obsidian it picks the cheaper way from what it holds and has seen, and re-plans as that changes: usually it makes a second bucket and casts the frame in place, as speedrunners do (a lava bucket filled at a pool it has seen, a cobblestone mould behind each frame block, water poured over the block from its other bucket, lava poured in, where it hardens at once, and the water taken back); with diamonds in hand or diamond ore in sight it makes a diamond pickaxe and mines obsidian made by pouring water over lava instead. On a sky island (nothing at the bottom of the world under the player) there is no stone to dig, so it builds a cobblestone generator from a bucket of lava and a bucket of water (a short trench dug into the ground, water falling into a hole at one end, lava at the other, mined from a pocket beside the cell where cobblestone forms; grass within the lava's reach is cleared first and no table or furnace goes near it), replants every tree it cuts (breaking leftover leaves for saplings and waiting by its saplings when it needs wood), makes a spare log into charcoal for its iron (a log and a plank into the furnace), and skips the iron chestplate, as there are no caves to go down into. It counts stone in sight there only within eight blocks; farther off it is likely on another island. Charcoal and crossing between islands are not done yet. It sees only blocks it could see (exposed blocks nearby; ores, fluids and caves also need a line of sight). It looks for ore the way players do: ore in view first, then into caves it has seen toward the depth where that ore is common, lighting the dark with torches it crafts from spare coal; it digs (a staircase down, then branch mining) only when it has no ore or cave in view. Every step is one bounded skill through the same activity, ledger and body checks as the commands above. It takes the crafting table and an emptied furnace along when it leaves to gather, makes and wears an iron chestplate before the deep descent, fights back against a mob that is after it without chasing it into lava or off a drop (takes cover behind its own block from a skeleton it cannot reach, and backs off when badly hurt, breaking off a fight that has worn it down; with no sword or axe, as after a respawn, it keeps away from mobs and takes cover from skeletons rather than fight with its fists), never stands where lava beside or above it could flow in, digs itself out at once if gravel falls into its head space (or a block beside it reaches into its eyes), walls itself in when a dig breaks into a cave of mobs it is no match for and waits there to heal (and walls off a cave whose mobs it can see from its passage before they notice it, and an enderman it provoked), explores caves without a shield only where it cannot hear mobs nearby, never digs a staircase step over a deep drop or down into a place where mobs killed it, holds a tunnel mouth against a mob on foot instead of chasing it into the open, stays away for a day from the cave where a mob killed it, smelts its first iron straight into a shield when it kept wood for one (raised while closing in on a skeleton, and toward a creeper about to blow) and makes its iron pickaxe as soon as it has three more, jumps as its swing recovers and strikes on the way down for critical hits (half as much damage again; not against a creeper, nor in water or on a ladder), fights a creeper hit and run or sprints away from it, digs in on the surface at night and seals the way in with its own dirt or stone while it waits for daylight, loads a furnace with fuel matched to the ore (one coal for every eight items, and no more ore than its fuel can smelt), and drops surplus stone when its inventory fills (the items stay in the world). If it makes no progress for about three minutes of game time (no new stage, nothing new gained, no new ground), it gives up on the spot and tries somewhere else; the panel shows "tried elsewhere N times" and the log records each attempt. Progress shows in the panel and in the server log as `<name> goal at x y z: …`.

**The End goal (F-25c6, in development).** `/aiplayer goal end` earns the portal as above, then goes on the way players do:
- It stocks up (food, blocks, a spare pickaxe, iron into a sword, a shield, a helmet and boots) and goes through its portal, or any lit portal it has seen.
- In the Nether it looks for a fortress for blaze rods and for endermen's pearls, then comes back and makes eyes of ender.
- It throws one and watches it fly, walks 200 blocks to the side and throws another, and heads for where the flights cross. There it throws one more close by to pin the spot, digs down beside it (from the nearest dry land if the spot is under water) and looks for the portal room.
- It stops with the End portal in sight and says where in chat.

The player may use portals only while this goal runs. It never enters the End. Not yet seen in play: fortresses, blazes, endermen, eyes made from what it gathered, and the portal room. See [F-25c6](evidence/F-25c6.md).

When a native player dies, its current work settles first, vanilla drops its real inventory, and the same identity respawns about a second later at its bed or the world spawn. Unless a mob killed it (the mob is likely still there), it tries once to pick its items back up. Nothing is restored. Restart while dead is not yet covered and needs reconciliation.

### Optional: a model strategist for the goal (F-19b, F-19d)
The goal plays the same without any model. For development runs a model can be consulted as a strategist: it reads the player's own short report (stage, gear, health, recent deaths and their causes, danger zones, its notes) and picks how careful the player should be for the next five minutes: press on, play safe (no caving, backing off sooner) or surface and recover (climb out to daylight and heal first, then play safe). It may also name up to three places near the player's deaths or danger zones to avoid for a while. It never moves the player, gives items, writes code or decides the goal is done; every moment is still the local plan. It is asked only when something changed (the goal starting, a new stage, a death, a spell with no progress at most every two minutes, and otherwise every five minutes while progress changes), never per tick, off the game thread, and a slow or failed reply changes nothing. Switch it on with environment variables in the server's environment:
```text
KINDRED_STRATEGIST=on                          required; anything else leaves it off
<provider>_API_KEY=<your key>                  one provider's key (see below) or KINDRED_STRATEGIST_API_KEY; read from the environment only, never stored or logged
KINDRED_STRATEGIST_PROVIDER=<name>             optional: anthropic, openai, gemini, groq, mistral, deepseek, xai, openrouter, openai-compatible
KINDRED_STRATEGIST_BASE_URL=<url>              optional; another address for an OpenAI-compatible provider, or a local server
KINDRED_STRATEGIST_MODEL=<model id>            optional; the provider's default below
KINDRED_STRATEGIST_PACE=steady|realtime        optional; default steady (see below)
KINDRED_STRATEGIST_DAILY_TOKENS=<tokens>       optional; daily budget for all players (steady 100000, realtime 10000000)
KINDRED_STRATEGIST_PLAYER_DAILY_TOKENS=<n>     optional; daily budget per player (steady 50000, realtime 5000000)
KINDRED_STRATEGIST_EFFORT=low|medium|high      optional; default low
KINDRED_STRATEGIST_THINKING=off|on             optional; default off
```

Any of these providers can serve it, each with a small, fast default model (F-19d):
```text
ANTHROPIC_API_KEY     Claude Haiku 5.5 (claude-haiku-5-5), through the official Anthropic SDK
OPENAI_API_KEY        gpt-4o-mini
GEMINI_API_KEY        gemini-3.5-flash-lite (GOOGLE_API_KEY also works)
GROQ_API_KEY          openai/gpt-oss-20b
MISTRAL_API_KEY       mistral-small-latest
DEEPSEEK_API_KEY      deepseek-chat
XAI_API_KEY           no default: name the model with KINDRED_STRATEGIST_MODEL
OPENROUTER_API_KEY    openai/gpt-4o-mini
```
Model line-ups change often: check the provider's current list, and set `KINDRED_STRATEGIST_MODEL` if a default has been retired. With several keys set, the first in this list is used unless `KINDRED_STRATEGIST_PROVIDER` names one. A single `KINDRED_STRATEGIST_API_KEY` also works; its provider is recognised by the key's prefix (`sk-ant-`, `sk-or-`, `gsk_`, `xai-`, `AIza`, then `sk-` for OpenAI), and a Mistral or DeepSeek key needs the provider named. Any other server speaking OpenAI's chat completions, such as a local Ollama or LM Studio, needs only `KINDRED_STRATEGIST_BASE_URL` and `KINDRED_STRATEGIST_MODEL`, and no key. All but Anthropic go through the JDK's HTTP client and the Gson the server already carries, so they also work from the release jar. The reply is asked for with a strict JSON schema (JSON mode for DeepSeek, or once more in JSON mode when a provider refuses the schema) and validated in core exactly as Claude's is.
The pace sets how closely it follows play. Steady asks on the big changes (the goal starting, a death, a new stage) and otherwise every few minutes, within the contract's default budgets. Real time asks as often as the planning lane allows (every ten seconds per player at most), and also whenever something new happens: a fight with a new foe, a hit that costs six health or more, nightfall or daybreak. Its advice lapses after a minute of game time unless renewed. Answers come back in about two seconds: requests go at low effort without thinking first, with the instructions cached. Measured on one report, that was about 1.9 seconds every time, against 2.8 to 4.0 at the model's own default, with the same advice. A question costs about $0.00013 with the instructions cached, so real time at the full rate is about five cents an hour per player.

It also has a say in what comes next (F-19c). A reply may set a focus for the same five minutes: food first (hunt, and by day go out and look for animals when none is in sight, until well stocked) or wood first (logs while a tree stands). A reply's note may also teach a habit the player keeps in that world from then on: carry three more food, pack a second spare pickaxe for the work below, dig its own way rather than explore caves, or dig in for the night at dusk. A habit needs a note saying why, at most four are kept, and the strategist can retire one. A focus or a habit only reorders or resizes the route's ordinary jobs; fights, shelter and healing still come first. The habits live with their notes in the memory file below (shown as `"habit": "MORE_FOOD"` on a note). Delete the note, or the file, to make the player drop a habit; each goal the player starts in that world gets the habits it has learned there.

The log says `Kindred strategist on` at start, `<name> strategist: <stance>[, focus <focus>] (<in> in, <cached> cached, <out> out tokens, <ms> ms) - <reason>` for each reply, and `<name> strategist: habits in this world now [...]` when a habit is learned or retired. Each player keeps its own memory for each world in `<world>/kindred/model-context/<player UUID>.json`: its notes, its deaths and their causes, the advice it was given, and the consultations and tokens they cost. A lesson already noted is kept once however the model rewords it, and the model is told to write a note only for a new lesson and only from what the report shows. Delete the file to make a player forget a world; it holds only the player's own game events and the model's notes about them. Requests are bounded: a fifteen-second deadline, one at a time, at most six a minute per player and twelve in all, and the daily budgets, reserved before each request and settled to what it used. A consultation costs about 2,000 to 4,000 input tokens, most of them read from the cache, and 100 to 600 output tokens. The SDK is on the development classpath (`runServer`, GameTests) but not yet in the release jar; there the switch logs a warning and play goes on without it.

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

Wait/eat are explicit bounded development tasks. A request reserves at most five seconds through the existing activity writer before acquiring a lease. Eating uses one actual owned hotbar food with native timing, requires hunger, rejects chorus fruit and never retries an uncertain outcome. Stop clears use immediately. A failed/expired allowance starts no work; status distinguishes charged and observed milliseconds. Settlement must finish before another request; shutdown settles accounting without additional game ticks. A skill that runs past its slice (a long walk, obsidian with a diamond pickaxe, stone by hand) has the slice renewed shortly before it lapses, through the same writer and within the same cap and window; an exhausted allowance or window still stops it (F-11c). Walking is limited to one short flat loaded corridor, checked again during movement; unsafe or changed routes block. No jumping, digging or general pathfinding.

Equip moves one owned armor/shield into an empty native equipment slot using ordinary inventory-handler rules. Slots 0..8 are the hotbar; 9..35 are main inventory. Occupied gear/cursor, invalid or changed stacks reject. Durable intent acknowledgements precede the transfer; exact items/components/counts and durable outcome verification precede reported completion. Pending action IO is flushed before store shutdown. Unknown outcomes block the native record without replay; preserve its actual native save for reconciliation. No borrowing, gear replacement or automatic equipment selection.

A resource-work patch is explicit bounded task scope: at most nine loaded chunks and 4096 blocks, in the native player dimension and world bounds. No home or owner-presence requirement. Changing it stops current/pending work immediately; after the save acknowledgement, resume explicitly. Status shows its bounds/resource. Configuration does not install mining or grant server edit permission.

Mine executes a single scoped log break using owned hotbar contents, native survival timing, and tool wear. It requires explicit server edit policy opt-in, active paid activity, an unexpired resource claim, and a matching work patch. A matching native break receipt completes the action; drop collection is not claimed. Stop cancels digging immediately; conflicting world changes block for reconciliation without replay.

Missing, foreign, wrong-version or unverified state blocks for reconciliation; it is never replaced with an empty inventory. A saved dead body (a crash during the one-second respawn window) also blocks. Blocked/incomplete records keep their slot until recovery/removal is implemented. Autonomous work beyond the Nether-portal goal above remains disabled, and the goal itself is not qualified for unattended worlds. Default /aiplayer create still uses the legacy body; no saves are converted. Full client and restart recovery qualification remains open.

Legacy owner/mode/scope fields preserve access and saves; they are not the new product interface. Native creation has its development cap; team management and legacy migration remain unfinished. Combat/duels reject until qualified native skills use the arbiter. Timezone changes reject until safe accounting transitions exist; spent activity is not reset.

Inference is optional and currently disconnected. Planned Claude/OpenAI keys/budgets belong in private operator configuration, never memory/logs/source. No Jev.

Report exact build, seed/difficulty, IDs, commands and native before/after evidence. Stop and snapshot ambiguous actions; do not recreate missing items.
