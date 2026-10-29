# 📖 Start here

New to Kindred MC? This page explains the project, where things live and the words we use, without assuming you write code.

## The project in one breath

Kindred MC is a Minecraft Java mod (Fabric, 1.21.1) that lets you add **a player of your own** to a server. It joins like a real person and plays survival by the same rules, with no free items, no teleporting and no seeing through walls. You name it, skin it and tune its personality; or, to get going fast, spawn one of three starters: Kindred, Sol or Echo. The goal is for it to start with nothing and beat the Ender Dragon.

## Read in this order

1. **[The main README](../README.md):** what it is and how to try it.
2. **This page:** the words and the map.
3. **[Operator guide](operator-guide.md),** if you run a server: every command and switch.
4. **[The player contract](../minecraft_player_spec.md):** what a player must always do, and must never do.
5. **[The tracker](feature-spec.md):** every feature, whether it's done, and the proof.

## 🗣️ Words we use

| Word | What it means |
| --- | --- |
| **Player** (or *bot*) | A player the mod runs, as opposed to a person. You can make your own (`/aiplayer native create`); Kindred, Sol and Echo are the three ready-made *starters* (the *roster*). Echo was called Vesp until 28 October 2026. |
| **Native player** / **native body** | A player that joins the server as a real player entity, with a real inventory, hunger and so on. This is the one that matters. |
| **Legacy companion** | An older, mob-like body from an earlier version of the project. It's kept only so old saves load. Anything named "companion" in the code comes from that era. |
| **Goal** | A big aim a player works towards on its own, like "light a Nether portal" or "beat the dragon". |
| **Skill** | One concrete thing a player does: walk somewhere, break a block, eat, craft, place a block. A goal is a long chain of skills. |
| **Recorded action** | A skill that changes the world or the inventory (breaking, crafting, placing…). It's written down *before* it happens and checked *after*, so a crash can never duplicate or lose items. |
| **Ledger** | The notebook those actions are written in. |
| **Held for reconciliation** | The player paused itself because something uncertain happened (say, the server crashed mid-action). A human checks the world, then lets it carry on. It's the safe choice: it never guesses. |
| **Edits switch** | `/aiplayer edits on|off`. While it's off, your players never break or place blocks. It starts off. |
| **Activity allowance** | How long a player may be active each day (the *cap*), optionally only between certain *hours*, counted in a chosen *timezone*. Each day is a *period*. Time is paid for in small *slices* of a few seconds, booked before the player acts. |
| **Strategist** | The optional planning helper (Claude or OpenAI) a player can ask for advice on long goals. Off unless a server owner adds a key. With it on, the same model also gives a player its **voice**: a short reply in its own words when someone talks to it, told only what that person may know (`KINDRED_VOICE=off` keeps the fixed replies). Whoever may command a player can also ask it in chat to come, wait, stop or toss them something, done exactly as the matching command. |
| **Mode** | A way to play: Co-op Survival, Manhunt, Speedrun, Builder, or Factions. |
| **Match** | One round of a mode, like a duel or a manhunt. Players the mod runs only attack people inside a match those people joined. |
| **Unit test** (JUnit) | A quick automatic check of one piece of logic, without starting Minecraft. |
| **GameTest** / **fixture** / **native fixture** | An automatic check that starts a real Minecraft test server, builds a tiny world and watches a player do one thing (say, "break obsidian and pick it up"). |
| **Suite run** | Running all of the GameTests (over 350 of them) in one go. |
| **Live run** | A long real game on a real world, watched and logged. |
| **Seed** | The number that generates a Minecraft world. Testing on fixed seeds means results can be repeated. |
| **Evidence** | The write-up that proves a feature works: which tests passed, on which build. These live in [`evidence/`](evidence). |

### What the feature IDs mean

Everything in the [tracker](feature-spec.md) has an ID. A child gets a letter on the end: **F-11e** is part of **F-11**.

| Prefix | Covers | Example |
| --- | --- | --- |
| **F-** | Foundations: the body, movement, actions, limits, saving. | F-10 movement, F-11 activity limits |
| **E-** | Gameplay: survival, combat, building, travel, villagers. | E-07 combat and hazards, E-39 the End fight |
| **M-** | Game modes and matches. | M-02 Manhunt, M-04 Builder |
| **D** | Decisions the project owner made. | D4: world edits start switched off |

## 🗺️ The map

### The code

| Folder | Nickname | What it does |
| --- | --- | --- |
| `companion-core` | The brains | Decides what to do and plans how: recipes, routes, build plans, the to-do list. Contains no Minecraft code, so every piece can be tested quickly. |
| `companion-server-adapter` | The hands | The Fabric mod itself. It connects the brains to Minecraft: the players' bodies, every skill, the commands, the K menu, and the GameTests (in `src/gametest`). |
| `companion-scheduler` | The clock | Daily play limits, hours, timezones and the small paid slices of time. |
| `companion-persistence` | The memory | Saves players, plans, limits and the action ledger to a small SQLite database file. |
| `companion-model` | The planning helper | The optional link to Claude or OpenAI. |
| `companion-testkit` | The demo bench | Small runnable demos and test helpers. |

Inside each module the Java code lives under `src/main/java/dev/kindred/…`, and its tests under `src/test/java`. Most files start with a short comment saying what they do, in plain words, and the rest are getting one.

### The docs

| If you want… | Read |
| --- | --- |
| To run a server with your players | [Operator guide](operator-guide.md) |
| What a player promises to do | [Player contract](../minecraft_player_spec.md) |
| How the code is put together | [Architecture](player-architecture.md) |
| What's done and what isn't | [Tracker](feature-spec.md), [implementation status](implementation-status.md) |
| Proof that things work | [Validation](validation.md) (test runs), [`evidence/`](evidence) (one file per feature), [live qualification](live-qualification.md) |
| Plans for bigger features | [Building](building-plan.md), [combat](combat-plan.md), [`proposals/`](proposals) |
| How the players behave and talk | [Personality](personality-preferences.md), [conversation style](conversation-style.md), [social behaviour](social-behavior.md), [relationships](rapport-and-friendship.md), [mannerisms](mannerisms-and-autonomy.md) |
| Ideas for later | [Outings and hobbies](adventures-and-hobbies.md), [sharing and discoveries](gifting-and-discoveries.md), [naming and pets](naming-and-pets.md), [progression](independent-progression.md), [building design](architectural-design.md) |
| Minecraft mechanics and performance notes | [Optimization and quality-of-life guide](optimization-and-qol-guide.md) |
| A test world to try | [Sky island](worlds/sky-island/README.md) |

## ✅ How a feature gets done

1. **Claim it.** Someone writes their name next to its ID in the tracker, so nobody else works on the same thing.
2. **Build it.** The rules in [AGENTS.md](../AGENTS.md) apply, the big one being *earn everything*.
3. **Test it,** three ways: unit tests for the logic, GameTests in a real test server, and where it matters, live runs in real worlds.
4. **Write the evidence:** what was tested, on which build, and what passed.
5. **Tick it off** in the tracker, with the date and a link to the evidence.
