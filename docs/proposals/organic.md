# The organic feel: attention, walking together, and small habits

Status: proposal, 2026-11-09, from a read-only review of `main` at `910eb87`. IDs E-33b, E-33c, E-54 and E-55 are
reserved in the [tracker](../feature-spec.md); claim one in Active work before implementing it. Each item is small on
its own; together they are the difference between a bot that plays well and a player people forget is a bot.

## What was looked at

| Question | Today | Where |
| --- | --- | --- |
| Does it look around naturally? | **While waiting, yes** (E-33a): at a threat, at whoever speaks to it, back at someone who looks at it, at what it waits on, otherwise a slow look round drawn from its own seed so two players never turn as one. **While doing anything else, no**: a walking player's eyes stay on the path a few blocks ahead; mining, placing and fighting snap the head in one step; a player with nothing to do runs no wait and so never looks about. Head yaw is always set equal to body yaw, so it never looks sideways while moving. | `Gaze`, `NativeWaitSkill`, `NativePlayerBody.walk`, `NativeGoToSkill`, E-33a's own "remaining" |
| If a player asks it to follow, does it? | **No.** There is no native follow task; `FOLLOW` is not an ask for that reason (E-20b, E-52). The only follow code is the legacy mob body's (`FollowBehavior`, `FollowSpacing`: hold when close, catch up, back off, walk at the shoulder). | `PlayerDecisionLoop.RUN_FOLLOW` (legacy), `FollowSpacing` |
| When several travel together, line or spread? | **Neither, by design or by accident.** On an outing (E-27a) each participant walks to the same destination on its own route; with the same planner and costs from the same start they take the same route, so they walk in single file or on top of one another. There is no personal-space rule in `NativeGoToSkill`, no stagger and no pace variation. Team runs (E-40a) mostly split up, so the problem shows on outings and when people lead. | `Outings`, `NativeGoToSkill` |
| Walking itself | Good: sprinting on open runs, committed sprint-jumps, eased turns (half the remaining angle a tick, 6 to 45 degrees), pitch eased to the ground ahead, doors opened and shut. Two robotic tells: the same walk always takes the same path, and a refused target can be retried thousands of times (F-29b/c cover the loop). | `NativeGoToSkill`, F-10d |
| Chat pacing | A reply appears after 180 ms plus 32 ms a character, capped at two seconds, so a 100-character line "types" in two seconds (a person at 50 words a minute takes about twenty). Chat styles exist (`proper`, `casual`, `gamer`, `chaotic`, E-18b) but do not change the pace. | `SpeechTiming`, `ChatStyle` |
| Idle | `IdleObservationController` meets eyes, picks up dropped items, notices landmarks and strolls, but only in the idle-observing playstyle; the quirks (`SPRINTJUMP`, `LIVELY`, `CURIOUSGLANCE`, `STEADY`) are kept but E-32 lists native movement quality as remaining. | `IdleObservationController`, `Mannerisms` |

## E-33b Looking about while doing things

- **Head and body apart.** Vanilla players turn the head up to about 50 degrees from the body before the body
  follows. Let `walk` ease head yaw separately from body yaw, with the body catching up when the head has led for
  long enough, so a glance aside does not swing the whole player.
- **Glances while walking.** Every few seconds on an easy stretch, a short look (one to two seconds) at the nearest
  thing worth a look within 16 blocks: a player, a mob, exposed ore, a chest, a build, a lit portal, then back to the
  path; never on a jump, a drop, in water, or when the path turns. Drawn from the player's seed, weighted by curiosity.
