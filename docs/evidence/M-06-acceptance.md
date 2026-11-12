# M-06c to M-06h: the acceptance mapped to the tests

Date: 2026-11-12
Agent / branch: Claude Session A (`claude/m06-acceptance-map-1112`, off main `f009244`)
Build: none; read from source on main `f009244`
Status: a mapping only; docs, no code, no runtime test claimed

## Scope

This maps the M-06c..l row of the tracker's acceptance table ("the spec's worked examples and acceptance scenarios 1 to 12 as JUnit and fixtures; each bypass blocked; ...") and each of M-06c to M-06h's own acceptance lines in the design to the tests that cover them.
- **Verdicts.** Each test is named by class and method, and every one was found in source. Each item gets one verdict: Covered, Partly covered (what is missing is named), or Not covered.
- **What this does not show.** The mapping says what the tests check, not that they pass today. CI on main runs them.
- **Paths.**
  - Core JUnit: `companion-core/src/test/java/dev/kindred/core/factions/` and `.../core/market/`.
  - Adapter JUnit: `companion-server-adapter/src/test/java/dev/kindred/fabric/MarketArbitrageTest.java`.
  - GameTests: `companion-server-adapter/src/gametest/java/dev/kindred/fabric/`.

All methods claimed in `docs/evidence/M-06c/d/d1/e/f/g/g1/h.md` exist, including the ones they cite from other evidence
(`NativeWorldRecordGameTests.everyLedgerIsRebuiltFromTheRecordAloneAfterARestart`,
`NativeFactionPlayGameTests.freeTravelIsForbiddenByDefaultAndAnAiPlayerIsOnlyToldItsHome`, `ServerPolicyTest`,
`NativeHudGameTests`, `tools/eval/test_eval.py::FreeSwitchTest`). The case counts quoted are right: TerritoryTest 7,
SafeZonesTest 3, MarketTest 7. M-06h's acceptance table says "AuctionsTest (5 cases)", as it had then. Its later section names the sixth, `aDeliveryBegunIsOwedNoLongerSoARestartNeverHandsItOverAgain`.

## The rulebook's acceptance scenarios 1 to 12 (`proposals/factions-smp-spec.md`)

