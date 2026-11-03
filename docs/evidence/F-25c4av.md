# F-25c4av — Nothing to do at a crafting table without the makings, not a walk to it

Date: 2026-11-02
Agent / branch: Claude / claude/next-deps-1102
Build: `e732618`; recorded in [validation](../validation.md)
Status: native fix complete (fails before, passes after); on seed 20261030 in the evaluation of 3 November, 2 walks to the table against 928. Open: why the plan asked for a stone pickaxe with no stone.

## Scope and implementation

User-visible behavior: a player at a crafting table in reach, asked by its goal or a hand-over for something it has not
the makings of (sticks with no planks, a stone pickaxe with no stone), no longer walks to the table it is standing at,
over and over. The craft helper gives it nothing to do for that step, and the caller goes on as it does with any step
that cannot be made. The goal tries the next step of its plan: `PortalRunCampaign` realizes each step in turn and takes
the first that gives a task, so a stone pickaxe it cannot make is passed over for the stone. The hand-over waits its
minute and lets go after six. The log says `<name> cannot make <recipe> at the table in reach: not the makings`, once a
minute for each recipe.

Found: in the social track's evaluation of 2 November ([validation](../validation.md), the living-together section), on
seed 20261030 Kindred stood at its own table and walked to it 927 times in the run's last 19 game minutes. The goal's step
was "Craft stone pickaxe" without the stone, and the craft helper gave it out as "Walk to the crafting table", which ends
at once. The same cause made the hand-over loop (E-40a) seen in most arms before.

Changed modules/files:

- `companion-server-adapter`: `PortalRunCampaign.craft` (a table within reach and a recipe it cannot plan: empty, and
  the log line).
- gametest: `NativeCraftHelperGameTests` (new).

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| At a table in reach without the makings: nothing, not a walk | `atTheTableWithoutTheMakingsThereIsNothingToDoNotAWalkToIt`: a bare native body beside its own table, 2 iron ingots; `craft(body, "stick")` and `craft(body, "stone_pickaxe")` | Before the fix (`PortalRunCampaign` at `a875512`): fails, "No planks: nothing to do for sticks, not Optional[Walk to the crafting table]". After: passes, 3 of 3 runs alone |
| With the makings, the same call crafts (control) | the same fixture, with 3 cobblestone and 2 sticks: `native_craft` | Pass (in the same runs) |

Environment: Linux x64, OpenJDK 21, the manifest's Minecraft and Fabric. Commands: the GameTest entrypoints narrowed to
the class for each run and restored after; the full suite and build as in [validation](../validation.md).

## Remaining limitations and handoff

Seed 20261030 run again in the ten seeds of `e25a-7c8febf` (3 November, [validation](../validation.md)): 2 walks to the
crafting table in the run, against 928; the goal mined its cobblestone first ("Mine cobblestone for a stone pickaxe").
Nobody died on the seed this time, so the case behind the loop (back at spawn with nothing) did not come up. Why the
goal's plan asked for a stone pickaxe with no stone in hand then was not looked into: the planner's count and the
recipe's ingredients differ somewhere. Next: that. Updated documentation: none for users; the
operator guide's log lines are unchanged apart from the new line above.
