# F-27a: two e40a24 wakings with their whole kit in reach join the corpus (F-25c4di1f's measure)

Date: 2026-11-16
Agent / branch: Claude RUNNER 2 / claude/corpus-e40a24-1116
Status: complete (a scene-only part of F-27a)

This is a scene-only addition, as with #370 and #381: 2 scenes and their baseline entries, with no code change.

**These two are F-25c4di1f's measure** (drop recovery on waking). LOOPS chose them: they are the only wakings so far
with the player's own whole dropped inventory in its box.

**What is added** (from E-40 iteration 24, `e40a24-83321ac`: main 83321ac, ten seeds, `goal=nether; ticks=144000`,
earned, strategist off, replays on; #384):

| Scene | Death | Box | Drops in it | At the cut |
| --- | --- | --- | --- | --- |
| `e40a24-83321ac-707-sol-143252` | shot by a skeleton at −23, 70, 32 | 47 × 21 × 63, its death inside | its inventory, 19 ticks old: iron chestplate, shield, stone sword, stone pickaxe 3, water bucket, bucket, furnace, crafting table, coal 21, torch 2, lapis 12, cobblestone 631, dirt 150 and more (35 item entities) | health 2.1, food 5 |
| `e40a24-83321ac-8675309-sol-61008` | slain by a zombie at −3, 37, 83 | 56 × 34 × 43, its death inside | its inventory, 19 and 229 ticks old: iron pickaxe, iron chestplate, iron ingot, shield, stone sword, stone pickaxe, wooden pickaxe, water bucket, bucket, furnace, fishing rod, coal 7, cobblestone 899, flint 13 and more (39 item entities) | health 13.4, food 19 |

- Imported from `claude/corpus-data-e40a24-1116@3c00679` with `tools/corpus/import.py --keep-structures`.
- Their class is import.py's: "waking: its death in the box".

**Six runs of each alone** (`KINDRED_CORPUS_ONLY` naming one scene; 1f's "before"):
- **Setup:** alone on a 4-core container, `runCorpus --max-workers=2`, every run from an empty corpus world, on
  b47804b (main 711f5a6 with the scenes).
- **Failed per run:** 0 in all twelve. Replay players still acting after their own batch: 0 in every run.

| Scene | Passed of 6 | Armour back on | "Recover dropped items" lines | `native_goto` BLOCKED a run | Picked up | Baseline |
| --- | --- | --- | --- | --- | --- | --- |
| `e40a24-83321ac-707-sol-143252` | 6 | 0 of 6 (0 of 4 slots) | 0 | 6 to 9, all "No walkable route" while exploring for animals | dirt only, 8 to 12, its own digging | pass |
| `e40a24-83321ac-8675309-sol-61008` | 6 | 0 of 6 (0 of 4 slots) | 0 | 0 | dirt only, 9, its own digging | pass |

- **What it did instead:** on 707, "Wood: hurt and out of food" (exploring and cutting through for animals), then
  digging in for the night; on 8675309, digging in and sealing a shelter for the night, then waiting for daylight.
- **So the "before" is no recovery:** in none of the twelve runs did Sol go back for its kit or pick up any of it.
- **Not verified here:** that the item entities stand in the staged world. The structures hold them, and replay
  staging places the structure with its entities and discards only those left in the source box.

**Baseline:** `tools/corpus/baseline.json` keeps its entries as they were and gains these two. The pass rule is
unchanged: each passes by staying alive; recovery and armour are reported, not part of the pass. Its `notes` say which
runs made them.

**Who works on them:** LOOPS, for F-25c4di1f.

## Validation
- `python3 -m unittest discover -s tools/corpus`: OK.
- `support.py tracker`: in order.
- No runtime claims beyond the twelve runs above.

## Remaining limitations and handoff
- **Not tested:** the entries come from runs of each scene alone. In a full run they share batches with other scenes.
- **Exact next action:** LOOPS's 1f change, then the same six runs each as its "after".
- **Updated documentation:** this file, linked from `F-27a.md`.
- **Manifest changes:** none.
