# F-25c4de — Up for air means breathing

Date: 2026-11-11
Agent / branch: Claude, LOOPS (claude/f25c4de-swimup-1111)
Build: bae60a6 (main acb8a73 merged in)
Status: in progress (checked when merged)

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

## The corpus gate (bae60a6, against the 084dde5 baseline, three runs and the confirm step against main a60f919)

- **Whole corpus:** 119 of 275 pass (43.3%) over three runs: 90 fail and 66 are intermittent. Factions is 18 of 50 and
  survival 101 of 225. Replay players left after their batch: 0, 0, 0.
- **Flagged:** 1 (`e40a14-80b7150-8675309-kindred-120887`). Confirm cleared it: the branch failed it 1 of 3 alone, and
  main passed it 3 of 3. Confirmed: 0. The gate passes.
- **The 22 drowning scenes in the gate's runs:** 13 pass, 1 intermittent, 8 fail. The baseline had 0 pass, 2 intermittent
  and 20 fail.

The seven or eight still failing have other causes (above). They go to a child of their own if one of those causes is
shared.
