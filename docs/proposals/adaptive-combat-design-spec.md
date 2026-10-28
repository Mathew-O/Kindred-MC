# Adaptive Minecraft Combat and PvP System

**Status:** Proposed design, version 0.1  
**Date:** October 22, 2026  
**Purpose:** Enable an AI-controlled player to execute reliable combat mechanics, adapt to opponents, and combine movement, terrain, and inventory into resourceful survival play.

This is a standalone proposal. It does not inherit requirements or implementation choices from the outdated companion specification. The current engine, integration, supported Minecraft version, and existing capabilities have not been inspected.

“Fight like Dream” is a behavioral aspiration: quick execution, resourcefulness under pressure, terrain awareness, deceptive movement, and recovery from bad situations. This document does not claim to reproduce Dream's exact behavior or guarantee comparable performance.

All numerical budgets, thresholds, and acceptance targets below are proposed starting points. They require measurement on the chosen implementation.

---

## 1. Product objective

Build a combat system that can:

- Execute attacks, movement, defense, equipment changes, and item use with appropriate timing.
- Recognize unfavorable exchanges and change tactics during an encounter.
- Infer opponent habits from observable behavior.
- Use terrain and inventory to create opportunities, escape, or protect a teammate.
- Combine familiar skills in situations that were not individually scripted.
- Improve through recorded demonstrations, controlled training, and evaluation.
- Remain capable of fighting when the language model is unavailable.

The desired experience is an opponent or companion whose decisions are understandable after the fact: it notices a habit, creates an opening, commits, and recovers when the opponent responds differently.

There are three separate capability targets:

| Target | Observable behavior | Evidence needed |
| --- | --- | --- |
| Mechanical competence | Accurate legal attacks, controlled spacing, reliable defense and item use | Isolated skill trials and ordinary duels |
| Tactical adaptation | Adjusts to repeated habits and changing equipment or terrain | Matches against changing opponents, with adaptation disabled as a comparison |
| Resourceful survival play | Creates escape routes, separates enemies, improvises with available items | Unseen terrain and inventory combinations, pursuit and outnumbered scenarios |

Success on one target must not be presented as success on all three.

## 2. Scope and assumptions

### 2.1 Initial ruleset

Use one pinned Java Edition version with its actual combat rules. The examples assume survival combat with attack cooldowns, shields, swords, axes, bows, blocks, food, and water buckets.

Before implementation, record the exact version and all mechanics-changing mods or plugins. Attack timing, shield behavior, interaction reach, critical-hit conditions, item use, movement, and knockback must come from that ruleset.

Separate adapters and evaluations are required for legacy combat, Bedrock Edition, or a materially different modpack.

### 2.2 Supported encounter types

The architecture supports these modes, introduced progressively:

1. One-on-one duels.
2. Escaping a pursuer.
3. Protecting or retreating with a teammate.
4. Fighting multiple opponents using terrain.
5. Pursuit across changing terrain.
6. Objective combat where staying alive, escorting, or reaching a location matters more than kills.

The first playable milestone implements ordinary melee duels and disengagement. Terrain manipulation and advanced recovery follow after core mechanics are reliable.

### 2.3 Explicit exclusions from the first implementation

- Universal support for every combat mod or server.
- Crystal combat, elytra combat, and dimension-specific explosive tactics.
- Arbitrary new executable skills generated during a fight.
- Training a general Minecraft foundation model from scratch.
- Rewriting the entire game physics or networking stack.
- An assertion that downloading an existing Minecraft model provides elite PvP.

These may become separate projects after the initial system is evaluated.

### 2.4 Control backend

The core must work behind a narrow observation and action interface. Possible backends include a native player implementation, a client mod, or a bot client.

A player-shaped NPC is not automatically equivalent to a player. An adapter must demonstrate the appropriate movement, collision, inventory, cooldown, interaction, and damage behavior before it can be used for meaningful PvP evaluation.

A native server integration may observe more state than a normal player. The combat policy must receive only the configured player-equivalent observations. Broader diagnostic state may be recorded separately for evaluation.

## 3. System design

The system consists of fast local execution, tactical selection, opponent modeling, and an asynchronous strategy advisor.

The language model proposes objectives and combinations of existing capabilities. Local components own time-sensitive decisions and all action execution.

Minecraft normally advances at 20 game ticks per second, or 50 milliseconds per tick; overloaded servers can advance more slowly. Tick-based deadlines must follow actual game progression, and wall-clock processing deadlines must be tracked separately. [S1]

### 3.1 Runtime components

| Component | Responsibility | Initial cadence |
| --- | --- | --- |
| Observation adapter | Collect allowed observations and confirmed outcomes | Each game tick, using bounded incremental work |
| Belief state | Track uncertainty, visibility, and recent events | Each observation and relevant event |
| Motor controller | Movement, aim, attack timing, defensive response, item execution | Each game tick |
| Tactical selector | Choose a skill, target, position, and commitment | Every 2–4 ticks and on critical events |
| Local prediction | Compare short candidate continuations | On tactical decisions, within a fixed budget |
| Route planner | Find combat-aware paths and escape options | Every 10–20 ticks or after relevant changes |
| Opponent model | Update conditional behavioral tendencies | On observed opportunities and responses |
| Strategy advisor | Propose an encounter objective or skill composition | Event-driven, asynchronously |
| Replay recorder | Store inputs, decisions, and confirmed outcomes | Buffered throughout the encounter |
| Offline trainer | Improve policies and validate changes | Outside live gameplay |

