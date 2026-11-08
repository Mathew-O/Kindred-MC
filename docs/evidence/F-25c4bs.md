# F-25c4bs — Rotten flesh and raw chicken eaten when there is nothing better and the player cannot heal

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-12-1105 (E-40's ladder)
Build: on top of `ec27caa` (batch 11, merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a hurt player whose hunger bar is under 18, which in vanilla means it does not heal, and which
holds no other food, eats rotten flesh or raw chicken; so does one too hungry to sprint (6 or less). Their chance of
hunger costs less than going on unhealed. With any other food held, that food is eaten as before and the rotten flesh and
raw chicken are kept.

Before, rotten flesh and raw chicken were never eaten, whatever the player's state.

Found: E-40's iteration 8 (`e40a8-aed3e82`), its 23 deaths, from each death replay's state ten seconds before (F-27):
20 came with the hunger bar under 18, 13 with no food held at all. Echo on 101 (tick 128170) died at two health and two
hunger holding four rotten flesh; Echo on 404 (tick 116232) at four health and six hunger holding a raw chicken.

Changed modules/files:

- `companion-server-adapter`: `PlayerRuntime`'s eating candidate: `lastResort` (hurt with the bar under 18, or the bar
  at 6 or less, and no food it would eat in the ordinary way held, by `holdsEligibleFood`) lets rotten flesh and raw
  chicken be chosen, from the hotbar or swapped up from the pack as other food is.
- gametest: `NativeLastResortFoodGameTests` (new class, two fixtures).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Hurt (8 of 20), hunger 14, nothing to eat but four rotten flesh in its pack: one is eaten and the bar rises | `NativeLastResortFoodGameTests.hurtWithNothingElseToEatItEatsRottenFlesh`, staged as it says | On `ec27caa`: fails 3 of 3, the four rotten flesh still held and hunger still 14. With the fix: passes 3 of 3. |
| The same with two bread beside the flesh: a bread is eaten, the four rotten flesh kept | `hurtWithBreadAndRottenFleshItEatsTheBread` | Passes in all six runs, before and after. |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixtures say.

## Remaining limitations and handoff

- Spider eyes, poisonous potatoes and pufferfish are still never eaten: their poison costs health outright.
- Food is not sought sooner: 13 of iteration 8's 23 deaths came with no food held at all.
