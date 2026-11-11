# e40c2-d640518: runner notes

- Build d640518 is the head of claude/cast-outcome-1109 (main plus #83, #91, #96 and #98), used because #96 and #98 were not yet on main when the batch started.
- Scenario `goal=dragon; ticks=288000`, strategist off, `--keep-world`; the dragon fight was turned on for the dragon goal ("Evaluation run: the dragon fight on, for the dragon goal").
- The container restarted twice during the batch. Each seed is one uninterrupted run: seeds 8675309, 31102026, 20261030, 101 and 202 ran first (parallel 3); 303, 404 and 505 were re-run from scratch after the first restart (parallel 3); 606 and 707 were re-run from scratch after the second (parallel 2). Seeds cut off mid-run left no result and were not used.
- All 10 seeds exit 0 with 288000 ticks run.
