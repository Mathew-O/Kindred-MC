# F-25c4bm — Where the frame is cast, the first bucket from the iron after the chestplate's, before the rest

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-10-1105 (E-40's ladder, rungs a and b)
Build: on top of `88ccf2d` (batch 9, merged as `1c5ac61`); recorded in [validation](../validation.md)
Status: complete (planner and native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: on the cast route (the portal frame cast from lava and water, no diamonds), a player makes its first
bucket from the three iron it finds after the chestplate's eight, before it looks for the rest of the route's iron. With a
bucket in hand, water passed close by on the way down is taken while it is close: the upkeep that fills a bucket at water
within 24 blocks and six levels was there before, but had no bucket to fill until all the iron was in.

Before, both buckets were made once all the route's iron was in hand (19 for a player with no chestplate and no shield),
deep below by then. With no water in reach of the lava, the player dug a staircase to the surface for it and back down.

Found: E-40's iteration 6 (`e40a6-cb4a545`). Filling that bucket took 9.4% of the players' time across the ten seeds
(goal steps by wall-clock time between changes of step), up to a fifth on 20261030 (20.2%), 303 (18%) and 505 (17%).
On 303 Echo and Sol each climbed from Y=18 to the surface at Y=63 to Y=110 for it.

Changed modules/files:

- `companion-core`: `PortalRunPlanner.primary`. A new `bucketFirst`, after `shieldFirst`, `pickaxeFirst` and `chestFirst`:
  on the cast route, no bucket of any kind held, three iron in hand, and no chestplate still wanted where there are
  caves. Its iron is smelted and the bucket made before more iron is mined. The route's total iron is unchanged.
- tests: `PortalRunPlannerTest.whereTheFrameIsCastTheFirstBucketIsMadeFromTheIronAfterTheChestplatesBeforeTheRest`;
  gametest `NativeBucketGameTests.whereTheFrameIsCastItMakesItsFirstBucketAndTakesTheWaterCloseByBeforeTheRestOfItsIron`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| The chestplate still from the first eight iron; the first bucket from the next three; no more iron mined in all | JUnit, the planner simulated from no iron on the cast route with caves | The planner as on `88ccf2d`: fails, its first bucket made only once all the route's iron was in (41 of 42 of the class pass, this one failing). With the fix: passes; the class 42 of 42. |
| A bucket of water held before the rest of the iron, with water three blocks off | The fixture, staged: daylight, a pool two sources across set into the ground three blocks off; stone pickaxes, sword, shield, chestplate worn, three iron ingots, table, furnace, coal, food; no bucket | The planner as on `88ccf2d`: fails 3 of 3, each time branch mining for the rest of its iron its three iron ingots kept and no bucket made ("Mine iron ore (3/10) - Branch mine for iron: advance"). With the fix: passes 3 of 3. The class's other fixture (F-25c4bl) passes in all six runs. |
| The planner's other tests, among them F-25c4q's iron pickaxe made with the rest and F-25c4x's chestplate from the first eight iron | `PortalRunPlannerTest` | 42 of 42 with the fix |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Only water within 24 blocks and six levels is taken on the way. Deep below with none in reach, the climb is as before.
- Smelting three iron alone costs a furnace stop of its own (placed, fed, waited on, taken up).
- The mined route (diamonds) is unchanged: there the buckets come with the rest.