| # | Scenario | Tests (J = JUnit, G = GameTest) | Verdict |
|---|---|---|---|
| 1 | Power accounting: 10→8, 8→9 after 60 online min, not 15 | J `FactionsAndPowerTest.scenarioOnePowerAccounting` (15 min: 8; 59:59: 8 with 3599 s; 60:00: 9), `theTableOfSectionThree`, `onlyEnemyFactionMembersOutsideASafeZoneMayStrikeAndOnlyTheirKillsCount`, `theSamePairCountsOnceInTwentyMinutesAndNewAccountsNotAtAll`. G `NativeFactionPowerGameTests.aPvpDeathCostsTwoPowerAndARestartKeepsRegenerationToTheSecond` (10→8 natively; progress kept across a restart) | Covered. The hour's regeneration (8→9) is JUnit only; natively only about 3 s of progress is checked. |
| 2 | Overclaim trigger: 45@50 safe; 4 deaths, 45@42, 3 exposed | J `FactionsAndPowerTest.scenarioTwoTheOverclaimTriggerAndTheWorkedExample`, `CaptureTest.scenarioTwoTheOverclaimTriggerOpensCapture`. G `NativeCaptureGameTests.aBorderChunkIsCapturedByTheRuleAndItsChestChangesHandsUnmoved` (an overextended faction, power 1 with 5 chunks; power written to the Record directly) | Covered. The exact 45/50/42 numbers are JUnit only. |
| 3 | Capture boundary: no jump to an interior chunk; border first, then inward | J `CaptureTest.scenarioThreeBorderFirstThenInward`. G `NativeCaptureGameTests.aBorderChunkIsCaptured…` (centre refused "Not on the border"; border chunk taken) | Covered |
| 4 | Pre-capture protection: no chest opening or block breaking until captured | J `CaptureTest.scenarioFourAVulnerableChunkIsProtectedUntilCaptured`. G `NativeCaptureGameTests.aBorderChunkIsCaptured…` (attacker cannot open or break the chest before the capture) | Covered |
| 5 | Protected spaces: spawn and admin commercial plots never captured | J `CaptureTest.scenarioFiveSafeZonesAreNeverCaptured` (synthetic claim on safe chunk 0,0), `SafeZonesTest.theHubIsPlannedFromTheBuildersGenerators` (the hub plan is "spawn lighthouse" + "market district", both safe), `TerritoryTest.spawnTheBorderTheNetherAndSafeZonesAreNeverClaimed`, `FactionsAuditTest.aCaptureOfASecureFactionIsFound` | Partly covered. JUnit only: no GameTest tries a capture inside a safe zone. There is no separate admin commercial plot apart from the hub's market district. |
| 6 | Shops after capture: no duplicated money, wrong-owner shop trades, vanished stock | No player shop exists. `factions-mode.md` lines 6 and 21 and `packs/factions/README.md:103` say "no chest shops" (owner's decision). The market is server-run, so it has no owner who could lose it. Nearest checks: G `NativeCaptureGameTests.aBorderChunkIsCaptured…` (the chest's 5 diamonds unchanged through a capture and a recapture; the old owner can no longer open it). J `FactionsAuditTest.coinsFromNowhereAndABalanceThatDoesNotAddUpAreFound` | Not applicable as written. No test covers auction escrow, or a player's market or auction state, across a capture. |
| 7 | Every-item sell path, with edge cases | See the breakdown below | Partly covered |
| 8 | Dynamic-price bulk sale; atomic commit | J `MarketTest.aBulkSaleWalksTheCurveAndCostsTheNextSeller`, `aSaleAndItsCoinsAreOneFactAndTheMarketComesBackFromTheRecord`. G `NativeMarketGameTests.aSellAllSettlesAtomicallyAndMovesThePrice`, `aDisconnectMidSaleLeavesBothSidesWhole`, `theSellersFileIsWrittenBeforeTheCoinsAndFurtherTradesWait`. G `NativeMarketScreenGameTests.aStackIsSoldForExactlyWhatTheMarketQuotes`, `aPriceThatMovedIsRefusedUntilClickedAgain` | Covered |
| 9 | Arbitrage: buy/sell and crafting loops cannot mint | J `MarketArbitrageTest.noRecipeMintsCoins` (1,258 vanilla recipes from the jar, over the baseline plus 200 random states). It parses crafting_shaped/shapeless, smelting, blasting, smoking, campfire, stonecutting and smithing_transform; every other type is dropped (`default -> return null`). J `MarketTest.purchasesWalkUpAndOnlyRawMaterialsTheMarketStocksAreSold` (the buy price is above baseline × (1+spread); made items are not sold by the market). J `AuctionsTest.aListingCostsTwentyFiveCoinsAndAWinningBidThreePercentBothDestroyed`, `theFilterIgnoresTheSameItemBackAndForthAndOneAccountWithItself` | Partly covered. No test buys and sells back the same item and asserts a loss; the spread only implies it. The walk leaves out special recipes (e.g. `crafting_special_repairitem`), anvil and grindstone repair, and smithing_trim. The M-06g design names "repairing" and "uncrafting"; uncrafting is covered only where vanilla has a recipe, such as block→ingots. |
| 10 | Border: no crossing or exploit beyond ±12,000; predictable portals | G `NativeSafeZoneGameTests.theBorderIsSetAndANetherPortalBeyondItIsRefused` (overworld 8,000 wide centred on 0,0 with a 20-block warning; Nether 1,000; End 8,000; a Nether portal at x 600 refused with its reason, x 100 inside). J `TerritoryTest.spawnTheBorderTheNetherAndSafeZonesAreNeverClaimed` (claims refused past +4,000 of the 8,000 border; Nether playable to 500). J `FactionsAndPowerTest.settingsAreReadAndCheckedFromTheFile` (asserts default 8000 "not the rulebook's 24,000"; parses 24000) | Partly covered. The tests use 8,000 wide (±4,000), not ±12,000. 24,000 appears only as a settings parse. Not tested: a player crossing or escaping the border (pearls, elytra, boats); End portal or gateway transitions; the overworld→Nether direction; a capture beyond the border. |
| 11 | Persistence: restart mid-market and mid-regeneration | G `NativeFactionPowerGameTests.aPvpDeathCostsTwoPowerAndARestartKeepsRegenerationToTheSecond`. G `NativeWorldRecordGameTests.everyLedgerIsRebuiltFromTheRecordAloneAfterARestart` (the sale completes before the restart). G `NativeAuctionGameTests.aRestartMidAuctionLosesNothing`, `aStopMidHandoverHandsNothingOverAndWritesNothingAfterIt`, `aCrashAfterTheHandoverIsNeverHandedOverTwice`. J replay tests: `FactionsAndPowerTest.everythingComesBackTheSameFromTheRecord`, `TerritoryTest.claimsAndCoinsComeBackFromTheRecord`, `MarketTest.aSaleAndItsCoinsAreOneFactAndTheMarketComesBackFromTheRecord`, `AuctionsTest.theHouseComesBackFromTheRecord`, `aDeliveryBegunIsOwedNoLongerSoARestartNeverHandsItOverAgain` | Partly covered. No test restarts or crashes during a market sale (between intent and settle). The mid-sale case is tested only as a disconnect, and as a hold within one runtime. |
| 12 | Load test: farms, auctions and PvP together; ticks, DB load, market latency | Only eval runs exist: `docs/evidence/eval/factions2b-ffd02cb`, `factions2c-ffd02cb`, `factions2d-4ba244e` (3 AI players a server; mean and worst tick, ticks over 50/200 ms, heap). Those runs report "no captures or kills", market sales only in 2c and 2d (none in 2b), and no auctions. `NativeFactionsEvalGameTests` (3) audit rules and measure nothing about load. `SafeZonesCostTest` is a micro-benchmark of one lookup. | Not covered. Eval runs record tick and heap only. No concurrent PvP or auctions, and nothing on DB load or market transaction latency. The M-06k month run is not done. |

