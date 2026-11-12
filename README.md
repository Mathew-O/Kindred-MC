# Bases probe (RUNNER 2, 2026-11-12): fails

**Probe:** main `4ac2f3e`, seed 101, survival, `goal=none; ticks=36000`, `--policy bases=true`, earned, alone. It ran 14:43 to 14:46 and is complete at 36,000 ticks.
**Pass condition:** a " base: " line (PlayerRuntime.noteBase logs every base decision) and a cabin build started.

**Result: fails.**
- **No base work:** 0 " base: " lines and no build.
- **No progress:** no player reached any stage, not even a wooden tool, and `stages` is empty for all three.
- **No goals:** the console has 0 "goal at" lines.
- **Survival tasks only:** every task is `native_retreat` from `evaluateEssentialSurvivalCandidates` (238).
- **Deaths:** 25, all at night under the sky, holding nothing, "no goal".
- **Not the cause:** "autonomous progression remains disabled" appears 3 times here, and also in e40a21's and f35j-109's working consoles.

**Factions, goal=none, bases=true** (`f35j-109-before-973cc12` and `f35j-109-after-0476ee5`, 72,000 ticks):
- Stone tools were reached: Kindred at 63800 in the before run; Sol at 56040 and Echo at 58040 in the after run.
- The players do have goals there (6241 "goal at" lines in the after run), and their tasks come from `factionCandidates`.
- Still 0 " base: " lines in either run.

`analysis/f35j109-sol-stone.txt` has Sol's 50 "goal at" lines before and 50 from its first goal line past the Stone stage (console line 9387). Its labels run through `Stone:` and then `Iron:`, the portal-run planner's stages.

**Hypotheses for MODES (M-01c's `baseDecision`), not findings:**
1. In survival with goal=none, the routine produces no goal work. So no player reaches stone tools, and `BaseProgression` has nothing to act on.
2. In Factions, the routine returns faction work before `baseDecision` is reached ("if (faction != null) return faction;" comes ahead of it). Or the goal there is not NONE: the labels carry the planner's Stone and Iron stages. Either way `baseDecision`'s gate fails, or the code never gets that far.

The probe's console scans to 0 for keys and model IDs.