Cadences are starting values, not a claim that the current engine can meet them.

### 3.2 Control flow

~~~mermaid
flowchart TD
    W["Game world"] --> O["Allowed observations"]
    O --> B["Belief and opponent model"]
    B --> T["Tactical selection"]
    B --> L["Strategy advisor"]
    L --> T
    T --> P["Bounded local prediction"]
    P --> T
    T --> E["Skill executor and action arbiter"]
    E --> W
    O --> R["Replay and offline evaluation"]
    R --> V["Validated policy versions"]
    V --> T
~~~

There is one final action owner for each controlled player. Navigation, aiming, defensive reflexes, and item use submit requests through that owner.

### 3.3 Core requirements

- **ARCH-01:** A pending or failed model request must never stop the combat loop.
- **ARCH-02:** Only the adapter may commit actions to the game.
- **ARCH-03:** Each committed action must satisfy the current ruleset and current preconditions.
- **ARCH-04:** Local predictions are estimates; confirmed game outcomes are authoritative.
- **ARCH-05:** Stale plans must not replay after cancellation, death, respawn, target loss, or dimension change.
- **ARCH-06:** Every important tactical decision must be reconstructable from recorded observations and scores.

## 4. Observation and belief state

### 4.1 Own state

Collect:

- Position, velocity, orientation, collision state, and grounded state.
- Health, armor, active effects, food state, and available recovery resources.
- Selected item, equipment, inventory quantities, and ongoing item use.
- Attack readiness and other relevant cooldown state.
- Current skill, progress, commitment boundary, and recovery options.
- Confirmed damage, knockback, placement, item consumption, and interaction failures.

The controller must distinguish an attempted action from an accepted action and from a confirmed effect.

### 4.2 Opponent state

Observe what the player could perceive:

- Visible position, movement, orientation, equipment, and actions.
- Whether line of sight exists.
- Recent attacks, defensive actions, item use, and movement changes.
- Last known position and the age of that observation.
- Visible teammates and possible approach routes.

Keep hidden health, hidden inventory, exact readiness, and unseen movement as unknown or inferred unless the chosen game mode legitimately exposes them.

An animation can provide evidence of an attempted attack. It does not necessarily establish that the attack connected.

### 4.3 Local terrain

Maintain a bounded, incrementally updated terrain representation containing:

- Walkable surfaces, collision geometry, and relevant elevation changes.
- Cover, line-of-sight breaks, narrow passages, and exposed approaches.
- Falls, liquids, fire, and other hazards.
- Candidate block placements with support, reach, and resource checks.
- Escape routes and routes an opponent may take.
- Timestamp and confidence for previously observed areas.

Do not rebuild a large voxel map every tick. Invalidate affected local cells and edges after observed changes.

Unobserved terrain is uncertain. A path through it requires an exploration or risk policy.

### 4.4 Belief representation

Separate:

1. **Observed facts:** a player raised a shield; a block was placed.
2. **Estimates:** the player is probably moving toward the left exit.
3. **Unknowns:** remaining healing inventory.
4. **Derived opportunities:** cover could provide enough time to begin recovery.

Each estimate carries provenance, age, and confidence. Uncertainty increases during occlusion and after surprising events.

## 5. Action execution and mechanical competence

### 5.1 Legal action surface

Expose input-like intents such as:

- Move forward/backward and strafe.
- Turn toward a direction or aim point.
- Jump, sprint, or crouch.
- Select a hotbar slot or perform a validated inventory operation.
- Attack through the normal interaction mechanism.
- Begin, continue, or stop using an item.
- Interact with a block face or entity when legal.

The adapter must preserve actual timing and costs. It must not implement combat by directly subtracting health, teleporting the player into range, ignoring collision, or granting free items.

### 5.2 Mechanical controller

The controller should support:

- Maintaining a desired engagement distance.
- Intercepting a moving target over a short horizon.
- Scheduling attacks around actual readiness and opportunity.
- Aiming at a valid hit region and rechecking line of sight.
- Handling knockback without blindly continuing a previous movement command.
- Changing movement direction without repeatedly falling off ledges.
- Selecting an appropriate defensive response.
- Equipping and using items with their real transition requirements.
- Recovering after failed interactions.

Attack selection should consider both immediate expected damage and the resulting position. A strong hit that leaves the player trapped may be a poor decision.

Version-dependent mechanics, including critical attacks and shield interactions, require explicit ruleset tests.

### 5.3 Action arbitration

Use a coordinated action bundle containing movement, look, selected item, and interaction intent.

Requests have priority and compatibility rules. Examples:

- A recovery action may temporarily require gaze and item control.
- Movement may continue while a compatible defensive action is active.
- Eating, mining, placing, and attacking cannot independently compete for the same controls.
- A fall emergency can preempt a pursuit intention at a valid transition.
- A direct cancellation invalidates queued actions immediately.

