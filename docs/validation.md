# AI-player development validation

## The portal run in play, and the trip's wood — 30 October 2026 (`claude/loose-ends-1029`)

Linux x64 cloud container (4 cores), OpenJDK 21. Focused runs narrow the gametest entrypoints to a scratch class (not committed), each fixture in a batch of its own, and restore them after each run. Nothing else ran on the cores during the live runs.

- Live, on `fb02bc3` (F-25c4a to h), against B7's runs with the strategist off on `4d0884a`. Seeds 8675309 and 20261030, the starters on the Nether goal, 144,000 ticks each, every death logged with what it happened in. Echo again found no room at spawn on the second seed.

  | | 8675309, B7 | 8675309, now | 20261030, B7 | 20261030, now |
  | --- | --- | --- | --- | --- |
  | Deaths | 9 | 8 | 2 | 0 |
  | Furthest | iron | casting the frame (Echo) | obsidian | casting the frame (Kindred) |
  | Deaths taking cover with a mob on foot in reach | 5 | 0 | 0 | 0 |
  | Climbing out for food, Kindred | 18% | 0% | 0% | 0% |
  | Coal hunts, Kindred and Echo | 15% and 0% | 18% and 18% | 0% | 0% |
  | Tables put down, per player | 60, 55, 23 | 31, 11, 26 | 12, 137 | 9, 8 |

  - No portal lit in either.
  - F-25c4b, c, e, g and h met their in-play criteria: no death taking cover with a mob on foot in reach; the route's iron mined through the night; meat cooked; no suffocation; the table no longer put down and picked up in a loop (at most five times in a minute, where B7's loop did 26).
  - F-25c4d was not met: the fuel was packed once.
  - Three of the five players were stranded below with no pickaxe and no sticks: Sol on the first seed, Kindred and Sol on the second. They spent 35 to 43% of their time gathering logs for sticks and 24 to 29% waiting for daylight to go up for them. That is F-25c4i.
  - Falls into caves while gathering stone (Echo, here and in B7) were tried in a scratch fixture: a stone floor three thick over a cavern twelve deep, the goal needing cobblestone. It never fell through in three runs, so the fall is not reproduced.
