# F-25c4bh — Its flint found away from its frame: it comes back to the frame to light the portal

Date: 2026-11-05
Agent / branch: Claude / claude/e40-ladder-8-1105 (E-40's ladder, rung a)
Build: on top of `92054e7` (batch 7 with iteration 5); recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is in E-40's next evaluation.

## Scope and implementation

User-visible behavior: a player whose frame stands, who went off for flint and made its flint and steel there, walks
back to the frame's stand before lighting it. Before, it tried to light the portal from where it stood. The skill
refused it as out of reach, and the plan asked for the same thing again.

Only the cast route stands its frame before its flint. The mined route makes its flint and steel first, while the
obsidian is still in hand.

Found: E-40's iteration 4 (`e40a4-c237a6e`), on 505. Sol cast its frame at Y=-47, walked to gravel at 39, -54, 57 and
dug it until it dropped flint. It made its flint and steel there and tried to light the portal from there, 192 times
refused as out of reach. Then it climbed away and never lit it.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.ignite` measures from the player's eyes to the frame's opening (the
  cell over the frame's bottom row). Beyond 4.5 blocks, it walks back to the frame's stand first. The walk is not
  labelled as a "Portal" walk: a "Portal" walk that fails gives up the site, and this frame already stands.
- gametest:
  - `NativePortalLightGameTests.itsFlintFoundAwayFromItsFrameItComesBackToLightThePortal` (new class, registered in
    the gametest `fabric.mod.json`).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Its frame cast, its flint dug from gravel twelve or more blocks off: the portal lit, the player alive | the fixture. Staged: a field of stone 21 square, walled, open to the sky, daylight held; a lava pool in its floor at the north end; three gravel in the south-east corner; the player by the pool with the cast route's kit (an iron pickaxe, cobblestone, a bucket and a water bucket) and an iron ingot, but no flint. Passes once a lit portal stands. | With `ignite` as on `5a48c43`: fails 3 of 3. Each time it was refused as out of reach 19 to 21 times, then the watchdog took it away ("dig toward elsewhere", "climb away", "jump down from the ledge"), off the field, and it died. With the fix: passes 3 of 3, never refused, the portal lit from its stand. |
| The rest of the suite | the full native suite and CI, see [validation](../validation.md) | see there |

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions. Staged, as the fixture says.

## Remaining limitations and handoff

- The walk back is to the stand chosen for building the frame. If that stand cannot be reached any more, the walk
  fails and the watchdog takes over, as for any failed walk.
- The fixture's first form gave the player the mined route's kit (obsidian and a diamond pickaxe). It made its flint
  and steel at the gravel before it built the frame, so it never stood out of reach. Only the cast route shows it.
