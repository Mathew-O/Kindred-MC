# F-25c4cv — A sword or axe in the backpack is taken to hand and fought with

Date: 2026-11-09
Agent / branch: Claude / claude/backoff-1109 (E-40 ladder, batch 25), on main `e7e0b57`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is the next evaluation's to show.

## Scope and implementation

User-visible behaviour: a player whose sword or axe has ended up off its hotbar takes it to hand before it fights. Before,
it fought as though it had none: the goal's fights strike only with a hotbar weapon, and its check for a weapon looks only
at the hotbar, so it backed off from a mob it could have fought and struck what was in reach with a pickaxe.

Found: E-40's iteration 16 (`e40a16-922f4fd`, [deaths](eval/e40a16-922f4fd/deaths.md)).
- Four of the iteration's 23 deaths carried a sword in slots 9 to 35 and none on the hotbar: 202 Echo (tick 55002), 505
  Kindred (tick 33525), 505 Sol (tick 8079) and 707 Kindred (tick 43985).
- On 707 Kindred, three zombies after it at 17.5 health with a shield: its hotbar held dirt, spruce logs, sticks, arrows,
  bones, rabbit hide, a wooden pickaxe, rotten flesh and a stone pickaxe; its stone sword was in slot 15. It backed off
  ("Back off from zombie", the goal's no-weapon branch), struck with its stone pickaxe when they closed in, and died.
- Its death replay (F-27), played on main: it died again in 2 of 3 runs, each time fighting with the stone pickaxe.
- The goal's `strike` chooses a weapon with `Weapons.choose(..., hotbar=true)`, and `armed` looks at slots 0 to 8. A sword
  crafted or picked up with the hotbar full goes into the backpack, and nothing brought it out.

Changed file: `companion-server-adapter` `PortalRunCampaign`.
- `weaponToHand`: with no sword or axe on the hotbar and one in the backpack, the goal swaps its best one onto the hotbar
  ("Take its weapon to hand") before its fight decision. The slot is an empty one; else one holding nothing kept to hand
  (no tool, bucket, food, torch, shield or flint and steel, and no block); else a block while another block stays on the
  hotbar; else none, and nothing is swapped.
- `stopFor`: the swap, a step of one tick, is not stopped for the mob attacking the player.
- `swapToHotbar`: with the hotbar full, its other swaps ("Ready a block", a torch, a portal item) no longer put their item
  in place of the only sword or axe on the hotbar, which would only be taken back to hand.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| With its sword in the backpack and a zombie after it, it takes the sword to hand, strikes with it and kills the zombie | `NativeWeaponToHandGameTests.aSwordInItsBackpackIsTakenToHandAndFoughtWith`, staged as below | On `e7e0b57`, without the fix: fails 3 of 3, "The sword never reached its hotbar", the zombie struck five times with the stone pickaxe each time. With the fix: passes 3 of 3, the sword taken to hand ("Take its weapon to hand") before "Fight zombie" |
| The death it came from | `ReplayGameTests` with 707 Kindred's replay (`kindred-43985-707`) | On `e7e0b57`, without the fix: died again 2 of 3, fighting with its stone pickaxe. With the fix: lived 3 of 3 here, the sword taken to hand first. Not kept: on #96's CI (run 42583930289, attempt 2) it died again 2 of 3 with the sword taken to hand, and locally 3 of 21, each time badly hurt among several zombies backing off "the way it came". F-27 keeps a replay only with the fix that makes it pass 3 of 3; this replay waits for the back-off fix, with which it is to be committed |
| The classes that fight, swap and cast, with the replays | Nine classes, 126 fixtures, three runs | See [validation](../validation.md) |

The fixture's staging: an 8-by-8 room walled and roofed in stone on the flat test plot, the player's inventory set to 707
Kindred's hotbar as its replay held it, the stone sword in slot 15, bread in slot 10 and the stone pickaxe selected, and a
zombie spawned in the room and set on the player. Nothing else is staged.

The other three death replays benched with it (`sol-120619-505`, `echo-18586-101`, `echo-82476-101`) are not kept:
- Sol's on 505 did not die again (3 of 3 alive). In the run, nothing was dispatched for its last 14 seconds while its goal
  chose to fight, then to back off, a runtime gate the replay does not reproduce. It is recorded for the next batch.
- Echo's on 101 at tick 18586 died again 1 of 3.
- Echo's at tick 82476 died again 3 of 3, but from 3 health with a zombie already in reach in its shelter; what it does
  then does not decide it.

The benches ran with nothing else on the cores but the worktree builds.

Environment: Linux x64 cloud container, OpenJDK 21, the manifest's versions.

## Remaining limitations and handoff

- A better weapon in the backpack than the one on the hotbar (an iron sword behind a stone one) is not swapped in.
- A hotbar with every slot holding a tool, bucket, food, torch or the only block gets no weapon.
- The rest of the iteration's deaths at low health, backing off or holed up with a mob in reach, are not changed here.