### Scenario 7 breakdown

| Edge case | Tests | Verdict |
|---|---|---|
| Vanilla items in general | J `MarketTest` (iron, cobblestone, iron block, pickaxe); G `NativeMarketGameTests` (iron ingots, diamonds) | Partly covered. No test sweeps every item in the registry through the sell path. |
| Damaged tools | J `MarketTest.theCanonicaliserKeysOnlyWhatIsValued` (pickaxe at 25 and 10 durability bands; price below a third of a fresh one) | Covered in JUnit. No GameTest checks `MarketDesk.facts` damage mapping. |
| Enchanted books | J same method (mending book keyed and worth more than a plain book; unknown enchantment `othermod:zap` gives "Valuation pending") | Covered in JUnit. No GameTest checks `STORED_ENCHANTMENTS` mapping. |
| Potions | J same method (swiftness keyed and pending with its reason). G `NativeMarketGameTests.anItemTheMarketCannotValueIsRefusedWithItsReason` (swiftness potion refused with its reason, kept, nothing paid) | Covered, as a refusal. No potion has a baseline, so no potion sale is tested. |
| Shulkers | J same method (a filled shulker gives "A container with things in it: empty it first"). G `anItemTheMarketCannotValueIsRefusedWithItsReason` (a shulker with 3 diamonds refused) | Covered for a filled shulker. No test sells an empty one. |
| Renamed items | J same method (a plain `ItemFacts`: the core type carries no name field, so this case is trivial). G `anItemTheMarketCannotValueIsRefusedWithItsReason` (a `CUSTOM_NAME` diamond is quoted as a diamond) | Covered (the real check is the GameTest) |
| Custom metadata | J same method (`minecraft:unbreakable` gives "Valuation pending: data the market does not know"); filled map and suspicious stew keyed on the base item | Partly covered. JUnit only: no GameTest puts `CUSTOM_DATA` or another unknown component through `MarketDesk.facts`. |
| Unsafe or illegal items denied with a reason | J same method (command block and zombie spawn egg give "Never paid for: an admin-only item"). G `anItemTheMarketCannotValueIsRefusedWithItsReason` (command block refused; the seller is told "Refused: Valuation pending…" for the potion) | Covered |

