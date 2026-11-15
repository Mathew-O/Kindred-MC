# F-25c4df — A weapon broken on a single attack's blow is wear, as the game counts it broken

Date: 2026-11-15
Agent / branch: LOOPS, claude/attack-wear-1115
Status: complete (2026-11-15)

## The stop: E-40 rung c (`e40c4-b23acc0`, #334), seed 101, Kindred
- **The cost:** held for reconciliation for the run's last ~64,500 ticks, from about tick 223,500. It was the batch's only ERROR line.
- **Source:** RUNNER 2's `claude/corpus-data-e40c4-1115` @ a2dfa4e: `extract/101-all-lines-30s-around-the-error.txt`, the full console, and `worlds/101-world-kept.tar.gz`.
- **What happened (wall clock, 22:23:36):**
  - "Hunt sheep for wool": three `native_attack` COMPLETED, the last at −280 78 −235.
  - Then "Pick up white wool", and in the same second "Native player … blocked: Native action outcome requires reconciliation" with `IllegalStateException: Attack item conservation check failed; reconcile` (`PlayerRuntime.observeRunner:8370`), then "no replacement inventory will be created".
- **What it held at the stop** (its save in the kept world, `playerdata/43ef4f9e-…`, at −279 78 −234; it did nothing after the stop):
  - selected slot 6, empty;
  - no sword, axe or pickaxe anywhere in its pack;
  - the stone and iron pickaxes it picked up at 22:05:10 were both gone.
- **Why:** with no sword, it struck the sheep with its last pickaxe, which broke on the blow.
  - `NativeAttackAction.verifyAttackConservation` checked the attack slot's item before and after; empty after the blow was "a different item", so the check failed.
  - F-25c4o taught `NativeFightAction.conserved` that a weapon worn out in a fight is wear, as the game counts it broken. The single attack, used for hunting, was never taught it.
- **No corpus scene:** a blocked runner raises no stuck spell and no death, so no replay was taken after it.

## The change (`NativeAttackAction`)
- **Before the blow,** it notes how many of the item it strikes with the game counts this player has broken (its own `Stats.BROKEN` statistic).
- **After the blow,** the attack slot may be empty only when that count rose by exactly one. Every other rule is as it was:
  - every other slot exactly as it was;
  - the weapon the same item, at most two wear on;
  - anything else still uncertain, held for reconciliation.
- **Nothing is replayed or recreated;** the broken pickaxe is not given back.

## Fixtures (staged): `NativeAttackWearGameTests`
- **`aPickaxeThatBreaksOnTheBlowIsWearAndThePlayerCarriesOn`.**
  - Setup: a native player with a stone pickaxe one blow from breaking (its wear set by the fixture) in hotbar slot 0 and nothing else; a sheep in reach in front of it, its AI off; one `native_attack` on the sheep from slot 0.
  - Passes if the attack's record ends VERIFIED, the player is not held for reconciliation, the pickaxe is gone and the game counts one stone pickaxe broken, and a next task is accepted.
  - **Before** (main d257164's `NativeAttackAction`): 3 of 3 fail. Each logs "record UNCERTAIN, held for reconciliation true, slot 0 air, stone pickaxes broken 1": the run's stop.
  - **After:** 3 of 3 pass. Each logs "record VERIFIED, held for reconciliation false, slot 0 air, stone pickaxes broken 1".
- **`aWeaponGoneWithoutTheGameCountingItBrokenStillAsksForReconciliation`:** the rule itself, on inventories made in the test.
  - Passes: a blow's wear (an iron sword one wear on); a pickaxe gone with exactly one counted broken.
  - Still fails, each asked: a pickaxe gone with none counted broken; one gone with two counted; one turned into a stick; one gone with another slot changed.
  - **This is the proof the check was not loosened:** only the game's own count of one broken lets an empty slot through.
- **The other fixtures that attack,** 12 classes in one run: all 153 required tests pass.
  - The classes: AttackWear, Combat, Gold, HuntCompass, Manhunt, MatchFramework, Nether, PaidSkills, PortalCampaign, Raid, Ruleset and Storage.

## Corpus gate (F-27a, 444 scenes, on 39a06fa; main d257164, the merge-base)
- **The corpus does not all pass on main,** so raw failure counts say little. Each run against the baseline (6 runs on main; 239 scenes pass all six there):

| Run | Required tests failed | Scenes passing, of 444 | Baseline-passing scenes passing, of 239 |
| --- | --- | --- | --- |
| 1 | 116 | 328 | 228 |
| 2 | 108 | 336 | 231 |
| 3 | 108 | 336 | 234 |

- **Over the three runs:**
  - 292 of 444 scenes pass all three (65.8%), and 71 are newly passing against the baseline.
  - Replay players acting after their own batch: 0, 0, 0.
- **The changed check in the corpus:** 9, 18 and 28 `native_attack` steps in the three runs. None failed its conservation check ("Attack item conservation check failed": 0 in each).
- **Flagged:** 6 scenes that pass on the baseline failed 2 or more of 3. Each was confirmed alone, in a run of its own, 3 times on the branch and 3 times on main d257164, as `gate.sh` does:

| Scene | Branch failed (alone) | Main failed (alone) |
| --- | --- | --- |
| e40a13-fe2ba3b-20261030-sol-106709 | 1 of 3 | 0 of 3 |
| e40a15-dbf4e5c-404-kindred-36022 | 2 of 3 | 1 of 3 |
| e40a22-d2389c9-202-kindred-48843 | 2 of 3 | 3 of 3 |
| f29e-on-4h-20261030-echo-272968 | 0 of 3 | 1 of 3 |
| f29e-on-4h-707-kindred-222518 | 2 of 3 | 2 of 3 |
| factions-110-d5057a4-110-kindred-190720 | 2 of 3 | 2 of 3 |

- **The report:** 5 cleared, 1 confirmed (kindred-36022).
- **No flagged scene's player attacks** with `native_attack` in any of the three full runs, so the changed check never runs in any of them.

### How each clears (the lead's three-part rule)
- **The rule's three parts:**
  1. Main fails it alone at least once.
  2. The branch is not worse at a one-sided Fisher p < 0.10.
  3. No changed line acts in it, or it acts alike on passing and failing runs.
- **e40a15-dbf4e5c-404-kindred-36022** (a zombie death; confirmed by the report): all three parts hold.
  1. Main failed it 1 of 3 alone.
  2. The branch passed 1 of 3 against main's 2 of 3: p = 0.5.
  3. Its player makes no `native_attack` in any of the branch's six runs (three full, three alone), and no attack check fails in any of them. Its fights go through `native_fight`, which this change does not touch.
- **e40a13-fe2ba3b-20261030-sol-106709:**
  - Part 1 is not met: main failed it 0 of 3 alone.
  - It clears on part 2 (the branch 1 of 3 failed against 0 of 3, p = 0.5) and part 3 (no `native_attack`).
- **e40a22-d2389c9-202-kindred-48843:** main failed it 3 of 3 alone and the branch 2 of 3. No attack.
- **f29e-on-4h-20261030-echo-272968:** passed 3 of 3 alone on the branch, against main's 2 of 3. No attack.
- **f29e-on-4h-707-kindred-222518, factions-110-d5057a4-110-kindred-190720:** each failed 2 of 3 alone on both sides. No attack.
- **Where it is kept:** the summaries, the XML, the report and the confirms are on `claude/spacer-runs-1112` under `gate25c4df/`.

## Remaining
- None.
