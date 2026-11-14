# Data: e40a22-d2389c9's replays

The replays of E-40 iteration 22: `e40a22-d2389c9`, main d2389c9, ten seeds, `goal=nether; ticks=144000`, earned, 2026-11-14. That is 80 stuck-spell replays and 20 death replays, as `tools/eval/run.py` kept them, with each seed's `eval-result.json` and the run's `build.json`.

`tools/corpus/import.py --keep-structures --source claude/corpus-data-e40a22-1114@<commit>` turns them into corpus scenes.

## Consoles and extracts (2026-11-14)
- `e40a22-d2389c9/<seed>/console.txt.gz`: each seed's full console.
- `extract/window.py`: a player's console lines between two run ticks. Each line's tick is placed on the console clock between the run's marks ("/tick sprint" is tick 0, then "thinking at tick N"), about ±100 ticks.
- `extract/101-kindred-40000-52000.txt`: seed 101 Kindred's lines around the pillar into the hole near −244, 46, −79, and the start of the stall.
- `extract/101-kindred-47820-held.txt`: what Kindred held at the first spell's replay (tick 47820). It has no pickaxe.
- `extract/death-<seed>-<player>-<tick>.txt`: the dying player's lines from 600 ticks before each of the 20 deaths to the death. `extract/deaths-index.md` lists them with each death's corpus scene.
