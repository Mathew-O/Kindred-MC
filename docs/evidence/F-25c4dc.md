# F-25c4dc — Two or more mobs after it down a dark passage are sealed out, not met one at a time

Date: 2026-11-10
Agent / branch: Claude / claude/survival-1110-seal (SURVIVAL track), stacked on `claude/cover-climb-1109` (#107, F-25c4cy's
`holdCover`) with main `b95911c` merged in
Build: recorded in [validation](../validation.md)
Status: in progress. The fixtures pass; the ten-seed measure (RUNNER 2's survival-1) is to come.

## Scope and implementation

User-visible behaviour: underground in the dark, with two or more mobs coming for it down a tunnel one wide and none yet in
reach, the player blocks the tunnel on their side, the cell at its feet first and the one at its head next, and waits behind
the blocks while they are about. A short while after the last one has gone from near the blocks, it carries on with what it
was doing, and the blocks are no longer kept from its digs. With one mob after it, or in the open, it fights as before.

Found: the kept replay `kindred-43985-707` (restored from #96's history at `07ce2b4`; not in the suite). Kindred, at 17.5
health underground with three zombies after it in a dead end, met them one at a time and was killed; it had a shield in its
off hand.
- `holeUp` already walls a passage off when `outmatchedBy` names a mob: two hunters count only without a shield, and with one
  only below 8 health. With a shield and full health, two or three zombies were fought as they came.
- `defend` took cover from hunters only below 6 health, or below 14 with three or more.

Changed file: `companion-server-adapter` `PortalRunCampaign`.
- `sealPassage`, called in `defend` before the badly hurt back-off. It applies when all of these hold:
  - none in reach (`onFootInReach`);
  - underground (`underground`);
  - the light at its head under 8;
  - two or more non-creeper hostiles targeting it within 12 across and 6 up or down;
  - every one of them along the nearest's side;
  - its own two cells and the two cells on that side solid on both flanks.
- It places the feet cell, then the head cell when it holds two or more sealing blocks; one block goes at the feet only, since
  a zombie cannot walk a gap one high. Its label is "Take cover from <mob>: seal the passage", so F-25c4cy's `ownCover`
  records it.
- `holdCover`: a seal cell (`sealNear`) is held from every dig while a hostile is within six of it, and for `SEAL_RELEASE`,
  200 ticks, after one last was. `COVER_KEPT` still lets it go after two minutes, and so does the cell being air. Other cover
  is held as before.
- Behind a seal still held, `sealPassage` fills the head if a block is left for it, and otherwise waits a second at a time
  ("…: wait behind the seal"). A mob in reach is fought, as before.

Nothing is granted: the blocks are the player's own, placed through `placeBlock`.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Two zombies down a dark tunnel, with a shield, are sealed out, with no hit taken, the seal kept while one is within six, and let go after | `NativePassageSealGameTests.twoZombiesDownADarkTunnelAreSealedOutAndWaitedOut`, staged as below | On this branch's base without the change (a worktree of `HEAD~1` with the fixture added): fails 3 of 3, "Sealed false, the seal seen false"; it fought them and went on branch-mining. With the change: passes 3 of 3 |
| One zombie is fought, not sealed out | `NativePassageSealGameTests.oneZombieDownADarkTunnelIsFoughtNotSealedOut` | 3 of 3 without the change and 3 of 3 with it |
| The classes that fight, take cover and climb | `NativeCombatGameTests`, `NativeCoverClimbGameTests`, `NativeEncounterGameTests`, `NativeFallGameTests`, `NativeInitiativeGameTests`, `NativePassageSealGameTests`, `NativePortalCampaignGameTests`, `NativeTacticalGameTests`, `NativeWeaponToHandGameTests`, `NativeEdgeFightGameTests`, `ReplayGameTests`, 3 runs | Running; the result is to follow |

The fixture's staging: a stone tunnel one wide and two high along x (x 1 to 11, y 2 and 3, z 1), closed at both ends with
stone all round and over it, so it is dark at any hour; daytime is set on purpose. The player at its west end, at full health
and fed, holds a stone sword in hand, a shield in its off hand, 16 cobblestone and a stone pickaxe, with the goal begun.
Zombies (adults) are set on it from five and six blocks off, within six of where its seal goes. In the two-zombie case:
- it fails on any hit, or on the seal opening while a zombie is within six of it;
- the zombies are removed half a minute after it sealed, as though they gave up;
- it must then not be waiting behind the seal.

Without a shield, the existing `holeUp` seals the same tunnel. A first form of the fixture without a shield passed without
the change, so the shield is what makes it this change's.

A first form set the zombies at eight and nine blocks. With the change it passed 1 of 3: twice, sealed at once, the zombies
lost sight of it and stood about eight off, beyond the six the hold counts. So the hold let the seal go, as specified, and the
goal mined that cell. The zombies now start within six.

## Remaining

- The ten seeds against e40a17: deaths an hour, back-off deaths by kind, stuck share, and the count of seal and wait lines
  (RUNNER 2's survival-1 on `784691d`).
- The seal answers only a passage one wide with every mob on one side; a cave, or mobs from both ways, is fought or backed off
  from as before.
- It merges after #107.
