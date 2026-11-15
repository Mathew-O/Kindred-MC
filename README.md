# Data: e40c4-b23acc0 (E-40 rung c's fourth probe), for LOOPS

The run: main b23acc0, ten seeds, `goal=dragon; ticks=288000`, two at a time, worlds kept, earned, 2026-11-14/15. See `docs/evidence/eval/e40c4-b23acc0/notes.md` (PR #334).

- `e40c4-b23acc0/<seed>/`: each seed's `eval-result.json`, `console.txt.gz` and the replays (`replays/world/<player>-<tick>/replay.json`, `.snbt`).
- `worlds/101-world-kept.tar.gz` and `worlds/303-world-kept.tar.gz`: those seeds' kept worlds (region files, the companion database and the players' saves at the run's end; `DIM-1` for 303).
- `extract/`: a player's console lines between two run ticks (`window.py` from the e40a22 data branch; ticks from the console clock, about ±100).

## 101: Kindred blocked for reconciliation (about tick 223,500)
- `extract/101-kindred-218000-229000.txt`: Kindred's lines around it. While hunting sheep for wool, "Native player … blocked: Native action outcome requires reconciliation", from `IllegalStateException: Attack item conservation check failed; reconcile` at `PlayerRuntime.observeRunner` (PlayerRuntime.java:8370). Then "no replacement inventory will be created", and no goal line for the rest of the run.
- No replay exists after tick 189,720 for Kindred: a blocked runner raised no stuck spell and no death, so there is no corpus scene for it.
- Kindred did nothing after the block, so its save in the kept world (`playerdata/`) is its state from the block on.

## 303: Kindred's 23 deaths at its bed's spawn (ticks 194,201 to 237,417)
- `extract/303-kindred-190000-240000.txt`: Kindred's lines over the loop.
  - Every respawn was at 314, 51, 172, where it had slept once underground while tunnelling toward lava.
  - Its first goal after the first respawn was "Take its bed back up in the morning".
- Replays in the window: 12 death replays (194201, 196504, 207782, 209176, 210785, 213748, 216560, 218103, 230257, 234510, 235741, 237417) and 2 stuck spells (200600, 205340). The other 11 deaths have no replay.