- **A look before a break or a placement.** The eased turn onto the block (E-33a's drafted next action), the head
  arriving a few ticks before the hand swings, instead of the one-step snap.
- **Looking about with nothing to do.** An idle player that runs no wait gets the same `Gaze` the wait has, so it never
  stands staring at one point.
- **A look back when spoken to while walking** (one to two seconds, then on), and a look up at the sky at dusk before
  it heads home.

Acceptance: fixtures reading head yaw and body yaw over a walk past a chest and a mob (the head turns, the body does
not, the eyes return to the path within two seconds); the break and placement turns eased (yaw changes over several
ticks before the swing); an idle player's head moving over a minute; none of it during a jump or in water.

## E-33c Attention under load

The current gaze picks a threat first; add two cheap, human things:

- **Peripheral danger.** A creeper hiss or a skeleton's arrow from outside the view turns the head toward the sound's
  side before the body reacts (the game's own sound events; nothing seen through walls).
- **Watching a friend fight.** A player standing near a teammate in a fight keeps its eyes on the fight, not on its
  furnace.

Acceptance: a fixture per case, with the look arriving within ten ticks.

## E-54 Walking together: a native follow, and the spread

- **A native follow task** (`native_follow`), reusing `FollowSpacing`'s rules on the native body: hold still within
  about three blocks, approach past five, back off inside one and a half, walk at the leader's shoulder on a preferred
  side chosen from the player's seed (so two followers take different sides), sprint when the leader sprints, stop and
  look where the leader looks when the leader stops, give up and say so after a minute with no route. Asked for in
  chat (`FOLLOW` in E-52, from a controller) and by a teammate as a record. Following is a task like any other under
  the arbiter: a hazard, a stop or hunger interrupts it as they interrupt a walk.
- **A spread when several walk to one place.** In `NativeGoToSkill`: personal space (no two AI players within one
  and a half blocks when a sidestep is free; the one behind yields), a staggered start (half a second to two seconds
  by seed) when several are sent at once, a pace of their own (sprint and sprint-jump thresholds shifted a little by
  initiative and caution), and a lateral preference by seed on open ground so the same walk is not the same path.
  Arrival on an outing spreads to a loose group of two to four blocks facing the thing they came to see, not a stack
  on one block.
- **On the way.** A curious player on a group walk may stop for a glance (E-33b) and catch up with a short sprint;
  a cautious one keeps the pace. Nobody stops in a doorway.

Acceptance: three players sent across sixty blocks of open ground never come within one and a half blocks of one
another, start at different ticks, arrive spread over at least two blocks, and no two take the same waypoint
sequence; a native follow holds its distance behind a walking mock player over a hundred blocks, takes the shoulder
side, yields when the leader turns back, and ends with a line when the leader is unreachable; an interrupted follow
resumes after the hazard.

## E-55 Small habits

- **Typing pace.** About 60 to 80 ms a character with a cap near six seconds, scaled by chat style (`gamer` fast,
  `proper` slow); the voice's own latency already counts toward it (E-20a). A long answer should take as long as a
  person would take to type it.
- **The crouch hello.** On meeting a friend (E-24's friendship, E-25's warmth), two or three quick crouches, the way
  players greet; never during work or a fight, never to a stranger, once per meeting.
- **A wave.** An arm swing toward someone it greets or thanks, when it holds nothing it would swing at them.
- **The right item in hand a moment early.** Select the tool before arriving at the block, hold a torch in the hand at
  night underground, hold food when about to eat; today the swap can land in the same tick as the use.
- **A step back to look.** At each build stage's end, walk back three or four blocks, look at the build for a second
  or two, then carry on (the same pause before the stage's remark to its controllers).
- **A breath at the top.** After a long climb or a pillar, a second's pause and a look round before moving on
  (curiosity-weighted).
- **Sneaking at an edge.** A crouch while placing a bridge block or peering over a drop (the bridge already sneaks;
  extend it to the look).
- **"gg".** After a match, a line on the public channel in its chat style, once, win or lose.
- **Peeking at the sky.** A glance up when rain starts or stops, and at dusk.

Each is bounded by the mannerism quirks and the social budgets that exist, so a `STEADY` player does little of it and a
`LIVELY` one more; none of it moves items or costs anything.

Acceptance: a fixture per habit (the crouch counted on a friend and absent on a stranger; the slot selected before
arrival; the step back and look after a stage; the pace by chat style), and the mannerism and social fixtures still
passing.

## Order

E-54 first (the follow is the thing people ask for on day one; the spread is what shows on every outing), then E-33b,
then E-55, then E-33c. All four are adapter and core changes of modest size; none needs a model.
