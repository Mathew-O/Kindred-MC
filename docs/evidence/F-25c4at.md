# F-25c4at — Sealed into its night shelter, out by its own seal in the morning

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `3067b0f`; recorded in [validation](../validation.md)
Status: in progress (written; the fixture's runs before and after, and its neighbours', are queued; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player that sealed itself into its shelter for the night, and in the morning finds no other way up, opens its own seal again: it digs out the block it placed, or punches it out by hand with no pickaxe, and goes on from there. A walk back along the way it came that is refused ("no walkable route") is not asked for again from the same spot for half a minute, so the climb gets its turn.

Before, its climb in the morning tried one cell of its way back after another, each walk refused by its own seal. Its steps up were refused while water lay beside the block over its head, and nothing opened the seal.

Found in the live pair on `00e98f5` (seed 31102026, a desert, the End goal, strategist off): Kindred, wanting logs for sticks, sealed itself in for the night in a pocket two high at -2, 45, 156, at the bottom of a lake's basin. From 11:21 to the end of the seed, fifty minutes, its walks out were refused ("No walkable route" toward home and along its trail). Every step up was refused for the lake's water beside the cell over its head, and its stall line came 65 times. The save, read as it stood, showed its cobblestone seal the only way out of the pocket.

Changed modules/files:
- `PortalRunCampaign`: the cells of its night seal are remembered (`ownSeals`); `wayUp`'s last resort, `openShelter`, opens the nearest still in reach, never into water or lava nor a block set aside; a walk back the way it came that fails stalls the walk back from there for 600 ticks.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| Sealed in for the night in a pocket under a pool, no pickaxe: in the morning it punches its seal out by hand, unhurt | `sealedInForTheNightUnderAPoolItOpensItsOwnSealInTheMorning` | Pending: queued after the live pair on `00e98f5` | native |
| The climbs and the night shelter beside it unchanged | The night pit on an island, the pit below its way back with no pickaxe, the gravel over its head, under the desert's sand, the advised climb out, dead again on its way back, the night shelter and the pocket's move elsewhere, each alone | Pending: queued after the live pair on `00e98f5` | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the stone, the pocket, the corridor, the torch and the pool are staged; the cobblestone and bread are given; night is set, then daylight once both cells of the way in are sealed.
Analysis: the save round Kindred's pocket read with `rebuild.py` in the session's scratch space (not committed).

## Remaining limitations and handoff

- A seal with water beside it is left: opened, the water would pour in.
- The seal is opened only when every other way up fails; one with another way up leaves it standing, as before.
