# factions8-495b879 — eight players in the Factions scenario, three seeds (the capacity measurement)

Run by the lead session for Session A (M-06i), 9 November 2026, 02:35 to 03:03, with nothing else on the container's four
cores. Build `495b879`, `tools/eval/run.py --parallel 1 --policy maxNativePlayers=8`, scenario
`starters=Kindred,Sol,Echo; players=Ada,Bram,Cleo,Dara,Eli; founders=3; goal=none; mode=factions; ticks=72000`, seeds
101 to 103. Earned. Strategist off; no model calls. Three seeds: too few to compare with another build.

[summary.md](summary.md) is `score.py`'s output; each seed's `eval-result.json` and `run.json` are beside it.

- Audit: three of three audited, three factions each, no rule broken; claims 10, 7, 7; no captures, sales or kills.
- Load for eight players: mean tick 8.27 to 14.80 ms, worst tick 512 to 914 ms, heap at most 972 to 1,171 MB (3 GB
  allowed).
- As in `factions2-495b879`, `goal=none` with bases off gives the players no survival goal: none progressed, and deaths
  a game hour were 18 to 37, all but one fall to mobs. The load is of faction play alone; players that gather, build and
  fight would load the server more. A run with `bases=true` is the fairer capacity reading.
