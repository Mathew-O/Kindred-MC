# Improvising within the gate: rescues that come earlier, go further, and become rules

Status: proposal, 2026-11-08. Children F-29b to F-29e are reserved in the [tracker](../feature-spec.md) under F-29;
claim one in Active work before implementing it. Builds on F-29 (rescue plans), F-27 (death replay) and F-26 (the
evaluation engine); see [the next levers](next-levers.md).

## Why

The ten-seed runs say where the project loses most of its time: not deaths (about 1.5 an hour) but **stuck spells**,
a median of 36 to 42 game minutes out of 120, up to 147. Every one of those is a situation no rule anticipated. The
project's split (rules for the body, a model for judgment) is right for fairness, cost and safety, but it has one
weakness a model-driven agent does not: rules cannot improvise. F-29 was the first concession to that, and its own
evidence says why it has not paid off yet:

- it fires late: three minutes of no progress, and only after the local "move elsewhere" has had its own turn;
- it gets one plan per spell, and a plan has no second chance: 4 of 31 plans got the player out;
- 7 of the 11 plans that stopped did so at a `walk` the global route planner refused ("No walkable route"), which is
  the very thing the player is stuck on; 4 stopped at a tool that could not break deepslate; one at reach;
- nothing learned from the 4 that worked: the next player in the same hole asks again.

This proposal keeps every rule of the gate (a plan is one to six installed skills, validated and revalidated at
dispatch; AI-02, AI-03) and changes four things: when the model is asked, what it can say, how a plan is carried
out, and what happens afterwards. The aim is that a rescue is a conversation with the situation rather than one guess,
and that each success becomes a local pattern so the model is asked less over time, not more.

## F-29b Earlier, with a report that answers the questions the plans failed on

**Trigger.** A rescue is wanted when no progress has been made for 60 seconds of game time (`RESCUE_AFTER_TICKS`, 1,200;
F-29 waited 3,600 and then the local relocation), while the player is not sheltering, waiting on purpose, resting,
fighting or in a match countdown. The local relocation still runs, in parallel, as the first "plan" the model is told
about (so the model does not propose the same heading). At most one rescue conversation per spell, and a spell is
counted once per five minutes as now.

**The report** (about 1,000 tokens), in addition to F-29's 9×9×5 slice, inventory, goal and last failures:

- *Where it can walk*: for each of the six ways, how far the local step executor (below) could go unobstructed, and
  what stops it (stone, water, a drop of N, lava, a two-high wall). Computed from the slice, not the global planner.
- *What it can break*: its best tool and the hardest block it harvests, so the model never proposes deepslate with a
  wooden pickaxe; the blocks within reach it cannot break are named.
- *What it can place*: the count of placeable blocks it holds, torches, water bucket, ladders, boat.
- *What the local tries did*: the relocation's heading and how it ended.
- *Light and time*: light at its feet, time of day, whether mobs are near; so the model can prefer "shelter and wait"
  when that is the right answer.
- *The spell so far*: the steps already carried out in earlier rounds of this spell and how each ended (F-29c).

**Acceptance.** JUnit for the report's walkability and tool facts from staged slices. A GameTest where a player is
stuck in the pit asks for a rescue within 60 seconds of game time with its local relocation still running, and the
report names the ways it can walk and the block it cannot break. The evaluation engine: the time from a spell's start
to its first rescue ask, median under 90 seconds on ten seeds.

## F-29c Rounds, a probe, and local step executors

**Rounds.** A spell may have up to three rounds (`RESCUE_ROUNDS`). Each round is its own plan of one to six steps,
parsed and gated as F-29 does; after a plan ends (completed, or stopped at a step), the model is told the outcome of
each step ("walk north 4: went 2, stopped by a two-high wall") and asked again. A round that gets the player moving
again (progress on its goal for 20 seconds) ends the spell as a success; three rounds without it end it as a failure
and the local rules take over as before.

**A probe.** One step, `look <way>`, carries no action: the next report gets the slice shifted that way by four
blocks (what the player would see if it took four steps). It lets the model answer "what is behind that wall" without
digging. One probe per round; it costs a round only if it is the plan's only step.

**Richer verbs, each a local step executor.** The failures were in how steps were carried out, not what was asked.
Each verb below is executed by a small, local, recorded skill that works on the slice around the player and never
calls the global route planner:

