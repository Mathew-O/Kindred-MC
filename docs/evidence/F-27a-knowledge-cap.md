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
- **#219's first CI run (cb1ebc4) failed this fixture, and the fixture was at fault.** The fix was working: from tick 60 the player knew all 3 of the tree's logs and 1,800 far ones.
  - **What went wrong:** the fixture laid its ±10 ground round wherever the player came in, and put the far logs past its 80-block FIELD structure. In CI the player came in 2 blocks up, on what an earlier test had left there. Its first "Explore north for logs", started before the tree was set, took it off that raised ground, and it found no route back up to the tree ("expanded 6000"). It ended 109 blocks off.
  - **Restaged:**
    - The WIDE structure (120 by 120) is used, with stone, dirt and grass laid over the whole of it and air above; the whole structure is kept and restored.
    - The player stands at (8, 7, 60) and the far logs 100–111 blocks east, inside the structure.
    - The fixture takes the first look round itself, then fills the memory and sets the tree, all before the goal starts.
  - **A stricter pass:** on this field with no fix the player walked to the far logs and cut those, 3 of 3. So the test now passes only once the tree beside it is cut and the player holds wood.
  - **Results:**
    - With the old refuse-at-cap line put back, it failed 3 of 3 (0 of the tree's 3 known through tick 2,400).
    - With the fix, it passed 3 of 3.
    - In the full GameTest set it passed.
  - **The one other failure in that full set:** `NativeBaseGameTests.aPlayerWithStoneToolsSitesAndBuildsItsOwnCabinThenWaitsForIron` ran out of time short of logs.
    - Alone, it passed 2 of 3 on this branch (then based on main b867e1a) and 3 of 3 on main b7027be.
    - Its field holds two trees, 12 logs, and the change only acts once a kind holds 2,000. So the change does nothing in that test.
- **Build:** the full build was green on 557d9df.
- **Corpus gate:** 49ff60d against main b867e1a, over 3 runs. The baseline was #215's 811b0f7 (6 runs, 332 scenes), applied uncommitted. Main's committed baseline is 084dde5 (247 scenes).
  - **Result:** 179 of 332 pass (53.9%), 81 intermittent, 72 fail. Death is 149 / 71 / 66, stuck 29 / 10 / 6, fixture 1 / 0 / 0. Survival is 55.6% and factions 47.9%.
  - **Comparability:** 3 runs give a scene fewer chances to fail than the baseline's 6, so 53.9% against 49.7% is not a measured gain.
  - **Flagged 1, confirmed 0:** `e40a13-fe2ba3b-505-sol-18180` was flagged and then cleared. The branch failed it 1 of 3 and main passed it 2 of 3. Replay players still acting after their batch: 0, 0, 0. The gate passes.
  - **Seed 110's bamboo spot:** it is not in the corpus, so the gate does not test it directly. Only a live run can confirm that loop is gone.
- **#219's CI on c90adfa failed the cabin test, and the change was at fault.** The test was `NativeBaseGameTests.aPlayerWithStoneToolsSitesAndBuildsItsOwnCabinThenWaitsForIron`: "Stone mined for the walls: 43".
  - **Why the change acts there:** the field's 12 logs are not the only kind. The stone round the field fills that kind's 2,000 at the first look.
    - **Before the change:** the known stone froze there.
    - **After it:** the stone its own pit uncovers is remembered too.
    - **Where that leads:** `stoneFor` takes "stone in sight" within 6 blocks, and its staircase digs only to a fixed depth (six under the floor). The stone it now knew lay below that depth, so it mined down through its own floor.
  - **The counts:**
    - **In CI:** 19 of its 62 came from the layer under the field (y −1), so the field's own stone came up short.
    - **Locally, on the branch:** in the earlier run of three, it mined below the field in 2 (down to y −1 and y −2) and passed the third.
    - **On main:** 3 of 3 runs mined only the field's top layer.
  - **Fixture first:** the cabin test now also checks "No stone mined below where it digs to" (stone below the dig depth, counted before and after). Nothing in it was loosened.
    - **Without the fix:** it failed 3 of 3. One run failed that check (37 below). Two ran out of time, stuck below ground in a loop between "Back up to the build" and "Explore for logs", with 22 and 48 blocks below the field remembered.
    - **With the fix:** it passed 3 of 3, with 62 mined and 0 below each time.
  - **Change:** `PortalRunCampaign.stoneFor` gathers stone in sight only at or above the depth it digs to. The staircase and tunnel are unchanged, and so is eviction.
