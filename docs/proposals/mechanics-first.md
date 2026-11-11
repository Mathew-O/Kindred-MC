# Mechanics first: the order of work for the next two weeks

Status: a reordering proposed 2026-11-09, from the ladder's own numbers. It changes who works on what, not what
is built; the Factions and World Record tracks continue beside it.

## What the numbers say

Four ladder iterations (4 November) moved the build a lot and rung a not at all: stuck time under a tenth of the run on
**1 of 10 seeds in every iteration**; stuck spells 8 to 22 a run; the latest ten seeds still at 42 stuck minutes of 120.
Deaths are about 25 an iteration, and they are one kind of death: **24 of 28 below ground, 18 of 28 in the dark
(light 0 or 1), 19 of 28 while fighting back, zombies most of all.** The stuck spells are a few loops: the water loop
alone was 70 of 194 spells in iteration 4, and one refused walk was asked for 9,792 times.

Neither is a planning problem. Both are the body: how it moves, and how it avoids. Every other track inherits them:
an AI faction that dies in its own mine or stands in a flooded shaft for forty minutes is not playing Factions, and
the month run would record exactly that. Movement and avoidance are also the multiplier on development itself: a
run that is not stuck for a third of its length finishes its stages, so each ten-seed batch tells us more.

## The change

Keep the lead's loop (one cause a batch, measured on ten seeds) but run it on **three causes in parallel**, each with
its own session and its own class of cause, with the runner scoring all of them. FACTIONS A and COMMS B stay on their
tracks: they are on the deadline and independent. MODES and CI GREEN are re-pointed to mechanics until the exit
criteria below are met; UNSTUCK stays on movement. The organic work (E-54, E-33b, E-55, E-33c) waits, except gap
jumps and ladders, which are mechanics.

| Session | Class of cause | Backlog, in order of measured frequency |
| --- | --- | --- |
| UNSTUCK → **LOOPS** | Repeats and loops | A refused or failing target set aside for ten minutes, globally, so one request never becomes thousands; the water loop (F-25c4ay) closed; the local step executors of F-29c used by *every* walk as the fallback when the global planner says "no walkable route", not only by rescues; route caching while the goal and ground are unchanged. |
| MODES → **MOVEMENT** | "No walkable route" | One- and two-block gap jumps; ladders, vines and scaffolding as climbable; swimming up on purpose; out of a two-deep hole by jump and step without placing; a lateral choice on open ground so a blocked line is not retried on the same blocks. Each a navigator move with a fixture, then the engine. |
| CI GREEN → **SURVIVAL** | Dark, underground, fighting back | Torches as it goes as default behaviour below light 7 when it holds or can make them (E-46's habit, on by default); a retreat threshold that fires earlier underground (two hostiles in the dark, or health under half with no shield: leave and seal one block behind); no caving before iron armour and a shield unless the plan needs it; breaking line of sight from a skeleton with a placed block; six blocks from a creeper, then behind a block; in a hole or shelter by dusk unless a bed is near (F-25c9a) as the default, not a habit; wary walks (F-10g) on every walk, not the Nether goal's alone; the atlas's danger map weighting night routes everywhere. |
| LEAD | The ladder's measurement, and the causes nobody owns | Keeps the batches, the scoring and the merges; takes whatever the summaries show that is none of the above. |
| RUNNER 2 | Scoring | Ten seeds a batch, three batches a day, one per session's build, back to back. |

Each session: reproduce with a death replay or a scene fixture that fails first; fix the rule; three of three alone;
one ten-seed batch against the previous build; a pull request; the next cause. Terse evidence. No new rules for
anything the summaries do not show.

## Exit criteria

On ten seeds at two game hours, the earned scenario, no model:

- stuck time under a tenth of the run on 7 of 10 (today 1 of 10);
- deaths an hour, team median at or under 0.5 (today 1.25 to 1.75);
- iron pickaxe median under 30 game minutes (today 32 to 44);
- a lit portal on 7 of 10 (today 5 of 10).

When they hold on two consecutive batches, MODES and CI GREEN go back to their tracks and the organic work starts.

## Why this and not more features

The features in flight are good and most will land anyway. But a feature built on a body that gets stuck a third of
the time and dies in its own tunnels is a feature that will look broken in the month run. Two weeks of mechanics
makes every later week worth more, including Factions, whose AI players will fight, mine, flee and claim with this
same body.
