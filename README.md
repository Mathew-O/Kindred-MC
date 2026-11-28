# E-40 iteration 31, batch b: spell replays for the tunnel's instant-arrival loop

Build e40a31r-0f30534 (main 0f30534c, jar sha256 49110e4bbafb14275c050a84240ee644f3b34668d780fdb55d42a2c3cc727004), scenario goal=nether, ticks=144000.
Copied unchanged from `run/eval/e40a31r-0f30534/<seed>/replays/world/`, for CI GREEN's read of `Digger.tunnel`'s advance:
- 8675309/Echo-12380 (written 08:46:02), at the end of about 38 s of "native_goto COMPLETED [Tunnel past liquid toward iron: advance]" lines.
- 606/Kindred-89280 (08:27:21), a spell with "dig toward elsewhere: advance" bursts.
- 8675309/Kindred-37140 (08:51:16).
Consoles: claude/corpus-data-e40a31-consoles-1128, e40a31-consoles/e40a31r-0f30534-<seed>-console.txt.gz.

Added for F-22c130 (the instant-arrival loop), each captured at or just before a burst of instant "dig toward elsewhere: advance":
- e40a31-0f30534-404/Sol-132260 (batch a, written 06:00:45): Sol at -22, 63, -42, where its burst ran.
- e40a31r-0f30534-606/Kindred-32760 (batch b, 08:14:00): the burst ran at 301, 69, 26 from 08:14:03.
- e40a31-0f30534-8675309/Sol-42180 (batch a, 06:11:09): the burst ran at 20, 32, 83 from 06:12:20.
Batch a is build e40a31-0f30534, the same jar as batch b.