- F-25c4i (the trip's wood):
  - `PortalRunPlannerTest`: 36 of 36. The new case fails with the packing switched off, and with the spare pickaxe's guard switched off.
  - `:companion-core:test`: 615 of 615.
  - The new fixture on the code before: fails, 3 planks' worth at its first stone.
  - After, three runs alone, before `underTrunk`: 1 of 3 (19 and 11). With it: 3 of 3.
  - The full native suite, nothing else on the cores: 401 of 406 in 45 minutes. `NativePortalCampaignGameTests` 57 of 58, the new fixture among those passing; `NativeCraftGameTests` 13 of 13; `NativeSmeltGameTests` 5 of 5; the cattle and the hungry fixture passed. Its five failures, each alone after:
    - the two seed-build cabins: 3 of 3 each. In the suite, each cabin stopped after eight failed steps, the last a scaffold block whose route was blocked;
    - the pit climb: 3 of 3. It is open, and its fix is on `modest-faraday`;
    - the strolling librarian: 6 of 7;
    - the librarian off the line: 1 of 3, and 1 of 3 on the code before as well ("Bringing the librarian home", out of time). Open.
  - Evidence: [F-25c4i](evidence/F-25c4i.md); the in-play rows: [F-25c4b](evidence/F-25c4b.md) to [F-25c4h](evidence/F-25c4h.md).
- Live, on `10a60d5` (F-25c4i), the same two seeds and settings, nothing else on the cores:

  | | 8675309, `fb02bc3` | 8675309, `10a60d5` | 20261030, `fb02bc3` | 20261030, `10a60d5` |
  | --- | --- | --- | --- | --- |
  | Deaths | 8 | 4 | 0 | 2 |
  | Gathering logs for sticks, the worst player | 35% | 0% | 43% | 0% |
  | Waiting for daylight, the worst player | 24% | 5% | 29% | 7% |
  | Coal hunts, the worst player | 18% | 0% | 0% | 11% |
  | Furthest | casting the frame | casting the frame | casting the frame | casting the frame |

  - Every player packed the trip's wood; none was stranded with no pickaxe. Iron loads burnt coal and planks, never sticks.
  - No portal lit. What took the time now:
    - Kindred's furnace output on the first seed, at Y=9: a "Take smelted iron" was asked for and never carried out, with no action and no log line until the watchdog moved it on, and then asked for again; seven times, 28% of its time. Not yet explained;
    - lava for the cast looked for down to Y=−55 on the second seed (25% of Kindred's time);
    - climbs for water (up to 18%), at night undone by the night's dig-in;
    - the one late coal hunt (11%), for iron wanted after the trip's, at Y=−55.
  - F-25c4d, f and i met their in-play rows: all of F-25c4a to i are complete.
- The pasture's cattle (loose-ends page), with each cow's position, path and goals logged every second, five runs alone: 4 of 5. In the failure the pair stood side by side in the doorway's mouth, one block wide, each overhanging the wall beside it, for thousands of ticks. A doorway two wide, eight runs: 6 of 8. The pair came in together and jammed the same way at the pen's gate, one wide (the other failure: no calf). The doorway was put back; still open.

## The earned portal run, cleaned — 30 October 2026 (`claude/loose-ends-1029`)

Eight slices of F-25c4 from what B7's second pairs showed (loose-ends page, item 7), on top of `2ddefc4`. Linux x64 cloud container (4 cores), OpenJDK 21. Focused runs narrow the gametest entrypoints to a scratch class (not committed) that calls only the fixtures named, and restore them after each run. Nothing else ran on the cores.

- `./gradlew build`: 792 JUnit results, none failed, six skipped (the opt-in live model tests).
- `:companion-testkit:run` and `:companion-testkit:runPersonalityDemo`: both pass.
- `:companion-core:test`: 614 of 614.
  - New `PortalRunPlannerTest` cases: wood packed to smelt with, and planks made from logs before a coal-less smelt; the route's iron as the night's work below; raw meat cooked and waited for.
  - The iron stage's shared setup, and three cases that pack a spare pickaxe, a table's wood or a shield's, now carry coal for their iron, so fuel stays out of what they test.
  - The fuel case fails with the packing switched off ("Mine iron ore (0/15)" where logs for fuel are wanted), and with the planks rule switched off (the logs smelted with whole).
- The new fixtures on the code before their fixes (main sources set aside, fixtures kept), one run alone with the fixtures beside them: 5 of 10.
  - Failed:
    - the night's mining: "Mined the iron in the night";
    - the zombie in reach: "No cover taken while the zombie was in reach";
    - planks, not sticks: "No stick went in as fuel";
    - cooking: "cooked 0, raw 6";
    - the gravel overhead: it suffocated after "Climbed a step".
  - Passed: the four beside them (finishing a wounded zombie, breaking off a fight, cover from a skeleton it cannot reach, the cold furnace), and the lava-pool cast as it was staged then.
  - Restaged by the pool's west end, the cast fixture in a run of its own on the code before: fails, "0 portal blocks, 0 obsidian".
- After the fixes, the same scratch class alone three times: 10 of 10 in each.
  - A first version of F-25c4e also counted the iron's smelt, and failed planks and cooking in each of three runs: a counted load took a piece of fuel per item, so three iron burnt three planks and left no coal to cook with. It is fixed in `NativePlayerBody.planSmelt` (a counted load gets the fuel those items burn), and the iron's smelt is uncounted again.
- The full native suite on this code, nothing else on the cores: 402 of 405 in 41 minutes.
  - `NativePortalCampaignGameTests` 57 of 57; `NativeCraftGameTests` 13 of 13; `NativeSmeltGameTests` 5 of 5; `NativeNetheriteGameTests` 1 of 1.
  - The closing run's four failures on `fbdb8ed` all pass: the lava-pool cast (F-25c4a), the keep's roof stairs, the pit climb and F-25c9a's second night.
  - Its three failures are in classes this work does not change, and each failed before it. Each alone three times on this code:
    - the axe on a raised shield: 3 of 3. It also failed once in an earlier suite on older code (`ab-1`);
    - the hungry life-first fixture: 3 of 3. In the suite its ridge slab was out of reach ("No way to reach minecraft:oak_slab"), the cabin stopped after eight failed steps, and the fixture ended with five roof stairs unplaced: the #82 family, open, as in F-25c9b's third run;
    - the pasture's cattle: 2 of 3. The failure reproduced alone for the first time: the player went "To the gate", "Back out through the doorway for the cow" and "Through the doorway, the cow behind" in a loop, about every second, until the fixture ran out. The cause is in the ranch's leading, not in this work; it stays open (loose-ends page).
- Evidence: [F-25c4a](evidence/F-25c4a.md), [F-25c4b](evidence/F-25c4b.md), [F-25c4c](evidence/F-25c4c.md), [F-25c4d](evidence/F-25c4d.md), [F-25c4e](evidence/F-25c4e.md), [F-25c4f](evidence/F-25c4f.md), [F-25c4g](evidence/F-25c4g.md), [F-25c4h](evidence/F-25c4h.md). F-25c4a is complete; the rest wait for their in-play row from the next live runs.

## Loose ends — 29 October 2026 (`claude/loose-ends-1029`)

On `claude/player-voice-e20` with `claude/blissful-fermi-y4tt90`'s handoff merged in (`ebe16aa`). Linux x64 cloud container (4 cores), OpenJDK 21. Focused runs narrow the gametest entrypoints and restore them after each run.

- F-18a (talking to your own player in private), `249f476`. Two new `NativeVoiceGameTests` fixtures:
  - on the code before the change: "Its controller is answered in private: []";
  - the remark fixture with the `canTalk` change alone: "A remark to its controller: []";
  - after the change, with the voice, ask, social, gaze and mannerism classes: 29 of 29 in each of three runs.
  - Evidence: [F-18a](evidence/F-18a.md).
- E-20c (a replaced speaker answered), `6ea5681`. The coalescing fixture with its new check:
  - on the code before: fails, the bystander heard nothing;
  - after, with the voice, ask and social classes: 21 of 21 in each of three runs.
  - Evidence: [E-20c](evidence/E-20c.md).
- E-10d (starters' personalities), `21a8a3d`. `SocialProfileTest` 1 of 1. The new `NativePlayerRuntimeGameTests` fixture:
  - on the code before: fails, Sol at every trait 50;
  - after, with the rest of its class and `NativeSkinGameTests`: 5 of 5 in each of three runs.
  - Evidence: [E-10d](evidence/E-10d.md).
- Fixtures off the deprecated mock player, and `NativePlayerBody`'s unchecked cast removed, `a841f6b`: no deprecation or unchecked warnings with `-Xlint:unchecked`. `NativeCraftGameTests`, `CompanionGameTests`, `NativeCombatGameTests`, `NativeGazeGameTests`, `NativeVoiceGameTests` and `NativeAskGameTests` together: 45 of 45 in each of three runs.
- E-08d (a log built into something is never a tree), `2b1f325`. The new `NativeBuildNeedsGameTests` fixture (a hut standing nearer than the trees):
  - on the code before: fails, all 12 of the standing hut's logs cut and none of the trees';
  - after, with the rest of its class: 2 of 2 in each of three runs.
  - Evidence: [E-08d](evidence/E-08d.md).
- F-25c9a (at night a bed of its own is its way home), `bc1690a`. The new `NativeLifeFirstGameTests` fixture (its first night falling as its cabin goes up):
  - on the code before: fails, "Its body"; a diagnostic copy, not committed, failed the same way 3 of 3 and logged why;
  - after, the class alone ten times on both fixes: the night fixture 10 of 10, the new one 10 of 10, the hungry fixture 9 of 10 (its one failure is E-08e's cause).
  - Evidence: [F-25c9a](evidence/F-25c9a.md).
- Closing validation on `fbdb8ed`, nothing else running:
  - `./gradlew build`: 789 JUnit results, none failed, six skipped (the opt-in live model tests).
  - `:companion-testkit:run` and `:companion-testkit:runPersonalityDemo`: both pass.
  - Full native suite: 396 of 400 in 58 minutes. Compared with the open list (CI run 223's six and E21's five):
    - still failing, all open before this session: the lava-pool portal cast; the keep's roof stairs (#82, 8 stairs short); the pit climb (its fix is on `modest-faraday`, 292852f);
    - now passing: the night-and-bed fixture, the build that fetches its shortfall, the furnished cabin, the off-line librarian, the pasture's cattle, the portal restart;
    - new, and this session's: F-25c9a's second-night fixture, "Its body". It slept in its own bed both nights, but in the suite the second night was not skipped: vanilla skips a night only when every player sleeps, and two other fixtures' players were up (Founder, whose fixture had failed fifty minutes before, and Pitfallen). The fixture ran out before morning. Fixed after the suite by giving both night fixtures time for a whole night (tickLimit 42,000); with `NativePortalCampaignGameTests` beside them, 56 of 56, though the nights there were skipped, so the busy night itself was not reproduced.
  - The keep and E-08e: the keep alone twice on the current planner, 2 of 2; twice with the planner before E-08e, 0 of 2 (34 roof stairs short each). A small sample, but it does not suggest E-08e caused the suite's keep failure.
- Per-death logging, for B7's next pairs: each death's log line now says what it happened in. For example, from `NativeDeathConservationGameTests` in those three runs: `Ashes died at …: Ashes was killed (tick 15928, day, under the sky, hunger 20, armour 0, holding diamond_pickaxe, a shield; doing "nothing"; no goal)`. With a goal it adds the goal's stage and the strategist's stance, focus and habits in force.
- B9, a queued follow-up crafted from the follow-up: `NativeFollowUpGameTests` with a new control, nothing queued, alongside the queued-shield fixture; with the life-first and death-conservation classes, three runs alone (g2after1 to g2after3): queued, a shield made from its iron and planks, 3 of 3; not queued, no shield and its iron kept, 3 of 3.
- F-25c9b (food from its own storage), `5ebb9f7`. The new fixture: on the code before, five cows killed and no bread taken; after, 3 of 3, no cow killed and four loaves taken. The original hungry fixture in the same runs: 2 of 3. In the third, its cabin's ridge slab was out of reach, and the walk round to the ground beside it went to one spot 984 times with the player not moving, until the fixture's end. The walk round is the #82 family. It came up in 5 of the 13 runs before E-08e and always moved on. Why it never moved on here is not established, nor whether E-08e has a part. Evidence: [F-25c9b](evidence/F-25c9b.md).
- B7, one paired live run (strategist off, then on) on `f7f206a`: seed 8675309, the three starters on the Nether goal, 144,000 ticks, fixture runs on the same cores. Off: 2 deaths, three iron pickaxes. On: 10 deaths (8 Echo's), two iron pickaxes; 54 replies, 381,612 tokens in (327,805 from the cache), 8,634 out. No portal lit in either; one pair shows nothing either way (loose-ends page, items 7 and 9).
- B7, two paired live runs on `4d0884a`, nothing else on the cores: seeds 8675309 and 20261030 (Echo found no room at spawn on the second, in both runs), the starters on the Nether goal, 144,000 ticks each, every death logged with what it happened in. Deaths off/on: 9/5 and 2/1. The most iron toward the need, 14 of 15 in all four. No portal lit; the furthest, the second seed's off run, at obsidian. Strategist: 52 replies, 366,807 tokens in (315,435 cached), 8,365 out; and 25 replies, 172,919 in (148,440 cached), 3,523 out. No log has the key (loose-ends page, item 7).
- CI (`.github/workflows/build.yml`) on `main`, run 223 on `91f408a`: build and testkit pass; the native suite 373 of 379 in 41 minutes (six open failures, listed on the loose-ends page). From `fa0466b` a newer push cancels the run it supersedes.

## Asks in chat (E-20b) — 29 October 2026 (`claude/player-voice-e20`)

On E-20a. Code `64f4ac1`. Linux x64 cloud container (4 cores), OpenJDK 21.

- `./gradlew build` on `64f4ac1`: 787 JUnit results, none failed, six skipped (the opt-in live model tests). It ran alongside the full suite below.
  - New: three `StrategistLineTest` cases (9 in all), one `ConversationAudienceTest` case (6), one `ConversationPromptTest` case (6), one `OpenAiCompatibleStrategistTest` case (15).
- `NativeAskGameTests` (6), entrypoints narrowed and restored after each run.
  - The fixtures: COME, WAIT and TOSS from the owner; STOP during a gather; a bystander's ask refused; a refused toss answered in the command's words.
  - `ask1` on the code before the settling wait: 5 of 6. WAIT was refused because the last activity was still settling; fixed.
  - Then `ask2`, `ask3` and `ask4` on `64f4ac1`'s code: 6 of 6 each, the last two with `NativeVoiceGameTests`, 7 of 7 each.
- Negative control: the ask check in `Line.checked` removed. The bystander fixture fails: the model's words, then the command's own "Native player unavailable or administrative access denied.", and nothing done (the command's authority still holds). The other five pass.
- The full native suite on `64f4ac1`: 393 of 393.
- Opt-in live run (`ClaudeStrategistLiveTest.aRealAskFromTheOwnerToComeIsCome`): 1 of 1.
  - "come over here please" came back "Sure thing, Steve, I'm on my way over." with COME (1,403 in / 50 out, 4.4 s).
  - "nice weather today" came back with no ask (1,404 in, 1,134 cached / 56 out, 1.2 s).
- Evidence: [E-20b](evidence/E-20b.md).

## The player's voice (E-20a) — 29 October 2026 (`claude/player-voice-e20`)

On `main` at `91f408a` with `claude/blissful-fermi-y4tt90` merged in (`385a6c2`). Code `3d7c7f4`; `ee93a17` then shortened the reply's `reason` field. Linux x64 cloud container (4 cores), OpenJDK 21.

- `./gradlew build`: 780 JUnit results, none failed, five skipped (the opt-in live model tests).
  - New: `ConversationAudienceTest` (5), `ConversationPromptTest` (5), `StrategistLineTest` (6).
  - Four new cases in `OpenAiCompatibleStrategistTest` (14 in all).
- `NativeVoiceGameTests`, its entrypoints narrowed and restored after each run: all seven fixtures in each of three runs.
  - The fixtures: the owner on a gather; a bystander in private and in public; an opponent's coordinates refused; a failure; coalescing; a stopped player; the voice off.
  - Two of those runs also held `SocialGameTests`, `NativeGazeGameTests` and `MannerismGameTests`: 21 of 21 each.
  - Earlier runs, while the fixtures were being written, failed on three things, each fixed in the fixture:
    - a native player's own record is its identity, so the lane admits its controller only with public chat on;
    - its ambient greeting was heard among the replies;
    - the test server runs up to ten times real time while the lane keeps real time (gaps now waited out by parking).
- Negative control: the tier check removed (every listener a controller). The bystander and opponent fixtures fail on the position in their prompts; the other five pass.
- The full native suite on `3d7c7f4`: 385 of 387, every voice fixture passing.
  - Failed: `NativeLifeFirstGameTests.atNightItLeavesItsBuildForItsOwnBedThenBuildsOnInTheMorning` ("Its body") and `NativePortalCampaignGameTests.goalClimbsOutOfAPitBelowItsWayBackWithNoPickaxe` (still in the pit at the end). Neither was on the open list.
  - Their two classes alone, twice on each build: on the base `385a6c2` 54 of 54, then 53 of 54 (the bed fixture again, "Its body"); on `3d7c7f4` 54 of 54 twice.
  - So both are intermittent, outside the voice lane, and the bed fixture's failure predates it. Open.
- Opt-in live runs with a key from the environment, three of three (`ClaudeStrategistLiveTest.aRealReplyToWhatAreYouDoingNamesTheLogsItIsChopping`):
  - Each reply named the logs it was chopping, its goal and its three logs cut, and gave no place to a listener who may not know it.
  - 1,058 to 1,059 input tokens (833 from the cache once) and 63 to 101 output, in 3.6 to 6.8 s.
- Evidence: [E-20a](evidence/E-20a.md).

## Support reconstruction — 24 October 2026

On base `fa8c3ac` (`claude/modest-faraday-r3spro`), reconstructed the lost support-batch scope on `codex/rebuild-support-integration`. The only production changes fix ModelGateway cleanup after caller cancellation and overflow-safe token accounting. Both dedicated regression tests fail on the unchanged base gateway and pass after the fixes.

Linux x64 / OpenJDK 21.0.2 standalone validation: **64 support tests in 22 classes + 145 selected original Java tests; 34 Python checks; zero skips**. The real SQLite migration resources are included. File and directory symlink rejection is exercised without a permission skip. [Sanitized XML, source hashes, commands and limitations](../tools/support-batch-05/HANDOFF.md) are retained. This is a new bounded run, not reuse of the missing commit's historical totals. Full Gradle/provider SDK/native/client/playthrough qualification was not completed; no release gate or parent checkbox is advanced.

## Current regression — 22 October 2026

Linux x64 cloud container, OpenJDK 21, pinned Minecraft 1.21.1 / Fabric Loader 0.19.5 / Fabric API 0.116.17. Branch `claude/modest-faraday-r3spro`.

- E-16a (enchanting), E-16b (brewing), E-16c (netherite), E-39i (blast exposure) and E-05 (fishing), on `39978c4` and the working tree committed after it (2026-10-28).
  - JUnit (light runner): `EnchantingTest` 4 of 4, `BrewingTest` 4 of 4, `NetheriteTest` 4 of 4, `BlastExposureTest` 3 of 3.
  - Narrow native run d1 (the enchant, brew, netherite, kit-name, compound, base and seed classes) did not finish: the main classes were recompiled while it ran, and the server crashed on a class gone from under it (`NoClassDefFoundError: NativeBrewAction$Mode`). Not a fault of the code; no results taken from it.
  - Run d2 (enchant, brew, netherite, blast): 3 of 6. Passed: brewing three potions of fire resistance at a stand it put down, from bottles it filled; debris to a netherite sword with its Sharpness III kept; nothing enchanted short of thirty levels. Failed: the sword enchanted, but the fixture checked what it said a tick before the finish hook said it (fixed in the fixture); both blasts read 0 taken, as the crystal went off within the three seconds a player who has just joined is spared all harm (the fixture now waits four seconds and reads health in the blast's own tick).
  - Run d3 (enchant, blast, fish): 5 of 6. Both enchant fixtures; the blast reckoned and taken alike, 9.17 in the open and 1.0 behind an obsidian wall. The fish fixture failed: with no ranch, field, build or bases its life-first needs never ran.
  - Runs f1 to f3 (fish): failed and fixed in turn: fishing added to the goal's hunt and its look for food; the fish skill let act by the body; the bobber's water read from its block or the one below; the table for a rod left down while it makes one.
  - Run f4 (fish, and the pasture's cattle alone with a debugging log): 3 of 3.
- E-45d (the trading hall), `28d63b8` on top of `bf69257`.
  - New JUnit `TradingHallTest` (2), light runner. New native fixture `NativeTradingHallGameTests` (1): a hall of two cells built exact from the items it takes, then a jobless villager set in a cell takes up the lectern across its front and stays there a librarian for thirty seconds. The first runs stopped short of the middle row of a roof over the corridor (the corridor is now open to the sky), found no way to put a bed down facing across a cell two wide (the bed now lies along a cell one wide), and failed on the fixture's own count of the items (a door's upper half and a bed's head come with their partner's item). Then nine runs in a row passing: alone, with `NativeRerollGameTests` twice, and with it, `NativeTradeGameTests` and `NativeBuildGameTests` six times.
  - Found alongside: with the trade and build classes, `NativeRerollGameTests` failed in four of six runs, then two of six more, in three ways. Picking the broken lectern up left Hal's body over the edge of the lectern's spot, and it "stepped back" to where it already stood until the test ended; and once it stood on the lectern, so that breaking it was refused as the block underfoot six times and the reroll gave up with the lectern in the ground. Both fixed in `b4e6609` (another side of the villager clear of it, or off to the middle of a block beside; off the lectern before breaking it): then six of six with the trade, build and hall classes, the reroll alone once. The third way: twice the villager kept its job a minute after its lectern was broken, the lectern and its point of interest gone but its job site still remembered, once with the villager only 13 ticks old at the end. Found with E-45e: the GameTest server sets no simulation distance, and the villager by its chunk's edge stopped ticking when Hal stepped into the next chunk; the fixture's chunks kept simulated in `337e4c4` (below).
- Full suite run 49 on `b5d56b3` (2026-10-28), Linux x64 OpenJDK 21, in a clean worktree: `./gradlew build` 728 JUnit results, 724 passed, four skipped, none failed; native suite 359 of 362. All seven bring fixtures but one passed, the lake crossing's for the first time in a full run, with the cart, the three trading hall fixtures (the hall's repair among them) and the seed-grown cabin that failed in run 48. Failed: the pasture's cattle (open since run 44); `NativeBaseGameTests.aPlayerWithStoneToolsSitesAndBuildsItsOwnCabinThenWaitsForIron`, the cabin stopped after eight steps found no way to reach a roof stair from where Mason stood, ended short and was built at its second go, so its first-go check failed (#82's kind, as in run 22; not this work); and the strolling bring fixture: held by the hall's west front corner, with something in the way of the line straight south, the cheapest line went north a block and came back south beside its own run-up and through the cart's rail, which, joined to the line's return, curved and turned the cart off east when pushed, away from the boat ("the cart … rolled past the librarian"). Fixed after (below): a line no longer comes back onto or beside its own rails or run-up.
- E-45e, the rail line kept clear of itself, `47fa3f7`; E-45d, a hall's beds through its needs from wool it shears, `65da7bd` (2026-10-28).
  - `RailRouteTest` 10 of 10 and `WaterCrossingTest` 3 of 3 (light runner); the new test, a villager in its boat by the hall's west front corner with the line straight south blocked, fails on the old search (the cart's own rail came twice in the line). `BuildNeedsTest` 6 of 6 (a bed's recipe; wool gathered). New native fixture `NativeTradingHallGameTests.aTradingHallsBedsAreMadeFromWoolItShears`: no beds given, shears and nine white sheep about; five sheep shorn, their wool picked up, three beds made and the hall built exact, no sheep killed. `NativeBringGameTests` and `NativeTradingHallGameTests` together, 11 of 11, the strolling librarian brought home on a line of 39 rails that keeps clear of itself and its last line saying "I've left the cart in there with it."
  - Seen alongside: a walk for a wool drop still falling found no route to its block in the air (it was walked to again once it lay); a drop still falling is now waited for (not yet run natively).
- Full suite run 48 on `c98910b` (2026-10-28), Linux x64 OpenJDK 21, in a clean worktree: `./gradlew build` 725 JUnit results, 721 passed, four skipped; native suite 358 of 361. The hall repair fixture passed in a full run for the first time, and the base repair fixture with its new check. Failed: the pasture's cattle (open since run 44); `NativeSeedBuildGameTests.aSevenByNineCabinIsBuiltWithTwoWindowsAside`, the cabin built and then a repair of it ending short, "No way to" reach a block (a seed-grown variant, #82's kind; not this work); and the strolling bring fixture: the librarian was brought into its cell, but when the player took the shutter out of the cell's front to step in for the cart, the librarian, at work in its working hours and bound for its old lectern, walked out through the front in that moment and off across the field. A fixture whose villager's AI is off never moves then; one whose AI is on can. Fixed after (below).
- E-45e, across a lake by boat, and one that strolls kept behind its cell's front with the cart left it, `948a6de` on top of `c98910b` (2026-10-28).
  - New pure `WaterCrossing`: `WaterCrossingTest` 3 of 3 and `RailRouteTest` 9 of 9 (`./gradlew :companion-core:test`). New native fixture `NativeBringGameTests.aLibrarianAcrossALakeIsRowedOverAndBroughtHome`: a lake eight wide across the field and four dirt, too few for a bridge; the librarian, its AI on, held in its boat, rowed over (13 blocks), left in the boat on the water by the far bank, and taken up from the water by the cart on a line laid along the bank (20 rails); no bridge, the lake as it was. `NativeBringGameTests` alone 7 of 7, then the bring, cart, hall, reroll, trade and boat classes together, 17 of 17.
  - For run 48's escape: a villager that strolls is no longer let loose with its cell's front open. The cart is left in its cell (the front's rail taken up, the job block put in across the front where it misses the boat, else the opening shut a moment, then the boat broken over the cart), the boat's item left lying in the cell and logged. The floor's rail is left to every cell. Found on the way, in four runs of the bring class (3, 1, 3 and 3 of 7 failed): the lake fixture's librarian walked off the field (a fence now rings it); the boat broken over the cart set its rider inside the cart's box, not on its top; a dirt shutter at the front's foot fell into the cell (the job block now goes in first where it fits); the villager shoved the cart left in its cell into the front (now broken there first); the cart's item at the bed out of reach from the front's middle, its waits counted by look rather than by tick (now edged in for by the clock, then left and logged); a cart stopped dead against the villager's boat taken for one that rolled past (now put down again for another push).
- E-45d, a trading hall kept whole as its base is, `00cdc66`; and the base repair fixture's race, `1c76d57` (2026-10-28).
  - New native fixture `aBrokenTradingHallIsRepairedAroundTheVillagerInItsCell`: a hall of three of the player's own, a librarian shut in its first cell behind its lectern, three blocks gone (front wall, a lintel, a roof plank). The three put back from what it holds, the lectern and the librarian left as they were. With the base-repair class, 4 of 4; then with the base, base-repair and bring classes, 11 of 11, the base repair fixture's new check (every block of the cabin in place once its repair has begun) passing in both.
- Full suite run 47 on `4f8c3d4` (2026-10-28), Linux x64 OpenJDK 21, in a clean worktree: `./gradlew build` with 725 JUnit results, 721 passed, four skipped (the opt-in live model tests), none failed; then the native suite, 359 of 360. All six bring fixtures passed, the strolling librarian's among them, with the cart, both trading hall fixtures, the ranch and the pasture's cattle, which failed in runs 44 to 46. The one failure: `NativeBaseRepairGameTests`, whose cabin was repaired ("Repairing its cabin, 29 blocks out of place", then "Its cabin is whole again"), but the fixture waited on that last note, which the player's next look at its base overwrote in the same tick ("Iron tools first"), and timed out. A race in the fixture, not touched by this work; the fixture now checks the cabin itself, every block in place once its repair has begun.
- E-45e, a villager that strolls held in a boat and carried in it by the cart, `4f8c3d4` on top of `f5a39b9` (2026-10-28).
  - `RailRouteTest` 9 of 9 (`./gradlew :companion-core:test`; four new: three open over every rail and a run-up of three for a villager in a boat; a step climbed only where its lower column has room for the rider at the slope's top; the line set out only the way that leaves room behind the villager for its run-up; the hall's only blocks at a boat rider's eyes, the wall over the door and the cell's lintel, for halls of 1, 3 and 4 cells). `NativeBringGameTests` alone 6 of 6, then the bring, cart, hall, reroll, trade and boat classes together, 15 of 15.
  - Found on the way, in nine runs of the bring class before it passed: vanilla's riding cooldown (a mob let out of a vehicle cannot ride another for 60 ticks) makes a villager let out of its boat untakeable by the cart for three seconds, which also explains the eight runs of the boat hold backed out below; a rolling empty cart takes up a boat, villager and all, so the boat is now carried, not broken. The rest were fixed in turn: the cart's run-up (three rails for the boat's wide box), a lectern by the villager ruling out every line (the run-up now chosen with the line), a boat put down overhanging the hall's wall, a villager off its block's middle, a boat step in flight read as "no boat", the shutter refused at the front's foot under the boat's overhang (now across the opening), and a loose boat that worked its way out of the cell before it was broken (now broken while it rides the cart).
  - Once, in one of those runs, the iron fixture's floor rail popped onto the cell's bed out of reach from the front (31 rails of 32); open.
- E-45e, an item it cannot walk to left, `6b9544f` on top of `1d87ee9`.
  - The bring's last sweep for what it took up walked at a rail inside a cell shut since, and three blocked walks gave the whole bring up (the iron fixture, once). An item it cannot get to is now left where it lies, not counted against the bring; its pushes, and a cart that rolls past the villager without it, are logged. Two runs of the bring, cart, hall, reroll and trade classes, 12 of 12 each.
- E-45e, a villager that strolls held in a boat, tried and backed out (not committed), after the hold in the cart below. A boat put down beside it (vanilla puts none down where it would overlap a mob or a player, and a boat takes up a mob within a fifth of a block of it), the line laid from where it sat, and the boat broken from behind the cart just before the push. Eight native runs of the bring class with a strolling librarian, every one failing that fixture: the boat held it, but once let go a villager that stepped toward the oncoming empty cart shoved it back (mobs push the carts they bump) before the cart took it up, and the cart rolled back off its rails; and with a lectern of its own by the line in its working hours it once walked where no line could be laid before it was held. The other five fixtures passed with it. The diff is kept outside the repository.
- Full suite run 46 on `d86cdda`, Linux x64 OpenJDK 21: 354 of 359, the river and mined-iron bring fixtures passing in a full suite for the first time. Failed: the pasture's cattle and the keep's roof stairs (#82), both open, as in runs 44 and 45; the ranch ("Two wheat fed: 8 left") and the portal goal's walk back up its staircase ("Well down the staircase when it broke"), both seen before in earlier runs and not touched by this work; and the off-line bring fixture, its cart stopped on the line laid again (open, as in one mix run before). `./gradlew build` on `d86cdda`: 721 JUnit results, 717 passed and four skipped; JAR SHA-256 `d8fb272e019e09762636de4c3c6c99f8eeb0a11140de8c87f8415fa77f840223`.
- E-45e, a villager held in the cart before the line is laid, tried and backed out (not committed). For a villager whose AI is on, which strolls off a line in the minutes it takes to lay: a short line to it with a block across the line past it, the cart pushed into it and stopped against the block, then the rest laid. Six versions in native runs of the bring class, all six fixtures failing in each: the loaded cart, left waiting while the player worked past it, was nudged back off the end of its rails (a loaded cart rolls a long way on a nudge); with a block behind it too it was nudged against that and rolled off once it was taken up; boxed in with no room it was still found off its rails at the push. A fixture with the librarian's AI on also showed a villager walking up to the hall, where its beds are, too close for a line. The flow stays as committed; the diff is kept outside the repository. A villager in a boat, which the player puts down on it, may hold it better (a boat on land barely moves when bumped); not yet tried.
- E-45e, a villager over a river and the iron for the rails, `d86cdda` on top of `a1c0865`.
  - `RailRouteTest` 5 of 5 (one new: a river bridged level with the ground, in the line's order; no line down to water two below; over a puddle a block dearer rather than round it four rails dearer; never a cart or push over water), `PortalRunPlannerTest` 32 of 32 (one new: seventeen ingots mined, fuelled and smelted after the run has no more use for iron, the furnace waited for rather than emptied an ingot at a time) and `BaseSiteTest`, light runner.
  - Two more `NativeBringGameTests` tests: a librarian across a river three wide brought home over a bridge of dirt put down from its bank and taken back after; and no iron but a stone pickaxe, coal, a furnace and ore in sight, the ore mined and smelted before the rails and cart are made. Both passed in the first run of the class that had them; the iron fixture first set its ore flush with the floor, which the player's sight (a ray to the block's middle) does not see from more than three blocks off past the floor and the ore about it, and it went looking in caves until the test ended; its ore now stands as a face, as a cave's wall shows it. That failed run also failed the off-line fixture beside it: the iron fixture's librarian, left standing when it failed 13 blocks off, was the one Hal looked at and brought (each bring fixture now clears the villagers about its hall first). Then the class 5 of 5, and the bring, cart, hall, reroll and trade classes: five runs, of which the first crashed the test server in the river fixture's own check, which read Hal's inventory after its runtime stopped (a dirt block broken over the water had floated off, by the look of it; it is now walked to and picked up, and the check fails cleanly), and the fourth failed the iron fixture (the furnace it put down was never its own, below); then three more, of which one failed the off-line fixture with one of its 64 rails left lying where it was broken (what it took up is now walked to and picked up where it lies, rails as well as the bridge's blocks; the fixture now logs what it is still waiting on); then three runs of the bring, cart, hall, reroll and trade classes on the code as committed, 12 of 12 twice and 11 of 12 once (the off-line fixture's cart stopped short of its cell after the line was laid again; open).
  - A furnace's iron is taken out once the furnace is done (or holds the number), not an ingot at a time: the first pass made seventeen trips to it.
  - The furnace it put down for the iron was not counted as its own when it planned again while the step was running (the steps it had proposed were forgotten at each plan, and with them the step that put the furnace down), so it set about mining stone for another and then logs for a table; its steps toward the iron are now kept until each ends.
- Full suite run 45 on `a1c0865`, Linux x64 OpenJDK 21: 355 of 357, the three bring fixtures, the cart, both trading-hall fixtures (the hall of its own accord in a full suite for the first time) and the reroll passing. Failed: the pasture's cattle (two cows by the pen's doorway; open) and the keep (`NativeCompoundGameTests`), roof stairs not placed (#82, open), as in run 44. `./gradlew build` on `a1c0865`: 719 JUnit results, 715 passed and four skipped; JAR SHA-256 `242515f6c7286f63a7aa54f3e19715f76bf8354a2748a6ab1e02160b8e5dbe6b`.
- E-45e, a villager off the line, `3d85979` on top of `2259a19`.
  - A third `NativeBringGameTests` test: the librarian moved three blocks aside once the cart is down; the line laid again from where it stands, the cart taken up and put down again behind it, the librarian brought home. The first runs found a cart stopped short just past a turn to be pushed from two rails behind it, which ran through the turn into the corridor's wall (from the rail behind it now), the cart's item walked to within two and a half blocks and never picked up (onto it now), and a column with a rail on it counted as no room for a rail. Then three runs alone and three of the bring, cart, hall, reroll and trade classes, 10 of 10 each.
- E-45e, the rails and cart made from its own iron, `8c13a77` on top of `6660beb`.
  - A second `NativeBringGameTests` test: 24 iron ingots, 2 sticks and a crafting table instead of rails and a cart; Hal makes two stacks of rails and a minecart and brings the librarian home with them, every rail and the cart taken back. The first run found its run's progression making an iron sword between one craft and the next, a stick spent (it now plans again at once while it makes them), and the first test, in the same run, stuck at the cell's front, its body over the front's edge after a walk to the block before it (the front's steps are now taken from two back). Then both alone, and twice 9 of 9 with the cart, hall, reroll and trade classes.
- Full suite run 44 on `77a7a5e`, Linux x64 OpenJDK 21: 353 of 355, the bring, cart and trading-hall fixtures and the reroll passing. Failed: the pasture's cattle (two cows by the pen's doorway; open, as in runs 33 and 38 to 41) and the keep (`NativeCompoundGameTests`), four of its roof stairs not placed (#82, open). `./gradlew build` on `77a7a5e`: 719 JUnit results, 715 passed and four skipped; JAR SHA-256 `393b91226cd44060618605fd2788e29584b217d05a567b762eafc398994e64c4`.
- E-45e (bringing a villager home), `77a7a5e` on top of `337e4c4`; the parts in `337e4c4`.
  - New JUnit `RailRouteTest` (4), light runner. New native fixtures `NativeCartGameTests` (1: a pushed cart takes a villager up and rolls eleven blocks into a cell, which it is set down in when the cart is broken) and `NativeBringGameTests` (1: a kept librarian that has traded brought home by minecart into a cell of Hal's hall, its lectern across the front, the door back, every rail, the cart and the dirt taken back). The first runs found the cart pushed back by a body running through it (a push at a walk now), an empty cart that stopped against the villager without taking it up (a gentle push, from two blocks behind), a cart that froze leaving its player's chunk (below), the push refused by the skill gate, pushes counted where proposed, a rail never seen to be broken (the aim now tries a low block's own shape), cobblestone that drops nothing broken by hand, a walk that never came near enough to a rail, and a cart stopped half in the cell's front after the corridor's turns (a nudge). Then three runs of the bring, cart, hall, reroll and trade classes, 8 of 8 each.
  - Found alongside: the GameTest server sets no simulation distance, so a player keeps entities moving only in the chunk it stands in. That was the reroll fixture's last flake (its villager by a chunk's edge stopped ticking, 13 ticks old after 1,200, when Hal stepped over into the next chunk). Fixtures keep their ground simulated (`FixtureGround.simulated`); with that, the reroll, trade, build, hall and cart classes nine of nine six runs in a row.
- E-45d, a hall of its own accord, `c096701` on top of `1d3cf34`.
  - A second test in `NativeTradingHallGameTests`: Mason's cabin set in place by the fixture and recorded as its base, bases on, and a librarian selling a Mending book three blocks off. Mason looks at it in passing, finds it worth keeping, sites a hall of three twelve blocks north of its cabin on the ground it stands on, clear of it, and builds it from the blocks it holds, 204 of 204 in their state; its cabin still its base. Pass first time alone (both tests), then 8 of 8 with `NativeBaseGameTests`, `NativeBaseRepairGameTests`, `NativeRerollGameTests` and `NativeTradeGameTests`. `TradingHallTest` 2 of 2 (light runner), the hall within the width, depth and height it is sited by.
- Full suite run 43 on `1d3cf34`, Linux x64 OpenJDK 21: 350 of 352, the trading hall's first test and the reroll fixture passing. Failed: the portal cast from a lava pool (`goalCastsAWholePortalFrameFromALavaPoolWithoutDiamonds`, its frame not cast in time, as in runs 17 and others; open), and the build short of blocks (`NativeBuildNeedsGameTests`), which set out on its one trip with 77 of the 79 blocks it could place placed; the fixture beside it, the portal cast, ran at the same time 13 blocks off. Not touched by this work; open. `./gradlew build` on `1d3cf34`: 715 JUnit results, 711 passed and four skipped; JAR SHA-256 `4bfd738d8ef414b3a03fdb950966b2cfc177f3b30d53dc63cd0cc3a0ca41de9f`.
- Full suite run 42 on `bf69257`, Linux x64 OpenJDK 21: 351 of 351, every fixture passing (runs 33 and 35 to 41, above, each had one to four failures), with the reroll, trade and aggression fixtures and the cabins and base repair of #82 passing. One run, not two in a row. `./gradlew build` on `bf69257`: 713 JUnit results, 709 passed and four skipped (the opt-in live model tests); JAR SHA-256 `deea22dab443d9aff717c0a19f6341c326f3c8ec393e932d08d29d0fbd78bba1`.
- E-45c (whom to keep) and #82, `bf69257` on top of `55d9560`.
  - `TradesTest` 5 of 5 with Gradle, one new. New native fixture `NativeRerollGameTests` (1): a jobless villager's lectern rerolled to its own end. The first runs found the break refused by the rule against breaking block entities (an empty lectern is now allowed, as an empty furnace is), the reroll ending before the lectern just broken was picked up, the player itself standing where the lectern goes, refused steps asked for every tick unlogged, and the villager jumping the pen from the top of the lectern (the pen is walled three high). Then it passed, given up by its own rule after 25 looks: under the GameTest server's `trade_rebalance` flag a plains novice librarian draws only Smite, Punch and Bane of Arthropods books, none worth keeping; the keep is pure-tested only. Then 14 of 14 with `NativeTradeGameTests`, `NativeStorageGameTests` and `NativeSeedBuildGameTests`.
  - #82: a walk round to the ground beside a block it cannot reach that finds no way is tried next at the next-nearest side of the build. `NativeSeedBuildGameTests`, `NativeBaseGameTests` and `NativeBaseRepairGameTests` 8 of 8, one walk blocked and the next side reached; then seed-build 6 of 6 again. Not yet a full suite.
- E-45a (what a trade is worth) and E-45b (trading), `93ce043` on top of `d3cb788`.
  - New JUnit `TradesTest` (4), with Gradle; the `guide` package 29 of 29.
  - New native fixtures `NativeTradeGameTests` (3): a look and then a sale and a purchase exactly conserved; a sold-out offer left; an overpriced book never paid for. The first run held the trade for reconciliation (the check of what the trades yielded wanted the emeralds from the sales still held after they bought the book) and then crashed the server's tick in the fixture's own check, reading the body taken out for reconciliation; a later run said the trades out of order. Each fixed; then three runs passing, the last with `NativeStorageGameTests`, `NativeKitNameGameTests`, `NativeRaidGameTests`, `NativeLootGameTests`, `NativeBaseGameTests` and `NativeSeedBuildGameTests`, 21 of 23; the two failures seed-build cabins stopped short of their roof stairs (#82, open).
  - New recorded action `native_trade`. Found alongside (E-43): a blocked walk to look at a store where it saw someone cleared what it saw, and the match fixture's look never came (one run); a look now tries again after ten seconds.
- Full suite run 41 on `d3cb788`, Linux x64 OpenJDK 21: 345 of 347, with the four aggression fixtures passing. Failed the pasture's cattle (two cows by the pen's doorway; open, as in runs 33, 38, 39 and 40) and the base repair (`NativeBaseRepairGameTests`), stopped after eight steps failed in a row with no walkable route to its roof stairs (#82, as in two cabin builds of run 40; open). `./gradlew build` on `d3cb788`: 708 JUnit results, four skipped (the opt-in live model tests); JAR SHA-256 `20a5fd6dc75380af6ee35b800c1263694641bc288a2ed9c41b20d9489bd2c4ca`.
- E-41g (hit hard by a player) in play, `d3cb788` on top of `35cb93a`; E-41h (whether a fight is winnable), its reckoning, in `35cb93a`.
  - New JUnit `FightOddsTest` (4). New native fixtures `NativeAggressionGameTests` (4): the first runs failed all three answers (the hit was read from Fabric's `AFTER_DAMAGE`, which gives it before armour and only to the mod's own runtime; a duel's own fight felled the unarmoured opponent before the fixture's hit), then passed 4 of 4 falsely twice: an outclassed player's fight, chosen just before the hit and still being admitted, started after it and felled the held opponent, who came back far off at its spawn (seen in the log; the interrupt now stays owed until the answer runs, and the fixture checks that the duel runs on). Then 4 of 4, and 32 of 32 with `NativeMatchGameTests`, `NativeManhuntGameTests`, `NativeCombatGameTests`, `NativeEdgeFightGameTests`, `NativeSurvivalGameTests`, `NativePaidSkillsGameTests` and `NativeRaidGameTests`.
  - A hit is measured on the native body as the health and absorption it cost (its damage override); a stopped player only notes it.
  - Found alongside and fixed: a hurt player holding golden apples looped on eating one at full hunger; the eat action uses vanilla's rule for food that can always be eaten.
- E-43 follow-up, `2183b0c` and `d3cb788`.
  - Two native fixtures (`2183b0c`): a teammate's take told and counted by both; in a match, an opponent seen at its chest named to its own side and the rest moved. Three runs with the raid and build-needs classes, 9 of 9 each.
  - Full suite run 40 on `2183b0c`, Linux x64 OpenJDK 21: 340 of 343. Failed the pasture's cattle (two cows by the pen's doorway, open as in runs 33, 38 and 39), and two cabin builds (`NativeSeedBuildGameTests` with no seed, `NativeBaseGameTests` with stone tools) stopped after eight steps failed in a row short of their roof stairs, with no walkable route to the ground beside (#82). In their logs, two loops of this slice's own: a bed stowed and then walked back for, and a walk to a base chest inside the unfinished cabin. Fixed in `d3cb788` (beds kept on; only a complete base's chests count; a blocked store, kit naming or bed walk waits a minute); then 15 of 15 with `NativeBaseGameTests`, `NativeSeedBuildGameTests`, `NativeStorageGameTests` and `NativeRaidGameTests`. `./gradlew build` on `2183b0c`: 704 JUnit results, four skipped (the opt-in live model tests); JAR SHA-256 `0bbcee625816a4dab9a759c83e6bb49b75627ce1d1c4d37557135daaeeb34589`.
- E-43 (extended inventory) in play, `fd789ed` on top of `2cc3040`.
  - New native fixtures `NativeStorageGameTests` (2): three runs alone with `NativeRaidGameTests` (6 of 6 each), then `NativeBuildNeedsGameTests`, `NativeLootGameTests`, `NativeKitNameGameTests` and `NativeTeamShareGameTests` with this work, 7 of 7. `WorthTest` and `StorageTest`, 7 of 7 each, one new in each. Not yet in a full suite.
  - New recorded action `native_store`; a store of its own under the roaming rule (`RoamingEdits.ownStoreDenial`); the strategist's report says what it has stored.
- E-16d (named kit), `2cc3040` on top of `52aab8f`.
  - New JUnit `KitNamesTest` (4); with `NameForgeTest`, 8 of 8. New native fixtures `NativeKitNameGameTests` (3): the first run failed two (a player with no work area found nowhere to put its anvil down; the spot is now judged by the one edit decision), then three runs of three alone, then 8 of 8 with the loot and raid classes, and passed in full suite run 39 on `2cc3040`.
  - New recorded action `native_rename`: only the piece's name changes, and exactly the anvil's cost in levels is spent.
- E-44 (raiding in a match), `52aab8f` on top of `4007eec`.
  - New JUnit `RaidTest` (4); `OpenAiCompatibleStrategistTest` 10 of 10 with two new. New native fixtures `NativeRaidGameTests` (3): three runs of three alone, then 18 of 18 with `NativeManhuntGameTests`, `NativeMatchGameTests`, `NativeCombatGameTests` and `NativeEdgeFightGameTests`, and passed in full suite run 39 on `2cc3040`.
  - A fourth fixture, a fellow hunter's chest it sees used never its to raid, in `fd789ed`: three runs alone, with the storage fixtures, 6 of 6 each.
  - Found by its first fixture and fixed: a hunter whose fight ended the hunt was held for reconciliation, as the match took back its tracking compass mid-fight; a hunt's compass is the match's, not counted by the fight.
- Full suite run 39 on `2cc3040`, Linux x64 OpenJDK 21: 337 of 338. Failed only the pasture's cattle, two cows left outside the pen by its doorway (as in runs 33 and 38; open). The hall's south-east tower, which failed in run 38 (#82), passed. `./gradlew build` on `2cc3040`: 702 JUnit results, four skipped (the opt-in live model tests); JAR SHA-256 `7c23feba6ca37fa603f2fad4edc54e9d42da1e0568990e503dfcbf5e319d151a`.
- E-42 (valuables), its first slice, `baa841b` on top of `7ae84d4`.
  - New JUnit `WorthTest` (6). New native fixtures `NativeLootGameTests` (2): passed alone and in full suite run 38 on `7a2738c`. Found by them and fixed: a loot table spreads items over slots, so a container's contents are judged item by item, all of each there is.
  - New recorded action `native_loot`; the roaming rule for containers takes only a structure's container nobody has opened (`RoamingEdits.lootDenial`).
- E-43 (extended inventory), its core, `7a2738c`: new JUnit `StorageTest` (6). Pure only.
- E-40a: one portal for the team, and three fixes from suite runs, `091f470` and `79bb3e7` on top of `b417072`.
  - `RunDivision` keeps the builder of a begun portal over a better pickaxe; the planner's gatherer, with the portal a teammate's, stocks the run's food and wood and waits for it once its own kit is in, not the portal's flint and steel or buckets (`gearIn`). `PortalRunPlannerTest` and `RunDivisionTest` together: 37 of 37.
  - New native fixture `NativeTeamPortalGameTests.theBestPickaxeBuildsTheTeamsOnePortalWhileTheOtherStocksTheRunAndWaitsForIt`: passed three runs of three alone, then with the goal's classes, and in full suite run 38.
  - Found by suite runs 36 and 37 and fixed: trees on an island are those within twelve blocks (`goalBreaksLeftoverLeavesForSaplings` had seen its neighbours' trees); ground open to the sky is never a cave floor (`goalWalksBackUpTheWayItCameWhenItsPickaxeBreaksBelow`: while sky light lagged behind freshly set stone, a wall's top read as a cave, three walks to it left the player stranded, and stranded it would not walk to the iron it then saw); at a flooded shaft's top, its face at the surface and dry ground a few steps off, it climbs out instead of being sent up for air at each bob under (`goalSwimsUpOutOfAFloodedShaftBeforeAnythingElse` drowned so once in a class run).
  - The goal's classes together (`NativePortalCampaignGameTests`, `NativeTeamPortalGameTests`, `NativeBoatGameTests`, `NativeFollowUpGameTests`, `NativeNetherGameTests`, `NativeStrongholdGameTests`): 69 of 69.
  - Full suite run 38 on `7a2738c` (this work with E-42's first slice and E-43's core): 330 of 332. Failed: the pasture's cattle, both cows left outside the pen (as in run 33; open), and the hall's south-east tower, stopped with no walkable route down to take its scaffold down after the hall was built (#82, open). The three fixtures fixed here passed.
  - The build of a first run 38 on `79bb3e7` failed one JUnit test, `AttentionWorkSchedulerTest.deterministicSliceAndDetailedScanBudgetCaps`: selection reads the real clock, and with another compile beside the build the default two-millisecond soft limit ran out after three slices. It now keeps the default caps with room in its soft limit, as its round-robin neighbour does (`7ae84d4`). `./gradlew build` on `7a2738c`: 692 JUnit results, four skipped (the opt-in live model tests); JAR SHA-256 `d939b82da69954b5912a870474776dcbfdb5c0663acd85bcb42e37ce451fdafb`.
- The support batches merged, `b417072`, from `codex/rebuild-support-integration` (`015f271`): ModelGateway's cleanup after a caller cancels and its overflow-safe token accounting, each with its regression test; 22 support test classes; the support-batch tools. Recorded under [Support reconstruction](#support-reconstruction--24-october-2026). Suite run 37 on it, below.
- E-40a: a find shared out and handed over, `b04114d` and `7e6e700` on top of `fa8c3ac`.
  - New JUnit `HandOversTest` (3). New native fixture `NativeTeamShareGameTests.aFindIsSharedOutByWhatEachLacksSaidAndHandedOver`: passed three runs of three alone, then in suite runs 37 and 38.
- E-33a (where a waiting player looks), `fa8c3ac` on top of `782a25d`.
  - New JUnit `GazeTest` (6). New native fixtures `NativeGazeGameTests` (4, each with its control): passed alone, then in suite runs 36, 37 and 38.
- Full suite runs since the last recorded here, Linux x64 OpenJDK 21: run 35 on `782a25d`, 321 of 324 (failed: the bed fixture's second night, open; a cabin's last six roof blocks, #82, open; the ranch); run 36 on `fa8c3ac`, 326 of 328 (failed: leftover leaves for saplings and the walk back up, both fixed in `79bb3e7`); run 37 on `b417072`, 325 of 329 (the same two, the keep's last roof blocks, #82, and the ranch). A first run 38 on `79bb3e7` stopped part way when the disk filled: the test world, never cleared between runs, had grown to 21 GB, and it is now cleared before each run; its build had failed one JUnit test (below). Run 38 on `7a2738c`, with E-42's first slice and E-43's core: above. JUnit in the builds of runs 35 to 37: 595, 611 and 678 results, four skipped in each.
- The review of 2026-10-24's slice 5 and the start of slice 7 ([review](review-2026-10-24.md)), on top of `150971f`.
  - F-13, E-08b, M-06a: work kept across a restart. New native fixtures in the new class `NativeWorkRestartGameTests`: `aHutInterruptedByARestartIsTakenUpAgainAndFinished` (Pass in full suite runs 35 to 38) and `aFieldInterruptedByARestartIsTakenUpAgainAndFinished` (Pass in full suite runs 35 to 38). The field fixture's body is shared with `NativeCompoundGameTests.withItsWardStandingAPlayerWatersTillsAndSowsItsField`, unchanged.
  - F-25c9: eating as a person does, new JUnit `SurvivalPolicyTest.aHealthyPlayerEatsOnlyWhatFillsWhatIsMissingAHurtOneTopsUp`; the two reflex fixtures that eat now also check no second apple is eaten (Pass in full suite runs 35 to 38). A shore it could not reach passed over, new native fixture `NativeSurvivalGameTests.inDeepWaterItSwimsToAShoreItCanReachNotTheNearestSealedOff` (Pass in full suite runs 35 to 38).
  - F-25c9: stranded, the place and not the targets; new native fixture `NativePortalCampaignGameTests.goalDigsUpOutOfAChamberAfterAFewWalksFindNoWayToItsTrees` (Pass in full suite runs 35 to 38). A route from beside a cell no one stands in (woken on its bed), found by `atNightItLeavesItsBuildForItsOwnBedThenBuildsOnInTheMorning` in suite run 33 (it failed again in run 35, held at its own bed the second night, and passed in runs 36 to 38). `PlayerRuntime.walksFailed` counts a player's walks in vain for fixtures.
  - F-18: `SocialGameTests.aNativePlayerHurtSaysWhatHurtItAndBlamesNobodyForAFall` hurts a native player of the runtime's own once it is past the few seconds' grace a joining player has; a player made in the fixture and never joined keeps that grace, so no fall hurt it (suite run 33). It passed in full suite runs 35 to 38.
  - Fixtures: the two restart fixtures stop the player between its steps, as an administrator would; stopped in the middle of a scaffold step, the player was held for reconciliation as it should be. The cattle fixture says where the player and its cows stand if it runs out; that showed the cow it led left outside the pasture's wall beside the doorway, and `Ranch` now goes back out through the doorway for it (`RanchTest`, one test changed; the fixture Pass in full suite runs 35 to 37; failed in runs 33 and 38 (run 38: both cows left outside the pen, seven blocks from its gate; open)).
  - E-40a: the division of the run (`RunDivision`, core, new), the share-out of a team's finds (`ShareOut`, core, new), and what a player tells its team of them (`TeamTalk`, core, new; `SocialBrain`'s `TEAM` topic); JUnit `RunDivisionTest` (5), `ShareOutTest` (6), `TeamTalkTest` (3), `SocialBrainTest` (1 new). Not yet in play.
  - Run metrics (`companion-testkit` `RunMetrics`, new; `./gradlew :companion-testkit:runMetrics --args=<log>`): the review's section 5 measures that a server log carries, per player. New JUnit `RunMetricsTest` (7). Baseline, live runs 68 and 69 (before these slices): run 68, the most repeats of one failure on one target within ten minutes 23, work resumed after an interruption 1 of 1, changes of work 0.07 a minute, stuck spells recovered 5 of 5, walks that found no way 2.8 a minute and at most 57 in a row; run 69, 19, 46 of 46, 0.12, 31 of 31, 26.0 and 207.
  - Full suite runs 35 to 38, recorded above.
- The review of 2026-10-24's first slices ([review](review-2026-10-24.md)), on top of `0f03c9f`.
  - `main`'s `4cb2b33` brought in: resume connects a saved body first; the roster reconnects saved players rather than skipping them; the one connected player is the default. The resume message no longer says progression is disabled.
  - F-25c9: its reflexes admitted apart from the edit switch and the idle playstyle. `NativeAutonomyGameTests.defaultPolicyKeepsAutonomyDisabled`, which asserted that with edits off a hungry player did not eat, is now `withEditsOffItStillEatsButEquipsAndMinesNothing`; new `NativeSurvivalGameTests.withEditsOffItStillStepsOffMagma` and `anIdlePlayerStillStepsOffMagmaAndEatsWhenHungry`.
  - F-18: native players in the social lane; hurt speech from the damage's record; a player's own news to an audience. New JUnit `SocialBrainTest.hurtSpeechNamesOnlyWhatTheGameSaysHurtItAndDoesNotRepeatWhileTheHurtLasts`; new native fixture `SocialGameTests.aNativePlayerHurtSaysWhatHurtItAndBlamesNobodyForAFall`.
  - E-11 reopened. `TeamCoordinator`: a direct claim checks its project; completion credits only observed output; every change reported. The runtime saves a stored project's changes through the ordered writer and credits what the player made since its claim; startup holds an uncertain action's task and reservation. JUnit in `TeamCoordinatorTest` (3 new; one changed to pass what it observed) and `SharedProjectStoreTest` (1 new); new native fixture `NativeTeamGameTests.aStoredProjectsClaimAndCompletionAreSavedAsTheyHappen`.
  - Found by running the touched classes: with its work not admitted, the reflexes were evaluated and then dropped before the decision (the three reflex fixtures failed); and builds (`BuildRunner`) counted a walk to a spot to place from each time the next step was asked for, which the runtime does more than once, so spots were given up after one or two walks and the farm and pasture wards stalled with every spot round a block given up. Walks are now counted when they end.
  - The touched classes together: Pass in full suite runs 35 to 38 (run 33: see validation)
  - Full suite run 33 on `150971f`: 316 of 320. Failed: the edits-off reflex and a native player's hurt remark (both fixed in `4b13db3`, below), the pasture's cattle and the bed at night (below). Runs 35 to 38 recorded above. The build and JAR beneath are run 38's, on `7a2738c`.
  - `./gradlew build` passed: 692 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `d939b82da69954b5912a870474776dcbfdb5c0663acd85bcb42e37ce451fdafb`.
- M-06a's fourth stage (the hall and its corner towers), on top of `9bf29b6`, validated with the field's work and the third stage below.
  - New JUnit in `CompoundsTest` (2): the hall's own walls whole and lined, the house inside, a tower at each outer corner, nothing of the other wards' in it; reached only by its own way in.
  - New native fixture `withItsWardsStandingAPlayerBuildsItsHallAndTowersAndWalksInByItsOwnWay`. Pass in full suite runs 33 and 35 to 37; failed in run 38: after the hall itself was built, 952 of 952, its south-east tower stopped with no walkable route down to take its scaffold down (#82, open).
  - Builds (`BuildRunner`): a walk over to a block aims at the ground beside the build at its floor, not its lowest block, and at a place it can stand; the hall's south-east tower had stopped with its walk aimed into the ground under it, a course down at its foundation, and in suite run 32 a cabin grown in the woods stopped with its walk aimed at a spot no one could stand on. A spot walked to a dozen times to place from, never there by the plan's reckoning, is left for another (the hut's last stair in suite run 32). Placed from where it stands after three walks running to the same spot that end in its block but off its middle: 7040 walks, in the hall, on a stair it had just placed. The block it stands in reckoned on top of a stair's step or a slab, not in the stair's own block: in another run of the hall, stood on a stair it had just placed, it walked again to the spot above it more than 4700 times.
  - Every class this change touches together: `NativeCompoundGameTests` with the build and ranch classes, in the full suite runs above
  - Full suite runs with this work: run 33 on `150971f`, 316 of 320; runs 35 to 38 recorded above. The build and JAR beneath are run 38's, on `7a2738c`.
  - `./gradlew build` passed: 692 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `d939b82da69954b5912a870474776dcbfdb5c0663acd85bcb42e37ce451fdafb`.
- M-06a's third stage (the pasture ward), on top of `9bf29b6`.
  - New JUnit in `CompoundsTest` (3): the ward's own walls whole and lined, its pen, its doorway built shut; reached only by its own way in, and from the south only by the doorway opened; the whole ward built by the planner onto a standing farm ward.
  - New native fixture `withItsWardsStandingAPlayerBuildsItsPastureWardAndWalksInByItsOwnWay`. Pass in full suite runs 33 and 35 to 38.
  - Its cattle: `RanchRunner` opens a doorway it is given, runs the ranch, and shuts the doorway from the blocks it took; `Ranch` leads an animal up to a doorway in line and through it in one walk. New JUnit `RanchTest.walledInItLeadsTheAnimalUpToTheDoorwayInLineThenThroughItWithoutStopping`; new native fixture `aPlayerBringsAPairInByThePasturesDoorwayBreedsThemAndShutsItBehindThem`. Pass in full suite runs 35 to 37; failed in runs 33 and 38 (run 38: both cows left outside the pen, seven blocks from its gate; open)
  - Validated with M-06a's fourth stage, above: one build, its classes run together, its suite runs and its JAR.
- M-06a's farm ward field watered, tilled and sown, on top of `9bf29b6`.
  - New JUnit `FieldWorkTest` (8): water first, then the whole field from its margins, round the end of the field by a row it can stand on; what stops it; a pour reckoned by the water seen. `CompoundsTest` now checks the field's soil is free of the ward's blocks, which failed on the plan before the ward's own way in moved a row south.
  - New native fixture `withItsWardStandingAPlayerWatersTillsAndSowsItsField`. Pass in full suite runs 33 and 35 to 38.
  - Tilling and planting (`NativeTillAction`, `NativePlantAction`) take `roam`, asked for only by the field's runner: without an assigned area, the server's roaming policy. With an area, and without `roam`, as before.
  - Builds (`BuildRunner`): a placement it sets out for again two walks after it last did, another between, is made where it walks to, as planned when it set out. The farm ward fixture on the moved way in had walked to and fro for four minutes between a plank's spot and a stone's. Committed to every placement walked to, the keep fixture stopped at 682 to 684 of 687 three times in seven runs (its last roof blocks out of every scaffold's reach: #82, open); committed on the ground only, roofs were left in suite runs 29 and 30 (the keep's last three slabs; a cabin's stairs). Committed only to and fro, the farm ward and the pasture ward fixtures walked to and fro again, at 195 of 480 and 185 of 417, whether the block was planned again on arriving or placed as first planned: the runtime asked for the next step again before each walk began, which took the commitment before it set out. It is now kept until the player stands in that spot or the block is placed, and a repeated ask for the same walk changes nothing. A walk round to a block close by after ten waits was tried for the nine by seven cabin and taken out.
  - Validated with M-06a's fourth stage, above: one build, its classes run together, its suite runs and its JAR.
- M-06a's second stage (the farm ward), on top of `b473cd5`.
  - New JUnit in `CompoundsTest` (3): the ward's own walls whole and lined, its channel; the field reached under its own east wall, or through the keep and under the shared wall, and no other way; the whole ward built by the planner onto a standing keep. The keep's way in is now the same passage helper, with the same blocks.
  - New native fixture `withItsKeepStandingAPlayerBuildsItsFarmWardAndWalksInUnderTheSharedWall`. Pass, alone or with its class five times in a row (the first runs, before the ward had its own way in, left the player shut outside its walls at 411 of 437 blocks), every touched class together twice, and full runs 23 and 24
  - Builds (`BuildRunner`): a block it cannot reach says, as it goes over to it, where it stood. (A break's stance walked to the end only where it was not up on the build was tried and taken out: standing on a scaffold column it then planned its next break again, found it out of reach and waited, 94 times in one run against 13 before, and the life-first night fixture's cabin finished after nightfall.)
  - M-01a (`Ranch`): outside the pen to shut it again (one that slipped out no longer out within reach), it goes two in from the gate, not one: within a block of the cell just inside, it stood in the gateway, not in the pen, and walked there again and again until the fixture's time ran out. New JUnit `RanchTest.oneThatWalksBackInOnItsOwnWhileItIsOutsideIsShutInFromInsideThePen`.
  - Every class this change touches together (eleven classes, 31 tests), twice: the first run failed the life-first night fixture (the break-stance change, since taken out) and the ranch fixture (the gateway); the second, after both fixes, passed 31/31.
  - Full `runGametest` runs 23 and 24 passed 312/312 each. A narrow game-test run of the compound class (the next slice's fixtures) ran on the machine alongside part of each.
  - `./gradlew build` passed: 562 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `227d0b3bf3e659841265dec3f20a8337d8c664926c0dc2323cf97bcb7be3f5ee`.
- M-06a (a walled base: the keep) and fixtures that clean up after themselves, on top of `4d7f19a`.
  - New JUnit: `CompoundsTest` (3: the wall whole and lined; the only way in under it; the whole keep built by the planner, three variants turned three ways).
  - New native fixture `anAiPlayerBuildsItsKeepWalledLinedAndEnteredOnlyUnderTheWallThenWalksIn`: the keep built from the blocks it holds, every block exact, then walked into from outside by the passage, three below the yard, the keep still exact. Pass: alone (the fifth run, after four that led to the fixes), in two of three runs of every class this change touches together (the third 30/30), and in both full runs (310/311 twice, their failures elsewhere)
  - Every build (`BuildRunner`), from what the keep and the combined runs showed:
    - A break it sets out for is walked to the end and made there (it had walked to and fro digging the passage, breaking nothing).
    - The build's floor is its origin's height where it goes below that (on a scaffold one up beside the keep's cabin it was reckoned "up on the build" and waited).
    - In the air, just down off its scaffold, it lands before looking for a way round. Eight route searches made from mid-fall, all "no walkable route", had stopped a cabin within a second (the seven by nine seed fixture once, the nine by seven cabin in run 19).
    - After a step fails, it pauses a second before the next, so that eight failures in a row take eight seconds at least: a cherry cabin had still stopped within a second, its route searches failing every tick.
  - The approach to a distant log in a work area stops beside it, never on it. Two blocks off, a walk of two had stepped up onto the log it was to mine, where it could not break what it stood on (the distant-log fixture, in a run of these classes, where its new failure message showed it; it had failed in runs 19 and 20 as well, the cause not shown then). Its failure message now says where the player stood and what lay round it.
  - Fixtures:
    - **Each its own field.** The test server runs several tests at once, spaced by their structures. Fixtures that lay ground far wider than the small empty structure they named laid it over the test beside them: in one run the night fixture and the repair fixture, two players called Mason, built on one field, and the ranch penned a cow not its own. The base, repair, life and seed fixtures now name a structure of empty ground 120 by 120 (`kindred_gametest:wide`), and the ranch, the keep and the two wide manhunt fields one of 80 by 80 (`kindred_gametest:field`), each with its origin put in from the corner. The server clears each structure, keeps it loaded and walls it round with barriers; the fixtures ask for sky access, as without it the server roofs the structure in barriers too.
    - **Put back.** The same fixtures put back what was there when they end, and take away the animals and items left on it (`FixtureGround`).
    - **Morning.** The distant-log fixture and the manhunt's hunt and kit fixtures start in the morning. A hunter waits for daylight before it goes up for logs, and the test server's day runs on from test to test.
    - The ranch logs what it saw when it gives up fetching, as it did only at its end.
  - Full `runGametest` runs: run 21 passed 310/311 and run 22 310/311, nothing else running on the machine. One failure each, both a build that stopped after eight steps in a row found no walkable route to the ground beside what it wanted:
    - run 21: the nine by seven cabin of E-29a, at 267 of 276, a roof stair (as in run 19);
    - run 22: the cabin of M-01c, at its scaffold's taking down. It was built on its second go, so its check that it was built at the first go failed.
    - Where the player stood is not yet known; the next change logs it. One of this change's runner rules, walking a break's stance to the end, may make this likelier if the stance is up on the build.
  - `./gradlew build` passed: 558 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `80c26aaf6a1ac4768f937bd9f4bff2e1743e905d4c55998f0d72241396f430d6`.
- M-02c (staying alive while evading), with the fixes below, on top of `342c1bc`.
  - New JUnit: `EvasionCourseTest` (3).
  - New native fixture `aChasedRunnerNeverRunsTowardItsHomeAndEatsOnlyOnceClear`: a hurt, hungry runner whose home lies straight away from a hunter in sight runs off across, not home, eats nothing while the hunter is within twenty blocks and eats once clear. With each of two controls (no home kept; eating weighed above running) it fails.
  - Full `runGametest` runs with the fixes below: run 19 passed 305/310 and run 20 307/310; this slice's fixtures passed in both, the failures set out below.
  - JAR SHA-256: `e04fe6bf680a4e7555e11af59012c755a5493a734908e0adacdd3222c477048b`.
- Three fixes from the full runs, on top of `342c1bc`.
  - M-01a: a cow hung on the fence beside the pen's gate is brought round in line with it again from outside, twice at most, before it is given up. New JUnit: `RanchTest.oneHungOnTheFenceBesideTheGateIsBroughtRoundInLineWithItAgainFromOutside`.
  - Builds (M-01c's trip, every build): a stone trip with no way on tunnels away from the site, else waits for its set-aside spots, before ending without its stone; and when all else is built, a build goes back once to the cells it gave up on as it went.
  - Full `runGametest` runs, with M-02c's change: run 19 passed 305/310 and run 20 307/310. Nothing else ran on the machine during run 20; the keep's pure test (M-06a, not in this tree) ran beside part of run 19. The failures:
    - **The portal cast** (both runs): `goalCastsAWholePortalFrameFromALavaPoolWithoutDiamonds`, the cast frame not made in time, as in run 17.
    - **The distant log** (both runs): `autonomousApproachAndMineDistantScopedLog`. One more fixture moved where each test stands, and this one now stood 26 blocks east of the seven by nine cabin fixture of E-29a, which had just run, inside the ground that fixture lays (stone and dirt up to six above the test's floor) and leaves behind. In run 18 it stood 78 blocks off and passed. It passed alone, and with the manhunt class (12/12). (Later: that ground was put back once the cabin fixture ended, and the fixture failed again; with a fuller failure message it was seen to have stepped up onto the log it was to mine. See the M-06a entry above.)
    - **The ranch** (both runs): in run 20, the player went after a cow standing on ground another fixture had left five blocks above the pen, and a cow not of its pair ended in the pen ("Both cows penned: 2" failed on which cows). In run 19 the ranch ended at once with "No cows in sight to bring in" as it drew the cows from inside, no cow given up, though the look at its end saw both just outside the gate; why is not yet known. The re-line below came into neither run.
    - **Run 19 only:** the build-needs fixture set out for logs with 77 of the 79 blocks it held placed, the rest not traced; the nine by seven cabin stopped stuck on its roof with no walkable way down at 272 of 276.
    - This change's other fixtures (the cabin of M-01c, the life fixtures, the build classes) and M-02c's passed in both runs.
  - `./gradlew build` passed: 555 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `e04fe6bf680a4e7555e11af59012c755a5493a734908e0adacdd3222c477048b`.
- E-29a (builds grown from a seed block), on top of `85507e4`.
  - New JUnit: `BuildStyleTest` (6).
  - New native fixtures (`NativeSeedBuildGameTests`, six):
    - `givenACherrySeedItGrowsACherryCabinOfItsWorldsOwnPlanAndBuildsItExact`: given a cherry log as its seed and as much of each block as any cherry cabin takes, Mason grows its cabin in cherry on its world's own plan and facing, not the first plan, and builds it exact; its own repair check finds it whole.
    - `withNoSeedItGrowsItsCabinInTheWoodItHasToHand`: with no seed, holding spruce, it grows a spruce cabin.
    - Four fixed shapes, each turned another way: nine by nine under a flat roof, nine by seven, seven by nine with two windows a side, seven by seven laid out the other way about.
  - Found on the way: a nine by nine cabin's gable stopped short of its last ridge slabs, out of reach from the builder's scaffold. No gable now spans more than seven, and a cabin nine by nine is roofed flat.
  - The base, repair and life fixtures build the first plan in oak, as their bills are made for.
  - Full `runGametest` runs: run 18 passed 308/309 and run 17 307/309; the three failures, none in this slice's code, are set out in [E-29a](evidence/E-29a.md). `./gradlew build` passed: 551 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `a797ab283fb69f6c6406cce0a86da4167ce4ec3de18b965249ea62df19167a8b`.
- F-25c9 (one weighted list, life first), on top of `cac6f4c`.
  - New JUnit: `LifeFirstTest` (5).
  - New native fixtures:
    - `atNightItLeavesItsBuildForItsOwnBedThenBuildsOnInTheMorning`: with its cabin built and a hut under way, night falls; Mason walks to its cabin's bed, sleeps, its spawn set there, places nothing asleep, wakes as its sleep passes the night, and finishes the hut exact.
    - `hungryWithTooLittleToLastItHuntsFirstThenBuildsItsBase`: hungry (8) with one loaf among eight cows, it hunts before it begins its base, until a full bar and the reserve are in hand, then builds its cabin exact.
  - Three hand controls, as in [F-25c9](evidence/F-25c9.md): no life list, food only to the next mouthful, sleep only in a work area's bed.
  - Found on the way: the test server keeps loaded and ticking only its tests' own chunks. Cows beyond them froze and shrugged off every blow after the first; a cabin beyond them unloaded and was never judged for repair. The ground the base fixtures share is now kept loaded, and its stone runs on past the field, after a tunnel dug for stone walked off its edge.
  - Suite runs 13 and 14, before those fixes, failed the cabin fixture twice, the repair fixture once and the ranch fixture once; the ranch's failure (cows that would not come through the gate) is the one seen before this slice.
  - The base and repair fixtures now begin by day.
  - Full `runGametest` runs: run 16 passed 303/303; run 15 passed 302/303, its one failure the ranch fixture's known intermittent one (M-01a); runs 13 and 14, before the two fixture fixes above, failed as described. `./gradlew build` passed: 545 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `d49d4ff2a748c16c5c985bfd4f945dc617960321a840744ec9d9fc935e34912d`.
- E-31a (repair of a player's own base), on top of `6f9b4a9`.
  - New native fixture: `aPlayerRepairsItsOwnCabinWhenAChestIsBrokenAndAWallBlownOut`.
    - Mason builds its cabin as its base from the whole bill, as though gathered, with some left over and no spare chest.
    - One of its chests is broken with nothing left of it, and a blast of a creeper's strength goes off outside its north wall.
    - It sees its cabin out of place, says so, and builds back what is missing: the chest crafted from planks at a table, the wall from what it holds and what the blast dropped. The cabin is exact again.
  - Two hand controls, as in [E-31a](evidence/E-31a.md): no check of its bases, and the climb out from anywhere below its floor.
  - Found on the way: in the blast's crater beside the wall, the player climbed out and went back in to place a floor plank, until the fixture ran out of time. It now climbs out only from off its site.
  - The M-01c cabin fixture passed again with that change.
  - Two full `runGametest` runs in a row passed 301/301. `./gradlew build` passed: 540 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `a8a5664fd429245fdcd61f81da3d7d785ced9f38cfd064e6123c873ae760e85c`.
- M-01c (a player's own base), on top of `5b427bb`.
  - New JUnit: `BaseSiteTest` (4), `aTripEndsOnceEverythingIsInHandHoweverTheLastOfItCame`.
  - New native fixture: `aPlayerWithStoneToolsSitesAndBuildsItsOwnCabinThenWaitsForIron`.
    - Mason has the operator's leave for bases, stone tools, and the cabin's wood but half its logs and none of its stone, on grass over dirt over stone.
    - It chooses a cabin, sites it where it stood, and builds what it holds.
    - In one trip it cuts six logs and digs a staircase down off its site for 62 cobblestone, then crafts the furnace and climbs out.
    - It finishes the cabin at the first go, all 222 blocks exact, then waits for iron.
  - Three hand controls, as in [M-01c](evidence/M-01c.md): no leave, stone only from what is in sight, no way back up.
  - Found on the way:
    - surface stone mined round a tree left nowhere to stand;
    - stairs mined away left no way up;
    - a trip that never ended kept the climb-out from running;
    - a table set down went unrecognised, and a second was crafted from the cabin's planks;
    - the walk off the site before digging ended too close to leave it;
    - a climb out that failed one step ended the build;
    - sent "over to" a roof stair, the player walked onto the roof. Every build now walks over on the ground beside the block.
    - in the first suite runs: the staircase for stone dug six deeper each time stone in sight ran out, down to just above bedrock; a pillar from the route back up was taken down as scaffold from far below; mining at the bottom took its own stair steps; a roof block overhead was always "far", so the walk over to it never ended.
  - With the last fix in, the fixture passed six runs in a row alone, and the house and build-needs fixtures again.
  - The runner's limit on one `runGametest` run went from 25 to 45 minutes: the suite with these fixtures outgrew it once.
  - `runGametest` passed 300/300 twice in a row, the third and fourth of four runs on this tree. The second failed once in the ranch fixture (M-01a), whose choice of animals this change does not touch: Wren saw both cows outside its pen and led neither in; the ranch then passed twice alone. `./gradlew build` passed: 540 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `15af1607a4370846e9ed66a03e47131dec07468d472e168a0d0ce3178a335db6`.
- M-01b (bases in three stages): all three built in game, on top of `5ff7e99`.
  - New JUnit: `BaseProgressionTest` (3); `GeneratorsTest` checks the stages by name.
  - New native fixtures:
    - `anAiPlayerBuildsACabinWithItsFurnitureThenWalksInThroughItsDoor`: Mason builds the cabin from its bill, all 222 blocks exact. These include the door, the bed, the table, the furnace, two chests and a torch. Then, from outside, it walks in, opening the door ahead of it and shutting it behind.
    - `anAiPlayerBuildsATwoFloorHouseThenWalksUpItsStairsToTheBedroom`: Mason builds the house, all 553 blocks exact. Then it walks in through the door, which it shuts behind, and up its staircase to the bedroom.
    - `anAiPlayerBuildsAManorWithItsEnchantingRoomThenWalksUpstairs`: Mason builds the manor, all 972 blocks exact, its enchanting table drawing on fifteen bookshelves as vanilla counts them. Then it walks in and up to the floor above.
  - Three hand controls on the cabin and house fixtures, as in [M-01b](evidence/M-01b.md).
  - Found on the way:
    - A chest is saved with `type=single`, which the blueprint did not say, so the planner tried 782 times to break a chest it had placed right; the stages now state the chest's whole state, and a refused break is given up after three tries.
    - A walk that ended just past a door left it open; it is now shut on arrival.
  - `runGametest` passed 299/299 twice. `./gradlew build` passed: 535 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `cc46e4cc74a77ca0f4ca3614b78524c712bc0ec882674a97f005476d96a765ed`.
- E-08c (a build's needs as a queue) and M-01b's groundwork (bases in three stages), on top of `7423174`.
  - New JUnit: `BuildNeedsTest` (4), `eachStageOfABaseIsFunctionalAndBuiltWhole`, `aDoorAndABedArePlacedByTheirFirstHalfTheRestComingWithIt`; `ConstructionPlannerTest` and `PlacementTest` now use a trapdoor and a sign as the blocks with nothing to be set against.
  - New native fixture: `aBuilderShortOfBlocksBuildsWhatItCanThenFetchesTheRestInOneTrip`. Mason holds about two thirds of a 5×5 hut's 112 blocks. Its own storage, twelve blocks off, holds the torch and 20 cobblestone; it takes them before placing anything. It places all 79 blocks it then holds, then sets out once: 17 logs cut from three trees, planks, slabs and stairs crafted at its table. It finishes the hut, every block in its exact state.
  - Hand controls: four fail as expected (no needs at all, storage not taken at once, no building before the trip, a trip for each item: four trips). The fifth, the build's own blocks counted as a source, does not fail this fixture: its walks to its own corner logs found no route.
  - Found on the way:
    - storage could not be assigned to a native player;
    - a roaming player could use no container at all;
    - the first gather went for the hut's own corner logs;
    - no tree was in sight from inside the walls;
    - looks were counted as planned, not walked;
    - a build without a goal had edits disabled for its gathering;
    - back from the trees, the player waited with no target to walk to.
  - Walks are now door-aware (M-01b): a closed wooden door is passable to the navigator and opened ahead, then shut behind. The full suite runs every walk through this.
  - A clock-dependent JUnit test, `AttentionWorkSchedulerTest.dirtyEventStormCoalescesAndRetainsDueAge`, failed once in the first build of this change, which ran beside another build. The scheduler reads the clock itself, and on a busy machine its 2 ms soft budget for the tick ran out before the test's loop reached the work it looks for. The test's tick now starts a minute ahead of the clock, since the budget running out is not what it tests.
  - `runGametest` passed 296/296 twice. `./gradlew build` passed: 532 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `64de215dd9dceb6de6c1b1d1ed5b0d6282e2c5cecdab54242c28c5b704a1bb7b`, from the final build. The two `runGametest` runs were on the same tree before one doc comment in `PlayerRuntime` was moved and the scheduler test changed.
- M-02b (manhunt roles) and the owner's notes of 2026-10-23 recorded, on top of `bcfca5c`.
  - Native fixtures:
    - `aRunnerKeepsItsDistanceFromAHunterInSightAndFightsOnlyWhenCaught` (new): Rue moves off from Hal, held ten blocks away, rather than cut the tree beside it, and is twenty blocks off within eight seconds; with Hal kept at its heels, Rue turns and strikes it.
    - `aHunterMakesItsKitThenArmourFromItsIronBeforeItHunts` (extended from the kit fixture): from sixteen ingots, a sword, a shield, then an iron chestplate and helmet, worn before any strike; then it hunts the runner down.
    - The compass fixture also checks the runner's pace: reliable, not speedrun pace.
  - Four hand controls, each failing as expected: the runner at speedrun pace, no moving off, no fighting back once caught, no armour made.
  - Found on the way: with moving off reverted, an earlier evasion fixture still passed, since Rue's goal took it exploring away from Hal; the fixture now counts Rue's own move-offs.
  - The ranch (M-01a) sharpened after a calf slipped out of the pen in a suite run: animals led round to the front of the gate first, drawn in by turns from across the pen and from inside the gate, and any that get out brought back; six runs in a row alone.
  - `runGametest` passed 295/295 twice in a row, the second and third of three runs. The first failed once in `goalCastsAWholePortalFrameFromALavaPoolWithoutDiamonds`, which this change does not touch: its player did not see the lava pool on its first look and explored off. `./gradlew build` passed: 526 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `3f41fc536c82b6f5539d2faa631c9007c2f5775e0e65379f2c8295ffda5136e6`.
- M-01a (a ranch), on top of `fc2680e`.
  - New JUnit: `RanchTest` (5), `aPenIsARingOfFencesWithItsGateMidSouthAndIsBuiltWhole`.
  - New native fixture: `anAiPlayerPensAPairOfCowsWithItsOwnWoodAndBreedsThem`. From fourteen oak logs and eight wheat, Wren crafts the gate, 24 fences and a table, and builds a seven-by-seven pen. It leads two cows from across a field into the pen with the wheat in hand and shuts them in. It feeds both (two wheat spent); a calf is born; it goes out and shuts the gate. All three are still penned ten seconds later.
  - Three hand controls, each failing as expected: the food never held on the way (the cows never followed), the animals never fed (no calf), the gate not shut behind.
  - Found on the way: opening the gate at once to leave let a cow out in one run of three; the gate now opens only once nothing stands just inside it, and four runs in a row passed alone.
  - `runGametest` passed 294/294 twice. `./gradlew build` passed: 525 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `ce08294898fb8511f8245846cd871dd7f2464b33118028928dda7e41afc17abe`.
- M-04a (build battles), on top of `583b7ad`.
  - New JUnit: `BuildBattleTest` (4), `aBuildBattleBuildsForItsTimeThenWaitsOnTheVote`, `theTowerPyramidAndLighthouseAreBuiltWholeAndTheirScaffoldTakenDown`.
  - New native fixture: `twoAiBuildersBuildTheThemeInCreativeAndVoteOnEachOther`. Two plots marked out where the operator stands; the theme a pyramid; both AI builders in creative and at work, each only on its own plot; both build sandstone pyramids in a minute; each votes on the other; the best mean wins with fireworks; both leave with nothing and in survival.
  - Four hand controls, each failing as expected: creative's blocks not cleared at the vote, the builder's own game mode not given back, the plots not kept, and creative not allowed to the body (as first written: neither builder moved).
  - Found on the way: the body's controls worked in survival only, and a placement in creative (which uses nothing up) failed the conservation check.
  - `runGametest` passed 293/293 twice. `./gradlew build` passed: 519 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `c7e9b5c1ce4d2ef42768fac4770915843155beaa157632b4661c40e0c50f7017`.
- M-02a (manhunts), on top of `52c50cb`.
  - New JUnit: three manhunt tests in `MatchRegistryTest`, `aManhuntShowsItsSidesAndTheWinningSide`.
  - New native fixtures:
    - `aHunterFollowsItsCompassToTheRunnerAndTheHuntersWinAtItsFall`: the tracking compass handed out at the start, cursed to vanish and pointing at the runner. The hunter follows it round a wall to the runner and fights it (three critical hits). The runner's fall wins it for the hunters, the compass is taken back, and the hunter's own pace comes back.
    - `aHunterMakesItsKitFromItsIronBeforeItHunts`: with three iron ingots, sticks and planks, the hunter makes a crafting table, places it and crafts an iron sword and a shield, then hunts the runner down.
  - Four hand controls, each failing as expected: the needle not followed (the hunter went off on its own goal), no compass handed out, no compass taken back, no kit wanted (it went on branch mining for the route's iron).
  - `runGametest` passed 292/292 twice. `./gradlew build` passed: 513 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `87b987e5aee4edcee90fca99abc0d6cbc0fb883a70dbe633bc703697fd9477d9`.
- M-00b (a match shown to ordinary clients) and M-03a (speedrun races), on top of `8c69eba`.
  - New JUnit: `MatchPresentationTest` (4), `SpeedrunSplitsTest` (3), two speedrun tests in `MatchRegistryTest`.
  - New native fixture: `anAiRunnerRacesFromAFreshStartTimedByVanillaAdvancements`. A runner carrying an item is refused, and so is one without the dragon fight's leave. The race starts with the server's PvP off, which it does not need. The runner is set to the dragon goal at speedrun pace. Two advancements granted in vanilla's place: the iron split timed at 0:00.10 and Free the End at 0:01.10, which finishes it. Its own pace back, and refused again once it has the splits. The duel fixture now also checks what each player was shown: both on the boss bar, Ada 3, 2, 1, Fight!, Victory and Bram the same with Defeat, the bar taken down.
  - Four hand controls, each failing as expected: the fresh-start check (the race proposed carrying dirt), the finish (the race never ends), the pace given back (the runner left at speedrun pace) and the countdown shown once a second (each second shown again every tick).
  - Found on the way: starting a match checked the server's PvP, which a race does not need. Ending a race's goal went through the goal's start, which overwrote the pace just given back.
  - `runGametest` passed 290/290 twice. `./gradlew build` passed: 509 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `1ab0001cc284748c8599f88506ecb1ea15e5ca092fa7a0617bc032bea21fa53a`.
- M-00a (duel matches, the first of the match framework) and four fixtures fixed, on top of `5287416`.
  - New JUnit: `MatchRegistryTest` (5).
  - New native fixtures: `twoAiPlayersFightOnlyWhileTheDuelTheyJoinedRuns` and `anAiPlayerTakenOutOfTheGameForfeitsItsDuel`. A strike at the other player is refused before the match, through its countdown and after its end, with the server's PvP on. With the server's PvP off, no duel is proposed. While it runs, Ada (iron sword) beats Bram (wooden sword, leather chestplate) with three critical hits, as the ruleset predicts. A body taken out mid-duel forfeits it.
  - Four hand controls, each one rule reverted, each failing in its own check: the body's match rule (the strike before the match lands), the server's PvP (a duel proposed with it off), the command hold released at the start (Ada held, Bram wins), and a body's leaving (the duel runs on).
  - Found on the way: the test server starts with vanilla PvP off. The first duel ran with neither player hurt. The first no-gate control passed when it should have failed, since the leaked strike did no harm. PvP off is now its own refusal, and the fixture turns PvP on before its first check.
  - Four fixtures failed once each across the runs on this tree, each for a reason of its own, none of them M-00a's. Each is fixed:
    - `theHealthItBelievesIsWorkedOutFromTheHitsItSawLand` (E-41a): believed 20 after a hit that landed. Its zombie stood in daylight, and a burn the fixture did not control hurt it just before the strike. Inside the damage immunity that followed, the strike showed no hurt flash and was rightly not believed. Set alight by hand, the fixture failed the same way. The zombie now wears a carved pumpkin (no armour), which keeps it from burning.
    - `anAxeOnARaisedShieldPutsItOutOfUseForAHundredTicks` (E-41a): the player died to the zombie first ("slain by Zombie"). Its hits landed while the shield was not yet up or was turned away, before one landed on it. The player now has Resistance V, which takes all damage and leaves blocking and disabling as they are; the zombie wears a pumpkin.
    - `theRingIsFilledAndTheEndEntered` (E-39a): the player walked round the portal's ring for a minute knowing one frame, and never saw the rest. Twelve runs of it alone, with two setups, passed. The cause was not pinned down past the frames never reaching the player's knowledge from its background sweep. Beside the ring, the player now looks it over: every frame it can see from its eyes is known at once (`WorldKnowledge.look`, a bounded box with one sight line a frame).
    - `aHutIsBuiltFromItsOwnBlocksEachInItsState` (E-08b): built, it stood beside one scaffold column and waited for ever on another across the hut. The planner took scaffolding down by height alone, ties going by the scaffold set's hash order of absolute positions (so by where the test happened to run), and gave up when the one it picked was out of reach. Now each column comes down from its top, the highest first, of equal height the nearest. One it cannot reach waits while another comes down, and when none can be reached the planner names the column, so the runner walks over to it with the navigator. New JUnit: `ofScaffoldTopsAsHighTheNearestComesDownFirst`, `aColumnOutOfReachFromHereIsLeftForTheRestAndNamed`.
  - Runs on the way (each the whole suite): 289/289, then 288/289 (the belief fixture), 288/289 (the ring), 289/289, 287/289 (the hut and the axe), 289/289.
  - `runGametest` passed 289/289 twice with all four fixed. `./gradlew build` passed: 500 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `af8c5c2cfaab299886b8551e81e4152919567a8db3eadb53c9f9d3ee5f6f5bdb`.
- E-41a, third slice: each fight recorded and read back, on top of `e26e807`.
  - New JUnit: `EncounterRecorderTest` (3).
  - New native fixture: `aFightIsRecordedAndReadBack`. 40 frames in tick order under the ruleset's name, three hits seen to land, and the believed health falling from 20 to 0. The fight's choices are all there: close in, jump for a critical, wait for the swing, strike critically. A first version put the zombie eight blocks off, where the player never saw it; the record showed exactly that ("lost sight of the target").
  - `runGametest` passed 287/287 twice on the final tree. `./gradlew build` passed: 493 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `64228a08e3037f49a9224e76feab981839cc3e6f7124b51dbce31c4bf043f2fc`.
- E-41a, second slice: opponents' health as a player can know it, on top of `16fb83f`.
  - New JUnit: `HealthBeliefsTest` (4), `armourTakesFourPercentAPointOfWhatIsLeftEffective`.
  - New native fixture: `theHealthItBelievesIsWorkedOutFromTheHitsItSawLand`. A zombie hurt to 10 unseen was believed at 20. After one sword hit it was believed at 14.82 against the server's 4.82, the ruleset's estimate matching vanilla's damage to within 1e-3. Its control, reading the server's number, saw 10.
  - The first two suites failed two goal fixtures, both times (284/286). Each assumed the player knew a zombie's health it had not seen: one zombie spawned wounded, one healed mid-fight behind the player's back. With beliefs the players acted as players would, backing off from a zombie believed whole and finishing one believed nearly dead. The fixtures now stage what the player knows.
  - Two fixtures' expectations changed by design. The tactical observer now sees a zombie set to 14 health at its kind's 20. The weapon-choice fixture tests the finishing weapon for a known health directly, and fights a zombie the player saw no hits on with the sword.
  - `runGametest` passed 286/286 twice on the final tree. `./gradlew build` passed: 490 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `8e24515535e60c59597abafc2ede0ac8674c2a90c3042dd2edfa259235c70a39`.
- E-41a (the combat ruleset and adapter, Milestone 0 of the owner's combat design, in progress), on top of `417473e`.
  - New JUnit: `CombatRulesetTest` (5).
  - New native fixtures (`NativeRulesetGameTests`): `aSwingChargesTickByTickAsTheRulesetSays`, `aShieldBlocksOnlyOnceHeldUpFiveTicks`, `anAxeOnARaisedShieldPutsItOutOfUseForAHundredTicks`, `aSecondHitWithinTheImmunityLandsOnlyItsExcess` and `aStrikeReachesThreeBlocksToTheHitboxAndNoFurther`.
  - Hand controls: under the old four-block rule the far pig was struck; without the cooldown check a shield the axe had disabled was raised and blocking for 49 ticks.
  - Found on the way: a game-test step that fails is tried again the next tick, so the first versions' per-tick checks passed their controls. They now keep the first violation. And the test context's `spawnMob` clears a mob's goals: a zombie spawned with it never swung.
  - The manifest's counts had fallen behind (272 fixtures, 475 JUnit passed); they are brought up to date, and its list of native skills now names all 39 registered (fourteen, `native_stair` and `native_pillar` among them, were missing).
  - `runGametest` passed 285/285 twice on the final tree. `./gradlew build` passed: 485 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `39954f49062c4439e264d67bb866dda5af23a50ebfd104c3a27ce4626d000236`.
- F-10h (a staircase built up as it walks, in progress), on top of `fa05d18`.
  - New native fixtures (`NativeStairGameTests`): `aWallTenHighIsClimbedByAStaircaseInOneMovement` and `inTheOpenEachStepIsSetOnAFillerBlock`. They passed 4 of 4 and 5 of 5 alone on the final tree, the same each time: the wall in 79 ticks, never still more than a tick; the open climb in 43, never still. Each block was its own verified record, in one task.
  - Hand control, with stairs taken out of the route planner: the wall was climbed by a pillar in 138 ticks and the open climb by a pillar and a bridge in 163, standing still up to 8 and 11 ticks at a time.
  - Found on the way: a block placed before the jump stops the player against its side, so the block is set in the air. The flight first ended a step below the ledge, and the walk up onto it waited for another task; it is now the flight's last step. A failed flight set aside the player's own cell rather than its first step's.
  - M-05's rare start that never begins, found and fixed. An earlier pass of this tree failed the kiosk fixture (278/279), and the status line showed the body's chunk not simulated. A native body moved outside its own tick into a chunk nothing kept simulated was never ticked again. Its tickets now follow it wherever it is set down. New fixture: `aBodyMovedOutsideItsOwnTickIsStillTickedWhereItLands`; its control, without the fix, froze exactly as the stall did. See [M-05](evidence/M-05.md).
  - `runGametest` passed 280/280 twice on the final tree. `./gradlew build` passed: 480 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `312e6925eef402f84f8f83d2186e868a9d29795ec87792c999a87195017afd43`.
- E-07e (a piece of gold armour for piglins, in progress), on top of `fe4fb99`.
  - New JUnit: `aPieceOfGoldArmourIsFiveIngotsAndOneCraft`.
  - New native fixtures (`NativeGoldGameTests`):
    - `inTheNetherAPieceOfGoldIsMadeFromTheOreAndWorn` and `outOfTheNetherTheIronHelmetGoesBackOn`;
    - `aPiglinLeavesAPlayerWearingGoldAlone`, with its control `withoutGoldAPiglinGoesForThePlayer`;
    - `noGoldIsMinedWhileAPiglinIsNear`.
  - Hand controls: with the calm-piglin filter off, a strike on the calm piglin was offered for 88 ticks; with the piglin rule off, gold was mined with the piglin about.
  - Found on the way:
    - The game-test server simulates no chunk beyond the player's own, and the framework force-loads only the Overworld's test areas. In a Nether room a piglin one chunk away froze after a few ticks, and the first gold fixture passed with it frozen. The Nether fixtures now force-load their room and count the piglin's own ticks. Real servers simulate five chunks or more around a player.
    - A fixture that failed before taking its piglin away held the next fixture's gold back. Each room now clears leftover piglins first.
    - In the Nether gold counted only within twelve blocks, as the piece has no gate there. One suite failed the piglin fixture that way (276/277): freed 14 blocks from the seam, the player went on with the fortress search. In the Nether with no gold on, known ore up to 48 blocks away now counts.
    - One suite failed the kiosk fixture on M-05's rare start that never begins (276/277). The body had ticked twice and was then no longer ticked by its world. The status line now also reports the world's side; see [M-05](evidence/M-05.md).
  - `runGametest` passed 277/277 twice on the final tree. `./gradlew build` passed: 480 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `1ff2286eeee61721eb286956af1d0049c46d939d9142885aa1bde906f7ce7189`.
- E-07e (hoglins left alone, in progress), on top of `4373072`.
  - New native fixtures: `aHoglinAfterThePlayerIsLeftBelowNotFought` (its control, the rule off, never got up out of reach) and `aHoglinAlreadyOnThePlayerIsFoughtBack`, each 4 of 4 alone.
  - Found on the way:
    - An early version climbed with the hoglin already on it, was knocked off its pillar again and again, and died having never struck back (suite 3, 271/272). Too close to climb, it now fights.
    - `hazardArrivalDuringActiveWorkRevokesWorkWithoutOverlappingControllers` failed once (suite 2, 270/271). The retreat had happened, but an idle wait started before the fixture checked only the latest work. The fixture now notes the retreat while it waits; 3 of 3 alone after.
    - Suite 5 failed the kiosk fixture once more on M-05's rare start that never begins (271/272). The status line showed every gate open but the routine refresh never run, so the player's tick hook did not run. The line now also reports whether the body is ticking.
  - `runGametest` passed 272/272 twice on the final tree (suites 7 and 8). `./gradlew build` passed: 479 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `a5f489fafb2606a622c03e48483f5de0b8acf11ef1be1e08b9e4012f8cd28101`.
- F-25c6: a portal is gone through only on purpose, on top of `189a062`.
  - The fix for the bug found in M-05's suite 1: portal travel was allowed for the whole End goal, so a player working beside a lit portal could be carried through. It is now allowed only while the goal's step goes through a portal.
  - New native fixture: `aPlayerHeldInALitPortalIsNotTakenAcrossWhileItsStepIsNotATrip`. Its control, run by hand on the old rule, was carried into the Nether.
  - `runGametest` passed 270/270 twice. `./gradlew build` passed: 479 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `9327d9b1094424f5305b316d16567015e4727c313448a2432f36d745408c4129`.
- M-05 (schematics read into blueprints and built, in progress), with props for E-08b, on top of `843e643`.
  - New JUnit: `SchematicsTest` (4), `PropTest` (2); `BuildSimulation`, a whole build played through the planner as the runner plays it, now shared by `GeneratorsTest` and `PropTest`.
  - New native fixture: `aStructureSavedByVanillaIsReadAndBuiltTurnedAQuarter`. A kiosk is saved by vanilla's own structure writer, read from the schematic library, and built turned a quarter. Every block matches the original as vanilla turns it.
  - Alone, the build class passed 20 of 24 runs. Two failures were an unrecorded prop, now fixed. Two were a build that never began, its cause not yet known; see [M-05](evidence/M-05.md).
  - Suite 1 failed one fixture this work does not touch, 268/269: `whatTheEndFightStillLacksIsGatheredBeforeGoingDownToTheStronghold`. While digging stone for the End fight beside the fixture's lit Nether portal, the player stood in the portal and was carried into the Nether ("We Need to Go Deeper"); the goal then went on to look for a fortress. It was a real bug, fixed in the entry above: a walk or a dig beside a portal could take a player through by accident while its goal allowed portal travel.
  - `runGametest` then passed 269/269 twice (suites 2 and 3). `./gradlew build` passed: 479 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `61406cbbe87744a864626151779f0f52f916fa49db47df8556e37dc1395f090a`.
- E-08a (blueprints and the construction planner, complete) and E-08b (the native player building, in progress), taking over the world-design prototype ([building plan](building-plan.md)), on top of `08d94cc`.
  - New JUnit: `BlockSpecTest` (3), `BlueprintTest` (2), `PlacementTest` (2), `ConstructionPlannerTest` (14), `GeneratorsTest` (2: the hut, and whole huts of three sizes, each turned four ways, simulated step by step through the planner).
  - New native fixture: `aHutIsBuiltFromItsOwnBlocksEachInItsState`. It passed 5 of 5 alone on the final tree.
  - Found on the way. In the planner: a ridge slab with nothing to be placed against; scaffold columns chosen without checking what they reach; spots the player could not walk to. In the adapter: a navigator bug in every walk (a body over an edge whose goal was the block below it got "no walkable route"); the cell stood on misread over an edge; scaffold recorded before its step landed; a break refused where the server's reach and sight checks differ from the planner's. Each is fixed; see [E-08a](evidence/E-08a.md) and [E-08b](evidence/E-08b.md). Earlier runs of the fixture failed on each in turn (107, 110 and 112 of 112 placed but scaffold left; a crash of the fixture's own message once the runtime had stopped).
  - The core package is `dev.kindred.core.construction`. It was first `dev.kindred.core.build`, which the repository's `**/build/` ignore rule for Gradle output silently left out of git; it was renamed, and everything was run again on the renamed tree.
  - `runGametest` passed 268/268 twice. `./gradlew build` passed: 473 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `6100235e5b84aff944213f634793d17b605be2a1b49e5c5347971743524eef91`.
- E-39h (walled in against the dragon's wings) and E-07d (a long fall broken with a water bucket), both in progress, on top of `067bc69`.
  - New JUnit: `ApproachTest` (3), `FallClutchTest` (3).
  - New native fixtures: `wallsAtItsFeetHoldItOnItsTowerAgainstTheWings` and its control `theWingsFlingAPlayerOffABareTower`; `aLongFallIsBrokenWithWaterAndTheWaterTakenBack`, its control `withoutABucketAFallHurtsAsVanillaSays`, and `offAPillarTheFallIsBrokenWithWaterNotAPearl`. Each passed 3 of 3 alone before the suite.
  - Found on the way: vanilla spares a player that has just joined from harm for sixty ticks, and the first fall fixtures dropped the player inside them (a twelve-block fall hurt nothing). They now wait it out. The native body was already reporting falls to vanilla, so native players take fall damage as any player does. The clutch first waited out the hold after a commanded task; as a reflex it no longer does.
  - `runGametest` passed 267/267 twice. `./gradlew build` passed: 450 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `d7cf2879ee3f2f19d05de831255a6c32fe43d3c06cc41523605e7291989d1139`.
- E-39e (damage), E-39f (ender pearls), E-39g (the bed and recovery after a death in the End) and F-25c8 (the playbook and speedrun pace), all in progress, on `d636fa6` and the working tree committed after it.
  - New JUnit: `DamageModelTest` (4), `PlaybookTest` (4), two `BallisticsTest` cases, an `EndFightPlannerTest` case (the bed before going in), `NeedsAssessmentTest` cases (wool and the bed), a `StrategyAdviceTest` case and two `OpenAiCompatibleStrategistTest` cases (the advised way).
  - New native fixtures: `theFightsWeaponIsTheOneThatDealsTheMost`, `aPearlIsThrownUpOntoALedgeAndLandsThere`, `strandedOnAPillarItPearlsDownClearOfTheCrystals`, `aBedSetDownAndUsedSetsTheRespawnPoint`.
  - Dropped: a cage's roof reached by pearl. In suite 6 the throw the search chose (8 landings in 16 samples, none flagged as harmful) fell through the bars and blew the crystal up; see [E-39f](evidence/E-39f.md). Suites 3 to 6 failed only the pearl fixtures (260/262, then 261/262 three times) as the throw's choice was reworked. Suite 2 is void: a Gradle compile ran while it was running.
  - Suite 7 passed 262/262. Suite 8 failed two fixtures that had passed before:
    - `aHoglinWithLavaAtThePlayersBackNeverThrowsItIn`: a hoglin's toss, which vanilla turns by a random angle, threw the player sideways into the lava. Alone it failed 1 in 12 runs at the new full-charge swing and 0 in 12 at the old 0.9. The edge rule now checks a fan for heavy hitters, and the fixture passed 36 in 36 alone. See [E-39e](evidence/E-39e.md).
    - `theEndFightsBowAndArrowsAreMadeOnTheWayFromWhatItGathers`: all five chickens dropped no feathers (vanilla's 0 to 2 each; one run in 243). It passed 16 in 16 alone, and the fixture now has eight chickens.
  - `runGametest` then passed 262/262 twice (suites 9 and 10) on the final tree. `./gradlew build` passed: 444 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `dd3a045952d6aaf85cc2b2dc5ef545957ff058a07b1eb6817c9ffd31c394e61f`.
- E-39a to E-39d (the single player's End fight: into the End, the bow and the crystals, caged crystals, the dragon and home), all in progress, with F-25c7's queue for every task and review fixes, on `432ecdd`, `3279121` and `4faa7da`.
  - New JUnit: `EndFightPlannerTest` (4), `BallisticsTest` (4), two `StrongholdRunPlannerTest` cases.
  - New native fixtures: `theRingIsFilledAndTheEndEntered`, `withoutTheServersLeaveTheDragonGoalIsRefused`, `aCrystalOnItsPillarIsShotDownFromTheGround`, `wornArmourMidBreakIsNoLostItem` (no control run without the fix), `beforeItsPortalTheEndGoalTakesFeathersOnTheWay`, `aHoglinWithLavaAtThePlayersBackNeverThrowsItIn` (a control run by hand before the fix stood at the edge for 81 ticks; the fixture allows 20) and `underTheSeaWithABoatItSurfacesBeforePuttingTheBoatIn`.
  - The crystal fixture failed twice on its own request counting (a shot asked for while the last was still reserving its slice was refused but counted; then a check for an idle activity that is not yet loaded before the first request); the boat fixture counted the same way. Both now count only requests that were taken, with nothing running.
  - `runGametest` passed 258/258 twice on `4faa7da` (the main tree, then a clean worktree on the commit). `./gradlew build` passed: 431 JUnit results, four skipped (the opt-in live model tests).
  - Staged dragon runs 2 to 6 on seed 8675309 (an End kit and a teleport given by command, so not earned): the island's ten crystals cleared in about three minutes, both cages opened in earlier runs, and in run 6 the dragon killed (Free the End) and the player home after the credits. Recorded in [E-39b](evidence/E-39b.md), [E-39c](evidence/E-39c.md) and [E-39d](evidence/E-39d.md).
  - JAR SHA-256: `97d6c247daf11804eb7d7d3553970f6a3d64ae151cabf83abba3ffb0185062b7`.
- F-25c7 (what a larger task needs, assessed ahead and queued) and F-19e (the strategist's Minecraft guide and follow-ups), both in progress, on top of `a8d8b03`.
  - New JUnit: `NeedsAssessmentTest` (4), `TaskQueueTest` (5), two `StrongholdRunPlannerTest` cases, a `StrategyAdviceTest` case, two `OpenAiCompatibleStrategistTest` cases.
  - New native fixtures: `theEndFightsBowAndArrowsAreMadeOnTheWayFromWhatItGathers` and `whatTheEndFightStillLacksIsGatheredBeforeGoingDownToTheStronghold`. The memory round trip now covers the strategist's queue too.
  - Live consultations (opt-in `ClaudeStrategistLiveTest`, three requests to the Anthropic API): each reply valid, with follow-ups queued that fit the report and nothing it already held.
  - `runGametest` passed 251/251 twice. `./gradlew build` passed: 421 JUnit results, four skipped (the opt-in live model tests).
  - JAR SHA-256: `0a8c250834101bbe5919589aaecf22e0a77a8f111326f352fac72e27743c55bb`.
- F-10g (routes that change on the move, in progress), on top of `b3ae08d`.
  - New native fixtures: `aPillarDroppedInTheHallwayIsRunRoundWithoutStopping`, `twoThirdsOfTheHallwayBlockedAheadIsSlippedThroughWithoutStopping`, `aWitherSkeletonBesideTheWayIsGivenAWideBerth` and its control `walkingStraightPastAWitherSkeletonIsNoticed`. New JUnit: `MobSensesTest` (4).
  - Before the change the hallway fixtures stopped 42 and 35 ticks against the obstacle; after, none.
  - `runGametest` passed 249/249 twice. `./gradlew build` passed: 406 JUnit results, three skipped.
  - JAR SHA-256: `3045bcb96aae0f1bfedc0e81eb28696a75851afa361977c10365224a30537ed6`.
- F-10f (boats, in progress) and F-25c6's memory across restarts, on top of `b1c42bf`.
  - New: `aBoatIsLaunchedRowedAcrossAndTakenBackOnTheFarShore` (native) and `itMakesABoatFromWoodItCarriesButDoesNotWaitForOne` (JUnit).
  - `runGametest` passed 245/245 twice. `./gradlew build` passed: 402 JUnit results, three skipped.
  - Staged development runs 10 to 13 on seed 8675309 (gear and a boat handed over) are recorded in the evidence; they are not earned progress.
  - JAR SHA-256: `ae1126ccf3c55a070ec6d42d40da1e7538d381b332edabc1ba2cc3700e229bb7`.
- F-10e (routes that build and dig, in progress) with F-25c6's Nether exploration and its restart fix, on top of `8732b36`.
  - New native fixtures: `aRouteBridgesAGapAndDigsThroughAWall` (passed twice alone in a stress run) and `aBodySavedInAnUnloadedChunkReconnectsAfterARestart` (blocked for reconciliation with the fix set aside; passes with it).
  - The first suite run failed the route fixture once: another fixture's course left a way round its wall. With a wider clearing, `runGametest` passed 244/244 twice, and twice more on the final source.
  - `./gradlew build` passed: 401 JUnit results, three skipped (the opt-in live model tests).
  - Staged development runs 6 to 9 on seed 8675309 (a portal lit by command, a Nether kit handed over) are recorded in the evidence; they are not earned progress.
  - JAR SHA-256: `128ce112f621f9be39ba21d398d8cba469a4721648926f484c14cbc2bb2ede69`.
- F-25c6 (on from the lit portal to the End portal, in progress), on top of `f69d6dd`.
  - New: `StrongholdRunPlannerTest` (7) and `EyeFlightsTest` (4) pass. Two native fixtures, the portal crossing and a recorded throw, each passed alone in stress runs.
  - The crossing fixture with a debug count showed three dimension transits for one crossing before the fix and one after.
  - Staged development runs (a portal lit by command, kit handed over) on a second dev server, seed 8675309, are recorded in the evidence; they are not earned progress.
  - `runGametest` passed 242/242 twice. `./gradlew build` passed: 398 JUnit, three skips.
  - JAR SHA-256: `641f00c1302bd2c6bf76b116d96b7fb542f4839be4e17dc31b1274893816782c`.
- F-10d's live run: run 69 on `47ae8d8` (seed 8675309, sprinted, about 75 minutes) completed about 4,000 walks with no fall deaths.
- Out of flooded workings (F-25c4, live runs 60 and 69), on top of `3fe5110`, with live run 69 playing from its own worktree.
  - The new fixture drowned 3 of 3 in a stress run with the change set aside; with it, it passed 3 of 3.
  - On the way, the trace showed two more causes:
    - the runtime's own water escape kept choosing dry ground it could not reach and blocked the goal;
    - the nearest dry ground by distance was on top of the rock.
  - `runGametest` passed 240/240 twice. `./gradlew build` passed: 387 JUnit, three skips.
  - JAR SHA-256: `37b236404c8b6075fd953c48db926a47b5e39aa7d868b01b8246dc2c362b88fa`.
- E-10b (skin selector from a skins folder), on top of `47ae8d8`, with live run 69 playing from its own worktree on the same machine.
  - `SkinFilesTest` (3) passed. The new native fixture passed 2 of 2 alone in a stress run. In it a 64x128 image is refused with its reason in the log.
  - `runGametest` passed 239/239 twice. `./gradlew build` passed: 387 JUnit, three skips.
  - In game on a dev client (Xvfb, Mesa), on a second dev server (port 25566, flat world):
    - the selector showed two recoloured skins from the front;
    - picking one dressed Kindred at once;
    - the choice survived a server and client restart;
    - "Own skin" brought back the roster skin. Screenshots in `docs/evidence/artifacts/E-10b-2026-10-21/`.
  - JAR SHA-256: `8dc29103bf7f8122091f219fd0568c659c529b44d11f01c3c2a5b2a2373281b7`.
- F-10d (movement as a player moves), on top of `7cbe228`.
  - The new long-walk fixture failed against the old movement in a stress run: 166 to 170 ticks, no jumps, turns of up to 67 degrees a tick, six to eight reversals. The first version of the change jumped but swung round in the air (up to 180 degrees) and dropped its sprint, with 13 to 15 reversals; a per-tick trace showed why. With the fix: 112 to 114 ticks, seven jumps, at most 8.1 degrees a tick, one or two reversals.
  - The first full suite failed `goalClimbsOutOfAPitBelowItsWayBackWithNoPickaxe` once. Its course reaches into the Recoverer fixture's plot, whose setup can fill the pit's ore with stone. It now runs in a batch of its own.
  - `runGametest` then passed 238/238 twice. `./gradlew build` passed: 384 JUnit, three skips.
  - JAR SHA-256: `e9bbb0a9eb4734bdc54da6728b6f8091970f1c78a56216ff01e4c7b54d003ed7`.
- F-19d (the strategist from any provider), on top of `24709f3`: `./gradlew build` passed with 384 JUnit and three skips, among them 9 new model-module tests (the provider choice, and the OpenAI-compatible transport against a local fake server). The opt-in Claude live tests passed after the refactor. The release jar carries the model module but neither Gson (Minecraft's own is used) nor the Anthropic SDK. No native code path changed beyond how the bridge builds its strategist; the native suite is run with the next change.
- Out of a pit by pillaring, the stick reserve from a farther tree, and charcoal (F-25c4, F-25c5), on top of `e555b95`, with no live server running.
  - New fixtures failed before their fixes: the pit 3 of 3. The charcoal and stick-reserve checks are new behaviour.
  - The first two suites after the pillar crashed the test server: an uncertain pillar step (a drop picked up mid-jump failed the exact inventory check) made the runtime take the body away, and the recover fixture then read a missing player. Fixed by counting pickups, as the fight action does; the recover fixture then passed 2 of 2 alone.
  - `goalPunchesItsWayUpWithNoPickaxeLeft` now checks that the cobblestone never grows rather than staying at 24, since the pillar spends some.
  - `runGametest` passed 237/237 twice. `./gradlew build` passed: 375 JUnit, three skips.
  - JAR SHA-256: `d8996f8ac7cfd595b1fc31ff0ac128e40a81357c9b2c2dc9460ee969d45d3f80`.
- One note per lesson, and notes only from the report (live run 66), on top of `6a5ba9b`, with run 66 and sky-island run 13 playing on the same machine.
  - The memory fixture now also keeps run 66's reworded lesson once.
  - `runGametest` passed 235/235 twice. `./gradlew build` passed: 373 JUnit, three skips.
  - The two opt-in live model tests, run by hand with the new instructions, passed.
  - JAR SHA-256: `6d5e4c91bc7fe4a9bf76daa1b6944de5353887d4dccf3b675ed88c5956b2b5e9`.
- F-11c, renewal of a running native skill's paid slice, on top of `21b8dbf`, with live run 66 and sky-island run 13 playing on the same machine.
  - The new native fixture, obsidian broken with a diamond pickaxe with the test server held to 20 ticks a second, failed 2 of 2 in a stress run with the runtime change set aside ("Cancelled: Stopped or unavailable" after one slice). It passed 2 of 2 with it.
  - `runGametest` passed 235/235 twice.
  - `./gradlew build` passed: 373 JUnit, three skips; six new session tests among them.
  - JAR SHA-256: `19fa1aeefee627c56b7612df37b76df7af3b64e183b74956675584081e238424`.
- Logs at the top of a cut trunk (sky-island run 12), on top of `468e1e7`, with live run 66 and sky-island run 12 playing in real time on the same machine (load average about 2.6).
  - The new fixture failed 3 of 3 in a stress run before the fix, and passed 3 of 3 after it; the campaign class then passed 48/48.
  - The first full suite after it failed two fixtures, 232/234. Two more runs each failed one, `goalWalksBackUpTheWayItCameWhenItsPickaxeBreaksBelow`.
    - That fixture built its rock mass past the edge of the fixture's stone floor. There the plot is open two blocks down, and the foot of its staircase rested on whatever a neighbouring fixture had left. With the new batch the layout moved, the cell was open, the iron drop fell through, and the player followed it into a gap under its own route.
    - The fixture now lays a floor of its own under the mass; the goal is unchanged. It also showed that with no pickaxe, a walk back that cannot start (a two-block step) completes at once without moving and is asked for again until the no-progress rule moves the player on. That is left open.
    - The other failure, once in three runs, was `goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater`: its five-second obsidian break was cancelled ("Stopped or unavailable") at the moment it would have completed. It passed in every run before and since; not explained.
  - After the fixture fix, `runGametest` passed 234/234 twice. `./gradlew build` passed: 367 JUnit, three skips.
  - JAR SHA-256: `82a8f2500ae8f932f90daa9a3b14a7e4bb424cece268281947cb09bedb7207b8`.
- Wood first limited to trees in sight (sky-island run 11), on top of `349c270`, with live run 65 and sky-island run 11 playing in real time on the same machine.
  - `runGametest` passed 233/233 twice, with the new fixture.
  - `./gradlew build` first failed one JUnit test, `AttentionWorkSchedulerTest.dirtyEventStormCoalescesAndRetainsDueAge`. Like its neighbour fixed earlier, it read the wall clock separately for the tick start and for each start attempt, and on the loaded machine the tick's soft time budget ran out in between. That test and one other in the class that asserted starts with fresh clock readings now use one reading each; the scheduler is unchanged. Rerun: 367 JUnit passed, three skips.
  - JAR SHA-256: `b92339982654fc7014f14bd91a1236014f252503ba2f48363f7de819566f6d38`.
- Jump crits, the room-for-a-workstation fix and the real-time strategist, on top of `383a932`, with no live server running.
  - `runGametest` passed 232/232 in two consecutive runs, including the new crit fixture. A log line for each fight's hits and crits was added after them and checked with the campaign class (46/46).
  - Before the room fix, one suite run failed the island-outcrop fixture, as it had once earlier today. A stress run of that fixture, ten copies in separate batches, failed 1 of 10 before the fix and passed 20/20 in two runs after.
  - `./gradlew build` passed: 367 JUnit, three skips, which are the opt-in live model tests (the pace benchmark among them, run by hand, see [F-19b](evidence/F-19b.md)).
  - JAR SHA-256: `234c52bfca89053340eea4d5c8af0a372c8c21d2d9856cac3583217aa68823a1`.
- The morning climb out of an island pit (sky-island run 8), on top of `4f2de8d`: `runGametest` passed 231/231 twice with the new fixture, while live run 63 and the sky server ran (load average about 2.4). `./gradlew build` passed: 367 JUnit, two skips. JAR SHA-256: `8ba5672f7c2d3a9cfbf3376272932e106503b393a059f3ecffc84f867ed5210f`.
- The recovery check for casts (sky-island run 7), on top of `3c1c73f`: `runGametest` passed 230/230 twice, with the new fixture, while live run 63 ran on the same machine (load average about 3). `./gradlew build` passed: 367 JUnit, two skips. JAR SHA-256: `40bfa055131af88344327f9b95b335a5944e01526f1a483228149faa2f884223`.
- F-19c (habits and a focus from the strategist), tested on top of `a077a04` with no live server running.
  - `./gradlew build` passed: 367 JUnit results, with zero failures and two skips. The skips are the paid live model tests; run once by hand, both passed, the new one returning focus `FOOD` and habit `MORE_FOOD` for a report with two hunger deaths.
  - `:companion-server-adapter:runGametest` passed 229/229 in two consecutive runs on the final tree, including the three new fixtures.
  - Before the finisher change, one suite run failed `goalFinishesAWoundedZombieInReachRatherThanTurningItsBack` (see [F-19c](evidence/F-19c.md)). That fixture alone passed 12/12 in a stress run both with and without the change.
  - `:companion-testkit:run` passed.
  - JAR SHA-256: `3122034dd6574a470b83e5b8a30ebdad17cdce671532e0ecec6bf080b4cf69cc`.
- Pool skip, leaves pour and coalesced strategist questions, tested on top of `45c40c0`.
  - `:companion-server-adapter:runGametest` passed 226/226 in two consecutive runs. The new cast fixture (water poured beside leaves rather than into them) failed before the fix with the uncertain outcome of sky-island run 5.
  - `./gradlew build` passed: 357 JUnit results, with one skip.
  - The strategist trigger change (a no-progress spell asks at most every two minutes) came after those runs; fixtures never switch the strategist on.
  - On the final tree, three suite runs made while two live servers sprinted on the same four cores (load average about 4) each had one or two timing failures, each time in different fixtures: the gather pause and resume, the break-pickup shaft, the finisher and the island outcrop.
  - With the live servers stopped, the final tree passed 226/226 twice in a row, and `./gradlew build` passed again (357 JUnit, one skip).
- F-19b (optional Claude strategist), tested as an uncommitted tree on top of `ffbb4c4`.
  - `./gradlew build` passed (37 tasks): 357 JUnit results passed, with zero failures and one skip. The skip is the live model test, which runs only with `KINDRED_LIVE_MODEL_TEST` and a key. Run once by hand, it returned validated advice for 1139 input and 591 output tokens.
  - `:companion-testkit:run` passed.
  - `:companion-server-adapter:runGametest` passed 225/225 in two consecutive runs on the final code. An earlier run of the same suite also passed 225/225; it was before a guard for a missing SDK that the fixtures never reach.
  - The two new fixtures: a cave left alone when advised to play safe; climbing out and healing when advised to surface and recover.
  - JAR SHA-256 of that tree: `fd92e9548f7f9521cb83adc5c48982bd8d7925baf44b653bd37e9a96233538da`. The jar nests `companion-model` but not the SDK.
  - Live run 61 (seed 8675309, development, not a release gate) consults the strategist; see [F-19b](evidence/F-19b.md).
- `./gradlew build` passed (37 tasks); 345 JUnit results, zero failures or errors (336 before the F-25c5 planner tests). `:companion-testkit:run` passed its contract checks and `:companion-testkit:runPersonalityDemo` printed all four preference scenarios.
- `./gradlew :companion-server-adapter:runGametest` passed 190/190 in two consecutive runs (188/188 twice before the lava-edge and night-shelter fixtures were added), then 191/191 with the break-pickup shaft fixture, 192/192 twice with the caving fixture, 193/193 twice with the cold-furnace fixture, 194/194 twice with the cover fixture, 195/195 twice with the aquifer staircase fixture, 196/196 twice with the progress watchdog fixture, 197/197 twice with the jump-down fixture, 199/199 twice with the [F-25c5](evidence/F-25c5.md) fixtures (one obsidian cast in place; a whole frame cast from a lava pool and lit without diamonds), 200/200 twice with the F-25c4 fixture for a fight broken off once badly hurt, 201/201 twice with the F-25c4 fixture for digging out of a block in the head space, 203/203 twice with the F-25c4 shield fixtures (closing in on a skeleton, facing a hissing creeper), 204/204 twice with a creeper that lights up mid-fight, and 205/205 twice in a row with a hissing creeper faced when badly hurt and the cast-site fixes (in the run before those two, `NativeDefensivePrerequisitesGameTests.hazardArrivalDuringActiveWorkRevokesWorkWithoutOverlappingControllers` failed once: it races a 40-tick wait against the hazard check, in code this work did not touch; not investigated further). After the tree-fact change the suite passed 205/205 once and failed once on `goalExploresACaveAndMinesIronItCouldNotSeeAtFirst`: the ore was mined but its drop landed a cell beyond, where the pickup walk found no route; the campaign tests then passed four runs in a row. That fixture is intermittent on where a drop lands. Then 206/206 twice in a row with the F-25c4 fixture for walling off a cave the player is no match for, and 207/207 twice in a row with the fixture for holding a passage, then 207/207 twice again with the spare-stick change (345 JUnit) after both tunnel fixtures were made to wait 20 ticks for sky light to settle (each had failed once when the player still counted as above ground on the first tick), and 208/208 twice with the fixture for fighting a mob already in the tunnel (one run before those two failed `NativeAutonomyGameTests.hungryNativePlayerAutonomouslyEats`, "exactly one apple consumed", while the classes were being recompiled under it; not seen before or since). Then 209/209 with the fixture for keeping away from a zombie when unarmed, and 211/211 with the fixtures for taking cover from a skeleton when unarmed and digging out of a block reaching into the head from beside; a second run of that build failed two campaign fixtures: the tunnel-mob fixture once (its first hit knocked the zombie out of reach and the goal walled it off; fixed: a foe it is already fighting a few blocks away still counts as pressing it) and the cave fixture (its iron drop flew out of the fixture's stone block). With the spare-stick reserve raised to four, two full runs passed 210/211, the cave fixture failing in both: its six sticks were now all reserved, so it made no torch, and once more the drop flew out. The fixture now gives nine sticks and a stone block one wider; the campaign class then passed 25/25 twice. Then 212/212 twice with the fixture for walling off a cave with a skeleton in view, and the iron pickaxe made from the first three iron (346 JUnit results). Then 214/214 twice with the fixtures for leaving caves alone until there is a shield and holing up from a provoked enderman. Then 214/214 twice again with the staircase changes (no step over a deep drop or into a danger zone, own pits filled, no jump down among mobs without a shield), which have no fixture of their own. Then the F-25c5 slice (c) fixtures (a cobblestone generator on a floating island, a sapling planted and the grown tree cut, leftover leaves broken for saplings) and the hearing rule for caving without a shield: one full run passed 217/217 and the build 351 JUnit results; the second full run failed the obsidian-from-lava fixture once (allowed to cave again with nothing in earshot, the player walked off while its obsidian was still in the water; fixed: it waits up to five seconds for a valuable drop to clear the water), after which the campaign class passed 30/31 and 31/31 (the one failure: the sapling fixture's grown tree had low leaves round its trunk, and the player set the bottom log aside after two approaches; intermittent). Two full runs on that committed code (`83f546c`) then passed 217/217 each, and two more with sealing skipping openings a mob stands in, with the build (351 JUnit results). Then 217/217 twice more with the climb toward the surface for water, with the build (351 JUnit results). Then 218/218 twice with the fixture for leaving an unprovoked enderman alone (shown failing without the tactical-observer change: the enderman was killed) and the wider staircase danger check, with the build (351 JUnit results). Then 218/218 twice with torches kept out of the dig path and workstations allowed on the step above, with the build (351 JUnit results). Then, with blocks seen past a corner, the sky-island watchdog and search changes, the table pickup and climbing out by hand (two new fixtures), 220/220 in one full run and 219/220 in the other: `goalFightsAMobAlreadyInTheTunnelRatherThanWallingOff` failed once (at ten health with an iron sword it backed off from the adjacent zombie below six health and was killed; see F-25c4), with the build (351 JUnit results); the campaign class alone passed 34/34 before the last change (waiting for a sapling is not time stuck). Then 221/221 twice with the walk back up the way it came, the kit packed before the work below (a spare pickaxe, a table's worth of wood), workstations kept off a sky island's edge and picked back up when dropped, and no staircase through a sky island's last layer, with the build (352 JUnit results). Then 222/222 twice with combat under the goal's one controller (a new fixture finishes a wounded zombie in reach and shows no tactical step cutting in; back-off at a sprint), with the build (352 JUnit results). On the way: the walk-back fixture first failed when mining its ore broke its own staircase (a two-block rise it could not walk) and once when it never saw the ore and a concurrent night fixture had it dig in on the surface (the ore is now in sight at the foot of the stair); the island outcrop fixture timed out when the island-edge placement rule refused every spot on the outcrop (narrowed to columns open to the void) and shared its player's name with the generator fixture (renamed). Then 223/223 twice with the stick reserve sized to the digging ahead, saplings picked up where trees grow only from planting, no walk home when already there, and a cancelled break that only picked up loose items recorded as no effect, with the build (352 JUnit results). One run of the campaign class before that crashed the GameTest server: a break cancelled just after a walk-over pickup was recorded as uncertain, the player was blocked for reconciliation as designed, and a fixture step then read the missing player (the fixture now checks for it, and the test script reports a stale results file or a crash instead of the previous run's results). Then 223/223 twice with a chosen fight or retreat held for a few seconds, no caving while hurt, a climb for food when hurt and out of it, more food for the work below, and no workstation placed on a wall, with the build (352 JUnit results); before the last change the outcrop fixture failed once when its table would not go on the island's edge wall. Then 223/223 twice with backing off along the way it came, cover when a way off fails, a night shelter dug by hand, a fight under way kept against a mob a hit knocked back, no iron pickaxe where there is no ground to dig unless the frame is mined, eight iron ore on the bundled island, and workstations kept off cells an entity overlaps (with a walk to open floor when nothing near will take one), with the build (352 JUnit results). Fixtures adjusted on the way: the break-off fixture's zombie is made whole when the player is hurt (it had been worn down enough to be finished, which the goal now does), the enderman fixture checks the wall in front of wherever the player stands, and the walk-back fixture's rock mass has a wall round its top. Before that, full runs failed the sky fixtures while they were concurrent with each other and with night fixtures (one island's player walked to another island's leaves; night came and it dug in through the island's floor onto blocks left below by other fixtures; a wait of five seconds that `native_wait` refuses); the islands now float twelve blocks up, keep their own daytime, count leaves and stone within eight blocks only, and the build tries other places around the trench when a cell is out of view. A temporary stress class (not committed) ran the cast fixture eight times at once at different spawn positions, three times: 24/24. New since the 19 October gate: [F-25b4](evidence/F-25b4.md) death settlement and vanilla respawn, [F-10c](evidence/F-10c.md) pathfinding goto, [E-03a](evidence/E-03a.md) roaming break, and the five [F-25c4](evidence/F-25c4.md) Nether-portal goal fixtures (obsidian made from lava with poured water; frame placement and ignition from owned blocks; one recorded fight with a mob that is after the player; a fight chase that stops at a lava edge; a night shelter dug and sealed from an empty inventory).
- Earlier runs of this suite on the same branch had two intermittent failures, both now fixed. The night-barricade test hit a tactical retreat and barricade loop beside a frozen zombie while other tests moved the shared clock; a retreat and a barricade now each rest before repeating, and the test keeps its own night and checks the placed block. In the obsidian fixture, the poured water pushed the player off its mining spot; it now holds position while mining. Later the same fixture failed twice in six runs when the mined obsidian stayed in the flowing water filling the hole: the break's pickup timed out, and a later pickup walk into the current was refused and the spot skipped. The goal now picks up obsidian, diamonds, raw iron and flint that a break left lying, but waits until the water is taken back. In the two passing runs after this change the break itself collected the obsidian, so the deferred pickup has not been exercised by a fixture yet.
- `AttentionWorkSchedulerTest.singleUseTokensAndInvalidatedRunningTokenRejected` failed once while a live server ran on the same machine: it read the wall clock separately for the tick start and each start attempt, and the scheduler's soft time budget for the tick ran out in between. The test now uses one clock reading; the scheduler is unchanged.
- A pre-existing livelock was found and fixed: the tactical layer proposed an urgent strike whenever a hostile was observed, even out of reach. Dispatch rejected it and it won again every tick, starving all other work. It is now proposed only when the strike can land.
- Development live runs (not a release gate): a dedicated server on seed 8675309 with `/tick sprint`. One AI player from an empty inventory earned wood, stone and iron tools, smelted iron in its own furnace, a bucket, three diamonds and a diamond pickaxe. It respawned after deaths and recovered its drops. Later runs added a worn iron chestplate, digging in at night, recorded fights won against cave mobs, and dropping surplus stone. A lit portal has not yet been reached live: deaths to cave mobs, creepers and lava still cost each run its tools. Since run 23: fight chases check their footing, creepers are fought hit and run or outrun, and the night shelter is sealed. See F-25c4 for the open steps.
- JAR SHA-256 at commit `34b4c83`: `6f3c2679a6cfedb5138ec9e332056e9d0beebb2bc546255b970c9e77b05a2fa5` (`kindred-mc-0.1.0-SNAPSHOT.jar`). Build, 336 JUnit and 188/188 GameTests were rerun on that commit.

## Vesp renamed Echo (E-10c) — 28 October 2026 (`claude/blissful-fermi-y4tt90`)

On `main` at `91f408a` with the change. Linux x64 cloud container (4 cores), OpenJDK 21.0.12.
- `./gradlew build`: 759 JUnit results, none failed, four skipped (the opt-in live model tests). This includes the new `CanonicalRosterTest` and the updated roster-skin and weapon tests.
- `NativePlayerRuntimeGameTests`, its gametest entrypoints narrowed for the runs (not committed): 3 of 3 runs, all three fixtures each time, including the new `starterEchoReconnectsAPlayerSavedAsVespInsteadOfMakingASecond`.
- The same fixture with the former-name match switched off fails as expected ("Echo's control-deck slot selects the saved Vesp").
- The full native suite was not rerun.
- Evidence: [E-10c](evidence/E-10c.md).

## Accounting periods, activity hours and death conservation — 28 October 2026 (`claude/blissful-fermi-y4tt90`)

F-11d to F-11g and F-07b, on `main` at `f5a39b9`. Linux x64 cloud container (4 cores), OpenJDK 21.0.12.
- Baseline, the full native suite on `main` (`f5a39b9`): 356 of 359. Failed: the portal goal's walk back up its staircase, the off-line librarian bring, and a stone-tools cabin short of its roof stairs (#82). All three are open on `claude/modest-faraday-r3spro`.
- Full suite on `3921f91`, which carries all five slices: 365 of 367. Every new fixture passed: `NativeAccountingGameTests` (6) and `NativeDeathConservationGameTests` (2). F-11c's real-time break and the hazard interrupt passed too. Failed: the pasture's cattle and the keep's roof stairs (#82), the two listed open in that branch's runs 44 to 46.
- `./gradlew build` on `3921f91`: 733 JUnit results, none failed, four skipped (the opt-in live model tests).
- Focused runs, each in a scratch worktree with its gametest entrypoints narrowed (not committed):
  - F-11e, 5 of 5;
  - F-11f, F-11g and F-07b, 3 of 3 each;
  - `NativeDefensivePrerequisitesGameTests.hazardArrivalDuringActiveWorkRevokesWorkWithoutOverlappingControllers` in all eight of those runs, its flake fixed on that branch in `fe4fb99`.
- The clock-behind fixture, trimmed and run on unchanged `main`, failed as the bug predicts: "Activity budget could not be loaded; stopped pending reconciliation."
- Found and fixed:
  - A renewal storm in the last 1.5 s before any slice deadline: a budget write every tick.
  - Continuing a paused native skill ignored the hours and treated a refusal as a fault.
  - Fixtures that hold the server to 20 ticks a second must park again after an early return: each `server.execute` from the IO thread unparks the server thread.
- Evidence: [F-11d](evidence/F-11d.md), [F-11e](evidence/F-11e.md), [F-11f](evidence/F-11f.md), [F-11g](evidence/F-11g.md), [F-07b](evidence/F-07b.md).

## Previous regression — 19 October 2026

The earlier serialized JDK 21 gate passed 179/179 twice on its source version. On the current E-15 source, two consecutive serialized JDK 21 runs passed 182/182 after E-14 outward-expedition and night-return assertions were changed to wait for activity to settle. The runs include portal/restart, actual-arrival hazard observation, observed-Blaze durable native attack, owned-frame assembly and assigned wart harvest fixtures. See [E-15 stabilization](evidence/E-15-stabilization.md).

The current-source `build`, contract demo and personality demo also passed under OpenJDK 21.0.2 (39 Gradle tasks: 9 executed, 30 up-to-date). The contract demo reported 5/5 checks; the personality demo reported all four preference scenarios.

The fixture harness now gives otherwise unpaced `TestServer` ticks a minimum
5 ms duration to allow asynchronous SQLite work to run. This test-only pacing
does not ship in the mod. Empty startup padding was replaced with a real tick
delay; the crop fixture no longer stands inside its crop or cancels its own
harvest. Bounded traversal corrects overshoot, waits for landing, and clears a
descending ledge before braking. The explicit outward-leg fixture prevents a
subsequent autonomous task from replacing its completion status.

Full build and both demos passed (39 tasks: 7 executed, 32 up-to-date). Current
JUnit report aggregate: 329 tests, zero failures/errors; up-to-date test results
were reused. The contract demo passed 5/5 checks and personality demo passed 4/4.
The final JAR excludes GameTest classes and the pacing initializer; its SHA-256 is
`c8c83d0295746a6c69ce8e4f5eba298d883d5f0ea857bc468314527c6bb0a1c6`.

E-15 portal entry/return, exact all-slot/component conservation and cross-dimension runtime restart recovery, policy-authorized owned-frame assembly, assigned Nether-wart harvesting, and an observed-Blaze candidate followed by verified durable native attack now pass native fixtures. E-15 remains open for earned hazard-aware resource trips and physical Blaze Rod acquisition, F-25b production qualification, ordinary-client testing and an earned playthrough. No production release gate is claimed.
Current native increment: [E-14](evidence/E-14.md). Full offline build, 304 JUnit results and all 175 native GameTests pass. Report 2026-10-18. Bounded expeditions, exploration and return planning qualified: waypoint and landmark observation tracking (`WaypointKind`, `Waypoint`); expedition readiness pre-flight checks (>= 14.0 HP, >= 2 food rations, daytime, clear weather, < 32 slots occupied); maximum distance bounding (`DEFAULT_MAX_EXPEDITION_RADIUS` = 64 blocks or custom radius) from home base/shelter anchor; multi-factor return triggers (nightfall and dusk `tod >= 11500`, thunderstorms, critical health <= 8.0 HP, low rations and hunger, full inventory loot capacity >= 32 slots, max radius reached); return route planning and breadcrumb backtracking routing along trail waypoints back toward home anchor via `native_traverse` with priority elevation (`URGENT` for night/storm/health; `PROJECT` for full inventory/dusk/radius); arrival at base detection resetting state to `IDLE_AT_BASE` upon arrival within <= 2.0 blocks; and thread-safe shared team landmark registry (`TeamCoordinator.shareWaypoint`). Four new native GameTests (175 total) and 6 new pure unit tests (304 total). Nether travel (E-15), equipment progression (E-16), and End expedition / Dragon skills (E-39) remain open.

Current native increment: [E-13](evidence/E-13.md). Full offline build, 298 JUnit results and all 171 native GameTests pass. Report 2026-10-18. Establish and maintain home shelter and farm qualified: hoe crafting (wood, stone, iron) and authentic durability wear; native soil preparation (`NativeTillAction`) under `EditPolicy.Kind.PLACE` converting dirt/grass to farmland when within water hydration range (<= 4 blocks); native crop planting (`NativePlantAction`) under `EditPolicy.Kind.PLACE` consuming single seed items with clearance checks; native mature crop harvest (`NativeHarvestAction`) under `EditPolicy.Kind.BREAK` with strict replanting conservation (immediate seed replanting resetting crop to age 0, routing surplus yield to 36-slot inventory); bed placement in designated shelter area; native night and thunderstorm bed sleeping (`NativeSleepAction`) with spawn point anchoring (`player.setSpawnPoint`); and shared multi-step team farm/shelter projects (`FarmHomeProjects`) with task dependencies, stock reservations, single-assignee ownership, and stop revocation. Four new native GameTests (171 total). Bounded expeditions (E-14), Nether travel (E-15), equipment progression (E-16), and End expedition / Dragon skills (E-39) remain open.

Current native increment: [E-11](evidence/E-11.md). Full offline build, 326 JUnit results and all 167 native GameTests pass. Report 2026-10-18. Shared projects and AI-player task assignments qualified: thread-safe multi-agent team coordination (`TeamCoordinator`) with strict task dependency ordering; atomic single-assignee ownership and contention rejection; material reservations against shared stock ledger preventing double-spending; fail-safe revocation and reservation release on player stop or death; periodic lease expiration; durable SQLite Schema 8 persistence (`V8__shared_projects.sql`) with startup crash recovery reconciling unfulfilled tasks and active reservations; PlayerRuntime integration prioritizing team project tasks at PROJECT priority, atomic claim/start dispatch, and completion/revocation runner observation. Four new native GameTests (167 total) and 11 new pure unit tests (326 total). Home/farm establishment and Dragon encounter remain open.

Current native increment: [E-12](evidence/E-12.md). Full offline build, 315 JUnit results and all 163 native GameTests pass. Report 2026-10-18. Personal milestone graph and standing progression policy qualified: pure directed acyclic progression graph (`MilestoneGraph`) with 15 progressive survival milestones (`GATHER_WOOD`, `CRAFT_WOOD_TOOLS`, `MINE_COBBLESTONE`, `CRAFT_STONE_TOOLS`, `CRAFT_FURNACE`, `SMELT_RESOURCES`, `CRAFT_IRON_TOOLS`, `EQUIP_IRON_ARMOR`, `CRAFT_SHIELD`, `SURVIVE_FIRST_NIGHT`, `ESTABLISH_SHELTER`, `SECURE_FOOD_SOURCE`, `MINE_DEEP_ORES`, `CRAFT_DIAMOND_GEAR`, `LOCATE_STRONGHOLD`); dual physical postcondition and historical achievement evaluation distinguishing `PHYSICALLY_MET`, `HISTORICALLY_ACHIEVED`, and `LOST_RECOVERY_REQUIRED` without erasing achievement history; active frontier and recovery frontier discovery; standing progression policy (`ProgressionPolicy`) generating prioritized routine/project recommendations (`native_craft`, `native_smelt`, `native_place`, `native_equip`, `native_mine`, `native_traverse`); PlayerRuntime integration prioritizing progression tool crafting and workstation placement over raw patch mining, and guarding pickaxe requirements; and direct spatial workstation lookups via `body.findNearbyBlock` preventing depletion of the per-tick block scan quota. Three new native GameTests (163 total) and 12 new pure unit tests (315 total). Team assignments, home/farm, and Dragon encounter remain open.

Current native increment: [E-07c](evidence/E-07c.md). Full offline build, 303 JUnit results and all 160 native GameTests pass. Report 2026-10-18. Defensive combat execution, entity retreat, and night shelter qualified: authentic native player attack cooldown ($>= 0.9f$), target reach check ($<= 4.0$ blocks), sightline raycast check, yaw/pitch orientation, and target damage; strict item conservation with at most 2 durability damage to the weapon, and bit-for-bit conservation of all other 35 inventory slots, armor slots, and off-hand slot under single-use `ActionAdmission`; shield blocking with active item mechanics, damage reduction, and synchronous clearing of active item on completion; emergency retreat from ignited creeper fuse ($<= 25$ ticks remaining) at URGENT need 98.0 with single-step verified escape direction away from the threat; emergency night barricade placement under `EditPolicy` at URGENT need 95.0 placing cobblestone block between player and hostile mob within designated work area; and player privacy preservation in combat and single `ActionArbiter` lease compliance. Four new native GameTests (160 total). Parent E-07 remains open for broader survival, night shelter, and team combat integration.

Current native increment: [E-07b](evidence/E-07b.md). Full offline build, 303 JUnit results and all 156 native GameTests pass. Report 2026-10-18. Pure tactical priority hierarchy, read-only threat/weapon evaluation, and bounded combat prediction foundation qualified: pure survival-first priority filter (`SURVIVABLE > EMERGENCY_ONLY > UNKNOWN > INELIGIBLE`) and lexicographic objective ordering (`RESOLVE_IMMEDIATE_SERIOUS_HARM > PREVENT_IMMINENT_IRREVERSIBLE_MATERIAL_LOSS > END_ENCOUNTER_SAFELY > FULFIL_SURVIVAL_OR_TEAM_TASKS > WAIT_FOR_MEANINGFUL_CHANGE`) in `TacticalPriorityPolicy`; dead companion rejection (`health <= 0.0f` -> INELIGIBLE); critical strike candidate gating on physical eligibility; pure combat prediction (`StandardCombatPredictor`) modeling 1.21.1 attack cooldown charge scaling ($0.2 + 0.8c^2$), critical 1.5x on base damage portion only, target-sensitive enchantments (Sharpness, Smite, Bane of Arthropods), target armor mitigation formula, item switch charge resets, 10-tick i-frame non-additivity (`Math.max(0, current - prev)`), unknown enchantment refusal yielding `SurvivalClass.UNKNOWN`, creeper fuse with forced ignition, and multi-enemy accumulated self-damage; read-only native tactical observation in `NativeTacticalObserver` faithfully capturing visible mob health/armor/toughness and 36-slot weapon inventory while strictly preserving player privacy; and shadow tactical evaluation in `PlayerRuntime.beforeNativeTick` executing read-only diagnostics with zero body inputs, zero inventory slot switches, and zero mutations. Four new native GameTests (156 total) and 52 new pure unit tests (303 total). Production defensive combat, active shield blocking, and tactical retreat from entities remain open.

Current native increment: [F-12a](evidence/F-12a.md). Full offline build, 251 JUnit results and all 152 native GameTests pass. Report 2026-10-17. Attention-state policy, fair multi-player round-robin scheduler, and budgeted native candidate planning qualified: pure attention state machine (`AttentionStateMachine`) with states {ROUTINE, ALERT, COMBAT, RECOVERY}, quiet hysteresis, threat freshness knowledge, attack deduplication, and body generation fencing; pure multi-player round-robin scheduler (`AttentionWorkScheduler`) with single-use `WorkToken` pattern, dirty event storm coalescing, soft 2ms time budget, and diagnostic counters; PlayerRuntime integration splitting essential path (unconditional every-tick execution of stop/authority checks, prepaid activity settlement, active runner ticks, immediate hazard retreat, and emergency eating) and scheduled routine planning path; cached routine candidates with conservative cache invalidation on 36-slot inventory fingerprint changes, setup changes, or body generation increments; and shared tick block scan budget (`MAX_BLOCK_CHECKS_PER_TICK = 128`) bounding workstation discovery, resource scanning, nearby mining, and 3D approach traversal. Four new native GameTests (152 total) and 21 new pure unit tests (251 total). Hostile mob combat, defense, shelter, and earned playthrough remain open.

Current native increment: [F-05d](evidence/F-05d.md). Full offline build, 237 JUnit results and all 141 native GameTests pass. Report 2026-10-17. Startup ledger admission barrier, connection callback fencing, and authority preemption qualified (Guides 01 and 02): fail-closed startup ledger scan reading unfinalized action rows (`SqliteCompanionStore.readStartupLedger`), blocking connect and native mutation for companions with unresolved ledger rows; connection token fencing (`ConnectionToken`) preventing stale async connection callbacks from attaching or transitioning after stop/halt/detach; self-swap refused as no-effect with single-take ActionAdmission and `FAILED` ledger state without physical inventory mutation (`NativeSwapAction`); and furnace block entity incarnation validation (`NativeSmeltAction`) refusing mutation when the block entity is replaced. Six new native GameTests (141 total) and 1 new persistence unit test (237 total). Hostile mob combat, defense, shelter, and earned playthrough remain open.

Current native increment: [F-04b](evidence/F-04b.md) and [F-05c](evidence/F-05c.md). Full offline build, 236 JUnit results and all 139 native GameTests pass. Report 2026-10-17. Prerequisites for defensive combat qualified: read-only candidate generation in `PlayerRuntime.evaluateNativeCandidates` proposing `native_swap` operations rather than mutating inventory; durable `native_swap` skill with ActionAdmission and exact 36-slot item component/count conservation; bounded danger observation during active skill execution in `beforeNativeTick` interrupting through `SkillRunner`/`ActionArbiter` cancellation and settlement barrier, preserving goals/checkpoints; delayed callback fencing; and mutation-time furnace authority/claim/revision recheck in `NativeSmeltAction`. Five new native GameTests (139 total) and 1 new core unit test (236 total). Hostile mob combat, defense, shelter, and earned playthrough remain open.

Current native increment: [E-07a](evidence/E-07a.md). Full offline build, 235 JUnit results and all 134 native GameTests pass. Report 2026-10-17. Autonomous health recovery, inventory food management, and environmental hazard retreat qualified: dynamic eating urgency scaling up to 95.0 (`Priority.URGENT`) at critical health (<= 6.0 HP), full 36-slot inventory scanning with automatic hotbar swapping, environmental hazard detection (fire, soul fire, lava, magma, cactus, sweet berries, powder snow) with immediate evasion (need 99.0), registered native retreat skill (`native_retreat`), synchronous stop cancellation, and 100% item conservation under durable accounting. Four new native GameTests (134 total). Hostile mob combat, defense, shelter, and earned playthrough remain open.

Current native increment: [E-04](evidence/E-04.md). Full offline build, 233 JUnit results and all 130 native GameTests pass. Report 2026-10-17. Registry-based crafting and assigned-furnace smelting fully qualified: 2x2 inventory crafting (E-04a), crafting table placement & 3x3 workstation tool crafting (E-04b), stone-tier progression & furnace crafting (E-04c), assigned-furnace smelting & fuel accounting (E-04d), and integrated iron-tier tools (iron pickaxe, iron sword) and shield with tool tier prioritization (netherite > diamond > iron > stone > gold > wood, with hotbar swapping) and end-to-end autonomous smelting-to-iron-pickaxe progression. Strict 100% item conservation and single-take ActionAdmission tracking verified across all operations. Two new native GameTests (130 total). Survival defense, hazards, and earned playthrough remain open.

Current native increment: [E-04d](evidence/E-04d.md). Full offline build, 233 JUnit results and all 128 native GameTests pass. Report 2026-10-17. Assigned-furnace smelting and fuel accounting: authentic furnace workstation interaction (Blocks.FURNACE, AbstractFurnaceBlockEntity), fuel loading and combustion accounting, smeltable ingredient insertion (raw iron/copper/gold, cobblestone, food, logs), output extraction with vanilla XP drops, strict 100% item conservation, durable ActionAdmission (native_smelt) ledger tracking, and autonomous smelting progression (extract_smelt and load_smelt candidates). Five new native GameTests. Iron-tier progression and earned playthrough remain open.

Current native increment: [E-04c](evidence/E-04c.md). Full offline build, 233 JUnit results and all 123 native GameTests pass. Report 2026-10-17. Stone-tier progression and furnace crafting: native mining on stone/cobblestone/pickaxe-mineables with authentic pickaxe wear and durable receipts, 3x3 stone tool crafting (stone pickaxe) at workstation with 100% item conservation, 3x3 furnace crafting from 8 cobblestone, authentic furnace placement into the world under EditPolicy, and autonomous stone progression prioritizing tool upgrades and workstation deployment over raw mining. Four new native GameTests. Furnace smelting, iron progression, and earned playthrough remain open.

Current native increment: [F-25c3](evidence/F-25c3.md). Full offline build, 233 JUnit results and all 119 native GameTests pass. Report 2026-10-17. Autonomous local survival progression linking physical eating, scoped log mining, 3D terrain approach traversal, and multi-step tool crafting progression (planks -> crafting table -> table placement -> sticks -> wooden pickaxe at 3x3 workstation) without human intervention under EditPolicy. Durability wear, exact item conservation, ActionAdmission verification, and stop latching/safety checks verified. Two new native autonomy GameTests. General navigation, furnace smelting, and earned playthrough remain open.

Current native increment: [F-10b](evidence/F-10b.md). Full offline build, 233 JUnit results and all 117 native GameTests pass. Report 2026-10-17. Bounded paid native 3D terrain traversal supporting 1-block step-up jump using native LivingEntity jump physics and safe 1–3 block step-down drop. Profile pass verifies clearance, full support surfaces, headroom and environmental hazards; mid-route dynamic obstruction immediately cancels motion ahead of collision and settles into idle without block alteration; zero teleportation. Four new native traverse GameTests. General navigation, multi-block climbing, furnace smelting, and earned playthrough remain open.

Current native increment: [E-04b](evidence/E-04b.md). Full offline build, 233 JUnit results and all 113 native GameTests pass. Report 2026-10-17. Native player block placement under EditPolicy, proximity workstation discovery, 3x3 workstation tool crafting (wooden pickaxe), unreachable workstation refusal with item preservation, exact ingredient and product conservation, durable ActionAdmission verification, and world/player save/reload persistence. Four new native craft/place GameTests. General navigation, 3D terrain traversal, furnace smelting, and earned playthrough remain open.

Current native increment: [E-04a](evidence/E-04a.md). Full offline build, 233 JUnit results and all 109 native GameTests pass. Report 2026-10-16. Native player 2x2 inventory crafting (oak planks, sticks, crafting table creation), exact ingredient and product conservation, durable ActionAdmission verification, insufficient ingredient refusal, and save/reload persistence. Five new native craft GameTests. General navigation, 3x3 table tool crafting, furnace smelting, and earned playthrough remain open.

Current native increment: [F-08](evidence/F-08.md). Full offline build, 233 JUnit results and all 104 native GameTests pass. Report 2026-10-16. Native player log gathering with survival mining timing, owned tool wear, patch containment, attributable drop collection into inventory, checkpoint resumption after pause/continue without duplicate harvesting, and save NBT persistence. Five new native gather GameTests. General navigation, multi-step crafting, client/crash/zero-human and earned playthrough remain open.

Current native increment: [F-25c2](evidence/F-25c2.md). Full offline build, 233 JUnit results and all 99 native GameTests pass. Report 2026-10-16T01:35:00Z. Bounded native autonomous candidate selection and skill dispatch pass under server edit policy: eating on hunger, equipping armor, scoped log mining in assigned patches, default server policy refusal, and stop latching/cancellation. Drop collection, general navigation, crafting, and earned playthrough remain open.

Current native increment: [F-08b](evidence/F-08b.md). Full offline build, 233 JUnit results and all 94 native GameTests pass. Report 2026-10-16T01:18:14Z. Scoped native log mining with owned tool wear, default server policy denial, stop cancellation, shared claim contention, and verification recovery pass. Scoped mining only; drop collection not claimed; no earned playthrough qualified.

Current native increment: [F-03a](evidence/F-03a.md). Full offline build, 233 JUnit results and all 88 native GameTests pass. Report 2026-10-16T00:25:05.098720800Z. Overflow-safe scope bounds, native access/loaded-world validation, immediate work revocation, acknowledged scope saves and stop-preserving metadata reopen pass. Scope grants no new mining capability; no earned playthrough qualified.

Current native increment: [F-08a](evidence/F-08a.md). Full offline build, 230 JUnit results and all 87 native GameTests pass. Report 2026-10-16T00:20:50.447167700Z. Actual native break events now produce lease/identity/target-bound receipts; external changes and cancellation do not. Includes registered world-tick mining and a native action owner-ack settlement guard. Runtime mining remains disabled; no earned resources or playthrough qualified.

Current native increment: [F-05b](evidence/F-05b.md). Full offline build, 230 JUnit results and all 85 native GameTests pass. Report 2026-10-16T00:17:39.157646900Z. Native equipment now requires durable intent before transfer and verified outcome before completion; held acknowledgements, post-effect write failure, uncertainty/native-save conservation and pending-action shutdown are tested. No full world crash, client or playthrough gate passed.

Current pure/SQLite increment: [F-05a](evidence/F-05a.md). Full offline build and 230 JUnit results pass: core 147, model 12, scheduler 35, persistence 35, testkit 1. Single-use durable action admission remains unconnected to native work. The separate last 82 native results belong to F-07a below; no native run or world-outcome qualification is claimed for this increment.

Current native increment: [F-07a](evidence/F-07a.md). Full offline build, 216 JUnit results and all 82 native GameTests pass. Report 2026-10-16T00:08:22.690829900Z. Four real armor slots and shield conserve exact named/damaged stacks across native file reconnect; failure, pending source change, curse/occupied slot and stop tests pass. Fixture equipment is supplied, not earned.

Current native increment: [F-10a](evidence/F-10a.md). Full offline build, 216 JUnit results and all 80 native GameTests pass. Native report 2026-10-16T00:04:22.454691800Z. Added real paid flat traversal, actual arrival, stop and unsafe/dynamic corridor checks. No ordinary-client, full navigation or earned survival qualification.

Current native increment: [F-04a](evidence/F-04a.md). Full offline build, both demos, 216 JUnit results and all 78 native GameTests pass. Final native report 2026-10-16T00:01:18.731583Z. Real runtime/SQLite tests cover exact timed food, synchronous stop, paid waiting, failed food, pending shutdown and retained spent allowance after metadata reopen. This is fixture evidence within one Minecraft process, not an earned playthrough or full world restart.

Current pure increment: [F-11b](evidence/F-11b.md), asynchronous prepaid sessions. Full build passes; scheduler 35 and total 216 JUnit results pass. No native use yet; the last 76 native results remain the separate F-25b3 run below.

Current pure increment: [F-11a](evidence/F-11a.md). Full offline build passes; scheduler 25 and total 206 JUnit results pass. The unused ActivityPermit adds no native behavior, so native tests were not rerun; the last 76/76 native results remain the separately recorded F-25b3 run below.

Current result: [F-25b3 native runtime lifecycle](evidence/F-25b3.md), 15 October 2026. Full build and both demos pass; 199 JUnit results and all 76 native GameTests pass. Final native report: 2026-10-15T23:42:09.993393700Z. Tests create three bodies, verify file receipts, preserve seven fixture apples and stop across metadata close/reopen, deny unauthorized sources and fence delayed/missing save loads. Runtime reopen uses one Minecraft process; it is not world crash/restart or earned playthrough evidence.

Explicit development native lifecycle and bounded paid wait/eat/flat walking are enabled. Autonomy, portals and implicit death revival remain disabled. Schema stays 7; dependencies unchanged; no client/zero-human/live release gate passed.

## Earlier lifecycle/identity-foundation result
Current result: F-25b2/F-25c1, 15 October 2026. [Native lifecycle evidence](evidence/F-25b2.md) and [identity/migration evidence](evidence/F-25c1.md).

Full offline build and both demos pass. All 74 native GameTests pass, including registration of three native players, one world tick path, real .dat reconnect and bad-save rejection. JUnit results: core 137, model 12, scheduler 18, persistence 31, testkit 1 = 199, zero failures. Native suite timestamp 2026-10-15T23:23:19.428684100Z. Schema is 7; no dependency/version change or live release gate. Ordinary clients, production activation, full crash/recovery and earned progression remain unqualified.

## Earlier mechanics-foundation result
Current result: native mechanics foundation, 15 October 2026. [F-25b1 evidence](evidence/F-25b1.md) records the new backend and all limits; [the tracker](feature-spec.md) controls completion.

Full offline build and both demos passed. All **70 native GameTests** passed, including ten new native server-player mechanics tests. Existing JUnit results remain **193**, zero failures/errors/skips; unchanged tasks reused valid reports. Native fixtures prove bounded timed eating, real slots/tool wear, mining/cancellation, movement, fall/damage, policy revocation, duplicate rejection and thread enforcement. They manually advance unregistered native entities and do not prove connected-player lifecycle or earned survival.

NativePlayerBody ships as a dormant backend; current production commands still create the legacy mob. F-25b player-manager connection/tracking, native-save/runtime migration and remaining mechanics are unqualified. Schema stays 6, dependencies and enabled gameplay stay unchanged, and no live release gate passed. Startup catch-up warnings are not performance qualification.

## Earlier architecture-transition result
Current result: 15 October 2026. [F-25a evidence](evidence/F-25a.md) is the current architecture-transition record; [the tracker](feature-spec.md) alone controls completion.

Full offline build, both demos and 60 native GameTests passed on Windows with OpenJDK 21.0.2 and the unchanged pinned stack. JUnit reports: core 136, optional model 12, scheduler 18, SQLite 26, demo 1; total **193**, zero failures/errors/skips. Up-to-date Gradle tasks reused valid prior results.

The production JAR contains PlayerRuntime/PlayerCommands and core PlayerIdentity/PlayerDecisionLoop. Retired SurvivalBehavior/DuelBehavior exist only in GameTest sources; neither they nor optional model code ship in the server JAR. F-07 and F-08 are reopened for equipment conservation and native mining/traversal qualification. Schema metadata now correctly reports the pre-existing version 6; no migration was added.

No live client, native server-player body, automatic progression, zero-human session or earned three-player playthrough was qualified. Fixtures that grant resources/reposition entities, including seven retired survival/duel tests, cannot establish those outcomes. Both initial and final cancellation recheck passed all 60 GameTests; startup reported 2570 ms and 2123 ms catch-up warnings respectively. This is not performance acceptance.

## Historical validation — not current requirements
Everything below is a dated record from the retired companion prototype. Completion labels, pending-work lists, module/class names, AT labels and instructions describe those historical builds only. They must not guide new implementation; use [the AI-player contract](../minecraft_player_spec.md) and current tracker.

# Development validation — 13 October 2026

Validated locally on Windows 11 x64 with Eclipse Temurin 21.0.12.1+1, Gradle 8.14.3, and the platform versions in `release-manifest.json`. These results are development evidence, not complete M0 or first-playable release acceptance.

## Projects, activity modes and capability-aware task proposals increment (night of 13 October)

Same machine and toolchain, after adding F-16 (discrete Companion / Independent modes, home / approved scope restriction, goal persistence across follow interrupts, intention authority invalidation via monotonic intentRevision, personality display with mode/scope, and honest explanation of personal progression availability):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core | 90 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (58 GameTests across entity, follow, social, mannerisms, work, perception, chores, duel, lifecycle, intentions, survival, and inventory) | 58 GameTests passed |
| Remapped distributable JAR | Built successfully |

Totals: **135 JUnit tests and 58 native GameTests**. The new native tests verify that switching between Companion and Independent modes advances intentRevision monotonically to invalidate old authority while strictly preserving inventory, traits, and stopped state; goals/intentions survive across follow interruptions without being deleted; mode and scope grammar accept optional target UUIDs; and personality status truthfully displays mode and scope alongside traits without adding a tenth slider. See `docs/evidence/F-16.md`.

## Assigned deposit and gather-to-deposit chain increment (late night of 13 October)

Same machine and toolchain, after adding F-09 (deposit chore with checkpoint resumption, verified partial transfer, stack merging, full/broken container honest blockers, and gather-to-deposit chain tested across 10 controlled layouts):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core | 90 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 36 plus 6 deposit tests: exact deposit item conservation, stack merge conservation, full chest honest blocker, partial capacity checkpointing without double counting, replaced/broken chest refusal, and gather-to-deposit chain across 10 layouts) | 42 GameTests passed |
| Remapped distributable JAR | Built successfully |

Totals: **135 JUnit tests and 42 native GameTests**. The new native tests verify that items transfer into assigned containers with strict conservation, unaccepted excess rolls back into companion inventory, partial transfers checkpoint verified counts to prevent duplicate deposits on resumption, full and broken containers block honestly before mutation, and the gather-to-deposit chore chain functions reliably across 10 distinct physical world layouts. See `docs/evidence/F-09.md`.

## Resource claims and fencing increment (late night of 13 October)

Same machine and toolchain, after adding F-06 (expiring resource claims with fencing, runtime mutation gate integration, key release on commit/rollback, task release on observeRunner lifecycle transitions, and native competing-companion fixture):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core (previous 89 plus ClaimRegistry releaseKey 1) | 90 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 35 plus 1 competing-companion claims test) | 36 GameTests passed |
| Remapped distributable JAR | Built successfully |

Totals: **135 JUnit tests and 36 native GameTests**. The new native tests verify that competing companions are denied simultaneous claims on the same target, commits release keys to waiting companions, and task halt/stop releases claims immediately regardless of expiry. See `docs/evidence/F-06.md`.

## Attributable log gathering and checkpointed progress increment (late night of 13 October)

Same machine and toolchain, after adding F-08 (gather_logs chore with initial/target checkpointing, verified contribution, patch containment, and attributable pickup):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core | 89 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 32 plus 3 gather tests: partial gathering pause/resume preventing duplicate collection, patch containment refusal, pickup necessity) | 35 GameTests passed |
| Remapped distributable JAR | Built successfully |

Totals: **134 JUnit tests and 35 native GameTests**. The new native tests verify that gathering breaks only designated logs inside the patch, pickups attribute to inventory, pause/resume from checkpoints gathers only remaining need without re-gathering the initial count, and breaking without pickup does not produce false completion. See `docs/evidence/F-08.md`.

## Authoritative physical inventory and verified item transfer increment (late night of 13 October)

Same machine and toolchain, after adding F-07 (authoritative native slots, equipment helpers, inventory summaries with monotonic revisions, verified transfer primitives with strict conservation and excess rollback, and attributed pickup):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core (previous 86 plus inventory summary 3) | 89 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 26 plus 6 inventory tests: transfer reconciliation, excess rollback on partial capacity, attributed pickup, visual equipment reflection, summary revisions, NBT persistence) | 32 GameTests passed |
| Remapped distributable JAR | Built successfully |

Totals: **134 JUnit tests and 32 native GameTests**. The new native tests verify that item slots persist across entity NBT saves, visual equipment reflects actual owned inventory, transfer deltas between source and destination match exactly with unaccepted excess returned to the source, and pickups attribute only the requested item. See `docs/evidence/F-07.md`.

## Bounded block perception increment (night of 13 October)

Same machine and toolchain, after adding F-24 (chunk-section scan with palette prefilter, exposure filter, and per-tick budget):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core (previous 80 plus block perception 6) | 86 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 21 plus 5 perception tests: look command grammar, palette prefilter, buried block hiding, unloaded chunk refusal, 9-chunk area budget) | 26 GameTests passed |
| Remapped distributable JAR | Built successfully |

Totals: **131 JUnit tests and 26 native GameTests**. The new native tests prove that palette prefiltering skips uninteresting sections without visiting blocks, buried blocks are never located, unloaded chunks are never loaded, the scan stays within 16 sections and 2 detailed walks per tick (<50 ms/tick), and the `look` diagnostic reports truthful section stats and nearest exposed blocks. See `docs/evidence/F-24.md`.

## Work authority, skill runner, ledger, claims and movement skills (evening of 13 October)

Same machine and toolchain, after adding the F-03/F-04/F-05/F-06/F-10 slices (schema 5):

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core (previous 65 plus work setup/edit policy 3, claims 3, action record 3, skill runner 6) | 80 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite (previous 22 plus setup round-trip and schema 4 → 5 fixture, ledger replay/transitions, crash-to-UNCERTAIN recovery, checkpoints) | 26 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 17 plus setup/movement command grammar, storage identity by block+version, move_to blockers and fresh-position arrival, wait counting with pause/continue) | 21 GameTests passed |
| Remapped distributable JAR | Built; SHA-256 `64c158061898fb3ee153615b2feddfa9248c5bb65283057f12d220c12f4a2e71` |

Totals: **125 JUnit tests and 21 native GameTests**. The native tests check validation and identity boundaries in a real world; none shows a body traversing terrain to a marker, a live client, or two owners on one server. See `docs/evidence/F-03.md`, `F-04.md`, `F-05.md`, `F-06.md`, `F-10.md` and the F-01 run guide in `docs/live-qualification.md`.

## Mannerisms, tone drift, naming and pets increment (later on 13 October)

A second run on the same machine and toolchain after adding the E-32..E-37 core slices, schema 4 and the `quirk` commands:

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Core (previous 37 plus mannerisms 7, attention 5, quirk feedback 4, tone drift 4, name forge 4, pet policy 3, notice remark 1) | 65 JUnit tests passed |
| Scheduler | 18 JUnit tests passed |
| Real SQLite including schema-4 mannerism persistence, stale/forged rejection and schema 1/2/3 → 4 migration fixtures | 22 JUnit tests passed |
| Console contract demonstration | 1 JUnit test passed |
| Native GameTests (previous 13 plus quirk command grammar, low-ceiling/no-lease sprint-jump refusal, stop clearing sprint/jump, habit feedback routing) | 17 GameTests passed |
| Remapped distributable JAR | Built; SHA-256 `fe287edda99830d96a628fa7363dab97f6258669a447bd08ccb5bb67bab79e59` |

Totals: **106 JUnit tests and 17 native GameTests**. The personality demo output was unchanged (delivery at helpfulness 100, crops at farming 100, stone at mining 100, urgent delivery still wins at farming 100).

The new native tests prove the command tree, the world-probed hard bounds for a sprint-jump, that `haltControls` revokes sprinting and a queued jump, and that a habit remark is answered through the ordinary direct-chat path without starting movement or editing the saved setting. They do **not** show a live body sprint-jumping across terrain, a visible attention pause, or two-player feedback behavior; those remain E-32/E-33/E-34 qualification work. Tone drift, the name forge and pet eligibility are pure policies with no native behavior in this build. See the evidence records under `docs/evidence/`.

## Automated results (foundation run)

| Check | Result |
| --- | --- |
| Core ownership, control fencing, cancellation, traits, identity, follow spacing, social policy and task preferences | 37 JUnit tests passed |
| Schedule windows, DST, rollback and reservation math | 18 JUnit tests passed |
| Real SQLite identity, quotas, optimistic writes, accounting, profiles, schema migration and restart | 20 JUnit tests passed |
| End-to-end console contract demonstration, including another reopen | 1 JUnit test passed |
| Native Minecraft/Fabric entity, startup, follow, social and personality command contracts | 13 GameTests passed |
| Remapped distributable JAR and sources JAR | Built successfully |

The final automated command was:

```powershell
.\gradlew.bat build :companion-testkit:runPersonalityDemo :companion-server-adapter:runGametest
```

The console demo recorded a durable five-second reservation, simulated reopening before settlement, and reported **5,000 ms charged**, **0 ms observed active**, and **115,000 ms remaining** from a two-minute allowance. The companion identity and explicit stop remained intact. This is a metadata/authority simulation, not actual gathering evidence.

The native tests start a real Minecraft GameTest server and check:

- Kindred's type is registered with Polymer as a server-only entity and maps to `minecraft:villager`.
- Its tracked-data entries use IDs and serializers understood by that vanilla representation.
- Native NBT serialization preserves entity UUID, companion UUID, owner UUID and the stop latch.
- Native navigation and queued movement are cancelled.
- Kindred's real SQLite writer initializes successfully inside Fabric before commands are admitted.
- Follow landing checks reject low ceilings, flooded destinations, damaging ground and missing support.
- Yield targets do not point through the owner, and revoked follow callbacks cannot restart native navigation or gaze.
- Social perception rejects threats behind opaque walls and beyond eight blocks.
- Public conversation requires opt-in, direct replies remain private, and chat while stopped does not start navigation.
- Companion exchanges terminate after two lines and consume both participants' allowances; inactive/private companions are excluded.
- Ambient recipients must be nearby and authorized, with shared listener cooldowns across companions.
- All nine traits and their aliases parse in the native command tree; out-of-range values are rejected.

The earlier native startup test caught a driver-discovery failure caused by the worker context class loader. Explicitly loading the JDBC driver through the persistence module's defining class loader fixed it. The current run logged `Kindred M0 ready` and passed all thirteen native tests, alongside 76 JUnit tests.

Migration fixtures upgrade schema 1 and schema 2 records to schema 3 and verify preserved identity, stop, charged allowance and saved social choices. New preferences default to neutral 50; all four survive reopen. Social profiles and personality revisions survive reopen; stale or removed-companion writes are rejected. The social tests cover observed-fact freshness, privacy changes, cooldowns, deterministic trait effects and dialogue/control separation.

Eight task-preference tests cover close choices, neutral/default values, blended and weak relevance, the five-point cap, hard eligibility filters, urgency, stable ties and checkpoint switching. The synthetic demonstration selected delivery at helpfulness 100, crops at farming 100, and stone at mining 100; an urgent delivery still won with farming 100. These are core decision comparisons, not live world work.

Machine-readable reports are generated in each module's `build/test-results/test` directory and `companion-server-adapter/build/gametest-results.xml`. Test-only classes and the GameTest mod descriptor are excluded from the distributable JAR.

The earlier foundation JAR was also loaded by a separate Fabric server launcher using only Kindred and Fabric API in its mods directory. Fabric discovered all nested Kindred, SQLite, Polymer and packet-tweaker dependencies and reached the normal first-install EULA gate. That packaging smoke check was not repeated for this behavior change; it did not start a playable server or accept the EULA. The current native GameTest run above provides the separate world/SQLite-startup checks.

Artifact: `companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar`.

SHA-256 for the validated artifact: `9c5a317502db1bdf5b5993040b6472c32eb7627e9fcaa14f3bb357a72013d0c8`.

## Stage A shared-action correctness: placement fencing, gather action IDs and collision pickup (17 October 2026)

Run on Windows 11 x64, OpenJDK 21/24, Fabric/Minecraft 1.21.1:

```powershell
.\gradlew.bat build
.\gradlew.bat :companion-testkit:run
.\gradlew.bat :companion-testkit:runPersonalityDemo
.\gradlew.bat :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Full build (37 tasks) | BUILD SUCCESSFUL |
| Testkit contract demo | Passed (5/5 assertions) |
| Personality demo | Passed (4/4 decision checks) |
| Native GameTests (152 total) | All 152 GameTests passed cleanly |
| Remapped distributable JAR | Built (`companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar`) |

New acceptance test coverage under `NativeStageAGameTests` and `NativeGatherGameTests` covers:
- `NativePlaceAction`: Destination replaceability and support block drift fencing, setup revision gating, claim revocation recheck, and component-aware inventory conservation.
- `NativeGatherAction`: Multi-break distinct action IDs and sequential substep indexing under a single task and lease; causal drop tracking distinguishing pre-existing drops; authentic native player collision pickup (`Intent.MOVE` permitted under `native_gather`) without remote insertion; and vanilla pickup delay and cannotPickup preservation.
- Full evidence record: `docs/evidence/F-05e.md`.

## Qualification still pending

Actual logins and rendering using vanilla 1.21.1, Fabric without Kindred, and optional Kindred clients have not been exercised. Native tests validate the required registration/presentation mechanisms, not the complete login protocol or visual experience.

Player-driven creation/follow commands, multiplayer ownership denial, real world restart/duplicate-body reconciliation, and the specified 100 movement-stop samples remain pending. Gathering/deposit, resource claims, autonomous admission, daily activity enforcement in the world and zero-player work are not enabled. No first-playable acceptance gate or performance target is claimed.

The presence expansion adds preferred formation targets, spacing hysteresis, owner-proximity yielding and waiting glances. These still require visual evaluation and continuous native movement tests over varied layouts. The new native tests exercise landing/cancellation contracts; they do not establish smooth path traversal, general doorway negotiation or player-like appearance.

The social increment supplies finite event-based dialogue rather than free-form model conversation. Native tests exercise real entity visibility and the observation/output routing boundary with captured recipients; they do not test live signed-chat transport or a sustained multiplayer social session. Name-addressed chat, commands, pacing and public/private behavior still require that live session. No durable relationship memories or completed-work claims are generated by conversation.

The activity preferences are saved and editable, with their core ranking policy tested independently. The native task director does not yet use that policy because physical work skills and autonomous admission are not installed. No live farming, mining or combat behavior is claimed.

## Shared Projects and AI-Player Task Assignments (E-11) — 18 October 2026

Run on Windows 11 x64, OpenJDK 21/24, Fabric/Minecraft 1.21.1, SQLite schema 8:

```powershell
.\gradlew.bat build
.\gradlew.bat :companion-testkit:run
.\gradlew.bat :companion-testkit:runPersonalityDemo
.\gradlew.bat :companion-server-adapter:runGametest
```

| Check | Result |
| --- | --- |
| Full build (37 tasks) | BUILD SUCCESSFUL |
| Testkit contract demo | Passed (5/5 assertions) |
| Personality demo | Passed (4/4 decision checks) |
| Pure JUnit test suites (326 total) | All 326 tests passed (0 failures, 0 errors) |
| Native Fabric GameTests (167 total) | All 167 GameTests passed cleanly |
| Remapped distributable JAR | Built (`companion-server-adapter/build/libs/kindred-mc-0.1.0-SNAPSHOT.jar`) |

New acceptance test coverage under `NativeTeamGameTests` covers:
- Cooperative task dependency ordering enforced across peers (`cooperativeTeamTaskOrderEnforcedAcrossPeers`).
- Single-assignee ownership and atomic contention rejection (`contendingClaimsRejectedWithSingleAssigneeOwnership`).
- Shared stock reservations preventing double-spending of limited materials across peers (`sharedStockReservationPreventsDoubleSpendingAcrossPeers`).
- Clean revocation and reservation release on player stop or death (`playerStopOrDeathRevokesTaskAndReleasesStockForPeers`).
- Durable persistence (Schema 8) with startup crash recovery reconciling unfulfilled tasks and active reservations (`SharedProjectStoreTest`).
- Full evidence record: `docs/evidence/E-11.md`.


