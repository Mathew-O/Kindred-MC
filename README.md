# The six runCorpus results of the 16cb7a2 re-baseline (ff7ad22c, 2026-11-14)

Data only, for import. Not a code branch, and not evidence of earned progress.

- **Build:** 16cb7a2e (the grouping branch `claude/corpus-clock-batches-1114`), `./gradlew --max-workers=2 :companion-server-adapter:runCorpus`, six runs in a row, each from an empty corpus world (`build/corpus/{world,kindred}` removed before each). The runs were on 2026-11-14, 07:12 to 11:15 UTC; times and peak memory are in `rebaseline-16cb7a2/progress.log`.
- **Files:** `rebaseline-16cb7a2/run-1.xml` to `run-6.xml`, each run's `corpus-results.xml`, copied unchanged.
- **Counts (runner's, by parsing each `testcase`):**

| Run | Cases | Failures | Failures that say "died again, as it did" |
| --- | --- | --- | --- |
| 1 | 844 | 124 | 88 |
| 2 | 844 | 119 | 81 |
| 3 | 844 | 129 | 90 |
| 4 | 844 | 128 | 89 |
| 5 | 844 | 125 | 87 |
| 6 | 844 | 125 | 86 |
| All six | 5,064 | 750 | 521 |

- **Run 4:** its game JVM was still alive 5 minutes after `GAME TESTS COMPLETE`; three stack dumps were taken. Its XML had been written in full.
- **Key check:** no key or model-name pattern in any file here (0 matches).
