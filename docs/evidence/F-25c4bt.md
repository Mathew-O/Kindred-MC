# F-25c4bt — The night's shelter is the cells its body stands in

Date: 2026-11-06
Agent / branch: Claude / claude/e40-ladder-12-1105 (E-40's ladder)
Build: on top of `ec27caa` (batch 11, merged); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player sealing itself in for the night (below ground waiting for daylight, or on a sky
island) while standing across the line between two cells seals the openings around both cells. Before, it tried to put a
block into the cell its own body was half in.

The seal filled the open cells beside the cell its feet were counted in. Standing across the line between two cells, the
other cell was one of those, and every block put there was refused as obstructed by its own body. As it moved, the cell
its feet were counted in changed, and it tried the other one. Once both were set aside it waited with the shelter open.

Now the shelter is every cell the body stands in at its feet: one cell, or two or four across a line. The openings round
them, not the cells themselves, are sealed, and a shelter with more openings than two for each of its cells and two more
is a cave and is left, as before for one cell (four).

Found: E-40's iteration 9 (`e40a9-dd9b037`), seed 404, Sol, below ground under a lake wanting water at night. In a
pocket two cells long it tried to seal each cell from the other, every block refused as obstructed, for 14 stuck spells.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.seal`.
- gametest: `NativeShelterSealGameTests` (new class, one fixture).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Night, below ground, no wood, standing across the line between the two cells of a pocket whose first cell opens into a corridor: the corridor's mouth sealed at feet and head and it waits for daylight | `NativeShelterSealGameTests.standingAcrossTheLineItSealsTheWayIntoItsPocketAndWaits`. Staged as it says, in a block of stone with three blocks of it overhead; dirt and bread | On `ec27caa`: fails 3 of 3, waiting for daylight with the corridor's mouth open at feet and head. With the fix: passes 3 of 3. |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- Only the cells at its feet count: one it leans into at head height alone does not.
