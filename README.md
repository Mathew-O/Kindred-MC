# F-35j pair corpus (RUNNER 2, 2026-11-12)

Before `973cc12` (#213 merge) against after `0476ee5` (#217 merge); 973cc12..0476ee5 is F-35j only. One run per seed, `tools/eval/run.py --parallel 1 --policy bases=true`, no diagnostic patch:
- 303 and 110: `goal=nether; mode=factions; ticks=144000`
- 109: `goal=none; mode=factions; ticks=72000`

Each `runs/<run>/<seed>/` holds `eval-result.json` (all six complete) and `console.txt.gz`; `runs/<run>/build.json` is run.py's build record.

`counts.txt` is `f35jcount.py` over the consoles: only lines containing `[from factionCandidates`, matched with
`INFO\]: (\w+) (native_\w+) (COMPLETED|BLOCKED: No walkable route|BLOCKED: Route to|CANCELLED|BLOCKED)`.
asked = native_goto COMPLETED + BLOCKED: No walkable route + BLOCKED: Route to + CANCELLED; native_faction COMPLETED is separate.
No faction line follows the "thinking at tick" cap line in any console, so nothing past the cap is counted. The number in `[from factionCandidates:N]` is a source line, not a tick.

The first 110-before run was lost when the container restarted at about 02:36 UTC; it is not included or counted. 110-before was rerun from scratch at 05:01.

## F-35j1 (added 07:40 UTC)
`runs/f35j1-110-affd0ac`: seed 110 alone on `affd0ac` (main `e580024` plus F-35j1), same scenario and policy as the 110 pair, complete at 144,001 ticks; its counts are the last line of `counts.txt`.
`refusals.txt` is `f35jrefuse.py` over the three seed-110 consoles: per player, faction-walk native_goto refusals ("No walkable route" plus "Route to"), split by kind, and how many came within 2 s (console seconds, gap ≤ 2) of that player's previous refusal of either kind. Counting "No walkable route" alone, the 2 s repeats for Echo are 102 of 189 on 0476ee5 and 1 of 19 on affd0ac.
