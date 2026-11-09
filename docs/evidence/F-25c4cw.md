# F-25c4cw — On the cast route, its crafting table is taken along

Date: 2026-11-09
Agent / branch: Claude / claude/cast-table-1109 (E-40 ladder, batch 25), on main `e7e0b57`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is the next evaluation's to show.

## Scope and implementation

User-visible behaviour: a player on its way to cast a portal frame that puts its crafting table down to make a pickaxe picks
the table up again when it goes on. Before, it left the table where it stood. When the next pickaxe wore out far from it, it
had no table and no wood to make one, and climbed to the surface for logs.

Found: E-40's iteration 16 (`e40a16-922f4fd`). After iron, about a fifth of the players' time went to wood: "Gather logs for
a crafting table" 5.9% and "Climb toward the surface for logs" 7.3%. On 303 Kindred:
- On the cast route at Y=-54, its pickaxe worn out, it put a table down at (-57, -54, 141) and made a stone pickaxe.
- It branch-mined fifty blocks on for lava and wore that pickaxe out too. At (-105, -54, 131) it had no table and no planks
  to make one, and began "Climb toward the surface for logs" from Y=-54.
- The goal takes its own table and an emptied furnace along when it leaves to gather, its plan's step one of `TRAVEL`.
  The cast route's steps, `CAST_PORTAL` (the lava looked for and the frame cast) and `FILL_LAVA`, were not in it. At the
  iron stage the same table was put down and picked up again a dozen times as it went.

Changed file: `companion-server-adapter` `PortalRunCampaign`: `TRAVEL` also holds `CAST_PORTAL` and `FILL_LAVA`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| On the cast route, the table it put down to make a pickaxe is taken along before it goes on for lava | `NativeCastTableGameTests.onTheCastRouteItsTableIsTakenAlongWhenItGoesOnForLava`, staged as below | On `e7e0b57`, without the fix: fails 3 of 3, "Gone on for lava with its table left behind", each time branch-mining for lava a dozen blocks from it. With the fix: passes 3 of 3 |
| The cast route's classes, and the trip's wood | Nine classes, 106 fixtures, three runs | See [validation](../validation.md) |

The fixture's staging: a chamber deep in a block of stone with no lava in it, on the flat test plot, and the cast route's
kit as though made (an iron sword, a water bucket and an empty one, flint and steel, cobblestone, bread, an iron chestplate)
with its pickaxe gone and a crafting table, cobblestone and sticks to make another, as 303's Kindred had. The player puts
the table down and makes the pickaxe itself.

The benches ran with the batch's other benches on the cores, three at a time.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- A table left behind before the cast route began, at a step outside `TRAVEL`, is still left.
- With no sticks left, a new pickaxe still needs wood; this keeps only the table.
