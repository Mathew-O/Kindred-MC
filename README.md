# Data: e40a23-3115b1c (E-40 iteration 23), for LOOPS

The run: main 3115b1c, ten seeds, `goal=nether; ticks=144000`, earned, 2026-11-15 (PR #352). No kept worlds: iteration 23 ran without `--keep-world`, as iterations 21 and 22 did.

- `e40a23-3115b1c/<seed>/`: each seed's `eval-result.json`, `console.txt.gz` and replays (`replays/world/<player>-<tick>/replay.json`, `.snbt`).
- `extract/`: `window.py` (a player's lines between two run ticks, ticks from the console clock about ±100) and `around.py` (every line within N seconds of a time, stack traces included).

## 101: Sol's respawn loop at its bed (ticks 87,674 to 135,766)
- `extract/101-sol-82000-140000.txt`: Sol's lines from before the sleep through the loop (2,613 lines).
- `extract/101-all-lines-around-the-sleep.txt`: every console line within 90 s of 07:51:20 (3,219 lines).
- **The sleep:**
  - From 07:51:32 to 07:51:39, Sol tried "Set its bed down in its pocket for the night" and got "native_bed BLOCKED: The bed was not set down" 85 times.
  - Then "native_bed CANCELLED: A hostile mob is attacking; fight back first", and "Fight skeleton", which broke off at 07:51:42 ("Take cover from skeleton").
  - At 07:51:43 the bed set down at −377, 31, −143, and Sol slept there.
- **On LOOPS's question:** Sol had no "Seal the shelter" step before this sleep, blocked or otherwise. Its pocket was never sealed, and a skeleton was attacking it moments before it slept. On e40c4's 303 (data branch `claude/corpus-data-e40c4-1115`), Kindred's "Seal the shelter at 312, 51, 171" was BLOCKED once ("Placement preconditions, claim, or policy no longer valid"), and the next seal step completed.
- **The loop:** 12 deaths between ticks 87,674 and 135,766, under cover in the cave. 12 respawns at −376, 31, −145, and armour 0 from the second death.
- **Replays in the window:**
  - 9 deaths: 87674 slime, 90411 creeper, 96652 zombie, 110399 zombie, 113884 zombie, 116233 zombie, 132003 skeleton, 133523 witch, 135766 zombie.
  - 1 stuck spell: 120520.
  - The first three deaths are 87674, 90411 and 96652; the last is 135766.
  - The loop's other 3 deaths (111394, 114364, 114980) have no replay.
