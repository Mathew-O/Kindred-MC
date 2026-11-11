# F-27a — At the per-kind cap, the farthest are forgotten

Date: 2026-11-11
Agent / branch: claude/knowledge-cap-1111
Status: in progress (part of F-27a; linked from F-27a.md once merged)

For seed 110's bamboo loop: the follow-up to the logs-kept-out line (`F-27a-gather-debug.md`).

- **Cause:** `WorldKnowledge` keeps at most 2,000 blocks of a kind. Once a kind was full, `remember()` refused every new block. A player who had walked through a jungle knew 2,000 logs it had left behind, and none of the trunks five blocks off: the recap logged "0 known" with 42 to 82 logs within 8 blocks. An offline count by RUNNER 2 put 10,907 logs along Kindred's path, against the 2,000 cap.
- **Change:** when a kind is full, `remember()` now forgets the 200 farthest blocks of that kind (`EVICT = MAX_PER_KIND / 10`) and then keeps the new one.
  - **Distance:** measured from where the last scan looked round from. Before any scan, it is measured from the new block.
  - **Unchanged:** `recall()` (F-30d) still refuses at the cap, and other kinds are untouched.
  - **Cost:** one sort per 200 new blocks at the cap, not one per block.
- **Atlas (F-30):** a forgotten block seen again goes to `onFirstSight` again. `Atlas.sighted` then raises that chunk's sighting count by one and updates its time.
  - **Before:** at the cap, the atlas heard of no new block of that kind at all.
  - **After:** it hears of new ones again, and it can count a block twice if the player leaves, forgets it and comes back.
  - **Effect:** nothing in core reads the count. The atlas's behaviour is not changed here.
- **Pure tests:** `WorldKnowledgeCapTest` passes 4 of 4.
  - **Near and far:** near blocks are kept and the farthest go, leaving CAP − EVICT + 1.
  - **Repeats:** a block already known evicts nothing.
  - **Other kinds:** they are untouched.
  - **Timing:** the mean of 20,000 calls at the cap stays under 50 µs, after a warm-up (1.6–4.4 µs measured warm).
- **Native fixture:** `NativeKnowledgeCapGameTests` (staged).
  - **Ground:** bare field. After its first sweep, 2,000 real oak logs are placed 110–129 blocks east and remembered. The fixture places them; they are not earned.
  - **Target:** then a jungle log is placed three high, 4 blocks east.
  - **Pass:** the player holds wood.
  - **Before the change:** it failed 2 of 3, for the cap reason (2,000 known, 0 of the tree's 3, through tick 2,400).
  - **After:** it passed 3 of 3, with 1,800 far logs plus the tree's 3 known.
  - **Discarded stagings:** in the first, the memory was filled before the first scan, which wipes it, so it passed without the fix. In the second, fake logs were written where none stood and were purged within 20 ticks.
- **Build:** the full build was green on 557d9df.
- **Corpus gate:** 49ff60d against main b867e1a, over 3 runs. The baseline was #215's 811b0f7 (6 runs, 332 scenes), applied uncommitted. Main's committed baseline is 084dde5 (247 scenes).
  - **Result:** 179 of 332 pass (53.9%), 81 intermittent, 72 fail. Death is 149 / 71 / 66, stuck 29 / 10 / 6, fixture 1 / 0 / 0. Survival is 55.6% and factions 47.9%.
  - **Comparability:** 3 runs give a scene fewer chances to fail than the baseline's 6, so 53.9% against 49.7% is not a measured gain.
  - **Flagged 1, confirmed 0:** `e40a13-fe2ba3b-505-sol-18180` was flagged and then cleared. The branch failed it 1 of 3 and main passed it 2 of 3. Replay players still acting after their batch: 0, 0, 0. The gate passes.
  - **Seed 110's bamboo spot:** it is not in the corpus, so the gate does not test it directly. Only a live run can confirm that loop is gone.
