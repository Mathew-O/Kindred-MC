# Adaptive combat and PvP: plan

The owner's [design proposal](proposals/adaptive-combat-design-spec.md) (version 0.1, 22 October 2026) set out a combat system with three separate targets: mechanical competence, tactical adaptation and resourceful survival play. It was written without looking at this codebase. This plan reconciles it with what Kindred already has and with the [player contract](../minecraft_player_spec.md), and tracks it as E-41 in the [tracker](feature-spec.md).

## Where the proposal and the contract meet

The proposal's architecture fits the contract almost point for point:

| Proposal | Here |
| --- | --- |
| One final action owner per player (§3.2, §5.3) | One `ActionArbiter`, `SkillRunner` and cancellation barrier; combat cannot own its own body controller |
| Only the adapter commits actions; stale plans never replay (ARCH-02, ARCH-05) | World access only on the adapter's server thread; recorded skills never replay after cancellation or restart |
| Player-equivalent observations (§2.4, §4) | Core sees immutable observations, never Minecraft objects; no hidden-resource omniscience |
| No direct health changes, teleports or free items (§5.1) | Earned progress only: no free gear, copied items or teleport shortcuts |
| An asynchronous advisor that cannot grant itself capabilities (§11) | Optional model APIs in `companion-model`, no per-tick inference, no generated executable code |
| Attack and defense from the real ruleset (§2.1) | Java 21, Minecraft 1.21.1 and Fabric pinned in the manifest; native server players use vanilla's own combat |

Two contract rules shape the proposal's PvP:
- **Players are attacked only inside a match they joined** (MODE-03, D7). Duels and benchmarks run as matches whose participants joined, AI against AI or with humans who joined. Outside a match the body refuses player targets, as it does today.
- **Modes share one runtime** (MODE-01). Duels need the match framework (M-00); the first duel match is a small part of it, not a second runtime.

## Decisions the proposal leaves open (§20)

| Decision | Answer from this codebase |
| --- | --- |
| Combat ruleset | Vanilla Java 1.21.1 under Fabric Loader 0.19.5 and Fabric API 0.116.17, no combat mods |
| Control backend | The native server player (F-25b): vanilla movement, collision, attack cooldown, critical hits, knockback, shields, damage immunity and item use, with an adapter that must not exceed a client's reach or sight |
| Observation policy | What a player could perceive: visible entities in line of sight, held items and worn armour, a raised shield. An opponent's health and inventory stay unknown or estimated from hits the player saw land |
| Deployment | In-process and deterministic on the server thread, with bounded work; no inference worker yet |
| First benchmark | Melee duels and disengagement between AI players in a match |
| Training data | None yet; demonstrations wait for an instrumented recorder and players to record |
| Strategy model | The provider-independent strategist bridge (F-19b), with a new bounded combat proposal schema |
| Desired style | Resourceful survival play, measured separately from duel strength |

## What exists

- **Mechanics, as fixtures.** Vanilla attacks with the cooldown, weapon choice by damage (E-39e), critical hits by jumping, shields raised against melee, shooters and creepers, and fights that stop at lava or edges, hit-and-run against creepers, and a fight broken off when badly hurt. Also the water-bucket clutch (E-07d), pillaring out of reach (E-07e) and stairs (F-10h). All are against mobs.
- **Tactics.** A read-only tactical observer and priority policy (E-07b), a damage model (E-39e), mob senses (F-10g) and one combat controller per goal that commits to a fight or a retreat.
- **The advisor's channel.** The strategist (F-19b, F-19c), asynchronous and optional.

## What is missing, by milestone

**Milestone 0, the ruleset and the adapter (E-41a).**
- The combat ruleset written down: a manifest entry and a core class of 1.21.1's numbers, each with a test or a fixture. That covers the attack cooldown, critical-hit conditions, sweeping, damage immunity, knockback, an axe disabling a shield, reach and food.
- Reach and sight as a client has them. Today a strike reaches four blocks from centre to centre (the Dragon strike already uses vanilla's three to the hitbox).
- A combat observation in core under an observation policy: own state, opponents as estimates with age and confidence, and confirmed events. Today the tactical observer reads a mob's exact health, which no player sees.
- Parity fixtures for every mechanic the controller relies on, including the ones not yet covered (damage immunity, a shield disabled by an axe, sprint knockback).
- An encounter recorder: observations, decisions and confirmed outcomes in a bounded buffer, written off the server thread.
- Scenario resets: arenas the fixtures build and clear, with fixed equipment.

**Milestone 1, competent melee (E-41b).**
- A duel match: two participants who joined, player targets allowed between them only while it runs.
- The body's player-target refusal lifted only for a participant in a running match.
- Melee skills (spacing, timed strikes, strafing, shield, retreat, eating to recover) under one fight skill with commitment and a switching margin.
- A declared baseline opponent, the mechanics suite and a baseline-duel benchmark (the proposal's 60% over 200 matches), run apart from the regression suite.

**Milestones 2 to 5 (E-41c to E-41f).** Opponent tendencies and short prediction; combat-aware routes, cover and pursuit; learned execution (offline); the advisor's combat proposals and cooperative combat. Each keeps the proposal's ablations: the same benchmark with the component off.

## Gates

The proposal's release gates (§16.3) stand as written. No gate is claimed until its benchmark has run, and mechanical competence, adaptation and resourceful play are reported separately.
