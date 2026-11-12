# F-27a — The build's known-logs-kept-out line

Date: 2026-11-12
Agent / branch: claude/gather-diag-1112
Status: in progress (part of F-27a; linked from F-27a.md once merged)

For capM-2 on #219's CI.
- **What happened:** the cabin fixture's build went "Explore west for logs" while a tree stood 21 blocks east. The walk took it off the raised field onto the barrier edge, about 200 times over.
- **What was missing:** nothing said why the build's log gather, within 64 blocks, of its own wood and off its site, had passed the tree over. The lead asked for a diagnostic, not a guessed fix: "why each known log was kept out", once a trip.

## The line
- **When:** a build's log gather comes back empty while the player knows logs. It is logged at most once a trip. A trip whose empty gathers all come while it knows no logs logs nothing, and the next empty gather with logs known still logs.
- **What it says:**
  - the build, the trip and the log it wants;
  - how many logs it knows, and how many each rule kept out;
  - the nearest eight, each with its distance, the first rule that kept it out and whether its tree was given up.
- **The rules,** in the gather's own order (`PortalRunCampaign.gatherable`):
  - not loaded, or no longer a log;
  - set aside (until when, after how many failures) or cover held;
  - unreachable (until when, from where);
  - dangerous, or its own support;
  - beyond the radius;
  - no landing under it;
  - the build's own rules: "on its site", "not its wood (…)";
  - may not work here, or not harvestable;
  - above its trunk reach;
  - built in, or no trunk foot (no stand);
  - otherwise usable, with its approaches.
- **#193's stuck-spell line** (`F-27a-gather-debug.md`) now takes its reasons from the same rule chain. Its text is unchanged for the reasons it gave before. It can now also say "no landing under it", the gather's rule for a log below its feet, which it had left out.
- **Diagnostic only:** no decision reads the line, and the gather's choice is unchanged.

## Fixture: `NativeKnownLogsKeptOutGameTests` (staged)
- **The scene:**
  - `NativeBaseGameTests`' ground, with its two oak trunks.
  - The player is seeded with a cherry log (E-29a). It holds all any cherry cabin takes except its cherry blocks, as though gathered, plus stone tools, a table, dirt and bread.
  - It goes out for cherry logs, its gather keeps the oak out, and it explores.
- **Passes** if a line names the oak "not its wood (minecraft:oak_log)", and the build has logged no more lines than it has made trips.
- **Before the line was wired in:** the campaign method existed, but BuildRunner did not call it. The fixture failed 3 of 3, logging no such line in any run. Run 3's report says "No line naming the oak as not its wood yet: null / cherry cabin: 52/261 placed - Out of the ground for logs". The reports of runs 1 and 2 were overwritten by the next run.
- **After:** it passed 3 of 3, with 1 line for 1 trip each time. For example: "Build cherry cabin, trip 1, for minecraft:cherry_log: its gather within 64 found no logs, with 12 known from …, kept out by {not its wood (minecraft:oak_log)=12}; nearest: … (24 off) not its wood (minecraft:oak_log); …".
- **`NativeLogsKeptOutGameTests`,** run alongside, passed 3 of 3 with 1 line for 1 spell each time. Each line named its 3 logs "set aside until … after 0 failures", as before.

## Build fixtures with the line in
- **The runs:** `NativeBaseGameTests` (the cabin) and `NativeSeedBuildGameTests` (6 tests) together, 3 times on this branch, on main 8bddef4, which has #235's barrier fix. All 7 passed each time.
- **What they logged:** no "Explore … for logs" step, no `No route … on Block{minecraft:barrier}` line, and no known-logs line: the cabin's log gather never came up empty.
- **What this does not show:** these runs check that the line changes nothing. They do not test capM-2's pattern. capM-2 came on #219's branch, with its knowledge cap. Whether #235 ends that loop is for #219's runs once it has main merged. If the gather comes up empty there, the line now says why.
- **Unit build:** `./gradlew build` with the GameTests left out passed.
