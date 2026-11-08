# F-25c4cm — A cobweb in the climb's way is cleared, not taken for room

Date: 2026-11-08
Agent / branch: Claude / claude/e40-ladder-19-1108 (E-40's ladder, batch 19)
Build: on top of `433589d` (batch 18 and iteration 14's filing); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behaviour: a player climbing toward the surface with a cobweb over its head now clears the cobweb first, then
steps up. It uses a sword if it carries one, and its hand if not, which takes about twenty seconds; a pickaxe is no faster
at it than a hand. The same goes for a cobweb, a sweet berry bush or powder snow where it would step up to. Before, it
counted such a cell as room to climb through, because it has nothing in it to collide with. But a walk will not go through
one, so each step up was refused for "no walkable route".

Found: E-40's iteration 14 (`e40a14-80b7150`), Sol on 8675309, at -4, -48, 52 in an old mineshaft's corridor. It was
climbing for logs for a crafting table, with a cobweb at -4, -46, 52 over its head and an oak fence beside it:
- The step up north was refused because the jump went through the cobweb.
- The step up east, onto the fence, was refused because a fence is not a floor.
- With no way up, the climb walked "to the wall", to -4, -48, 54. That stand is beside the mineshaft's flowing lava, and a
  walk will not end beside lava.

"No walkable route" came back 1,141 times in a minute and a half, until the watchdog moved it on.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.climb`. The cells over its head and at the landing count as room only if
  the navigator would also walk through them. `snareClearable` covers a cell with no collision that a walk refuses and
  nothing it carries cuts faster: it is cleared by hand.
- gametest: `NativeCobwebHeadRoomGameTests` (new class, two fixtures); its ground, `cobweb-8675309.snbt`, is cut from the
  run's kept world.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Climbing from the mineshaft's corridor with a cobweb over its head, it clears it and is two above where it stood within 1,200 ticks | `NativeCobwebHeadRoomGameTests.aCobwebOverItsHeadIsClearedAndItClimbs`. Staged as described below | On `433589d`: fails 3 of 3, "Not up out of the corridor after 1,200 ticks". In each run, both steps up are refused as in the run, then the walk "to the wall" is refused 530 to 532 times. With the fix: passes 3 of 3. It cuts the cobweb with its sword and is two up after 123 to 125 ticks, with no refused walks. |
| The same with no sword: by hand | `NativeCobwebHeadRoomGameTests.withNoSwordItClearsTheCobwebByHandAndClimbs`. Staged as above, the sword left out | On `433589d`: fails 3 of 3, the same way (531 to 532 refused walks a run). With the fix: passes 3 of 3, two up after 517 ticks each, with no refused walks. |
| The rest | the classes that climb, tunnel and dig out; `bash ./gradlew build` | See [validation](../validation.md) |

The fixtures' staging:
- The block of the run's kept world round the spot, 21 by 13 by 19, read offline for diagnosis only; the liquid on the
  block's faces is made stone.
- Daylight is held.
- The player stands where Sol stood, with the iron stage's kit: two stone pickaxes, the iron sword Sol carried, food,
  cobblestone, torches and coal; no wood and no crafting table.
- The eighteen raw iron the goal wants are already in hand, so that its first step there is the climb for logs, as Sol's
  was.

A first form of the fixture staged three raw iron. It did not reproduce: 3 of 3 passed without the fix. The player mined
the iron in sight first, and then climbed from another cell.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- The walk "to the wall" still takes a stand by the climb's own reckoning, so a stand beside lava is chosen and refused
  again and again. Here the cobweb cleared means it is never reached. Skipping such a stand would only turn the refused
  walks into "waiting for a feasible step" until the watchdog, so it is not done here. 606's Echo had the same 1,127
  refused walks "to the wall" in iteration 14, in a gravel deposit, from a cause not yet known.
- A fence or wall beside it is still taken as a step to climb onto. Its landing is set aside after the first refused walk.
