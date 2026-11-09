# Cobblemon: a player that catches, raises and battles

Status: proposal, 2026-11-09. E-57 and its children E-57a to E-57e are reserved in the
[tracker](../feature-spec.md). Depends on F-35, F-36 and above all F-37's integration entrypoint
([mod support](mod-support.md)). Cobblemon is a Fabric and NeoForge mod for Minecraft 1.21.1 (its 1.6 line),
written in Kotlin, licensed MPL-2.0, with a public API and events for spawning, capture, battles, parties and
storage. The session that builds this verifies the API against the pinned Cobblemon version first; the shape below
is the design, the names are to be confirmed.

## Why it is its own spec

Cobblemon changes what playing is. Battles are turn-based and run through the mod's own battle system, not through
swings; captures are a throw whose result the mod decides; Pokémon are entities that are neither hostile mobs nor
livestock; progress is a party's levels, not a pickaxe's tier. None of that is expressible in a profile. It is the
first and the clearest case for an **integration**: `integrations/kindred-cobblemon`, compiled against Cobblemon's
API, loaded only when Cobblemon is present, registering skills, needs, event kinds, a HUD section and a voice
vocabulary through F-37's services, and nothing else.

## Earn everything, in Cobblemon's terms

- The starter Pokémon is the one thing the mod gives every player for nothing, humans included: an announced rule
  identical for everyone (D6), so an AI player takes its starter the same way, through the mod's own starter flow.
- Poké Balls are crafted from apricorns it grew and copper it mined; nothing is spawned.
- Captures, levels, evolutions and items come from play: wild encounters, battles, berries it farmed.
- Healing uses a healing machine it crafted or a public one; the PC it crafted or a public one.
- No Pokémon is taken from a person; no battle with a person except one they accepted in their own words, as a
  match (D7 in spirit: a battle is a consented match, never an attack).
- A wild Pokémon is never attacked as a mob for its drops; the player catches or walks on.

## E-57a Coexistence

Before any new skill: with Cobblemon installed, every existing behaviour is right. Pokémon are classed by the
integration as `pokemon` (not hostile, not livestock): never attacked, never fled from unless they are hurting it
(a few species can), never penned, never fed as cattle; their spawns and the pack's biomes read as content; the
mod's blocks (healer, PC, apricorn trees, berry bushes) as content with a profile naming them. The evaluation's
vanilla scenario runs with Cobblemon installed and reaches the same stages.

**Acceptance.** The three-seed vanilla scenario with Cobblemon installed within the vanilla ranges; a fixture that a
wild Pokémon beside a working player is neither struck nor fled from; the full native suite passing with the mod
installed.

## E-57b Catching

- **Apricorns and balls.** Apricorn trees found and farmed (saplings by the mod's tags; harvest when ripe; the
  atlas remembers where), copper mined, balls crafted through the recipe manager (the mod's recipes are ordinary).
- **The throw.** A `native_throw_ball` recorded skill: aim at a target Pokémon within range, throw through item use
  (the mod handles the capture roll), wait the mod's capture sequence, read the result from the mod's capture event.
  A miss is a lost ball, as for anyone.
- **Whom to catch.** `CatchChoice` in the integration's pure core: by what the party lacks (type coverage), level
  relative to its party, species it has not got, rarity as the mod reports it, and personality (curiosity: new
  species; combativeness: strong ones; farming: grass and bug types first, as a joke that fits). Never a Pokémon
  another player has sent out or is battling.
- **The goal.** `GOAL cobblemon` (ask in chat: "Echo, go catch some Pokémon") as a standing goal beside the Nether:
  farm apricorns, make balls, roam the atlas's frontier for species it wants, come home at dusk.

**Acceptance.** Fixtures: apricorns harvested and replanted; balls crafted; a throw that the mod reports as a capture
puts the Pokémon in the party and the ball is gone; a miss loses the ball and nothing else; `CatchChoice` in JUnit;
the goal running on the engine for two game hours with catches counted and no ball unaccounted for.

## E-57c Battling

- **Starting a battle.** Wild: send out its lead and challenge (the mod's API starts the battle). A person: only
  after their "I'm in" to a battle it proposed or they asked for, as a match in `MatchRegistry` so the lobby, clock
  and result presentation apply; no battle outside a match.
- **Choosing moves.** `BattleBrain`, pure: the mod exposes the type chart, each move's type, power, accuracy and
  effects, and both sides' visible state. The brain picks by expected damage with type effectiveness, switches when
  outmatched, uses items it holds (potions it crafted or found) under rules, and never cheats: it reads only what the
  mod shows a player (no hidden stats, no opponent's unrevealed moves). Personality tilts it: cautious players
  switch and heal earlier; combative ones press.
- **Turn pacing.** A turn is answered after a human-like pause (the speech timing's cousin), so a person battling
  it is not answered instantly.
- **Losing.** A fainted party goes to the healer, as the mod requires; the player says so and goes.

**Acceptance.** `BattleBrain` in JUnit against staged type and move data (effectiveness, switching, item use,
hidden information never read); fixtures: a wild battle fought to a win and the experience recorded; a battle with a
mock person starting only after "I'm in" and presented as a match; a loss followed by the walk to a healer; no
battle ever started against a person without consent (asserted).

## E-57d Care

Healing at a machine when the party is hurt (a need, data: `HEAL_PARTY`, satisfied by a healer in reach); PC use to
store what it will not carry and to keep a balanced party; evolution handled (the mod's event; the player says
something); berries farmed for held items; names for its Pokémon from `KitNames`' rules (E-16d), in its own temper,
and kept; the party in the peer ledger (E-50) so teammates know what it has and the voice can talk about it.

**Acceptance.** Fixtures per step; the party's state surviving a restart as the mod stores it.

## E-57e Trainer play

A trainer among people: proposes battles when idle with a person near (the attention budget, E-53); answers
questions about its team and theirs (the voice, told what it may know: its own party fully, another's only what
the mod shows on screen); gives a "gg"; a Cobblemon section in the HUD (party with levels and health; the last catch;
the last battle); battles between AI players as matches people can watch; a league ladder as a standing of wins in
the World Record.

**Acceptance.** The HUD section from a staged payload; a proposed battle refused and accepted; the voice answering
about its party; an AI-versus-AI battle presented as a match.

## Order and cost

E-57a (half a session, after F-37), E-57b (one), E-57c (one to two: the battle brain and the API wiring), E-57d
(half), E-57e (half). Three to four sessions in all. Plausibility: coexistence 9, catching 8, battling 7 (the API
is the risk: if it does not expose what a player sees of a battle in a way a program can answer, the brain waits
for it), care 8, trainer play 8. Cost: moderate; no model is needed for any of it, and a battle turn is a lookup, not
an inference.