Only combinations that the adapter has demonstrated to be legal may execute together. Releasing a skill must clear its held inputs and pending callbacks.

### 5.4 Player-like behavior

Competence and style are separate settings.

Configure reaction delay, turn speed, prediction error, observation limits, and decision variation explicitly when evaluating human-like play. Avoid making every failure a random missed attack.

Useful variation includes different viable approach angles, cautious versus aggressive commitments, and alternative escape routes. Mechanical limits remain consistent within a match.

## 6. Reusable skill contract

A skill is a feedback-controlled behavior with a clear purpose. It is not a fixed recording of inputs.

Every skill defines:

| Field | Meaning |
| --- | --- |
| Preconditions | Required observations, equipment, geometry, and rules |
| Parameters | Target, desired spacing, position, item, or risk limit |
| Control claims | Channels required during execution |
| Expected effects | Predicted state changes and their uncertainty |
| Resource cost | Items, health risk, time, and opportunity cost |
| Tick policy | How it adjusts to fresh observations |
| Completion condition | Evidence that its purpose was achieved |
| Failure conditions | Missing resources, bad geometry, blocked interaction, or changed target |
| Interrupt points | When another skill may safely take over |
| Emergency behavior | How to handle urgent danger before normal completion |
| Timeout | Maximum allowed duration or lack of progress |
| Evidence | Confirmed events used to assess the result |

### 6.1 Initial skill catalog

| Category | Skills |
| --- | --- |
| Movement | Close distance, maintain spacing, strafe, retreat, intercept, reach cover |
| Melee | Timed attack, favorable trade, defensive reset, ruleset-specific shield pressure |
| Ranged | Aim and release, track exposed target, break projectile line of sight |
| Recovery | Create space, consume food, use supported recovery item |
| Terrain | Place cover, obstruct an approach, cross a gap, take an alternate route |
| Survival | Avoid hazardous footing, recover from knockback, execute a supported fall recovery |
| Team support | Stay near ally, obstruct pursuit, pressure an attacker, regroup |

Fall recoveries, projectile interception, and terrain manipulation should enter the catalog only after isolated testing.

### 6.2 Example: create cover for recovery

**Goal:** Reduce immediate exposure enough to attempt recovery.

**Inputs:** Threat directions, candidate cover positions, available blocks, intended retreat route.

**Preconditions:**

- The selected ruleset permits the placement.
- The target location is observable and reachable.
- The required block and support geometry exist.
- Placement leaves a usable continuation or escape.

**Execution:**

1. Select a candidate position with useful occlusion.
2. Acquire the necessary movement, gaze, and item controls.
3. Perform the placement through the adapter.
4. Confirm the changed geometry.
5. Re-evaluate exposure and time to enemy contact.
6. Begin recovery only if the remaining window is adequate.

**Failure behavior:** Continue toward another valid cover position or disengage. Do not attempt recovery merely because a placement command was sent.

**Success evidence:** The expected cover exists and provides a usable protected position. Recovery success is recorded separately.

## 7. Tactical selection

Use a utility-based selector around a bounded library of skills. A behavior tree or state machine can manage execution and recovery, while utility scores choose among viable alternatives.

### 7.1 Candidate generation

At each tactical decision:

1. Update beliefs and detect urgent events.
2. Determine the current encounter objective.
3. Generate a small set of viable skill-and-parameter combinations.
4. Remove actions with failed preconditions.
5. Estimate outcomes under plausible opponent responses.
6. Select a continuation with sufficient benefit over the current one.
7. Execute only the immediate action segment.
8. Observe the result before extending the plan.

Start with approximately 8–24 candidates. Prune by geometry, resource availability, and relevance before expensive prediction.

### 7.2 Utility model

A starting conceptual score is:

~~~
utility =
    objective_progress
  + expected_combat_advantage
  + positional_advantage
  + ally_protection
  - incoming_damage_risk
  - death_risk
  - resource_cost
  - prediction_uncertainty
  - switching_cost
~~~

Normalize features before combining them. Weights depend on the encounter objective and player state.

For example, a duel values winning the exchange, while an escort objective values maintaining a safe route for the ally. Low health can increase the cost of exposure, but a fixed health threshold must not force the same retreat in every situation.

Hard constraints are evaluated before scoring. A favorable score cannot legalize an impossible attack or unavailable item.

### 7.3 Commitment and interruption

Without commitment, the controller may oscillate between attack and retreat every update.

Use:

- A minimum useful commitment where the skill requires it.
- A switching penalty.
- A required improvement margin for ordinary changes.
- Immediate reconsideration for threats such as an unexpected fall or a newly exposed flank.
- Completion and failure events that trigger selection independently of the periodic cadence.

Commitment never means continuing an invalid action.

## 8. Short-horizon prediction

The tactical predictor asks: “What could happen over the next few moments if I take this action?”

It does not need a complete simulator of the world.

### 8.1 Prediction scope

Use a local approximation of:

- Relative movement and collision.
- Attack opportunities and readiness.
- Likely defensive responses.
- Short knockback trajectories.
- Cover and line-of-sight changes.
- Nearby falls and restricted exits.
- Resource and time costs.

Reuse existing engine mechanics where practical. Validate approximations against recorded authoritative outcomes.

