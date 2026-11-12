# e40c3-2c67d67: Nether ground for two stalls (RUNNER 2, 2026-11-12, for LOOPS)

The build is main `2c67d67`, `goal=dragon; ticks=288000`, earned (e40c3, PR #271).
- No replay capture exists for either moment. Replays are captured only on a stuck spell or a death, and the corpus harness stages in the Overworld test level only.
- `run.py --keep-world` keeps only the region files and the companion database, so there is **no level.dat**. Use the seed and settings above instead.

## 303: Kindred's blaze fight, about tick 182300 to the end
- **Where:** the Nether, 43 24 -90, at the fortress ("End: Blaze rods: A blaze in sight: its rod - Fight blaze").
- **What happens:** "Fight blaze" is cancelled "Hazard/threat detected: urgent retreat required" 390 times and never completed, with no retreat run.
- **Files:**
  - `303/world-kept/DIM-1/region/r.0.-1.mca`, the Nether region holding x 0..511 and z -512..-1, so ±64 blocks of the spot;
  - `303/console.txt.gz`;
  - `303/eval-result.json`.

## 8675309: Echo's reconciliation hold, about tick 94356
- **Where:** the Nether, -75 43 -142 ("End: Fortress: fight back - Out of the hoglin's reach: pillar up").
- **What happens:** in the same second the `native_pillar` is BLOCKED ("no longer where the pillar was planned"), COMPLETED, then CANCELLED ("The hoglin is on it; fight back"). Then: "Native player … blocked: Durable native action requires reconciliation", `IllegalStateException: Pillar step cancelled with uncertain outcome` (PlayerRuntime.java:2256). Echo has no goal or task line for the remaining ~194,000 ticks.
- **Console:** the block is at line 25923 of the console.
- **Files:**
  - `8675309/world-kept/DIM-1/region/r.-1.-1.mca`, the Nether region holding x -512..-1 and z -512..-1, so ±64 blocks of the spot;
  - `8675309/console.txt.gz`;
  - `8675309/eval-result.json`.

The whole kept worlds, Overworld and Nether, stay in the runner's run folder if more ground is needed. The consoles scan to 0 for keys and model IDs.
