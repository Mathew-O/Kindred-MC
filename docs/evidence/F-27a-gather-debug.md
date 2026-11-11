# F-27a — The gather's logs-kept-out line

Date: 2026-11-11
Agent / branch: claude/gather-debug-1111
Status: in progress (part of F-27a; linked from F-27a.md once merged)

For seed 110's bamboo loop. In `diag-factions110` Kindred spent four hours beside a bamboo thicket with a tree in it and no "Mine logs" step. Replays of that spot start the goal's memory fresh and pass, so only a live run can say what kept the tree's logs out.

- **The line:** logged when the player is in a counted stuck spell, its gather for logs found nothing, and logs stand within 8 blocks. It fires at most once a spell. It gives the counts known, set aside, given up and unreachable. Then, for up to eight logs, it gives the first rule that kept each one out: not known, set aside until when and after how many failures, unreachable from where, dangerous, beyond the step's radius, the step's own filter, not harvestable, may not work there, above its trunk reach, built in, or no trunk foot.
- **Diagnostic only:** no decision reads it, and the gather's choice is unchanged.
- **Fixture:** `NativeLogsKeptOutGameTests` (staged). A pen of bedrock with a jungle log three high inside it, the logs set aside by the fixture, and a 200-tick stuck threshold. The first version, on open grass, failed: the player explored away, every new piece of ground counted as progress, and no spell came. Penned, it passed 3 of 3. Each run logged one line per spell or fewer (1 for 1, 1 for 1, 1 for 2), each naming 3 logs "set aside until … after 0 failures".
- **After merging main (5c11143):** the fixture passed 3 of 3 again, with 1 line for 1 spell, 1 for 2 and 1 for 2.