### 8.2 Search procedure

A first implementation can use a small beam search over parameterized skills:

- Initial horizon: roughly 10–30 game ticks.
- Expand only a few promising continuations.
- Sample several plausible opponent responses.
- Penalize high variance or severe downside when survival matters.
- Apply a strict compute deadline and return the best available valid candidate.
- Replan as observations arrive.

These horizons are intended for local exchanges. Longer escape routes and preparations are handled by the route planner and encounter objective.

### 8.3 Partial observability

Never simulate a single imagined opponent response as certain.

Compare plausible responses such as pursuit, holding position, strafing, defending, or switching targets. Use the opponent model to adjust their probabilities, while retaining uncertainty.

If prediction confidence is poor, prefer actions that preserve options or gain useful information when appropriate.

### 8.4 Prediction failure

Track predicted versus observed outcomes. Persistent error should:

1. Reduce confidence in the relevant prediction.
2. Shorten the planning horizon or simplify the maneuver.
3. Increase the cost of relying on that prediction.
4. Produce a replay segment for offline investigation.

The controller must not continue trusting a broken knockback or collision approximation because its score remains high.

## 9. Opponent adaptation

Adaptation should occur during the encounter without retraining a large model.

### 9.1 What to learn

Track conditional tendencies such as:

- Pursues after the controlled player retreats.
- Defends when approached within a given range.
- Repeats a strafe direction in comparable engagements.
- Commits to attacks before achieving useful range.
- Tries to recover after creating distance.
- Continues through a narrow route when an alternative exists.
- Changes targets when pressured by a teammate.

Condition these tendencies on visible equipment, terrain, distance, and encounter phase. A person may behave differently while healthy, outnumbered, or defending an objective.

### 9.2 Update method

Start with interpretable probabilistic estimates:

- Count relevant opportunities, not just observed actions.
- Update a probability estimate with a prior and observed outcomes.
- Decay older evidence.
- Store sample count and uncertainty.
- Detect when recent behavior conflicts with the existing estimate.
- Fall back toward the population prior after a substantial change.

For a binary behavior, a Beta-Bernoulli estimate is a reasonable starting model. More complex movement prediction can use a compact recurrent model later.

Example: two observed chases are weak evidence. Repeated pursuit across many comparable retreat opportunities is stronger evidence. The selector should exploit the latter more confidently.

### 9.3 Behavioral consequences

| Observed tendency | Candidate adjustment |
| --- | --- |
| Often chases immediately | Consider a retreat that improves terrain or separates opponents |
| Holds position against bait | Stop spending resources on the same bait and change the objective |
| Uses a repeated strafe pattern | Adjust short-horizon interception while retaining uncertainty |
| Defends predictably | Consider the supported counter or repositioning |
| Recovers only at long range | Apply pressure when the observed recovery window begins |
| Changes approach after obstruction | Update route predictions and avoid reusing an ineffective trap |

These are hypotheses to test, not universal counters.

### 9.4 Anti-exploitation and stability

Do not always select the highest-scoring move when several choices are similarly good. Controlled variation can reduce predictability.

Bound parameter changes per encounter, use confidence thresholds, and penalize repeated failed tactics. An opponent should not permanently reshape the policy through one unusual action.

Persistent opponent memory is optional. Default to encounter-local adaptation; longer-lived profiles need explicit retention and versioning.

## 10. Terrain and inventory as tactical tools

This is central to the desired resourceful style.

The terrain representation should describe what an object or location permits:

- A wall offers cover or blocks an exit.
- A narrow passage may reduce simultaneous attackers.
- A height change alters approach and fall risk.
- A block can obstruct pursuit or create a step.
- Water may offer recovery or movement options under the selected rules.
- A route can create separation even if it is longer.

These opportunities must be computed from geometry, inventory, timing, and rules.

### 10.1 Combat-aware navigation

A route cost should consider:

~~~
route_cost =
    travel_time
  + exposure
  + enemy_interception_risk
  + fall_or_hazard_risk
  + resource_spend
  + uncertainty
  - useful_cover
  - retained_escape_options
~~~

The planner should reason about where the opponent may be when the route is traversed. A currently empty passage is not necessarily a safe escape route.

Include elevation, jumps, placement requirements, and reachable surfaces. A flat two-dimensional threat map is insufficient for vertical combat.

### 10.2 Skill composition

Represent tactics as short, conditional graphs of existing skills.

Example:

1. Retreat toward a route that limits simultaneous pursuit.
2. Observe whether the opponents follow together or separate.
3. If separation occurs, pressure the isolated pursuer.
4. If the group remains coordinated, continue toward another exit.
5. If the route becomes unsafe, cancel the maneuver.

Skill effects must connect: the output of one step must establish the next step's preconditions.

The planner may propose a composition; the executor validates every step against the live world.

### 10.3 Boundaries on novelty

Distinguish:

- **New composition:** familiar skills used in an unfamiliar combination.
- **New parameters:** a familiar skill aimed at a new location or timing.
- **New skill:** an execution behavior the system does not yet possess.

The first two can be explored within validated limits during play. A genuinely new skill needs offline implementation or training and evaluation before deployment.

An LLM describing a clever maneuver does not establish that the player can execute it.

