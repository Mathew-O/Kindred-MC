# F-27a — Each corpus scene's replay player has a name of its own

Date: 2026-11-12
Agent / branch: claude/replay-names-1112
Status: in progress (part of F-27a; linked from F-27a.md once merged)

A harness change. No player behaviour changes.

## What was wrong
- **The old rule:** `ReplayGameTests` named a replay's player `R` + hex(hash(player) × 31 + tick) + `_` + run, from the recorded player and tick alone.
- **The collisions:** two pairs of corpus scenes, each the same player caught at the same tick in another world, were made under one name.
  - `e40a20-79a9aad-404-kindred-99080` and `diag-factions303-079bdc2-303-kindred-99080` were both `R2d3924b_1`, set in one batch and then the next.
  - `e40a20-79a9aad-20261030-kindred-123080` and `e40a20-79a9aad-404-kindred-123080` were both `R2d3f00b_1`, in the same batch, playing at the same time.
- **Found by:** `report.py`'s count of replay players acting after their own batch. It was 1, 2 and 1 in #241's runs on main, and 1 in each of #219's gate runs. It was a reused name, not a player outliving its test.
- **What it did to results:** not shown. The `123080` pair passed every run. `404-kindred-99080` passed 3 of 3 alone, but 1 of 3 in #219's full runs, against 3 of 3 in main's. Either way, it confounds what is being measured.

## The change
- **The new rule:** `ReplayGameTests.replayName(scene, player, tick, run)` names a scene's player `R` + hex(the low 28 bits of the scene id's hash) + `_` + run.
  - The corpus passes its scene id, and the replay suite its file id.
  - A scene staged in a fixture has no id, so it keeps the old player-and-tick name.
- **Within limits:** names stay within a profile name's 16 letters, digits and underscores (11 characters).
- **Nothing else depends on the name:** the native player's id is random (`UUID.randomUUID` in `createNative`). The name is no saved key, nothing seeds from it, and canonical skins go by roster names, which `R…` names are not.
- **Consequence for gates:** a gate on a branch cut before this change compares across the name change, the same caveat as for the spacers (#241). Main's results with it differ from main's without it only where the colliding scenes did.

## Evidence
- **New GameTest:** `ReplayGameTests.twoScenesOfOnePlayerAtOneTickGetNamesOfTheirOwn` checks both pairs, two runs of one scene, and profile-name form.
  - Before, with the old rule behind `replayName`: failed 3 of 3, "One name for two scenes: e40a20-79a9aad-404-kindred-99080 and diag-factions303-079bdc2-303-kindred-99080 both R2d3924b_1". It was the only failure.
  - After: passed 3 of 3, with all 10 of ReplayGameTests' tests passing each time.
- **New CI test:** `tools/corpus/test_names.py` replays the rule over the corpus's scene files: 422 names for 422 scenes. A scene imported later whose name another already has fails it. It runs in the existing `unittest discover -s tools/corpus` step.
- **The four scenes alone:** a `runCorpus` of just these four, on this branch, made them as `R179de23_1`, `R99cac67_1`, `Rc4acd4d_1` and `Rd3c7b91_1`, and all 8 tests passed (scenes and spacers).
- **Not yet shown:** that the leftover count reaches 0 in full runs. The 422-scene re-baseline runs on main 4ac2f3e with this change, and its report gives that count for each run.