| Verb | What the executor does | Bounds |
| --- | --- | --- |
| `walk <way> <n>` | steps block by block, over one-block rises and down one-block drops, stopping at the first obstruction; reports how far it went | 1–8 |
| `tunnel <way> <n>` | breaks a two-high passage block by block with the right owned tool, a torch every eight blocks if it has them, stopping at liquid it would release or a drop of more than two | 1–6 |
| `stairs <up|down> <way> <n>` | a staircase one block a step, never straight down; down stops at liquid or a void | 1–6 |
| `pillar <n>` | as F-29 | 1–6 |
| `bridge <way> <n>` | as F-29, sneaking at the edge | 1–6 |
| `swim_up` | breaks the block over its head if submerged and rises to air, as F-25c4ax does | once |
| `torch` | places a torch at its feet or on the wall beside it | once |
| `water <way>` | pours and takes back its own bucket to descend or cross, as the clutch does | once |
| `eat`, `wait <s>`, `retreat <way> <n>`, `shelter` | as F-29 and the survival rules | as F-29 |
| `look <way>` | the probe | once a round |

Every executor is an ordinary recorded skill under the edits policy, the arbiter and the claim registry; a step the
policy refuses ends the plan as before. `ProposalGate` stays the gate: each verb maps to an installed skill id
(`native_rescue_walk`, `native_rescue_tunnel`, ...) and a plan's fence is checked at dispatch.

**Acceptance.** JUnit for each executor's bounds and stops on staged slices; a GameTest per verb, including the stops
(a tunnel that would release water stops; stairs down stop at lava; a walk reports how far it went). A GameTest of a
three-round spell with a stub strategist: round one's plan stops at a wall, the report says so, round two tunnels
through, the player moves on; and one where three rounds fail and the local rules resume with nothing broken. The
evaluation engine: rescues carried out and their success rate, against F-29's 4 of 31.

## F-29d Rescue recipes: what worked becomes a local rule

**The capture.** When a rescue spell succeeds, the runtime keeps a *recipe*: the scene's signature at the spell's
start, and the plan steps that worked (the ones that ran, with how far each went). When `KINDRED_REPLAYS` is on, the
scene is also captured as a replay (F-27) at the spell's start, so every successful rescue is a candidate fixture.

**The signature** (`RescueSignature` in core, pure): coarse features of the 9×9×5 slice, not coordinates: enclosed
on which sides within two blocks; headroom; the drop below; liquid above, below, beside; the hardest block hemming it
in and whether its tool breaks it; light; whether a ledge is within pillar reach; whether it stands in a one-wide
shaft. Two scenes match when their features agree; the signature is designed so that a few hundred recipes cover
the long tail the runs show (pits, shafts, flooded pockets, hemmed-in scaffolds, ledges).

**Use.** When a spell starts and a recipe's signature matches, the recipe's plan is tried first, locally, at no token
cost, before any model round; the model is asked only if the recipe fails. A recipe that fails twice in a row is
retired; one that works gains weight. Recipes are kept per server in `kindred/rescue-recipes.json` (bounded to 500),
shared by every player, and shipped: the repository carries a starter set under
`companion-server-adapter/src/main/resources/data/kindred/rescue-recipes.json`, grown from the evaluation runs and
reviewed by hand before each is committed, each with the replay that proves it. This is how an improvisation becomes
a rule without anyone writing code.

**Acceptance.** JUnit for the signature's features and matching, the weights and retirement, the bound. A GameTest:
the pit scene is rescued by a stub's plan once; the same scene a second time is escaped by the recipe with no model
call (the stub records none); a scene that differs in a feature does not match. The evaluation engine on ten seeds:
rescues by recipe against rescues by the model, and model tokens falling between a first and a second run on the
same seeds with the recipe file carried over.

## F-29e Measured at the scale that can show it

Ten seeds at two hours cannot show a change in stuck time smaller than the seed-to-seed range. For this work the
evaluation is: 20 seeds at two game hours, rescue off against F-29b+c+d on, the same build, the same seeds, nothing
else on the cores; and 10 seeds at four game hours to see the later stages. Report stuck minutes, stuck spells, time
to iron pickaxe and to a lit portal, rescues asked, rounds, success rate, recipes used and tokens, with ranges. A
result is "worth noting" only when ranges do not overlap. Whatever it shows is filed.

**Acceptance.** The two comparisons filed under `docs/evidence/eval/` with the engine's summaries, and F-29's
evidence updated with the before-and-after success rate.

## What does not change

- A plan is one to six steps of installed skills, validated in core, gated, revalidated at dispatch (AI-03); rounds
  are separate plans. Nothing the model says is ever executed as code or outside the installed skills (AI-02).
- One rescue conversation per stuck spell; a spell is a meaningful change (AI-04); the planning lane's deadline,
  rates and daily budgets (AI-05). Recipes make the model *less* used over time.
- No hidden-world knowledge: the report and the probe show only what the player could see; `look` shifts the slice
  through blocks the player's perception has exposed, and marks the rest unknown.
- Earn everything: every block broken or placed by an executor is a recorded action under the edits policy.

## Order

F-29b, then F-29c (its executors are what make the plans succeed), then F-29d (needs successful rescues to learn
from), then F-29e. Where F-28's simulator can run the executors on staged slices, use it to check the stops in
seconds before the GameTests.