## 11. Language-model strategy advisor

### 11.1 Responsibilities

The advisor may:

- Interpret an objective such as “keep them away while I heal.”
- Suggest a high-level tactic using known capabilities.
- Identify a useful relationship among terrain, inventory, and opponent habits.
- Propose a change in encounter objective.
- Review a replay and suggest experiments after combat.
- Provide concise dialogue about confirmed events.

It does not schedule individual attacks or continuously steer the camera.

### 11.2 Input

Send a compact, structured summary containing:

- Encounter objective and constraints.
- Own health, resources, and capabilities.
- Observed threats and confidence in opponent estimates.
- Relevant terrain opportunities.
- Current tactic and recent confirmed successes or failures.
- Available skill names and parameter schemas.

Avoid sending raw full-world data or a long unfiltered tick log.

### 11.3 Proposal example

~~~json
{
  "schema_version": 1,
  "encounter_id": "example-encounter",
  "proposal_id": "example-proposal",
  "objective": "escape",
  "tactic": "separate_pursuers",
  "skills": ["reach_cover", "obstruct_approach", "retreat"],
  "constraints": {
    "max_blocks_spent": 3,
    "preserve_recovery_item": true
  },
  "abort_if": [
    "escape_route_invalid",
    "opponents_flank",
    "required_resource_missing"
  ]
}
~~~

The gateway binds authorization, allowed targets, deadlines, and the current control epoch. The model cannot grant itself capabilities.

This is an illustrative schema. The implementation must use bounded enums and validate all fields.

### 11.4 Request lifecycle

- Trigger on a material tactical change or a safe planning opportunity.
- Allow at most one outstanding strategy request per controlled player.
- Coalesce repeated events and enforce a configurable request budget.
- Continue local combat while waiting.
- Reject expired or incompatible proposals.
- Recheck the proposal against current observations before adoption.
- Use deterministic local selection when the model fails or returns an invalid proposal.

Start with a request cooldown of several seconds, then tune using actual usefulness and cost. High-frequency polling should not be necessary.

A small or inexpensive model can be evaluated as the advisor. Provider choice is independent of the motor controller.

## 12. Multi-opponent and cooperative combat

### 12.1 Threat representation

Track each visible threat and its likely short-horizon approach. Score:

- Time until possible contact.
- Exposure to simultaneous attacks.
- Available escape directions.
- Crossfire or competing projectile angles.
- Which opponents can reach the same position.
- Whether a teammate is creating or closing an opportunity.

Avoid selecting a target solely because it is nearest.

### 12.2 Objectives

Supported objectives should have different scoring:

| Objective | Priority |
| --- | --- |
| Win duel | Favorable exchanges and eventual victory |
| Escape | Reach a valid disengagement condition while preserving survival |
| Protect ally | Reduce threats to the ally and maintain a usable route |
| Delay | Consume opponent time without unnecessary commitment |
| Pursue | Maintain contact while preserving resources and avoiding bait |
| Regroup | Reach the teammate without exposing either player unnecessarily |

### 12.3 Coordination

When several controlled allies share information, specify what they may communicate and how stale it can become.

Use lightweight role allocation such as pressure, cover, regroup, or recover. Avoid multiple allies independently claiming the same narrow position or blocking each other's retreat.

Shared information must retain observation timestamps and uncertainty.

## 13. Training and improvement

Use a staged hybrid approach.

### 13.1 Stage A: deterministic competence

Implement ordinary skills and tactical scoring directly.

Benefits:

- Immediate functionality.
- Inspectable failures.
- A reliable fallback.
- A baseline for judging whether learning helps.

Do not start with large-scale reinforcement learning before the adapter and telemetry are trustworthy.

### 13.2 Stage B: demonstrations

Record skilled players in the actual supported ruleset.

Useful data includes:

- The observations available to the player.
- Input actions and their timing.
- Inventory and selected item.
- Relevant world geometry.
- Confirmed combat outcomes.
- Scenario objective and ruleset version.

Prefer instrumented gameplay over trying to infer every action from edited video. Videos can help identify tactical examples, but cuts, camera changes, and missing inputs make them an imperfect source of training labels.

VPT demonstrated a Minecraft pipeline using inverse dynamics to label video and behavioral learning with subsequent fine-tuning. That supports the general training direction; it does not establish elite PvP performance or compatibility with this project's interface. [S2]

### 13.3 Stage C: compact local policies

Train specialized policies where they outperform handcrafted control:

- Spacing and strafing.
- Short movement prediction.
- Attack commitment.
- Defensive selection.
- Aim control under defined limits.

A compact recurrent policy is a candidate when recent history matters. Its input can be structured observations rather than full video if the chosen adapter supports them.

Select architecture and size through measured latency and quality. Keep a deterministic fallback for unsupported states and missed inference deadlines.

### 13.4 Stage D: self-play and opponent diversity

Train against:

- Simple aggressive and defensive scripts.
- Opponents with distinct conditional habits.
- Earlier versions of the policy.
- A pool of diverse learned policies.
- Recorded human behavior where appropriate.

Randomize terrain, equipment, starting positions, and supported latency conditions. Include weak and strong opponents to reduce specialization.

