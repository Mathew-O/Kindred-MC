# The 36-game-day soak on main 554b0a6, with the month run's go/no-go column (M-06k7, M-06k8)

Date: 2026-11-15
Agent / branch: Claude RUNNER 2 / `claude/soak-554b0a6-1115`
Status: a soak report. It changes no code and passes no release gate. The go/no-go is read by the lead and the owner.

**The question (lead):** run M-06k7's soak, then fill the soak column of M-06k8's table ("Go or no-go for the month
run" in the [operator guide](../../operator-guide.md#go-or-no-go-for-the-month-run-m-06k8)) from the soak's own
figures, each check go, no-go or still open.

**Answer, in short:** no STOP fired. Two rows with a threshold are outside it: the p99 (over 50 ms on 26 of 37 day
lines) and the share of ticks over 50 ms (over 5% on 7 days). By the table's rule ("Go needs every row with a threshold
within it") that is **no-go** as written. Six rows are open. The ticks, the pace and every figure are below.

## The run
- **Build:** main 554b0a6 (#349's merge), built and run by `tools/eval/run.py --ref`. The server's console starts
  `Kindred AI-player foundation ready: 0 durable records` (a new world).
- **Settings:** [M-06k7](../M-06k7.md)'s soak: `starters=Kindred,Sol,Echo; players=Ada,Bram,Cleo,Dara,Eli; founders=3;
  goal=none; mode=factions; sprint=off; ticks=864000`, policy `maxNativePlayers=8`, bases off (the pack's default),
  `--memory 10G`, `--keep-world`, seed 101, earned. Strategist off; no model key was in the server's environment.
- **Nothing else** ran on the cores for the soak's 12 hours.
- **The report:** `tools/soak/preflight.py --console <run>/console.txt --db <copy> --heap-max-mb 10240` from the same
  build, on a copy of the kept world's `companions.sqlite`. It was also run at 11:30, 13:33, 15:34, 17:35 and 19:38
  on the live console; none of those had a STOP either.

## Ticks and pace
- **Ticks run: 864,001 against 864,000.** run.py: exit 0, `ticksRun` 864,001, all 36 game days.
- **Real time:** run.py's 43,602 s (12 h 6 min 42 s), start to exit. The server ran from `Done` at 09:38:46 to its
  last save at 21:45:04 UTC, about 43,578 s.
- **Pace:** 864,000 ticks in about 43,578 s is **19.83 ticks a second**, 20.17 real minutes a game day (20 tps would be
  20.00). Over 2,160 game days that pace is about 30.3 real days, not 30.
- **Early days ran slower:** about 22.6 real minutes a game day by day 5 (the progress log), about 20.7 later.

## The go/no-go column

The table's first three columns are as in the operator guide; only the soak column and a status are added here.

| Check | Threshold (from the guide) | Tonight's soak, 554b0a6, seed 101, 36 game days | Status |
| --- | --- | --- | --- |
| Heap ceiling | STOP over 9,216 MB (90% of `-Xmx10G`) | high water 1,791 MB on the day lines (run.py's `heapMbMax` 1,792), 19.4% of 9,216 | **go** |
| Database size | open (host disk not recorded) | 95,731,712 bytes kept (WAL checkpointed away at shutdown); 99,926,016 bytes on the day-36 line, which includes a 4,194,304-byte WAL | **open** |
| Database growth a game day | open | 20.7 MB days 1 to 2, 18.5 MB 2 to 3, 10.1 MB 3 to 4, 6.1 to 6.5 MB 4 to 6, 1.0 to 1.4 MB 7 to 12, 0.16 to 0.32 MB 12 to 30, 0 bytes 31 to 36 (freed pages reused; 440 to 461 events a game day still written). The report's month, "100 MB", is the last day's growth (0) times 2,160 plus today's size, so it is not a measure of the month | **open** |
| Write-ahead log | open; measured bound about 4.2 MB | at most 4,194,304 bytes, sampled each minute **from 17:36 only** (game day 24 on): 249 samples, all 4,194,304. Not sampled before 17:36 | **open** |
| A restart's knowledge-web rebuild | open | **not measured:** the soak had no restart, and 554b0a6 predates the start line (#360) | **open** |
| Log volume a game day | open | `latest.log` 13,547,681 bytes over 36 game days, 0.376 MB a game day; 922,923 bytes gzipped. `console.txt` 13,555,154 bytes, 115,156 lines | **open** |
| Mean tick | WATCH per day if the p99 is over 50 ms or more than 5% of ticks are; none on the mean | mean 35.06 ms over 864,362 counted ticks; per day 30.3 to 49.9 ms. Over 50 ms: 40,084 ticks, 4.64% of the run; over 5% on 7 days (1 24.0%, 4 14.8%, 8 11.0%, 9 11.7%, 11 18.4%, 12 35.8%, 17 5.1%) | **no-go** (7 days outside) |
| p99 tick | as above | over 50 ms on 26 of 37 day lines (1, 3 to 18, 21, 23 to 28, 32, 37; day 37 is 364 ticks). Highest 110 ms. Days 13 to 36: 47 to 58 ms | **no-go** (26 days outside) |
| Worst tick | open | 899.01 ms at run tick 75,040 (run.py); the report's longest 899.0 ms on day 4. 10 ticks over 200 ms | **open** |
| STOP lines | none for a go | none: integrity `ok`, schema 15, 139,373 events in the copy, 36 `ledger_check`s all agree, 0 unresolved actions, heap as above | **go** |
| ERROR lines | each WATCH; open whether a count stops a go | 0 ERROR lines | **go** on this soak (0 lines; no count needed) |

## Other figures from the soak
- **WARN lines:** 82 "Can't keep up!", all by game day 11, and the 4 lines of vanilla's offline-mode notice.
- **Prunes:** `fold` 34 passes removing 26,223; `action_bound` 7 passes removing 23,118.
- **Day lines:** 0 native disconnects; 76 Wire replies refused (the plain line said).
- **The factions audit:** 1,599 events, 3 factions, 10 claims, 93 sales, 32 purchases, 20 deaths, 0 kills; 0
  violations.

## Method notes
- **One seed, one run.** This is the soak M-06k7 asked for, not a ten-seed batch; its figures are not ranges.
- **Tick counts differ by source:** the report counts 864,362 ticks over its 37 day lines; run.py counts 864,001 and
  its own 40,080 over 50 ms. The report's worst-tick tick (75,402) is its own count, run.py's (75,040) the run's.
- **The WAL samples** were taken by a side script reading the file sizes once a minute; it was started at 17:36.

## Not claimed
- No release gate, no M-06k8 completion and no threshold for an open row: those are the lead's and the owner's.
- Nothing about a restart, since none was run.
