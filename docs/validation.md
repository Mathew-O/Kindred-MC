# AI-player development validation

## Current regression — 22 October 2026

Linux x64 cloud container, OpenJDK 21, pinned Minecraft 1.21.1 / Fabric Loader 0.19.5 / Fabric API 0.116.17. Branch `claude/modest-faraday-r3spro`.

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