Avoid evaluating only against the latest copy of the same policy.

### 13.5 Reward design

Use the actual encounter objective as the main reward. Add limited shaping for meaningful progress.

Potential shaping signals include favorable exchanges, preserving escape options, and completing a recovery. Penalize invalid actions, avoidable hazards, and unjustified resource waste.

Explicitly test for:

- Endless retreat to preserve health.
- Stalling until timeout.
- Farming low-value damage instead of completing the objective.
- Repeatedly forcing a reset.
- Exploiting simulator errors.
- Sacrificing the teammate for a favorable personal score.

Draws, timeouts, and reset behavior must be defined per mode. Distance traveled alone must not reward escape indefinitely.

### 13.6 Stage E: validated tactical compositions

Store successful tactical compositions with:

- Preconditions and observed context.
- Skills used.
- Expected and actual effects.
- Resource costs.
- Success and failure examples.
- Validation status and compatible rulesets.

Retrieve candidates by structural relevance, then validate against the live situation.

Voyager provides a research precedent for reusable and compositional Minecraft skills. Its reported exploration and progression results are not evidence that the same approach alone solves real-time PvP. [S3]

### 13.7 Live adaptation versus model updates

During a match, update beliefs and bounded tactical parameters.

Train and promote new model weights offline. Candidate versions run through a fixed evaluation suite, receive an explicit version identifier, and retain a rollback path.

## 14. Interfaces and data contracts

The following TypeScript-style shapes illustrate contracts; they do not prescribe the implementation language.

~~~typescript
type Estimate<T> = {
  value: T | null;
  confidence: number;
  observedAtTick: number | null;
  basis: "observed" | "inferred" | "unknown";
};

type CombatObservation = {
  schemaVersion: number;
  rulesetId: string;
  encounterId: string;
  controlEpoch: number;
  observationTick: number;
  own: OwnPlayerState;
  opponents: OpponentObservation[];
  terrain: TerrainObservation;
  confirmedEvents: CombatEvent[];
};

type SkillRequest = {
  requestId: string;
  encounterId: string;
  controlEpoch: number;
  basedOnTick: number;
  validUntilTick: number;
  skillId: string;
  arguments: Record<string, unknown>;
};

type SkillStatus = {
  requestId: string;
  phase: "starting" | "running" | "completed" | "failed" | "cancelled";
  reason: string | null;
  confirmedEventIds: string[];
};

interface CombatAdapter {
  snapshot(): CombatObservation;
  capabilities(): CapabilityCatalog;
  commit(intent: ActionIntent): ActionReceipt;
  cancel(controlEpoch: number): void;
}
~~~

Referenced structures must be expanded into concrete schemas during implementation. Arbitrary argument dictionaries in these examples must become skill-specific validated schemas.

### 14.1 Required identifiers

Carry these through telemetry and asynchronous work:

- Ruleset and adapter version.
- Encounter identifier.
- Controlled player identifier.
- Control epoch.
- Observation tick.
- Skill request identifier.
- Policy and configuration version.

### 14.2 Confirmation semantics

An action receipt means the adapter accepted or rejected the request. It does not by itself prove a hit, placement, or consumed item.

Skills complete from authoritative observations or confirmed outcomes. If an outcome cannot be established, return an explicit uncertain state and reconcile before a dependent action.

## 15. Scheduling, concurrency, and freshness

### 15.1 Runtime loop

~~~text
on game tick:
    read bounded observations through the adapter
    reconcile confirmed outcomes
    update beliefs and active skill
    detect urgent threats and invalidated intentions

    collect completed worker results
    reject stale or incompatible results

    if a tactical decision is due:
        choose a valid immediate fallback
        request bounded prediction if useful

    arbitrate the current skill and urgent responses
    validate the next action against current state
    commit the action bundle
    append telemetry to a bounded buffer
~~~

Do not wait for a worker or model response inside this loop.

### 15.2 Worker ownership

A worker receives an immutable snapshot. It must not access mutable live-world objects.

For server integrations, world reads and mutations must occur on the thread required by the chosen platform. Paper documents that substantial portions of its world API are unsafe for asynchronous use. [S1]

Return results through a bounded inbox. Recheck them at the point of action.

### 15.3 Freshness

Different decisions need different lifetimes:

- An immediate aiming or movement recommendation may become stale within a few ticks.
- A route can remain useful while its relevant geometry is unchanged.
- A strategic objective can remain useful through many observations.

Revalidate based on relevant dependencies rather than rejecting every plan whenever any observation changes.

Invalidate immediately after death, respawn, dimension change, explicit cancellation, or a new control epoch.

### 15.4 Latency handling

For a network client, distinguish local input time, send time, server observation time, and outcome confirmation.

Estimate uncertainty introduced by latency and jitter. Never assume the most recently received position is the opponent's current position.

A native server actor has different timing advantages. Record its observation and reaction policy explicitly when comparing it with human clients.

### 15.5 Initial performance targets

