# F-25c4aj — A frame site's cells are dug in the order it can reach them, not given up for one walled in

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `238ff1e`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player opening the room for its frame digs, in each pass (the row in front, then the frame and its inside), the first cell it can break from where it stands. A cell walled in on every side it faces waits until the cell beside it is dug and shows it. With nothing it can break from where it stands it walks to the site's stand, then to the open cell of the row in front nearest the cell, and only a pass with nothing it can break from either gives the site up. Before, the first cell of the pass in its order decided: out of reach meant a walk to the stand, but in reach and out of sight gave the site up at once.

Found in the live pair on `64c298d` (seed 20261030, strategist off): Kindred filled its bucket at Y=-54 and climbed two steps out of the lava's reach. It then gave up 17 frame sites in two minutes, each "cannot clear" a block of deepslate beside it, and its watchdog moved it away with its lava unused. The site was rebuilt as it stood then: the world save, with what the log shows Kindred dug and placed afterwards undone. On the stand of the first site it chose, the row in front's first cell, diagonal to its feet, was walled in on all three sides it faced; the cell straight ahead, which would have shown it, came second in the order. Sol gave up 32 sites on the same seed, two of them for its own obsidian.

Changed modules/files:
- `PortalRunCampaign.clearPortalSite`: in each pass, the first cell breakable from where it stands is dug; the first that is not is kept, and given to `clearOrApproach` only if none is breakable.
- `PortalRunCampaign.clearOrApproach`: with nothing it can break from here, it goes to the stand, then to the open cell of the row in front nearest the cell, each once since it last broke one (`clearTriedFrom`), and only then gives the site up.
- `PortalRunCampaign` (the cast route): no walk back to the stand before clearing. Asked for again on every step of a walk to the row in front, it undid that walk; the clearing walks to the stand itself when it needs to. `buildPortal` (owned obsidian) keeps its walk to the stand, except from a spot its clearing sent it to.
- `PortalRunCampaign` (the cast): a cell to open before a cast that it cannot break from here sends it to the stand before the site is given up, and that give-up is logged (`<name> gives up the frame site at … : cannot open …`).
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| On the stand of the first site it chooses, in Kindred's rock over the lava lake rebuilt from the save, its lava in hand: its first frame block is cast in that site, and it is unhurt | `onItsStandItDigsTheCellBesideAWalledInOneFirstAndCastsInThatSite` | Before, the change set aside, it fails 3 of 3: "0 there", 111 sites given up across the three runs, the first each time for the walled-in cell. Two of the runs later cast a block or more elsewhere; one cast none. With the cells taken in reach order alone it still failed 3 of 3: the row's last column was walled in behind the stand's own staircase. With the walk to the row in front added it failed 2 of 3: the cast route's walk back to the stand, asked for on each step of that walk, undid it. After, 3 of 3 alone, no site given up | native |
| The other casts unchanged | the frame under thick rock, the roofed cast, the whole cast, the climb over the lava lakes, the cast refused for its water, the one-block cast and the owned-block frame, alone on this code | 7 of 7 | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the region round the stand is staged from the live pair's world save as it stood when Kindred chose the site: the save's blocks, with what the log shows it dug and placed afterwards undone, deepslate where a block was dug, ores as deepslate, inside a shell of deepslate. Two blocks of glowstone are staged for light; the kit, its lava bucket filled, is given as though made.
Analysis: `rebuild.py` in the session's scratch space (not committed) read the save's blocks and undid the logged edits.

## Remaining limitations and handoff

- A cell no stand of the site can see or reach still gives the site up, as before.
- Sol's first frame on that seed was dropped with seven cast, the cause not found in its log; the cast's own give-up is now logged to find it.
