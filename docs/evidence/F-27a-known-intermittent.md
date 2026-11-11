# F-27a — Known-intermittent scenes

Date: 2026-11-11
Agent / branch: claude/corpus-intermittent-1111
Status: in progress (part of F-27a; linked from F-27a.md once merged)

`tools/corpus/known-intermittent.txt` lists scenes that are intermittent on main although the baseline counts them as passes. The report marks a flagged scene on the list as expected. The flag and block rules are unchanged, and a listed scene the confirm step confirms still blocks. The list is cleared at the six-run re-baseline.
- **First entry:** `e40a14-80b7150-8675309-kindred-120887`, a skeleton death in fight-back. It was flagged by the gates of #181 and #126, and each time it failed only part of the time alone (#181: 1 of 3).
- **Check:** report.py on #181's gate results gives the same result as before (flagged 1, confirmed 0, cleared 1, gate passes), plus the line marking the scene as expected.
