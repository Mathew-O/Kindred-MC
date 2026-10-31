# F-25c4ac — Looking for lava, a block above the deep lava level, its tunnel opens a cell over a lake to see it

Date: 2026-10-31
Agent / branch: Claude / claude/loose-ends-1029
Build: on top of `41f9de9`; recorded in [validation](../validation.md)
Status: in progress (native passed; live runs pending); F-25c4 stays open

## Scope and implementation

User-visible behavior: a player looking for lava for its cast (or to make obsidian) digs down to Y=-54, a block above the deep caves' lava level, and branch-mines there in long legs far apart: 48 blocks, then 16 to the side. When the next cell at its feet has lava directly under it and no other liquid round it, it opens that cell from where it stands, sees the lava and fills its bucket there; it never steps into the cell. Lava never rises, so none comes into the gap. Before, it dug to Y=-55, level with the lakes, and branch-mined in the dense legs used for diamonds (20 blocks, 3 apart). Every cell with lava on any side, under it included, was refused, and the tunnel turned away from a lake without ever seeing it: the lava was known only once a tunnel broke into an open cave.

Found in the live pair on `a4df9b4` (strategist off):
- No player filled a lava bucket on either seed. On the second seed Kindred dug down to Y=-55 and branch-mined there for thirteen minutes, through deepslate with no cave in it, until a skeleton killed it.
- The world saves, ray-cast from every place the players stood every ten seconds, show no exposed lava in their sight within 32, 64 or even 96 blocks: a longer sight would not have found any.
- Simulated on those saves from 200 random starts at the lava level on each seed, the search as it was had lava within 800 blocks mined from 12.5% of starts on the first seed and 9% on the second. Most of the lava the tunnels came on lay beside or under a cell they would dig, where they turned away. A block higher, cells with lava only under them opened, the legs as above: 64% and 56%.

Changed modules/files:
- `RoamingEdits.breakDenial`: with `overLava`, lava directly under a block does not refuse its break; lava on any other side still does.
- `NativeBreakAction`, `NativePlayerSkills` (`native_break`'s optional `over_lava`), `PlayerRuntime`: the argument carried through.
- `PortalRunCampaign`: the lava search's depth (`LAVA_TOP`, `LAVA_TARGET`) and legs (`LAVA_BRANCH_LEG`, `LAVA_BRANCH_STEP`); `tunnel` with `revealLava` opens such a cell (`onlyLavaBelow`); the lava under an opened cell is remembered as seen.
- `NativePortalCampaignGameTests`: one fixture.

## Acceptance evidence

| Criterion | Check or scenario | Result | Evidence |
| --- | --- | --- | --- |
| In a closed obsidian chamber, a corridor of stone the only way on, a lava pool sealed under the corridor's far half, a water bucket and an empty one: its bucket is filled with the pool's lava, and it is unhurt | `lookingForItsLavaItsTunnelOpensTheCellOverAPoolAndFillsItsBucketThere` | Fails on the old campaign, 3 of 3: "Its bucket filled with the pool's lava: now 3, 0, 0, Obsidian: waiting for a feasible step", the cell over the pool refused. Passes after, 3 of 3 alone | native |
| The other lava searches and casts unchanged | alone on this code: obsidian from poured water, the lava legs, F-25c4n's night lava, F-25c4z's climb over the lava lakes; the full native suite | 4 of 4 alone. The full native suite on `77ddbb4`, 419 of 425. The six failures ran while log and world-save analyses ran on the same cores, three back to back as a log report ran; alone on the same code the follow-up without a shield queued, the ruined portal's chest, the long fall broken with water, the route that bridges a gap and digs through a wall, and the librarian off the line pass, 5 of 5. The second-night fixture, 42,000 ticks long, was not run alone: its cabin's repair ended short, its roof stairs out of reach, as the life-first fixture's has before | native |
| In play | live runs on this code | Pending | live |

Environment: Linux x64 cloud container (4 cores), OpenJDK 21, Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API 0.116.17.
Fixtures: the chamber, its corridor, the pool and the glowstone are staged; the kit is given as though made.
Analysis: `branch_sim2.py` in the session's scratch space (not committed) read the saves' blocks; it models the tunnel's cells, its turns and the two ways lava is found, not the player's fights, food or night.

## Remaining limitations and handoff

- One cell opened shows one source; each bucket for the cast takes a cell of its own, and the frame is cast a few blocks up, out of the lava's reach (F-25c4z).
- Lava level with the tunnel, or above it, is still refused unseen.
- A staircase down whose next step has lava under it turns, as before; the tunnel at that level then opens a cell only where the lava lies directly under it.
