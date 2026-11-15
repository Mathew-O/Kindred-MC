# F-25c4di — On the way, flint only from gravel at hand, never searched or tunnelled for

Date: 2026-11-15
Agent / branch: LOOPS, claude/respawn-cave-1115
Status: complete (2026-11-15). Its open part, the respawn loop's deaths, is the child F-25c4di1.

## What this row claims
- On the way, for what comes later, a follow-up that is not due no longer searches for its source.
- FLINT ignored `due`; it no longer does.
- **It does not claim** that the respawn loop's deaths are fixed. They are F-25c4di1, below.

## The loop: E-40 rung c (`e40c4-b23acc0`, #334), seed 303, Kindred
- **The deaths:** 23, from tick 194,201 to 237,417, 23 of the batch's 48.
- **Source:** RUNNER 2's `claude/corpus-data-e40c4-1115` @ a2dfa4e: `extract/303-kindred-190000-240000.txt` and `extract/303-all-lines-the-sleep-and-first-death.txt`.
- **The night:**
  - Tunnelling toward lava for the cast, it sealed a pocket at 314, 51, 171. Its first seal block was refused ("Placement preconditions, claim, or policy no longer valid"); the next completed.
  - It set its bed down there and slept (00:24:07).
  - Woken among zombies, a creeper, a spider and a skeleton, it was slain by a spider at 281, 40, 188 (tick 194,201).
- **Every respawn was at 314, 51, 172,** underground, with nothing. Armour was 0 from the second death on.
- **What it spent its steps on** between respawns and deaths (goal lines, positions folded):
  - "on the way, for what comes later - Tunnel for gravel: advance" 308; "… step down" 134; "… Walk to gravel at …" 131;
  - "Gather logs for a table and a wooden pickaxe - Climb toward the surface for logs" 242;
  - the rest was fights and retreats ("Back off from zombie" 79, "… skeleton" 54, …).
- **After the second respawn, for example:**
  - "Walk to gravel at 306, 51, 159", "307, 51, 157" and "307, 53, 155", each `native_goto BLOCKED: No walkable route`;
  - then "Tunnel for gravel: step down" and "advance", hundreds of times, to and fro between 314 and 315.

## Why
- **The doc and the code disagree.** `FollowUps.realize`'s doc says a chunk searches for its source only when it is due at its gate, otherwise only what is at hand.
- **FLINT broke that rule.** It called `campaign.flint`, which gathers gravel within 48, then `search(body, "gravel")`: underground, a tunnel.
- **What made flint "at hand"** (`atHand`): gravel within 12, or gravel carried. Gravel seen within 12 with no route to it therefore started an open-ended tunnel.
- **Flint is wanted for the End's arrows,** so only on the End goal (rung c ran `goal=dragon`).

## The change
- **`PortalRunCampaign.flintAtHand`:** gravel beside it dug, gravel carried set down to dig, or gravel within the twelve that made flint count as at hand. No search.
- **`FollowUps.realize`'s FLINT case** calls it when not due; when due, `campaign.flint` as before.
- **`campaign.flint` had no other caller.**

## Fixture (staged): `NativeRespawnCaveGameTests`
- **Setup:**
  - its pocket two high under rock, open east along a ledge to a chasm four wide and eleven deep;
  - across the chasm, two blocks of gravel nine and ten east of its feet: in sight, no walkable route;
  - the player with nothing, no armour, as respawned; daylight held;
  - the dragon goal, as the run's, with the fight on for this fixture's runtime;
  - the gravel seen by the campaign's own sweep from where it stands.
