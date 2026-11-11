# F-25c4de — Up for air means breathing

Date: 2026-11-11
Agent / branch: Claude, LOOPS (claude/f25c4de-swimup-1111)
Build: 0dda410 on main bc49b07
Status: in progress (the corpus gate on main with #164 to come)

## Scope and implementation

User-visible behaviour: a player short of breath just under the water's surface swims the last of the way up and stays
up until it has its breath back, rather than drowning a hand's breadth from the air. With no way out of the water it can
reach, it is no longer handed step after step only to have each stopped at once.

Changed:
- `NativeGoToSkill`: a walk ending in water at a cell with open air straight above it treads there, swimming up, until
  its air is full, at most 100 ticks. It still ends COMPLETED. Walks under a ceiling or onto dry ground are unchanged.
- `PortalRunCampaign.next`: a step that `stopFor` would end on its first tick with "In deep water; up for air first" is
  held, not given, and the progress says "in deep water with no way out it can reach". The watchdog's spell counts the
  time as for any spell with nothing feasible. Steps the stop exempts (F-35g's included) are unaffected.
- `NativeSwimUpGameTests` (new, staged fixtures).

## Cause

This was the failure corpus's biggest cluster failing with one cause on 084dde5: 22 drowning scenes ("Get out of the
water"), 20 failing 6 of 6. A diagnostic run of f29e-on-707-sol-12912 traced it. The player hovered with its feet 0.8–0.9
below the top water cell and its eyes under, air −7 to −17 and health 14 to 0.8, for 250 ticks. Meanwhile its walk up
for air "completed" 10–15 times a second: each walk ended on arrival at the top water cell, the swimming stopped, and
the body sank back. Of the cluster's 65 drowning runs, 39 show that loop.

## Acceptance evidence

| Check | Before | After |
| --- | --- | --- |
| `stagedHovering…` (feet 0.85 under the top water cell, as the scene) | drowned 6 of 6 | 6 of 6 pass |
| `stagedJustUnderTheSurfaceOfAWidePool…` (a race on the first stroke) | drowned 3 of 8 | 6 of 6 pass |
| `stagedSealedInWater…` (no way out) | 67 steps stopped at once in 300 ticks | 1, 3 of 3 |
| The 22 drowning scenes ×3, filtered, on 6957e28 + #164's harness | 0 pass, 2 intermittent, 20 fail | 13 pass, 2 intermittent, 7 fail |
| `NativeRescueGameTests` (9), `ReplayGameTests` (7), `NativePortalCampaignGameTests` (97, F-25c4af's drowned case included) | — | 3 of 3 each |
| `./gradlew build` | — | green |

The seven still failing each have another cause:
- digging the way up under rock with little air left (3);
- a drowned's attacks (2);
- a weapon swap stopped in the water, and a walk to dry ground that made no progress (1 each).

None is the hover.

## Remaining

The corpus gate on main once #164 (the harness and its baseline) has landed, with the 22 scenes' counts from the gate's
runs on main and the branch.
