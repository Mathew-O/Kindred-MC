# F-25c4ba — A spare stone pickaxe is not made from the sticks the iron pickaxe needs

Date: 2026-11-04
Agent / branch: Claude / claude/e40-ladder-1104 (E-40's ladder, rung a)
Build: on top of `78fdb82`; recorded in [validation](../validation.md)
Status: complete (pure and native suite classes). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: below ground with no tree near, a player packing spare stone pickaxes keeps back the sticks (or
the planks for them) its iron pickaxe and, on the mined-frame route, its diamond pickaxe still need. With iron for the
pickaxe it can make it at once.

Found: the one seed of E-40's starting build (`f25c4ax-84b331c`) with no iron pickaxe, the desert seed 31102026.
- Sol made a shield (six planks) and a chestplate from its first iron, then two spare stone pickaxes from its last
  sticks.
- With the iron for its pickaxe smelted, it had no sticks and no tree near. It went back to the wood stage, "Gather logs
  for sticks - Head back up toward home", for the rest of the run: 11 of the build's 144 stuck spells.
- The planner already kept those sticks from torches (`sticksKept`), but the spare pickaxe took them: two sticks in
  hand were enough for it.

Changed modules/files:

- `companion-core`: `PortalRunPlanner`: a spare stone pickaxe is made only from sticks over `sticksKept`, or wood over
  the planks those sticks would take, or with a tree near.
- JUnit: `PortalRunPlannerTest.aSparePickaxeIsNotMadeFromTheSticksTheIronPickaxeNeeds` (new).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| The iron pickaxe's sticks are kept from a spare | the JUnit: Sol's state after the shield and the chestplate, two sticks, no wood, no tree near, one stone pickaxe | On main's code: fails, "Craft a spare stone pickaxe for the work below". With the fix: passes; and with six sticks (two over the four kept on the mined route) the spare is made as before |
| The planner's other rules | `companion-core` `dev.kindred.core.survival.*` | 116 of 116 |
| Native | `NativePortalCampaignGameTests` and `NativeAtlasGameTests` together | 99 of 99 |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- Where wood is scarce, the shield's six planks still come before the iron pickaxe's; only the spare pickaxes now give
  way.
- A desert run still needs wood from somewhere for every tool: F-25c4ap's skyline trees remain its way to it.
