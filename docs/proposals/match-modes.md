# Manhunt, Speedrun and Builder: the same depth as Factions

Status: proposal, 2026-11-10. IDs M-02d to M-02h, M-03b to M-03e and M-04b to M-04e are reserved in the
[tracker](../feature-spec.md). The framework (M-00), the duels and the presentation are done; the modes have their
core loops in fixtures and not yet a hunt, a race or a battle played through with people. This is what each needs
to be a mode people come back to.

---

## Manhunt

Today (M-02a..c): runners and hunters, the announced compass (D6), hunters that gear up and follow the needle,
runners at a reliable pace that keep their distance and fight only when caught, evasion that stays alive. What is
missing is what makes manhunt a *game*: prediction, deception, teamwork and talk.

### M-02d Hunters that think

- **Prediction, not pursuit.** A runner's compass position over time gives a heading and a speed; hunters aim ahead
  (`Pursuit` in core: where will it be in thirty seconds), and read the terrain: a runner heading for lava is going
  for obsidian, one heading down is going for iron, one at a village is trading. Each is a place to meet it.
- **Splitting up.** Two or more hunters divide: one on the needle, one ahead to the likely goal, one at the portal
  when the runner is in the Nether (the compass points at the portal they left by: the camper). `HuntDivision`
  like `RunDivision`.
- **Gearing by the clock.** Iron and a shield before the first engagement; a bow when the runner has one; water
  buckets; blocks; food from the runner's trail (its farms, its chests: fair game in a manhunt, the rule announced).
- **Engagement rules.** `FightOdds` decides whether to fight or hold: a hunter does not duel a runner with better kit
  alone; it waits for the second hunter, blocks the way, or burns the runner's time (breaking its portal, flooding
  its tunnel). Fire and lava used as tools, never griefing outside the match.
- **The end game.** At the stronghold: a hunter stands on the portal; in the End: the fight at the fountain.

### M-02e Runners that outwit

- **Speed over safety, within limits.** The runner's pace is the speedrun playbook (F-25c8) under a risk cap that
  rises when hunters are far and falls when they are near (the compass tells it where they are only if it has one:
  it reads the Wire's `side` channel for what its teammates saw, else infers from being chased).
- **Deception.** Doubling back along water (the compass lags), a decoy portal lit in the wrong place, leaving a trail
  the hunters follow into a dead end, a tower pillared and the blocks taken, a bed trap in the Nether, a nether
  portal lit and left while it travels by another, a pearl at the right moment. Each a `Ruse` with a cost and a
  chance, chosen by personality (a cautious runner hides; a bold one taunts).
- **Nether skipping.** Enter the Nether early to lose the overworld compass, travel eight to one, come out behind.
- **Resource denial.** Taking the only boat, the village's beds, the obvious lava pool.

### M-02f Talk and mind games

The Wire's `side` channel for each side's own plans ("I'll hold the portal, you flank"), `public` for what manhunt
is known for: the taunt, the bluff ("I'm in the Nether", when it is not), the gg. Taunts under the expression preset
and the recipient preferences (E-18a/E-19a); a runner's bluff is a `Ruse` like any other, spoken by the voice; a
hunter may believe it or not by trust (E-49's wariness reads bluffs that cost it).

### M-02g Variants and fairness

Rules a match may set when proposed, announced to all (D6): compass on or off, a head start in minutes, a runner
count, assassins (hunters with one life), a time limit, the runner's win at the dragon or at the End portal. Teams
mixed of people and AI players on either side; an AI runner against human hunters has its pace capped to the
humans' as M-01e caps base stages, so it is beatable, and says so in the rules.

### M-02h The hunt gate

Three hunts played through with people on both sides, recorded under the engine: a human-joined hunt as runner,
one as hunter, one mixed; AI-versus-AI hunts on three seeds with the result and the duration. Passing: no strike
outside the match, the compass rule kept, the hunt ending by the rules, and a person saying afterward it was a
hunt and not a chase.

---

## Speedrun

Today (M-03a): races from a fresh start, splits at vanilla's advancements, the first to free the End wins; the
playbook of ways (F-25c8) chosen by cost. What is missing is what speedrunners actually do.

### M-03b The route

The playbook's ways as a race knows them: a lava-cast portal in the first minutes; a village for beds and food;
bastion bartering for pearls or a fortress for blaze rods by what the seed gives; eye throws from the portal exit;
the stronghold by triangulation with as few throws as its pearls allow; beds against the perched dragon (F-25c8's
way), the one-cycle when the ways allow. Each a stage with a target time from real runs, and the `Pace` that says
when the run is behind and should take the riskier way.

### M-03c Reading the seed

What a runner reads at spawn and acts on: the biome (a desert temple, a village, a ruined portal), the terrain for
lava, the first seconds' loot. The atlas's priors by biome (F-30) made race-sharp: "a ruined portal here means
obsidian and a lava pool nearby".

### M-03d Racing people

A race joined by people (the match framework has the lobby); splits on the public channel for all; the AI pace
capped or not by the race's announced rule; a handicap option (an AI racer starts five minutes late); the results
with a split table everyone can see, kept as a ladder in the World Record.

### M-03e The race gate

Ten AI races on random seeds with the time distribution reported against the ways taken; three races with people;
a ladder page on the watch page.

---

## Builder

Today (M-04a, done): plots, a theme, a timed build in creative, AI designs from the schematic library, a vote as a
tour, results. What is missing is taste, variety and people.

### M-04b Designs of its own

Not only schematics: a design generated for the theme from the building grammar (E-29a's `BuildStyle` grown:
massing, roof, materials, details), varied by personality so three AI builders give three builds, scaled to the plot
and the clock. The schematic library stays as inspiration and as the fallback.

### M-04c Judging with taste

A vote that is a judgement, not a coin flip: `Taste` in core scores a build on theme fit (does a "lighthouse" have a
light and stand by water), silhouette, palette coherence, detail density, use of the plot, and a personality tilt
(a farmer likes gardens); the voice explains its vote in one line. People's votes count the same as an AI's.

### M-04d Themes, rounds and people

Themes with constraints (a palette, a size, a material), rounds (speed build in five minutes; a long build in an
hour), teams of two, guess-the-build (one builds, the others guess the theme), and people as builders and judges
through the lobby. A gallery of past winners kept as schematics with attribution (M-05) at the hub.

### M-04e The battle gate

Ten AI battles across themes with the taste scores and winners recorded; three battles with people; the gallery.

---

## Order

Manhunt first (M-02d, M-02e, M-02f, M-02g, M-02h): it is the mode people know Kindred for before they know anything
else. Then Speedrun's route and seed reading (M-03b, M-03c), then Builder's designs and taste (M-04b, M-04c), then
the people-facing slices and gates. Roughly eight sessions in all; the hunter prediction and the runner ruses are the
two that need a careful hand.