## The design's acceptance lines, M-06c to M-06h (`proposals/factions-mode.md`)

### M-06c (line 57)
| Item | Tests | Verdict |
|---|---|---|
| JUnit for every rule in the spec's table | J `FactionsAndPowerTest.theTableOfSectionThree` (plus the death, anti-farming, ranks and 10-cap tests) | Covered |
| 10→8 on a PvP death; 8→9 after 60 min, not 15 | J `scenarioOnePowerAccounting` | Covered |
| 45@50 safe, 3 exposed @42 | J `scenarioTwoTheOverclaimTriggerAndTheWorkedExample` | Covered |
| GameTest: two AI players and a mock person; a wilderness PvP death costs the victim 2 and the faction capacity; a restart keeps progress to the second | G `NativeFactionPowerGameTests.aPvpDeathCostsTwoPowerAndARestartKeepsRegenerationToTheSecond` (Ada and Bram are AI; Cleo is a mock person; Red's capacity 20→18; `progressSeconds` equal after the restart) | Covered |

### M-06d (line 73)
| Item | Tests | Verdict |
|---|---|---|
| JUnit for adjacency | J `TerritoryTest.theFirstClaimIsAnywhereLegalAndTheRestGrowFromIt` | Covered |
| JUnit for separation | J `aChunkIsLeftBetweenUnrelatedFactionsButNotBetweenAllies` | Covered |
| JUnit for exclusion | J `spawnTheBorderTheNetherAndSafeZonesAreNeverClaimed` | Covered |
| JUnit for cap and fees | J `powerAndTheEightyChunkCapBoundTheClaimsAndCoinsPayForThem` | Covered |
| Piston cannot push into protected land | G `NativeTerritoryGameTests.aPistonCannotPushABlockIntoAnotherFactionsLand` | Covered |
| Water at the border does not flow in | G `waterPouredAtTheBorderDoesNotFlowIn` | Covered |
| Hopper does not pull from a protected chest | G `aHopperDoesNotPullFromTheProtectedHalfOfADoubleChest` | Covered |
| Creeper blast leaves protected blocks | G `aCreepersBlastLeavesProtectedBlocksStanding` | Covered |
| An AI player's own action refused in another claim, nothing moved | G `onlyTheLandsOwnFactionChangesItPeopleAndAiPlayersAlike` | Covered |
| (Extra, M-06d1) mob griefing | G `NativeMobGriefGameTests` (6: enderman take and place, ravager, silverfish, wither, zombie door) | Covered |

Outside the acceptance line: projectiles across the border have no test, and claim flags for doors and buttons are not built (M-06d.md: "outsiders use nothing").

### M-06e (line 89)
| Item | Tests | Verdict |
|---|---|---|
| JUnit scenario 2 (trigger) | J `CaptureTest.scenarioTwoTheOverclaimTriggerOpensCapture` | Covered |
| JUnit scenario 3 (boundary) | J `scenarioThreeBorderFirstThenInward` | Covered |
| JUnit scenario 4 (pre-capture) | J `scenarioFourAVulnerableChunkIsProtectedUntilCaptured` | Covered |
| JUnit scenario 5 (protected spaces) | J `scenarioFiveSafeZonesAreNeverCaptured` | Covered |
| G: attacker cannot open a chest in a vulnerable, uncaptured chunk | G `NativeCaptureGameTests.aBorderChunkIsCapturedByTheRuleAndItsChestChangesHandsUnmoved` | Covered |
| G: border chunk captured; the new owner opens its chest, contents unchanged | same method | Covered |
| G: interior chunk refused | same method | Covered |
| G: the 30-minute shield | same method ("Blue lost land lately") | Covered |
| G: recapture when the attacker's power falls | same method (plus J `theSameChunkOnceAnHourAndARecaptureWhenTheAttackersPowerFalls`) | Covered |

Note: one GameTest method carries all five native items. The 48-hour new-faction shield is JUnit only (`shieldsTheHourCapacityAndCoins`, `FactionPlayTest.aNewFactionsShieldIsRespected`).

### M-06f (line 101)
| Item | Tests | Verdict |
|---|---|---|
| A strike in the hub does nothing, and the striker is told | G `NativeSafeZoneGameTests.aStrikeInASafeZoneDoesNothingAndTheStrikerIsTold` | Covered (the zone is written over the test's own chunks, not a hub built at 0,0) |
| A claim at 400 refused, at 600 accepted | G `aClaimAt400BlocksIsRefusedAndAt600Accepted`; J `TerritoryTest.spawnTheBorder…` | Covered |
| A Nether portal exiting beyond the border refused | G `theBorderIsSetAndANetherPortalBeyondItIsRefused` | Covered |

### M-06g (line 123)
| Item | Tests | Verdict |
|---|---|---|
| JUnit: curve | J `MarketTest.theCurveFallsAsTheMarketFillsWithinItsFloorAndCeiling`, `aBulkSaleWalksTheCurveAndCostsTheNextSeller` | Covered |
| JUnit: classes | J `theCurveFalls…` (mass < ordinary < scarce sensitivity) | Covered |
| JUnit: floors and ceilings | J `theCurveFalls…` | Covered |
| JUnit: reversion | J `anIdleStockDriftsBackAndAnHourLimitsTheMove` | Covered |
| Canonicaliser: damaged tools | J `theCanonicaliserKeysOnlyWhatIsValued` | Covered |
| Canonicaliser: enchanted books | same | Covered |
| Canonicaliser: potions | same | Covered |
| Canonicaliser: renamed items | same (trivial in core); G `NativeMarketGameTests.anItemTheMarketCannotValueIsRefusedWithItsReason` | Covered |
| Canonicaliser: filled maps | same | Covered |
| Canonicaliser: suspicious stew | same | Covered |
| Arbitrage walk | J `MarketArbitrageTest.noRecipeMintsCoins` | Covered for the parsed recipe types (see scenario 9) |
| G: atomic `/sellall` moving the price | G `NativeMarketGameTests.aSellAllSettlesAtomicallyAndMovesThePrice` | Covered |
| G: disconnect mid-sale, both sides whole | G `aDisconnectMidSaleLeavesBothSidesWhole` | Covered |
| G: pending item refused with its reason | G `anItemTheMarketCannotValueIsRefusedWithItsReason` | Covered |

Outside the acceptance line: M-06g1 added screen GameTests (`NativeMarketScreenGameTests`, 5). M-06g.md says potion baselines are not set; M-06g1.md says the browser has no search.

### M-06h (line 138, for completeness)
| Item | Tests | Verdict |
|---|---|---|
| JUnit fees | J `AuctionsTest.aListingCostsTwentyFiveCoinsAndAWinningBidThreePercentBothDestroyed` | Covered |
| JUnit caps | J `capsBidsAndBuyNow` | Covered |
| JUnit escrow and closes | J `anUnsoldListingComesBackAndABuyOrderIsFilledByALaterListing`, `aDeliveryBegunIsOwedNoLongerSoARestartNeverHandsItOverAgain` | Covered |
| JUnit filter | J `theFilterIgnoresTheSameItemBackAndForthAndOneAccountWithItself` | Covered |
| G: listing, bid, close that pays and delivers | G `NativeAuctionGameTests.aBidWinsAtTheCloseAndTheItemIsDelivered` | Covered |
| G: restart mid-auction | G `aRestartMidAuctionLosesNothing` | Covered |
| G: buy order filled by a later listing | G `aBuyOrderIsFilledByALaterListing` | Covered |

## Gaps

1. Scenario 12 (load): not covered. Only eval runs exist, with 3 players and tick and heap figures. They had no PvP kills, no captures and no auctions. Nothing measures DB load or market latency. The M-06k month run is not done.
2. Scenario 10: the tests use an 8,000-wide border (±4,000), not ±12,000. Untested: players crossing the border (pearls, elytra, boats), End portal and gateway transitions, the overworld→Nether direction, and a capture beyond the border.
3. Scenario 6: not applicable, since there are no chest shops (owner's decision). No test covers auction escrow, or a player's market or auction state, across a capture.
4. Scenario 7: no sweep sells every item. Damaged tools, enchanted books and custom metadata are checked only in JUnit; no GameTest puts `MarketDesk.facts` through those components (`CUSTOM_DATA`, `STORED_ENCHANTMENTS`, damage). No potion has a baseline, so potions are only refused. No test sells an empty shulker.
5. Scenario 9: no explicit buy-then-sell-back test. The walk leaves out special recipes (repair_item and others), anvil and grindstone repair, and smithing_trim.
6. Scenario 11: no restart or crash during a market sale. The mid-sale case is tested only as a disconnect and as a hold within one runtime. Market persistence is shown only for a sale completed before the restart.
7. Scenario 5: JUnit only. No GameTest tries a capture inside a safe zone.
8. Scenarios 1 and 2: the hour's regeneration (8→9) and the 45/50/42 numbers are JUnit only. The GameTests check a 10→8 death, a few seconds of progress, and a 1-power, 5-chunk exposure.
9. M-06d, outside its acceptance line: projectiles across a claim border have no test, and claim flags for doors and buttons are not built.
10. Evidence docs: every claimed method exists.

## M-06d1 and M-06g1 checked against their rows (2026-11-12)

- **M-06g1: met; ticked.**
  - **Its row:** browse what the market sells and what your stacks fetch; buy or sell with a confirming click; the price quoted again then; every trade the desk's own.
  - **Fixtures:** each criterion has its own fixture in `NativeMarketScreenGameTests`, five methods, 3 of 3 each ([M-06g1](M-06g1.md)).
  - **CI:** the PR's final commit (`912ad8a`, #202) passed CI, run 43094949173 ("Build and test", success). It merged into main.
- **M-06d1: not met; left open.**
  - **Its row:** each of the six mobs refused "on claimed land and in safe zones".
  - **What passes:** on claimed land, each mob has a fixture with a wilderness control (`NativeMobGriefGameTests`, six methods, 3 of 3 each). The PR's final commit (`24cb70c`, #203) passed CI, run 43028109259 (success).
  - **What is missing:** no fixture puts a mob in a safe zone. The code refuses there through the same rule. `TerritoryGuard.mayMobChange` is `mayExplode`, which refuses wherever `Territory.access` is not `ANY`, so on claims and in safe zones alike. But no test shows a mob refused in a safe zone, and no explosion in a safe zone has a fixture either.
  - **To close it:** one fixture per mob (or one per mixin) with the six mobs in a safe zone, and its control just outside.

## What each parent row still waits on

| Row | Waits on |
| --- | --- |
| M-06c | The hour's regeneration (8→9) natively, rather than about 3 s of progress (JUnit has it); otherwise covered |
| M-06d | M-06d1 (above); projectiles across a claim's border, untested though `TerritoryGuard.mayHit` exists; and the residual mobs M-06d1 names out of scope |
| M-06e | Covered by its acceptance line. The rulebook's scenario 5 is natively untested: no GameTest tries a capture inside a safe zone |
| M-06f | Covered by its acceptance line. The rulebook's scenario 10 is partly covered: no border-crossing test (pearls, elytra, boats), no End portal or gateway, no Overworld→Nether exit beyond the border. The rulebook's ±12,000 is not the mode's, which is 8,000 wide by the owner's design |
| M-06g | Covered by its acceptance line. The rulebook's scenarios 7, 9 and 11 are partly covered, and potion baselines are unset; see Gaps 4 to 6. The market block at the hub is not built |
| M-06h | Its own row: a real client's pass over `/ah` |
| All | The rulebook's scenario 12 (load, with farms, auctions and PvP together) has eval runs only; and the M-06c..l row's "eight AI players in three factions for twelve game days with no rule broken" and "the month run recorded" (M-06k) |

No parent row is ticked here.
