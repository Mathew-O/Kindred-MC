# f29e-off-4h-lead: a second rescue-off arm at four game hours

- Build: `cd56501`. Seeds: `tools/eval/seeds-10.txt`. Scenario: `goal=nether; ticks=288000; strategist=on; advice=off`.
  Run with `--parallel 2 --keep-world` in the lead's container, 8 November 2026, 16:49 to 21:12 UTC.
- **The strategist was off.** It was meant as F-29e's rescue-on arm. But only `ANTHROPIC_API_KEY` was set:
  `KINDRED_STRATEGIST=on` and the compatible route the release jar needs were not. Each seed's result says so in its
  `build` field: "strategist asked for, the strategist not configured". There are 0 rescue plans and 0 model tokens on
  every seed. `summary.md` keeps its run name, `f29e-on-4h`.
- It is filed as a second rescue-off sample at four hours, beside the F-29 session's own `f29e-off-4h`: the same build,
  seeds and arm, run in another container.
- All 10 seeds ran their 288,000 ticks. Stuck game minutes, the team: 63.0 [30 to 165]. Portal lit on 7 of 10.
  Deaths: zombie 14, skeleton 8, drowning 5, drowned 3, spider 2, suffocation 1, zombie villager 1, husk 1, creeper 1.
- Here are each seed's `eval-result.json` and `run.json`. The consoles, worlds and death replays are kept in the lead's
  container only.
