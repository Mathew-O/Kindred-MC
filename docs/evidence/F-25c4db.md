# F-25c4db — A shield in the off hand is raised between swings against a mob

Date: 2026-11-10
Agent / branch: Claude / claude/survival-1110-shield (SURVIVAL)
Build: recorded in [validation](../validation.md)
Status: complete (native)

## Scope and implementation

Root cause, in the product. The source is the kept replay `kindred-43985-707`, restored from #96's history at `07ce2b4`.
- Kindred, underground at night at 17.5 health, had three zombies on it in a dead end.
- It fought them with a shield in its off hand that never once came up. A debug line each quarter second showed it.
- `NativeFightAction` raised a shield only for a hissing creeper or a shooter. Against a mob that strikes in reach,
  `strikeInReach` kept it down all the time.

Changed file: adapter `NativeFightAction.strikeInReach`.
- **The shield:** with a shield in the off hand, not cooling down, against a hostile that strikes in reach, the shield
  is up while the swing recovers. It comes down for each swing, as a player fights.
- **No jump for a critical** with the shield: a critical needs the shield down for the jump.
- **Unchanged:** a creeper is still run from or faced behind the shield as before, and a fight with no shield is as it
  was.

New fixture: `NativeCombatGameTests.aShieldInTheOffHandIsRaisedBetweenSwingsAgainstAZombie` (batch `combatShield`).
- A player with a stone sword and a shield in its off hand fights a zombie that comes at it.
- Watched every tick until the zombie is dead, the shield must be up at least once. The zombie must be killed.

## Acceptance evidence

| Criterion | Check | Result |
| --- | --- | --- |
| The new fixture before the change | 3 copies | 0 of 3: the shield up 0 of 42, 41 and 43 ticks of the fight |
| The new fixture after | 3 copies | 3 of 3 |
| The fight classes | `NativeCombatGameTests`, `NativeEncounterGameTests`, `NativeEdgeFightGameTests`, `NativeWaterFightGameTests`, `NativeDuelBenchmarkGameTests`, 1 run | 12 of 12 |
| Side figure: the 707 replay on current main, `ReplayGameTests` with 707 restored | 3 runs, before and after | 7 of 9 before (with F-25c4cv's sword to hand, on main); 9 of 9 after. 707 is not in the suite; whether it goes back is the lead's call |

The 707 baseline was re-taken on main after F-25c4cv (the sword taken to hand) landed, so the shield is not credited with
the sword's effect. On `e0e0ab6`, before F-25c4cv, 707 survived 1 of 3. The shield alone there was 3 of 6.

## Remaining limitations and handoff

- The shield guards the front only: mobs on several sides still land blows.
- F-25c4da, filed open: the player eats with a hostile after it within reach. In 707 it ate rotten flesh between two
  zombies' blows. Changed alone on `e0e0ab6`, 707 survived 1 of 6; no PR yet.
