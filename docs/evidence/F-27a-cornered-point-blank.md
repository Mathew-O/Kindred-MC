# F-27a — A shooter at point blank is a mob in reach

Date: 2026-11-11
Agent / branch: claude/cornered-echo-1111
Status: in progress (part of F-27a; linked from F-27a.md once merged)

From the e40a20 triage: `e40a20-79a9aad-20261030-echo-74065`, a "cornered" skeleton death. It failed 0 of 6 in the e40a20 runs. This PR adds the scene with the fix.

- **On main b7027be:** the scene failed 4 of 6 alone. Every death read "shot by Skeleton … doing Rest to heal in Iron: heal before going on", at ticks 220 to 236 of the scene.
- **Cause:** found with a temporary diagnostic line in `cover()`, which was not kept.
  - **The scene:** Echo is at 2.7 health, and the skeleton is 0.8 blocks off.
  - **Cover:** the cell it chose between them held the skeleton, and it overlapped the player's own box. Every "Take cover from skeleton" ended "Block placement produced no effect or was obstructed".
  - **Back off:** "Back off from skeleton" found no walkable route.
  - **Fall-through:** `defend()` then returned nothing, and the goal fell through to resting in the line of fire.
- **Change:** `onFootInReach` now counts a ranged mob as in reach when it is closer than `POINT_BLANK` (2.0 blocks). At that range no block goes between them.
  - **How defend() takes it:** it doesn't try cover from a mob in reach. With no way off, it fights, and `cornered()` keeps that fight from being broken off.
  - **Unchanged:** a skeleton farther off is still a shooter, and the player still takes cover from it.
- **Corpus scene:** after the change it passed 6 of 6 alone. Each time Echo fought the skeleton (3 hits), broke off once it was out of reach, rested and climbed out.
- **Guards:** narrowed to NativePortalCampaignGameTests, NativeDynamicPathGameTests, NativeCombatGameTests, NativeCoverClimbGameTests and NativeFollowUpGameTests, 113 tests in all. All 113 passed in each of 3 runs.
- **Corpus gate:** 287398b over 3 runs, against #215's 811b0f7 baseline (6 runs, 332 scenes), applied uncommitted.
  - **Result:** 181 of 333 pass (54.4%), 77 intermittent, 75 fail.
  - **This scene:** it passed in all 3 runs.
  - **Flagged and cleared:** 4 scenes. The branch failed each 0 of 3 alone:
    - `diag-factions303-079bdc2-303-sol-102920` (main 3 of 3)
    - `e40a13-fe2ba3b-20261030-sol-106709` (main 3 of 3)
    - `e40a13-fe2ba3b-505-sol-18180` (main 2 of 3)
    - `f29e-on-891567637-kindred-93075` (main 3 of 3)
  - **Confirmed:** 0. Replay players still acting after their batch: 0, 0, 0. The gate passes.
  - **Main side:** the confirm ran on main's tip a7147186, because this gate predates #221.
  - **Overlap:** two short compiles in another worktree overlapped the gate, one at the end of run 2 and one at the start of run 3. Neither run stands out:
    - run 1: 115 failures, 20 scenes failing in that run only;
    - run 2: 109 failures, 11 failing only there;
    - run 3: 113 failures, 11 failing only there.