- **Fails** as soon as the step is "on the way, for what comes later - Tunnel for gravel" (or an explore for gravel). **Passes** once the step is the climb toward the surface, within 1,200 ticks, alive.
- **A slip on the way:** the first version ran on the portal goal, on which flint is never wanted, and passed before the fix. On the dragon goal it reproduces the run.
- **Before** (main's `FollowUps` and `PortalRunCampaign`): 3 of 3 fail. The steps cycle "Tunnel for gravel: advance" and "Explore east / west for gravel" in its pocket for the whole budget.
- **After:** 3 of 3 pass. "Walk to gravel" is tried once and refused, then "Climb toward the surface for logs" in 4 to 6 ticks.
- **The other fixtures it could touch,** 31 classes in one run: all 173 required tests pass.
  - Atlas, CastRefusal, CastTable, CastWaterLost, DigFill, End, FactionWants, FallingColumn, FollowUp, FrameReturn, GravelTunnel, HalfCastFrame, Initiative, Nether, ObstructedPortal, PeerLedger;
  - PortalCampaign, PortalLight, RecipeGuard, RespawnCave, SeabedWater, SealedUnderSea, SnowSite, Spire, TeamPortal, WaterBox, WaterCut, WaterEdge, WaterHill, WeaponToHand and Wire.
- **On the merged tree** (main 7d28647): RespawnCave, FollowUp and End pass in one run, all 15 tests.

## The run's scenes (RUNNER 2, #337), each alone on the fix
Copied into this tree for these runs only; on main they passed as RUNNER 2 measured them, 6 runs of only the new scenes.

| Scene (tick) | Kind | On main, passes of 6 | On the fix, passes of 3 | On the fix |
| --- | --- | --- | --- | --- |
| 200600 | spell, refused target repeated | 0 | 0 | no tunnel; about 70 climb steps, 8 blocks up by hand, short of the budget |
| 210785 | creeper death | 0 | 0 | dies as it did; the scene starts mid-fight |
| 196504 | zombie death | 2 | 2 | |
| 213748 | zombie death | 1 | 0 | dies as it did; mid-fight |
| 237417 | zombie death | 1 | 0 | dies as it did; mid-fight |

- **200600 on main**, once, in a tree at main: 387 "on the way, for what comes later - Tunnel for gravel" steps and no climb at all.
- **On the fix:** no step for gravel but the refused walks, and the climb by hand. Stone by hand is about 7.5 s a block, and the spell's 3,600 ticks are not enough to get out ("out of its 8 blocks only for the last 1,246"). The scene's budget stays as it is.
- **The loop on the way is gone; most of these scenes still fail.** See F-25c4di1.

## Corpus gate (F-27a, 458 scenes, on 3332aec; main 7d28647, the merge-base)

| Run | Required tests failed | Scenes passing, of 458 | Baseline-passing scenes passing, of 242 |
| --- | --- | --- | --- |
| 1 | 111 | 347 | 233 |
| 2 | 117 | 341 | 234 |
| 3 | 121 | 337 | 236 |

- **Over the three runs:**
  - 295 of 458 scenes pass all three (64.4%), and 69 are newly passing against the baseline.
  - Replay players acting after their own batch: 0, 0, 0.
- **On the way, in the corpus:**
  - "Tunnel for gravel" appeared in none of the three runs.
  - "Walk to gravel" (at hand) appeared 75, 48 and 63 times.
- **Flagged:** 7 scenes. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on main 7d28647, as `gate.sh` does. **0 confirmed, 7 cleared:**

| Scene | Branch failed (alone) | Main failed (alone) |
| --- | --- | --- |
| diag-holepit-079bdc2-8675309-echo-51440 | 1 of 3 | 0 of 3 |
| e40a13-fe2ba3b-707-echo-80901 | 0 of 3 | 3 of 3 |
| e40a14-80b7150-101-echo-75842 | 3 of 3 | 3 of 3 |
| e40a15-dbf4e5c-404-kindred-36022 | 0 of 3 | 1 of 3 |
| e40a20-79a9aad-202-sol-100125 | 0 of 3 | 1 of 3 |
| e40a20-79a9aad-505-echo-52752 | 1 of 3 | 1 of 3 |
| f29e-on-4h-707-kindred-222518 | 0 of 3 | 1 of 3 |

- **No flagged scene's player takes any step for flint on the way** (no walk to gravel, no dig, no tunnel) in any of the three full runs, so the changed code never acts in them.
- **echo-75842** fails 3 of 3 alone on both sides: main's own, not this change's.
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4di/`.

## Remaining (F-25c4di1, open)
- **The respawn loop's deaths themselves:**
  - the mid-fight scenes 210785, 213748 and 237417 die either way;
  - 200600 is now a climb by hand too slow for its budget, against main's 387 tunnel steps and no climb.
- **The respawn-point levers are untried:** a bed set down in a cave of mobs, and armour 0 after the first death.
- **The same pattern recurs** on iteration 23 (`e40a23-3115b1c`, #352): seed 101 Sol slept in its carried bed underground at −377, 31, −143 and died 12 times there, 13 of the batch's 28 deaths. Its pocket was never sealed: its bed was BLOCKED 85 times, then set down the moment a skeleton fight broke off (RUNNER 2's `claude/corpus-data-e40a23-1115`).
- **Next, fixture first:** where it chooses to sleep and set its spawn, or what it does on waking in a cave that killed it. Earned behaviour only: no moved spawn, no granted armour.
