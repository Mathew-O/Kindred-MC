# Climb-loop stuck scene: factions-110-d5057a4, around spawn

- Source: the kept world of factions-110-d5057a4 (build d5057a4, seed 110, `goal=none; mode=factions; ticks=288000`,
  `--policy bases=true`), as the run left it at tick 288000, read from its region files.
- Box: x -16 to 31, y 68 to 99, z -36 to 11 (48 × 32 × 48), its corner at (-16, 68, -36), centred on 8, 84, -12. The
  densest cells of the console's 2,909 "Climb toward the surface for logs: back the way it came" lines (06:51 to 07:50)
  are inside it: Sol at 8, 88, -16; Kindred at 0, 80, 0 / 0, 80, -16 / -8, 80, -16 / -8, 72, -16.
- `structure.snbt` is in the replays' form (DataVersion 3955, size, data of {pos, state} with air left out, palette),
  with no entities and no block-entity data: chests and other block entities are their bare block states.
- It is the world at the run's end, not at the moment of the loop, which ran some 200,000 ticks earlier.
- Cut by `cut_structure.py` (Python 3 standard library). Checked against the replay Sol-21363 from the same run, cut
  from the same files over that replay's own box: 97.1% of positions agree, the rest vines grown and grass become dirt
  in the ticks between.
