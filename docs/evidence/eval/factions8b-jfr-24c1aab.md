# factions8b with Java Flight Recorder on main 24c1aab: where the slow ticks go (for M-06k6)

Date: 2026-11-14
Agent / branch: Claude RUNNER 2 / `claude/factions8b-jfr-1114`
Status: a diagnostic. It changes no code and claims no fix. The performance campaign is held for the owner.

**The question (lead):** in the 8-player faction world, what are the worst ticks? factions8b-4ba244e had a worst tick of 521 to 774 ms and 271 to 757 ticks over 50 ms per run, unattributed. Each tick over 50 ms is attributed to its top frames below.

## The runs
- **Build:** main 24c1aab, built once with `./gradlew :companion-server-adapter:remapJar` (jar sha256 6236f3d3…). It was run by `tools/eval/run.py --jar`, one server at a time, with nothing else on the cores. No model key was in the server's environment.
- **Settings:** those of factions8b-4ba244e: `starters=Kindred,Sol,Echo; players=Ada,Bram,Cleo,Dara,Eli; founders=3; goal=none; mode=factions; ticks=72000`, policy `maxNativePlayers=8` and `bases=true`, earned.
- **One deliberate difference: `sprint=off`.** In sprint the ticks run back to back, and the server keeps no time for each one. With sprint off, the server thread idles between ticks, so in the recording each tick is a run of the thread's samples with a gap on either side.
- **Seeds:**
  - **102** (4ba244e's worst) first. Its attribution was mixed: no family was largest in most of its long ticks.
  - **101**, by the lead's rule.
  - 103 was not run.
- **The recorder:** JFR from the JDK (21.0.12), started with `-XX:StartFlightRecording` through `JAVA_TOOL_OPTIONS`. Settings: `profile`, with execution samples every 5 ms, thread parks and monitor waits of 5 ms or more, every GC pause, and safepoints. The recordings (164 and 134 MB) are on the runner's container, not in the repository.
- **The audit:** clean on both seeds, 0 violations, 3 factions and 10 claims each. Each run completed its 72,000 ticks in about 61 minutes.

## The server's own numbers, against 4ba244e

| Seed | Run | Mean tick, ms | Worst tick, ms (at run tick) | Ticks over 50 ms | Over 200 ms | Heap at most, MB |
| --- | --- | --- | --- | --- | --- | --- |
| 101 | 4ba244e (sprint on) | 19.25 | 521.37 (40,585) | 271 | 6 | 964 |
| 101 | 24c1aab (sprint off, JFR on) | 29.00 | 207.64 (3,362) | 2,875 | 2 | 838 |
| 102 | 4ba244e (sprint on) | 26.21 | 773.75 (21,180) | 757 | 6 | 1,355 |
| 102 | 24c1aab (sprint off, JFR on) | 41.32 | 2,836.28 (60,866) | 9,484 | 4 | 1,273 |

**The rise in mean tick and in ticks over 50 ms is not separated.** Three things differ from 4ba244e at once: the build (222 merges into main between 4ba244e and 24c1aab, among them #256, far-column sight), sprint off, and the recorder's own cost. These two runs cannot say how much each contributes. A control is queued (seed 102, the same jar, sprint off, the recorder off), and its result will be added here.

## How a tick is attributed
- **Finding the ticks:** a tool reads each recording through `jdk.jfr.consumer`. A tick is a run of the server thread's samples and parks inside `MinecraftServer.tick`, with no gap of 8 ms or more between them. Frame names are put back through the pinned Yarn mappings (1.21.1+build.3).
- **Counting:** each sample counts 5 ms and each park its length. A GC pause that overlaps the window counts as GC.
- **Families:** a stack's time goes to the first family, by priority, whose marker is anywhere on it. So a chunk read made for Kindred's sight counts as the sight's, and only time with no Kindred frame falls to vanilla. The priority is:
  1. the construction planner;
  2. Kindred's sight scan (`WorldKnowledge.scan`, `core.route.Sight`);
  3. Kindred's routes (`RoutePlanner`, `Navigator`, `NativeRoutePlanner`, `NativeNavigator`);
  4. SQLite;
  5. other Kindred code;
  6. chunk generation or IO;
  7. mob spawning;
  8. entities;
  9. the rest of the world tick.
- **Checking the windows:** the windows over 50 ms number 2,957 on 101 and 11,925 on 102, against the server's 2,875 and 9,484. Back-to-back catch-up ticks merge into one window, so a window can hold more than one tick.

## The ticks over 50 ms, by family (share of their time)

| Family | Seed 101 | Seed 102 |
| --- | --- | --- |
| Kindred routes (RoutePlanner, Navigator) | **32.5%** | 4.5% |
| Kindred sight scan (`WorldKnowledge.scan`, `Sight`) | 22.0% | **26.0%** |
| Entities (vanilla) | 17.3% | 24.9% |
| Kindred, other | 8.5% | 10.3% |
| World tick, other (vanilla) | 7.6% | 15.4% |
| Chunk generation or IO (vanilla) | 6.2% | 8.4% |
| Mob spawning (vanilla) | 5.4% | 10.1% |
| GC (a pause in the window) | 0.5% | 0.3% |
| The construction planner | 0 | 0 |
| SQLite | 0 | 0 |
| All Kindred families | 63.0% | 40.8% |

**The family with the most time in each long window:**

| Family | Seed 101 (of 2,957) | Seed 102 (of 11,925) |
| --- | --- | --- |
| Routes | 1,761 (60%) | 759 |
| Sight | 766 | 5,962 (50.0%) |
| Entities | 311 | 3,784 |
| Other Kindred | 101 | 745 |
| World tick | 4 | 484 |
| Chunk generation or IO | 13 | 114 |
| Mob spawning | 1 | 77 |

**Who calls the routes and the sight scan in those windows** (the first Kindred frame below them on the stack):

| Caller | Seed 101, s | Seed 102, s |
| --- | --- | --- |
| `PortalRunCampaign.observe` → the sight scan | 47.9 | 263.9 |
| `NativeGoToSkill.plan` → a route | 51.6 | 24.8 |
| `PortalRunCampaign.buildRoute` → a route | 9.1 | 25.7 |
| `PortalRunCampaign.surface` (and its lambda) → a route | 14.6 | – |
| `PortalRunCampaign.facts` → the sight | 3.0 | 20.6 |

**The top Kindred frames in those windows:**
- **Seed 102:** `WorldKnowledge$WorldSight.state` 180.8 s, the body's own tick 63.5 s, `WorldKnowledge.classify` 39.9 s, `Sight.evictFarthest` 25.3 s with its sort comparator 24.4 s, `NativeNavigator.state` 12.9 s.
- **Seed 101:** `WorldSight.opaqueFullCube` 25.4 s, `NativeNavigator$WorldTerrain.fluid` 20.3 s, `NativeNavigator.state` 15.5 s, `CellMemo.slot` 14.9 s, `Navigator.find` 8.2 s.

## The worst tick (seed 102: 2,836 ms at run tick 60,866)
- **The console:** "Can't keep up! Is the server overloaded? Running 2793ms or 55 ticks behind" at 20:31:32. Two seconds earlier, at 20:31:30, Eli (an AI player) made the advancement "We Need to Go Deeper".
- **The recording:** the server thread recorded only 63 events in the 3.4 s around it.
  - Its parks were all `PortalForcer.createPortal` → `World.getBlockState` → `ServerChunkManager.getChunk` → `ThreadExecutor.waitForTasks`. That is vanilla finding or making the exit portal, waiting on the Nether chunks being generated.
  - Then came `ServerPlayerEntity.travel` → `getChunk` waits, as the player arrived.
  - GC pauses in it: 17, 12 and 14 ms.
- **So:** the worst tick is vanilla's synchronous portal-exit chunk generation, set off by an AI player's Nether entry.
- **Why the windows miss it:** the thread parks in waits shorter than the recorder's 5 ms threshold, so the recording shows gaps.

## What is and isn't attributable
- **Attributable:** the time in ticks over 50 ms, by family and by caller, as above. The windows track the server's own count within about 3% on seed 101 and 26% on 102.
- **The construction planner:** no sample on the server thread in either run is in `ConstructionPlanner`. All of `core.construction` together has 6 samples on 101 and 159 on 102, about 30 and 795 ms over an hour. The planner M-06k6 measured (62 to 75 ms at p99 a call) is not on main 24c1aab, so this run says nothing about it.
- **SQLite:** never on the server thread. Its 47 and 52 samples are on `kindred-metadata-writer` and `kindred-metadata-check`.
- **GC:**
  - Seed 101: 4,913 pauses, 32.6 s in all, the longest 40.9 ms.
  - Seed 102: 4,924 pauses, 38.0 s in all, the longest 41.7 ms.
  - No pause was over 50 ms, so GC does not by itself make a tick over 50 ms.
- **Not attributable here:**
  - Single ticks over 200 ms, other than the worst. Windows that merge catch-up ticks make 129 (101) and 1,322 (102) windows over 200 ms, against the server's 2 and 4 such ticks.
  - Any wait made of parks under 5 ms, such as the worst tick's.
  - Seed 103.
  - The rise against 4ba244e (see above).
- **Against Session A's spark profile (M-06k, 2026-11-12: seed 101 on an earlier build, the navigator's searches 66.9% over 50 ms):** routes lead on 101 here too, at 32.5%. The sight scan, which came with far-column sight, is now a large second on 101 and the largest family on 102.

## Kept with the run
The two `eval-result.json` files are on the runner's container under `run/eval/factions8b-jfr-24c1aab/`. So are the recordings, the analysis tool (`Ticks.java` and its helpers) and its full output. Nothing is filed beyond this page.
