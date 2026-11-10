# F-25c4dd — Badly hurt, a mob on foot in reach stops the goal's work

Date: 2026-11-10
Agent / branch: Claude / claude/survival-1110-cornered (SURVIVAL track), on main `335da88`
Build: recorded in [validation](../validation.md)
Status: complete (native). Its effect on the ten seeds is the next evaluation's to show.

## Scope and implementation

User-visible behaviour: a player badly hurt and out of food, with a mob coming for it, stops whatever it was doing for the
mob once the mob is in reach. It backs off, takes cover or, with nowhere to go, fights. Before, the goal's work went on. In
the case seen, that was the climb toward the surface for food, digging by hand, and the zombie killed it there.

Found: `ReplayGameTests` with the kept replay `echo-73670` on main `335da88` (with F-25c4cy in). It lived 11 of 12 in 4
runs, and 100 of 102 plays over every run here. Two deaths were looked at, each with debug lines in a scratch worktree, never
committed:
- One: badly hurt with a zombie on it, its way off refused, then "Fight zombie" broken off at once each time for its health
  ("Badly hurt; break off the fight").
- The other: its cover from a zombie villager put up, then the hungry climb beside it, its second cover refused, and a back-off
  "the way it came".

A fixture staging the first death's state, badly hurt with no food and a zombie two blocks off in a dead-end tunnel, failed
3 of 3 on a cause that comes before the break-off:
- On its first plan the goal chose "Climb toward the surface for food: clear … by hand" (the zombie not yet after it).
- That dig was never stopped, and the zombie killed the player during it.

Cause: `stopFor` stops a running goal task for a mob through `threats(player)` (or a creeper at six health or more).
`threats` returns nothing below six health, which is how the goal starts no fight when badly hurt. So below six health no mob
on foot stopped the goal's work, however close.

Changed file: `companion-server-adapter` `PortalRunCampaign`.
- `stopFor`: a mob on foot after the player and in reach (`onFootInReach`: within 3 blocks, in sight, not a creeper or a
  shooter) also stops a running task, as "A hostile mob is attacking; fight back first".
- The defensive tasks are left alone, as before: back-offs, cover, hole-ups, a held passage, the weapon to hand.
- With one in reach, `defend` always gives a task: a way off or cover if there is one, otherwise a fight. So the stop does
  not leave the goal empty-handed.

Nothing is granted.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| Badly hurt and out of food in a dead end with a zombie coming, it stops its climb and fights the zombie, and no fight is broken off while it has no way off | `NativePortalCampaignGameTests.badlyHurtAndStarvingWithAZombieOnItItStopsItsClimbAndFights`, staged as below | On main `335da88` without the fix: fails 3 of 3, "The zombie still stands, at health 20.0"; the player was killed digging by hand. With the fix: passes 3 of 3; the dig stopped, the back-off was refused (no route), and the zombie was fought and killed |
| The classes that fight, take cover, shelter and climb | `NativeCombatGameTests`, `NativeCoverClimbGameTests`, `NativeEncounterGameTests`, `NativeFallGameTests`, `NativeInitiativeGameTests`, `NativePortalCampaignGameTests`, `NativeTacticalGameTests`, `NativeWeaponToHandGameTests`, `NativeEdgeFightGameTests`, `NativeWaterFightGameTests`, `NativeNightShelterGameTests`, `NativeShelterSealGameTests`, `ReplayGameTests`, 3 runs | 131 of 131 each |
| The replay it came from | `ReplayGameTests` (each run plays each kept replay three times), 6 runs alone plus the 3 class runs | With the fix, `echo-73670` lived 27 of 27. On main without it, 11 of 12 in 4 runs (the replay's figure), 100 of 102 over every run here. At that rate 27 of 27 tells nothing either way |

The fixture's staging: the stone tunnel of `badlyHurtAndCorneredByAZombieItFightsItRatherThanBreakEachFightOff`, five
blocks long and two high, the player at its closed end. A zombie (an adult, its own AI on, its target kept on the player each
tick) is two blocks in front. The player has 5 health with an iron chestplate (the replay's player had armour 6), food 10
with no saturation and nothing to eat, and a stone sword. The fixture watches the action record every tick, and fails on
any fight broken off for its health ("Badly hurt; break off the fight").

## Remaining

- The break-off seen in the first death (a cornered fight broken off once a hit knocks the mob past 3 blocks, out of
  `cornered`'s reach) did not show in the fixture with the fix, so it is not changed here. It is recorded as open in the
  handoff.
- The second death's path (the hungry climb beside its own cover, the cover refused) did not recur in 90 plays.
