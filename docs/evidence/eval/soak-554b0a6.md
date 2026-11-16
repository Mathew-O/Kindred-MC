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
| A restart's knowledge-web rebuild | open | **not measured in the soak**, since it had no restart and 554b0a6 predates the start line (#360). Measured on a partial restart (addendum below): 40 events, read in 18 ms on the writer, replayed in 5 ms | **open** (measured; no threshold) |
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
- The soak itself had no restart. The addendum's restart is partial, not the month run's.

## Addendum, 2026-11-15 22:23 to 23:04 UTC: a partial restart on main 4e5b77d

**The question (lead):** start the kept soak world unchanged on current main and read #360's rebuild line. Let it run
about 2 game days, to see whether a restart repeats the soak's slow early ticks.

**Why "partial":** `run.py --keep-world` keeps the region files, the players' saves and the companion database. It
keeps no `level.dat` and no memory files. So this start has the soak's ground, players' saves and Record, but world
time, gamerules and spawn start fresh, and the players have no memory files. It is not the restart the month run will
have. The gap is filed for a harness fix: `--keep-world` should also keep `level.dat` and the memory files.

**Setup:**
- **The world:** a copy of `world-kept/` (region, DIM-1, playerdata, `companions.sqlite` at 95,731,712 bytes) and the
  run's `usercache.json`, in a new folder. The kept world was only read.
- **The build:** main 4e5b77d (#366's merge), its release jar (`remapJar`).
- **The start:** as an operator would, with no `KINDRED_EVAL` and no model key, the policy `maxNativePlayers=8`, and
  `-Xmx10G`. Then at the console: `aiplayer mode world factions`; each of the 8 players `aiplayer native connect`,
  then `aiplayer native resume` (soak.py's steps). All 8 joined.
- **The stop:** real time, no sprint. `/stop` after the day-2 cost line, 2,429 s after the start; exit 0.
- **The report:** `preflight.py --console console-r1.txt --db <copy> --heap-max-mb 10240`.

**The start:**
- **Ready:** 16 s after launch. The foundation line reads `8 durable records`.
- **The rebuild:** `Knowledge web rebuilt from the Record: 40 events, read in 18 ms on the writer, replayed in 5 ms;
  the world's web 10 relations, 8 AI players' webs holding 0`.
  - **Cross-check:** 40 web events of 139,373 in all. The soak's copy holds 10 `claim` and 30 `message` events, and
    no `unclaim`, `capture` or `correction`: the kinds F-32 replays.

**The ticks, day by day (the cost lines; `server.getTicks()` starts again at 0):**

| Day line | Ticks | Mean | p99 | Over 50 ms | Longest | Heap high water |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 23,998 | 18.4 ms | 29 ms | 8 | 126 ms (tick 6,000) | 1,114 MB |
| 2 | 24,000 | 18.7 ms | 27 ms | 4 | 72 ms | 1,146 MB |
| 3 (to the stop) | 281 | 19.9 ms | 29 ms | 1 | 59 ms | 1,094 MB |

**Against the soak:** the soak's day 1 had a mean of 36.3 ms, a p99 of 99 ms and 5,767 ticks over 50 ms; its days 13 to 36
had a p99 of 47 to 58 ms. On this restart the slow early ticks did not come back. No "Can't keep up" line was written.
Why is not separated here. Not tested: whether the copied ground being already generated is the cause, and the build
differs (554b0a6 against 4e5b77d).

**The report:**
- **STOP:** none. Integrity ok, schema 15, 38 `ledger_check`s all agree, 0 unresolved actions.
- **The Record:** 138,397 events in the copy after the stop. The `fold` (2 passes, 180 removed) and `action_bound`
  (2 passes, 941 removed) prunes ran.
- **WATCH:** none.
- **ERROR lines:** 0.
- **Database:** 99,926,016 bytes on the day-2 line, WAL included; 57,792 bytes of growth from day 1 to day 2.
- **Deaths:** none in the console over the two game days.

**The restart row,** filled from this start: the rebuild was 23 ms in all (18 ms read, 5 ms replay) for 40 events, on
a partial restart. Its threshold stays open, for the lead and the owner.
