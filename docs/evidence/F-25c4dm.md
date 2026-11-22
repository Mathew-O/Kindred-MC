# F-25c4dm — A teammate's word of lava it filled its cast's bucket at, walked to before the blind hunt

Date: 2026-11-22
Agent / branch: REFACTOR, claude/f25c4dm-told-lava-1122, at the lead's word (stuck, "mining for a find", family B: the lava hunt)
Build: afa9b77c (the change and its fixture), on F-25c4dl's head 67ef4422 (#450); main 8d4d30c2 merged in after the gate
Status: Done. The mechanism is proved by its staged fixture; the corpus gate passed as a no-harm gate. The gain is measured in E-40 iteration 28, pre-stated below (iteration 27 began at 12:35 on 772dee37, without this change).

## What this row claims
- **The word:** a player that fills its cast's bucket at lava tells its team once a pool ("Lava here, if anyone needs it for the cast.").
  - It's told to exactly whom the trees word (F-25c4cc) is told: its own players in co-op, with a goal, alive, in the same world, not stop-latched, and none in a match. So no opponent in a race or a fight hears it.
- **The walk:** a teammate short of lava for its cast, with none seen and no cave to look in, walks to the told site a leg at a time before it mines blind.
  - Arrived, it sees the pool itself, and the cast's own fill goes on from there.
- **A heading, never a sighting:** nothing is known of the lava until the walker sees it. The told place is never read for lava, and never dug to.
- **When a site is set aside:** for the run, with the reason logged, on any of these:
  - a refused walk;
  - a walk that got it no nearer;
  - standing within 6 blocks for 200 ticks with no lava in sight;
  - 2,400 ticks with no ground gained.
  - The hunt then goes on as before.
- **It does not claim:** a seed under the 12-minute bar. Nor anything for iron: the lead agreed iron's spells are a working mine's ordinary variance.

