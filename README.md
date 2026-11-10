# Corpus data (not for main)

Raw evaluation output for F-27a's failure corpus, from the lead's container. Pushed on an orphan branch so it adds
nothing to main's history. Read it with `git fetch origin claude/corpus-data-1110` and `git show` or a worktree.

- `runs/<eval>/<seed>/eval-result.json`: the seed's result as the engine wrote it.
- `runs/<eval>/<seed>/replays/world/<player>-<tick>/replay.json` and `replay.snbt`: F-27's death replays, as captured.
- `runs/<eval>/<seed>/console.txt.gz`: the seed's server console, gzipped; the source of stuck-spell positions and steps.
- `runs/<eval>/summary.json`: the evaluation's summary.

Evaluations: e40a13-fe2ba3b, e40a14-80b7150, e40a15-dbf4e5c, e40a16-922f4fd, e40a17-7bc5b42 (ten seeds, 144,000 ticks,
the nether goal) and e40c1-eed2dc5 (four seeds, 288,000 ticks, the dragon goal). All earned. The worlds were kept for
every seed of these runs but are not here (about 1.2 GB); they stay on the lead's container, and a region around a
named position can be cut from them on request.

## Memory files (for F-32's tests)

- `memory/f29e-on-8675309/<uuid>.atlas.json` and `<uuid>.peers.json`: the three AI players' atlas and peer ledger as
  saved in `world/kindred/memory/` at the end of `f29e-on`'s seed 8675309 (earned, AI players only, no people's data).
