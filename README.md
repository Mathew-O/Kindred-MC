# Data: wake101-3360ef2, seed 101 with the waking-scene cutter (F-27a4), for LOOPS

The run: main 3360ef2 (#375's merge), seed 101, `goal=nether; ticks=144000`, earned, strategist off, replays on,
2026-11-16 00:48 to 01:13 UTC; exit 0, 144,000 ticks. run.py from `claude/eval-keep-level-1115` (#376), so the kept
world (on the runner's container, not here) has its `level.dat`.

- `wake101-3360ef2/101/`: `eval-result.json`, `console.txt.gz` and the five replays (`replays/world/<player>-<tick>/`).

## What happened
- **No respawn loop this time.** Sol died once, to a spider at −384, 29, −131 (tick 117,908, by day, under cover,
  hunger 2, armour 6, backing off from the spider), and respawned at −277, 71, −120, the world spawn: it had not slept
  yet. Its first bed was set down at 01:11:45 console time, after that death.
- **One waking scene:** `Sol-117928`, `kind: respawn`, box origin −293, 63, −136, 33 × 17 × 33, its death outside the
  box (`deathInside: false`), `drops: []`, "alive at the end of 2400 ticks", "its armour back on (reported, not the
  pass)". At the cut: health 12.1, food 2, task "waking", stage "Wood: hurt and out of food - Climb toward the surface
  for food: to the wall".
- **The other replays:** Sol's death 117908 (spider); stuck spells Kindred 38120, Echo 52420, Sol 89840.
- Kindred and Echo did not die.
