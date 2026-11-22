# Corpus data: iteration 26 consoles (for LOOPS)

Data only; not for merging.

- `e40a26-consoles/<build>-<seed>-console.txt.gz`: the server console of each of iteration 26's 20 runs, gzipped.
  `e40a26-f8b7ffe` is batch 1 and `e40a26r-f8b7ffe` is batch 2, main f8b7ffea. For batch 1, 303 and 31102026 are
  the re-runs after the container restart. The scored, filed evidence is in PR #451.
- `e40a26-consoles/census.md` and `census.py`: the bed, wool and sheep census on these consoles (iteration 26
  consoles). Run as `python3 -I census.py e40a26-f8b7ffe e40a26r-f8b7ffe`; it reads
  `run/eval/<build>/<seed>/console.txt`.
