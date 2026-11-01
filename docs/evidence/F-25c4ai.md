# F-25c4ai — A frame's top block is cast with the cell in front of its water opened first

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `f59492d`; recorded in [validation](../validation.md)
Status: complete 2026-10-31 (native and live)

## Scope and implementation

User-visible behavior: a player casting its portal frame in rock opens the cell in front of the water cell above each top block before casting that block. The water for a cast goes in the cell above the block and is taken back once the block hardens. Under rock, every line from below to that water otherwise runs through the new obsidian, so the cast was refused from every stand and the frame stood unfinished. A frame cast in the open was never affected: the cell in front of the water is open air there.

Found in the live pair on `64c298d` (seed 20261030, strategist off): Sol cast eight of its ten frame blocks and both top corners at Y=-54. Then "Cast obsidian at -85, -50, -43" was refused, "The water could not be taken back from here once the obsidian hardens", from every stand in front of the frame. Its watchdog's climb and the cast took turns for its last seven minutes. In the world save the frame stood as left, and the row in front of it was open up to the top row with deepslate over it. A model of the cast's check, run on the save's blocks, found no stand in front of the frame or behind it that passed. With that one cell open, the front row's middle passed from its centre and anywhere back from it, as did the row behind from its centre forward.

Changed modules/files:
- `PortalRunCampaign` (the cast): for a top block, the cell in front of its water is among the cells opened before the cast.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| The roofed cast fixture's room under rock five blocks thick, the kit and its two buckets: a frame stands lit there, the water bucket taken back each time, and it is unhurt | `underThickRockTheFramesTopIsCastWithItsWaterTakenBack` | Before, the change set aside, it fails 3 of 3: "0 portal blocks, 8 obsidian", the top two refused from every stand as Sol's were. After, 3 of 3 alone. Run after the under-lake fixture had failed in the next plot, one run met that fixture's water and failed; that run is not counted | native |
| The other casts unchanged | the roofed cast, the whole cast, the cast refused for its water and the one-block cast, alone on this code | 4 of 4 | native |
| In play | the live pair on `c432fba`, both seeds, strategist off | Met: Kindred's frame at Y=-54 on the first seed and Sol's at Y=-53 on the second were cast in rock, the cell in front of each top block's water dug before its cast, and both were lit | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the room, the rock over it, the pool and the glowstone are staged; the kit is given as though made.

## Remaining limitations and handoff

- The cell is opened like any other in the cast: one it may not break (lava beside it, say) gives the site up, as a cell over a top block always has.
- Gravel or sand over that cell falls into the row in front when it is opened; the player digs itself out as from any fall of gravel.
