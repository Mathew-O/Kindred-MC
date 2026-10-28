<p align="center">
  <img src="assets/banner.png" alt="Kindred MC: Kindred, Sol and Vesp standing on a grass block under a blue sky" width="100%">
</p>

<p align="center">
  <b>Three AI players who play Minecraft survival like real players.</b><br>
  They punch trees, craft tools, mine, smelt, build and fight, and earn every block on the way to the Ender Dragon.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=for-the-badge" alt="Minecraft 1.21.1">
  <img src="https://img.shields.io/badge/Mod%20loader-Fabric-DBD0B4?style=for-the-badge" alt="Fabric">
  <img src="https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge" alt="Java 21">
  <img src="https://img.shields.io/badge/Status-In%20development-F2C94C?style=for-the-badge" alt="Status: in development">
</p>

---

## 🎮 What is this?

**Kindred MC** is a mod for Minecraft Java servers. It adds AI players that join your world like real people: they show up in the player list, have their own skins and inventories, get hungry, take fall damage and respawn when they die.

**No cheats. Ever.** They don't get free items, teleport or see through walls. Everything they have, they found, mined, crafted or smelted themselves, by the same rules you play by.

The big goal: **three AI players start with empty inventories and beat the Ender Dragon together**, while you play alongside them or just watch.

---

## 👋 Meet the crew

<table>
  <tr>
    <td align="center" width="33%"><img src="assets/faces/kindred-full.png" alt="Kindred" height="160"><br><b>Kindred</b><br><i>Heart &amp; Homestead Anchor</i></td>
    <td align="center" width="33%"><img src="assets/faces/sol-full.png" alt="Sol" height="160"><br><b>Sol</b><br><i>Solar Vanguard &amp; Expedition Pioneer</i></td>
    <td align="center" width="33%"><img src="assets/faces/vesp-full.png" alt="Vesp" height="160"><br><b>Vesp</b><br><i>Evening Star Artisan &amp; Deep Scholar</i></td>
  </tr>
  <tr>
    <td>The homebody. The most helpful and chatty of the three, happiest farming and keeping the base going.</td>
    <td>The bold one. First to set off, last to back down from a fight, and not big on caution.</td>
    <td>The deep thinker. The most curious and the best miner, and careful about it.</td>
  </tr>
</table>

Their personalities are numbers in the code (initiative, caution, curiosity, helpfulness, farming, mining and so on), and they nudge what each player prefers to do. You can give them other skins too (see the cheat sheet below).

---

## ⛏️ What they can do

> 🚧 **Work in progress.** Most of this works in the automated tests and in test worlds, and is still being proven in long real games. Use a test world and keep backups.

- 🪵 **Start from nothing:** logs, a crafting table and wooden tools, then stone, then iron smelted in their own furnace, then diamonds.
- 🔥 **Get to the Nether:** obsidian made from lava and water, flint and steel, and a lit portal, all earned.
- 🟣 **Head for the End:** blaze rods, ender pearls and eyes of ender. They throw the eyes to triangulate and dig down to the stronghold.
- 🐉 **Fight the dragon:** shoot the crystals with their own bow, open the caged ones and fight the dragon at its perch. This only happens if the server owner allows it.
- 🏠 **Build:** huts, cabins, houses, manors, walled keeps, farms with fields, animal ranches and villager trading halls, all from blocks they gathered.
- 🍖 **Look after themselves:** eat when hungry, sleep at night, run from creepers and lava, break long falls with a water bucket, and go back for their stuff after dying.
- ⚔️ **Play games with you:** duels, speedrun races, manhunts and build battles.
- 🤖 **Optional AI advisor:** plug in a Claude or OpenAI key for smarter planning on long goals. It's off by default, and everything above works without it.

---

## 🗺️ Game modes (where it's heading)

| Mode | The idea |
| --- | --- |
| **Co-op Survival** | Play a normal survival world with them: bases that grow, farms, enchanting, and boss fights when you're ready. |
| **Manhunt** | Runners race to beat the game while hunters gear up and chase them. Humans and AIs on either side. |
| **Speedrun** | A random seed, and a race to the dragon using the tricks real speedrunners use. |
| **Builder** | A creative build battle on a random theme, judged by votes, minigame-server style. |
| **Factions** *(optional)* | Everyone for themselves: hidden bunkers, walled bases, scouting, and only players who joined are fair game. |

---

## 🚀 Quick start (for server owners)

There's no download yet, so you build the mod yourself (it takes a couple of minutes):