## The loop (E-40 iteration 25, `e40a25s-0d3432b`, consoles at `fe0be8cd`)
- **What the consoles show:** players branch-mined for lava at Y=−54 (`LAVA_TOP`, F-25c4ac's design) after a teammate had already filled its cast's bucket.
- **The spells,** each from its own console, its teammate's fill line earlier than its watchdog line:

| Seed | Hunter's spells (tick) | The teammate's fill | Distance |
| --- | --- | --- | --- |
| 101 off | Kindred 86980, 94180 | Sol, −366, −54, −239 | 199, 205 |
| 404 off | Echo 72840, 91100, 96660 | Kindred, −52, −54, 1 | 138, 155, 158 |
| 606 on | Sol 53240 | Kindred, 261, −54, −16 | 82 |

- **What the spells' windows held:** all six are the blind `descendOrTunnel` hunt ("Branch mine for lava", 110–121 cells cleared in the 3 minutes).
  - None is #411's tunnel toward seen lava: no "Cast: dig toward the lava" in any window.
- **Left out:** on-202 Sol's two spells. Their windows hold "Cast: dig toward the lava at …": it had seen its lava, and this lever acts only with none known.
- **Data only, no world read:** the consoles and `score.py`'s own classing, imported unchanged. The scripts are in the session's scratch (`bmine.py`).

## Code read: where lava was held, and how the hunt chose
- **Held in one place only:** the player's own `WorldKnowledge` (`Kind.LAVA`). It's fed by its own Sight, by a cell it opened over lava, and after a restart by its own atlas (`AtlasRecall`).
- **No lava was shared between teammates anywhere:** not through team knowledge, `SocialBrain` or the atlas.
- **The one precedent:** F-25c4cc's trees (`treesFound`, `takeTreesFound`, `PlayerRuntime.tellOfTrees`, `toldOfTrees`, `toldTrees`).
- **How the hunt chose:** `lavaForCast` with no lava known went `followPocket`, then `cave(…, "lava", …)`, then `lookAround`, then `descendOrTunnel(LAVA_TOP, LAVA_TARGET, "lava")`.

## The change
- **`PortalRunCampaign`:**
  - **`lavaTaskFinished`:** a completed "Cast: fill a bucket with lava" sets `lavaFound` to the source it filled at, once a pool (`LAVA_APART`, 16). Filled at a site it was told of, it logs "reached the lava … told it of".
  - **`takeLavaFound`, `toldOfLava`:** the nearer told site is kept, never one set aside.
  - **`toldLava`:**
    - walks legs of `LAVA_LEG` (96) toward the site, with a partial walk;
    - logs "walks to the lava … told it of at …, N blocks off" once a site;
    - logs "sets aside the lava … told it of at …: reason" for each of the four reasons above.
  - **`lavaForCast`'s two hunt chains:** `toldLava` comes after `followPocket`, `cave` and `lookAround`, and before `descendOrTunnel`. Its own sightings and caves come first; only the blind mine is displaced.
- **`PlayerRuntime.tellOfLava`:** beside `tellOfTrees`, with its guards unchanged. It's called as the campaign's candidates are made, next to the trees word.
- **Untouched:**
  - `Digger`, `digToward`, `descendOrTunnel`, `LAVA_TOP` and `LAVA_TARGET`, the legs;
  - the watchdog and `score.py`;
  - #411's `Digger.java` and MODES's F-25c4cr1 (`PortalRunPlanner`). The lead checked both against these lines.

## Fixture (staged): `NativeToldLavaGameTests`
- **The ground:**
  - an andesite block with no sky, a passage two high 44 east then 24 south, and a chamber whose floor is a lava pool past its first two rows;
  - glowstone along the passage and in the chamber.
  - **Andesite, not stone,** so no floor in it reads as a cave's (`Sight.caveFloor` wants STONE). As in the iteration-25 spells, where the hunt came to the blind mine, there is no cave for the walker to look in.
  - **The pool is out of the walker's sight,** round the corner and 50 blocks off.
- **The players:**
  - **Filler:** in the chamber with the cast's kit. It sees the pool by its own sweep and fills its bucket on its own portal goal, so the word goes out through the runtime as in play.
  - **Walker:** at the west end with the cast's kit.
  - **Rival** (first case only): nothing in its pack, in a one-player speedrun match (`proposeSpeedrun`, `startMatch`) started before the word.
- **Staging, not the runtime:**
  - Both goals start together, 51 blocks apart. That's farther than the run's division reaches (`TEAM_RANGE`, 48), so each casts its own frame.
  - Once Walker's events show it was told, **the fixture** ends Filler's goal. Close by, `RunDivision` makes the walker the filler's provisioner ("Food for the run"), right for a team that has met up, and the iteration-25 fillers had gone on to their own portals.
  - On the first try without it, Walker came within 48 on its walk and was given provisions.
- **First case,** `toldWhereATeammateFilledItsBucketAPlayerShortOfLavaWalksThereNotMinesBlind`:
  - **Fails if:** once told, Walker mines blind ("Dig down toward lava", "Branch mine for lava") before its lava; Rival is ever told or walks there (checked sticky); or Walker has no lava in 2,400 ticks.
  - **Passes:** with Walker's lava, filled after its told walk.
- **Second case,** `toldOfLavaNoWalkReachesThePlayerSetsItAsideAndHuntsAsBefore`: the passage walled 30 east of Walker, no Rival. It passes once the told lava is set aside and the hunt has begun after it.
- **Before** (67ef4422, #450's head, without the change, the fixture alone): both cases fail in each of 3 runs.
  - **Run 1:**
    - case 1 fails, "Walker mined blind for lava (1 steps) before its lava";
    - case 2 fails, with no set-aside: there is no told site to set aside.
  - **Runs 2 and 3:** both cases fail, "Walker not told of Filler's lava in 1200 ticks: Filler Done: The Nether portal is built and lit". With nothing told on the base, the fixture's wait for the word runs out first.
- **After,** 3 runs each:
  - **Case 1** passes in 322 to 328 ticks: "Cast: walk to the lava Filler found at …", then "Cast: walk to the lava" and "Cast: fill a bucket with lava" on its own sight. No blind step; Rival never told.
  - **Case 2** passes in 181 to 184 ticks: "Walker sets aside the lava Filler told it of at …: its walk there was refused (No walkable route toward …)", then "Dig down toward lava".
- **With its neighbours,** 3 times (ToldLava, ToldTrees, CastPool, PortalCampaign): all 102 required tests passed in each.
- **On the merged tree** (2b281b68, with main 8d4d30c2), the same four classes 3 times: all 102 required tests passed in each. Case 1 took 321 to 328 ticks, case 2 183 to 185.

## Corpus gate: a no-harm gate (F-27a, on afa9b77c; base 67ef4422)
- **The corpus can't exercise the lever.** Each scene is played by its own `PlayerRuntime` (`ReplayGameTests:330`), one player to a runtime, so no scene's player has a teammate to tell or be told.
  - **The trees precedent:** in F-25c4dl's gate run 1 there were 35 completed log cuts and 34 cast fills, and 0 "Trees here" and 0 "Walk to the trees" lines.
  - **So, by construction:** "Lava here" lines, told walks and set-asides are 0. That is the marker rule's coverage argument for any flagged scene.
- **The read, stated before the gate:** flags cleared under the usual rule; portal lit not worse; deaths not worse at a one-sided Fisher p < 0.10.

- **The gate passes:** 7 flagged, 0 confirmed, 7 cleared under the usual rule.
  - `e40a13-fe2ba3b-505-echo-5394`: branch failed 0 of 3, main passed 2 of 3.
  - `e40a14-80b7150-101-echo-75842`: branch failed 2 of 3, main passed 0 of 3.
  - `e40a15-dbf4e5c-303-kindred-66804`: branch failed 0 of 3, main passed 2 of 3.
  - `e40a15-dbf4e5c-8675309-echo-87839`: branch failed 0 of 3, main passed 2 of 3.
  - `e40a20-79a9aad-505-echo-52752`: branch failed 1 of 3, main passed 2 of 3.
  - `f29e-on-4h-707-kindred-222518`: the known intermittent, branch failed 2 of 3, main passed 1 of 3.
  - `factions-e40a16seeds-b7b4bec-8675309-echo-106722`: branch failed 3 of 3, main passed 1 of 3.
  - **Not one of them could have been the change:** no lever line in any confirm run.
- **The lever's markers, per run (branch runs 1, 2, 3):**
  - cast fills: 29, 29, 14;
  - "Lava here": 0, 0, 0;
  - told walks: 0, 0, 0;
  - set-asides: 0, 0, 0;
  - "reached the lava … told it of": 0, 0, 0.
  - All 0, as stated by construction.
- **Portal lit:**
  - branch 2, 2, 0 `native_ignite_portal` completions, 4 of 6 scene-runs over two scenes (`R35841e5`, `Re5fe137`);
  - base 1, 1, 2, also 4 of 6. The base is 67ef4422, this branch's base, from F-25c4dl's gate.
  - Not worse.
  - Run 3's two portal scenes ended mid-cast at the budget, both on the cast's own "Cast: walk to the lava", not a told walk.
- **Deaths, against the same base:**
  - every scene: branch 265 of 1,407 scene-runs, base 245 of 1,407. One-sided Fisher p = 0.18.
  - the confirm runs: branch 8 of 21, main 11 of 21. p = 0.89.
  - Not worse at p < 0.10.

## The re-read on iteration 26 (`e40a26` and `e40a26r`, `f8b7ffe`, consoles at `1b99f522`)
- **Read the same way:** a watchdog spell whose player's last 300 lines hold at least 5 blind-hunt steps and no "Cast: dig toward the lava", against every earlier fill of a teammate's cast bucket, 16 apart.
- **Spells found:** 10. **With a teammate's fill before it:** 4, 12 minutes.

| Run | Seed | Hunter's spell | The teammate's fill | Distance |
| --- | --- | --- | --- | --- |
| e40a26 | 20261030 | Echo, 04:12:55 | Sol, 140, 78, −31 (surface) | 284 |
| e40a26 | 20261030 | Echo, 04:16:49 | Sol, as above | 320 |
| e40a26r | 20261030 | Sol, 07:22:05 | Echo, −24, −54, 105; Kindred, 141, 78, −30 | 238, 215 |
| e40a26r | 303 | Echo, 08:03:36 | Kindred, −203, 71, 69 | 155 |

- **The other 6 had no teammate fill before them** (101 Echo and Kindred, 303 Sol, 707 Sol and Echo, 606 Echo). The lever cannot act there.
- **Compared with iteration 25:** 26's told sites are farther, 155 to 339 against 82 to 205. Three of them are surface pools reached from −54. So a walk pays less often, and the realistic share of the 12 minutes is lower.

## The gain, measured in E-40 (pre-stated for iteration 28)
- **The three named hunters' lava-spell minutes:** off-101 Kindred 6 (12 → 6, or → 3 with F-25c4dl), off-404 Echo 9 (42 → 33), on-606 Sol 3 (12 → 9).
  - The bound is 18 minutes in all; realistically 0 to 18.
  - Each told site was 82 to 205 blocks off underground, and only a walk that reaches it pays. A dig there would be no faster than the blind hunt, so the change never digs to one.
- **Each seed will play differently by 28,** so the mechanism is read across every seed, per run:
  - "Lava here" lines (once a site);
  - told walks, each one's distance, and how many reached lava;
  - set-asides, by reason.
- **The cost:** told walks that ended short where the blind mine would have found lava.

## Remaining
- None for F-25c4dm. Its gain is E-40 iteration 28's to read, as pre-stated above.
- **Its child,** the cast refusing at its own site (iteration 26, seed 707 batch 2: "Cast: step closer" with no route, "No reachable face to pour against"), is the next slice.
- **Not done here:**
  - lava seen by a teammate but not filled at is not told of;
  - nor is any lava across a match.