| Measure | Proposed target |
| --- | --- |
| Tick-path observation, arbitration, and action work | p95 ≤ 0.5 ms and p99 ≤ 1 ms per active fighter on a declared reference machine |
| Tactical worker decision | p95 ≤ 10 ms; bounded work even when the deadline is missed |
| Compact policy inference | Target p95 ≤ 2 ms on the selected deployment hardware |
| Model requests on the game thread | Zero |
| Unbounded world scans in combat | Zero |
| Late results replayed after cancellation | Zero |
| Load tests | 1, 4, 8, and 16 active fighters, with total game-tick impact reported |

These targets do not allocate the full 50 ms game tick to one agent. The entire server shares that budget.

Under load, reduce search breadth, horizon, route refresh frequency, and discretionary strategy requests. Retain bounded observation, action validation, and basic survival control.

## 16. Evaluation and acceptance gates

### 16.1 Benchmark families

| Family | Scenarios |
| --- | --- |
| Mechanics | Stationary and moving targets, shield interactions, equipment changes, knockback, failed use |
| Duels | Open arena, obstacles, elevation, restricted resources |
| Adaptation | Persistent habits, changing habits, misleading initial behavior |
| Terrain | Alternate routes, narrow passages, exposed crossings, changed blocks |
| Survival | Low health, pursuit, outnumbered starts, limited recovery |
| Teamplay | Escort, regroup, attacker pressure, conflicting ally routes |
| Robustness | Model unavailable, worker timeout, delayed observations, server slowdown |
| Generalization | Unseen maps, inventories, opponents, and starting configurations |

Every test names its exact ruleset and any available privileged diagnostic state. Policy observations remain constrained.

### 16.2 Metrics

Track:

- Win, loss, draw, and objective-completion rates.
- Damage dealt and received.
- Survival and escape success by scenario.
- Valid attack attempts and confirmed hit rates.
- Avoidable fall and hazard deaths.
- Successful recovery opportunities and wasted recovery attempts.
- Resource expenditure per objective.
- Invalid action rate.
- Skill failure reasons and recovery time.
- Tactical switching frequency.
- Adaptation delay and benefit.
- Prediction error.
- CPU, inference, and tick latency.
- Strategy requests, cost, and proportion of proposals actually adopted.

Do not aggregate everything into one win rate.

### 16.3 Proposed release gates

| Gate | Acceptance target |
| --- | --- |
| Adapter correctness | Pass explicit parity tests for every supported mechanic; no known action bypasses |
| Core skills | At least 95% completion on eligible isolated trials, with randomized legal starting conditions |
| Cancellation | No stale actions after cancellation in the regression suite |
| Baseline duels | At least 60% win rate over at least 200 independent matches against a declared baseline; report uncertainty |
| Adaptation value | At least 10 percentage points of objective-success improvement over adaptation-disabled control on the habit benchmark, with uncertainty reported |
| Behavior changes | No material sustained collapse when an opponent switches its policy |
| Generalization | Report held-out results separately; define an acceptable degradation before training |
| Model outage | Complete the full combat benchmark with the advisor disabled; quantify performance change |
| Human evaluation | Publish scenario, equipment, latency, participant strength, and sample size with results |
| Resourceful play | Succeed on unseen scenario combinations with observable causal use of terrain or inventory |

A threshold achieved by only one lucky evaluation does not establish readiness. Use confidence intervals, repeat seeds, and appropriate grouping when the same human or map appears repeatedly.

The 60% baseline gate is an engineering milestone. It is not a claim of Dream-level ability.

### 16.4 Ablations

Compare the full system with:

- Opponent adaptation disabled.
- Terrain manipulation disabled.
- Prediction disabled.
- Strategy advisor disabled.
- Learned motor policy replaced by the deterministic baseline.

Keep other settings fixed. These comparisons show which components justify their complexity.

### 16.5 Prevent evaluation leakage

Separate training and evaluation by maps, opponent policies, and scenario combinations. Freeze the benchmark before model selection.

Hold out opponent changes that the training curriculum did not directly rehearse. Record failures rather than selecting only successful demonstrations.

## 17. Example encounter

This example illustrates intended behavior, not measured capability.

**Situation:** The player is pursued by two opponents, has limited food, several blocks, and a water bucket. A narrow passage connects two open areas.

1. **Perception:** Observe pursuit speed, spacing between opponents, passage geometry, and available exits.
2. **Belief update:** Infer that the leading opponent often commits quickly. Confidence remains limited until several opportunities support it.
3. **Tactical choice:** Evaluate moving through the passage against taking a longer open route.
4. **Composition:** Plan to cross, obstruct one approach if useful, and continue toward cover.
5. **Execution:** The local controller handles movement, aiming, item selection, and valid placement timing.
6. **Feedback:** Confirm whether the obstruction changed the opponents' approach.
7. **Branch:** If opponents separate, consider a brief favorable exchange against the isolated pursuer.
8. **Counter-response:** If they flank or refuse the route, abandon the isolation attempt and preserve escape options.
9. **Recovery:** Eat only after observing a sufficient recovery window.
10. **Learning:** Record whether the prediction and tactic worked, including unsuccessful branches.

The impressive behavior comes from reliable skills, a useful representation of the situation, and timely changes after feedback. The sequence must not be treated as a guaranteed script.

## 18. Implementation roadmap

### Milestone 0: ruleset and adapter

Deliver:

- A pinned ruleset manifest.
- An observation/action adapter.
- Player-mechanics parity tests.
- Deterministic scenario resets and replay recording.