1. **Get the tools:** [Java 21](https://adoptium.net/) and a Minecraft **1.21.1** server running **Fabric Loader 0.19.5** with **Fabric API 0.116.17+1.21.1**.
2. **Build it:** in this folder, run `./gradlew build` (on Windows: `gradlew.bat build`).
3. **Install it:** copy `companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar` into your server's `mods` folder and start the server.
4. **Optional:** put the same JAR in your own game's `mods` folder too. That adds the **K** menu and lets you see the crew's skins. The mod is built so friends with plain vanilla Minecraft can join too (that part is still being tested).
5. **Say hi:** in game, as an operator:

```text
/aiplayer edits on                  let AI players break and place blocks (off until you say so)
/aiplayer roster spawn Kindred      spawn Kindred next to you
/aiplayer goal nether Kindred       watch Kindred earn its way to a lit Nether portal
```

> 💾 This is a development build. Use a world you don't mind losing, and back up your saves.

---

## ⌨️ Cheat sheet

Press **K** in game (with the mod on your client) to open the AI player menu, or use commands. `[bot]` means you can name a player (Kindred, Sol or Vesp); leave it out if there's only one.

| Command | What it does |
| --- | --- |
| `/aiplayer roster spawn <name>` | Spawn Kindred, Sol or Vesp beside you |
| `/aiplayer edits on` / `off` | Allow or stop all AI block breaking and placing |
| `/aiplayer goal nether [bot]` | Earn a lit Nether portal from scratch |
| `/aiplayer goal end [bot]` | Go on to find the End portal |
| `/aiplayer dragonfight on` then `/aiplayer goal dragon [bot]` | Allow the dragon fight, then go for it |
| `/aiplayer goal none [bot]` | Drop the current goal |
| `/aiplayer build cabin <x y z> [bot]` | Build a cabin there (also `hut`, `house`, `manor`, `keep`) |
| `/aiplayer ranch cow <x y z> [bot]` | Build a pen and breed a pair of cows (or `sheep`, `pig`, `chicken`) |
| `/aiplayer match duel <first> <second>` | Set up a duel (also `speedrun`, `manhunt`, `build`) |
| `/aiplayer skin <name> [bot]` | Dress a player in a skin from `config/kindred/skins` |
| `/aiplayer limit cap <hours> [bot]` | How many hours a day a player may be active |
| `/aiplayer limit window 18.00 23.00 [bot]` | Only let a player be active between those hours |

The full list, with every option, is in the [operator guide](docs/operator-guide.md).

---

## 🧠 How it works (no jargon)

- **Real players, not mobs.** Each AI joins the server as an actual player. Same hunger, same mining speed, same tool wear, same everything.
- **A brain with a to-do list.** Each player keeps one list of things it could do and picks the most important: staying alive first (food, sleep, escaping danger), then its goal, then building.
- **Plans from what it has seen.** It only knows about the ores, trees and structures it has actually spotted, like you.
- **A notebook for every important action.** Before it breaks, crafts or moves items, the mod writes down what it's about to do. If the server crashes halfway, nothing gets duplicated and nothing silently vanishes.
- **Off-switches everywhere.** Server owners decide whether AIs may edit the world, fight the dragon, build their own bases, how long they play each day and at what hours.

New here? The [**start-here guide**](docs/README.md) explains the project, the folders and the words we use, in plain language.

---

## 📦 What's in this repo

| Folder | What's inside |
| --- | --- |
| [`companion-core/`](companion-core) | 🧠 **The brains.** Decisions, plans, recipes and build plans. It contains no Minecraft code, so it's easy to test. |
| [`companion-server-adapter/`](companion-server-adapter) | ✋ **The hands.** The actual Fabric mod: the players' bodies, the commands, the K menu, and the in-game tests. |
| [`companion-scheduler/`](companion-scheduler) | ⏰ **The clock.** Daily play limits, hours and time zones. |
| [`companion-persistence/`](companion-persistence) | 💾 **The memory.** Saves everything to a small database file. |
| [`companion-model/`](companion-model) | 🤖 **The optional advisor.** Talks to Claude or OpenAI when a key is set. |
| [`companion-testkit/`](companion-testkit) | 🧪 **Demos and test helpers.** |
| [`docs/`](docs) | 📚 **Guides and design notes.** Start with [docs/README.md](docs/README.md). |
| [`assets/`](assets) | 🎨 **Art.** This banner, the crew's skins and the script that draws the banner. |
| [`tools/`](tools) | 🔧 Helper scripts for checking test reports. |

*Why "companion" in the folder names? That's left over from an earlier version of the project, when these were pet-like companions. The names stay so old saves keep working.*

---

## 🧪 Is it finished?

Not yet. It works in automated tests: hundreds of little test worlds the game builds to check one thing at a time. It's being proven in longer real games, and it isn't ready to run a survival server unattended. Progress is tracked feature by feature in the [tracker](docs/feature-spec.md).

---

## 🛠️ For developers

You'll need Java 21. The Gradle wrapper fetches everything else at the exact versions in [`release-manifest.json`](release-manifest.json).

```bash
./gradlew build                                   # compile everything and run the unit tests
./gradlew :companion-server-adapter:runGametest   # run the in-game tests (starts a real test server)
./gradlew :companion-testkit:run                  # a quick demo of the core pieces
```

The technical documents:
- [The player contract](minecraft_player_spec.md): what the AI players must do and must never do.
- [Architecture](docs/player-architecture.md): how the pieces fit together.
- [Tracker](docs/feature-spec.md): every feature, its status and its evidence.
- [Operator guide](docs/operator-guide.md): everything a server owner can set.
- [Validation](docs/validation.md): test results, run by run.
- [AGENTS.md](AGENTS.md): the rules for anyone (human or AI) working on the code.