Exit condition: observed and confirmed outcomes can be trusted.

### Milestone 1: competent melee

Deliver:

- Spacing, aiming, timed attacks, defense, retreat, and ordinary recovery.
- Action arbitration and cancellation.
- A deterministic utility selector.
- The mechanics and baseline-duel suites.

Exit condition: reliable basic combat without an LLM.

### Milestone 2: adaptation and short prediction

Deliver:

- Opponent opportunity/event tracking.
- Confidence-aware conditional estimates.
- Bounded candidate prediction.
- Commitment and change detection.
- Adaptation benchmarks and ablations.

Exit condition: measurable improvement against observed habits without brittle dependence on them.

### Milestone 3: terrain and survival

Deliver:

- Combat-aware navigation.
- Cover placement and approach obstruction.
- Validated fall recovery where supported.
- Conditional skill compositions.
- Pursuit and outnumbered benchmarks.

Exit condition: useful terrain decisions on held-out maps.

### Milestone 4: learned execution

Deliver:

- Instrumented demonstrations.
- One specialized local policy selected by demonstrated weakness.
- Diverse self-play curriculum.
- Versioned model evaluation and rollback.

Exit condition: the learned component improves its target capability within runtime budgets.

### Milestone 5: strategy and broader creativity

Deliver:

- Asynchronous strategy advisor.
- Retrieval of validated tactical compositions.
- Replay analysis and offline experiment generation.
- Cooperative and objective combat.

Exit condition: advisor and composition benefits survive ablation testing and unseen scenarios.

No calendar estimate is assigned until the current codebase, team capacity, and training infrastructure are known.

## 19. Risks and mitigations

| Risk | Consequence | Mitigation |
| --- | --- | --- |
| Strong-looking NPC with different mechanics | Misleading PvP results | Verify the adapter against player behavior |
| Weak motor execution | Clever plans fail physically | Build and benchmark isolated skills first |
| Incorrect local physics | Search confidently chooses bad maneuvers | Compare predictions with confirmed outcomes |
| LLM latency or stale context | Missed timing and obsolete actions | Keep the advisor asynchronous and advisory |
| Hidden information leakage | Unrealistic behavior and invalid evaluation | Enforce an observation policy at the adapter |
| Overfitting to one opponent | Predictable or fragile combat | Diverse opponent pool and held-out tests |
| Excessive tactical switching | Jittery movement and failed actions | Commitments, margins, and switching cost |
| Reward exploitation | Stalling or useless damage farming | Objective-based scoring and adversarial scenario tests |
| Unlimited search and scans | Server slowdown | Fixed budgets and graceful degradation |
| Unvalidated generated tactics | Impossible or unsafe-to-execute sequences | Restrict live proposals to validated skills |
| Confusing style with strength | Artificial mistakes or misleading claims | Measure competence and style independently |

## 20. Decisions required before coding

These decisions do not block architectural review, but they determine implementation work.

| Decision | Recommended starting point | What remains unknown |
| --- | --- | --- |
| Combat ruleset | One pinned modern Java survival ruleset | Actual version, plugins, and mods |
| Control backend | Reuse the existing player-control engine behind an adapter | Current backend and mechanics parity |
| Observation policy | Player-equivalent observable state with uncertainty | Current sensing and available events |
| Deployment | Local deterministic execution with optional worker inference | CPU/GPU resources and hosting |
| First benchmark | Melee duels plus disengagement | Existing test arena and baselines |
| Training data | Instrumented demonstrations in the chosen ruleset | Availability of recording and skilled players |
| Strategy model | Provider-independent asynchronous interface | Current provider and cost budget |
| Desired style | Resourceful survival play, evaluated separately from duel strength | Exact balance of companion behavior and competitive strength |

The first implementation package should contain the adapter contracts, core skill contract, action arbiter, observation schema, replay schema, deterministic combat baseline, and scenario runner.

## 21. Research basis and limits

The architecture and targets in this document are engineering proposals. The sources below support specific foundations; they do not validate this full system or its aspirational skill level.

- **[S1] PaperMC, “Scheduling.”** Documents ordinary Minecraft tick timing, slowdown behavior, and thread-safety considerations for Paper. Platform-specific threading guidance must be replaced with the chosen adapter's requirements.  
  [PaperMC scheduling documentation](https://docs.papermc.io/paper/dev/scheduler/)

- **[S2] Baker et al., “Video PreTraining (VPT): Learning to Act by Watching Unlabeled Online Videos,” 2022.** Supports imitation learning from Minecraft video with inferred action labels and subsequent fine-tuning. Its reported tasks do not establish competitive PvP mastery.  
  [VPT research paper](https://arxiv.org/abs/2206.11795)

- **[S3] Wang et al., “Voyager: An Open-Ended Embodied Agent with Large Language Models,” 2023.** Provides a precedent for reusable, compositional skills and feedback-driven improvement in Minecraft. Its evaluation focuses on exploration, progression, and transfer rather than elite real-time PvP.  
  [Voyager project and research](https://voyager.minedojo.org/)

Sources checked October 22, 2026. No implementation, training run, or combat benchmark has been performed as part of this design document.

