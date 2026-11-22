# Step 8's stuck time by cause, and its busy-work by commonest step

Date: 2026-11-21
Agent / branch: Claude, LOOPS / `claude/busywork-census-1121`
Status: a diagnostic. No code changes.

**Data:**
- **Step 8:** RUNNER 2's `e40a25s-0d3432b-on` and `-off` (main `0d3432b`, replays on and off).
- **Iteration 24's eight batches:** `e40a24-83321ac`, `e40a24r`, `b`, `c`, `e`, `f`, `g`, `h`.
- **Where they are:** the summaries are on #416's branch (`claude/e40a24-bisect3-1121` at `3400d071`). Step 8's consoles are on
  `claude/corpus-data-e40a25s-1121` at `fe0be8cd`, and iteration 24's on `claude/corpus-data-e40a24-1116`.
- **Classification:** score.py from `3400d071` (sha256 `34cb7ad3…`), its own `stuck_kind`.

**The exit criterion read:** stuck time under a tenth of the run means the team's stuck game minutes are under 12 of the 120.
Each stuck spell is 3 minutes, so a kind's minutes are its spells × 3. This reproduces the counts: step 8 on, 4 of 10; off, 3
of 10.

## Stuck minutes by kind, step 8, per seed

| Seed | On | Off |
| --- | --- | --- |
| 101 | 3 (route 3) | **12** (mine 9, busy 3) |
| 202 | **27** (busy 12, mine 9, water 3, hole 3) | **60** (busy 24, hemmed 18, route 6, refused 3, hole 3, wait 3, mine 3) |
| 20261030 | 3 (mine 3) | 9 (route 6, hole 3) |
| 303 | **24** (mine 15, hole 9) | 3 (refused 3) |
| 31102026 | 3 (hemmed 3) | **15** (hole 15) |
| 404 | 9 (route 3, mine 3, busy 3) | **42** (hemmed 18, mine 15, wait 6, busy 3) |
| 505 | **45** (hemmed 21, mine 6, busy 6, water 3, route 3, hole 3, wait 3) | **39** (hemmed 18, busy 15, water 3, hole 3) |
| 606 | **12** (busy 6, wait 3, mine 3) | **63** (route 45, hemmed 15, mine 3) |
| 707 | **21** (busy 12, water 9) | 9 (busy 6, mine 3) |
| 8675309 | **15** (busy 9, hemmed 3, mine 3) | **15** (hole 12, busy 3) |

Bold: the seed misses the bar.

**Over the 13 seed-batches that miss:** hemmed in 93, busy-work loop 93, mining for a find 66, no walkable route 54, hole or
pit 48, water loop 18, waiting 15, refused target repeated 3.

**Removing one kind alone brings a missing seed-batch under 12:**
- busy-work: 4 (606, 707 and 8675309 on; 101 off);
- mining for a find: 3;
- hole or pit: 2;
- waiting: 1.

So one kind carries 3 seeds on the on arm (busy-work, to 7 of 10), and none does on the off arm.

**Recurrence over all ten batches:** busy-work is the top kind on 8 of 10 seeds. It reaches 6 or more minutes in 5 to 8 of
the 10 batches on 101, 202, 404, 505, 606, 707 and 8675309. Hemmed in recurs on 505 (7 of 10) and on 404 and 606 (4 of 10). Two are
one-batch spikes:
- 606 off's no walkable route 45, which is 1 batch of 10;
- 31102026 off's hole or pit 15.

## Step 8's busy-work by commonest step

Busy-work is score.py's residual bucket: a spell matching no rule whose commonest step, numbers aside, came 6 or more times.
These are minutes, with each spell keyed by that step. On, 48 minutes:

| Minutes | Commonest step |
| --- | --- |
| 9 | `native_goto COMPLETED [Cast: walk up to the lava]` |
| 9 | `native_goto COMPLETED [Cast: dig toward the lava at N, N, N: advance]` |
| 6 | `native_swap COMPLETED [Ready furnace]` |
| 6 | `native_goto COMPLETED [Tunnel past liquid toward iron: advance]` |
| 3 each | clear space for a workstation; Cast: clear; walk to coal ore; picked up dirt; ready a torch; pick up flint (let what falls settle) |

Off, 54 minutes:

| Minutes | Commonest step |
| --- | --- |
| 12 | `native_goto COMPLETED [dig toward elsewhere: advance]` (202 only) |
| 12 | `native_goto COMPLETED [Tunnel past liquid toward iron: advance]` |
| 6 | `native_swap COMPLETED [Ready a torch]` |
| 3 each | picked up cobblestone; Cast: clear; light the way (ready a torch); ready crafting table; Cast: dig toward the lava (settle); Cast: dig toward the lava (advance); drop surplus cobblestone |

**By family, on the seeds that miss:**

| Family | On | Off | Seeds |
| --- | --- | --- | --- |
| The cast (walk up to, dig toward or clear for the lava) | 18 | 6 | 707, 606, 202, 505 |
| Readying a furnace, torch or crafting table, or clearing space for one | 12 | 9 | 202, 606, 8675309, 404 |
| Tunnel past liquid toward iron | 6 | 12 | 505 and 8675309, both arms |
| Dig toward elsewhere | 0 | 12 | 202 off only |
| Other | 9 | 9 | — |

**The cast's biggest spell is 707 on, 12 minutes.**
- Sol's three spells at 58, 36, −103 to −104 step between (58, 36, −103) "walk on", 181 times, and (58, 36, −104) "advance",
  179 times, digging toward the lava at 61, 26, −107.
- That is the tunnel-heading flip F-25c4dj1 (#411) fixes.
- Removing the cast alone brings 707 (21 to 9) and 606 (12 to 9) under the bar on the on arm, about 6 of 10. It brings none
  on the off arm.

**Readying** is four different steps, none over 6 minutes. **Tunnel past liquid**'s "advance" cells move along: it is a slow
tunnel, not an oscillation. Removing it alone leaves 8675309 at 12 on both arms, which still misses.

**Iteration 24 (`83321ac`), for comparison:** busy-work splits into the cast's lava walk and drain (33 of 78 minutes), readying
(21), back to seen iron ore (12) and the bed set-down (6). `83321ac` predates #390 (F-25c4dj), so most of that cast share was
fixed later.

## How it was read

The per-kind census, from each summary's `perSeed[].players[].stuckKinds`:

```python
import json, collections, sys
for b in sys.argv[1:]:
    d = json.load(open(f"{b}/summary.json"))
    for seed, x in d["perSeed"].items():
        c = collections.Counter()
        for p in x["players"].values():
            for k, v in p["stuckKinds"].items(): c[k] += 3 * v
        minutes = sum(p["stuckMinutes"] for p in x["players"].values())
        assert sum(c.values()) == minutes
        print(b, seed, "MISS" if minutes >= 0.1 * x["hours"] * 60 else "ok", minutes, c.most_common())
```

Busy-work by commonest step, from each seed's `console.txt.gz`. The script wraps score.py's `stuck_kind` and records the spell's
commonest step as score.py keys it:

```python
import sys, json, gzip, re, collections, pathlib, tempfile
sys.path.insert(0, sys.argv[1]); import score            # score.py from 3400d071
root, rec = pathlib.Path(sys.argv[2]), []
orig = score.stuck_kind
def spy(lines):
    k = orig(lines)
    steps = collections.Counter(re.sub(r"\(tried elsewhere \d+ times?\)", "", re.sub(r"-?\d+", "N", score.STEP.sub("", l))).strip()
                                for l in lines)
    rec.append((k, steps.most_common(1)))
    return k
score.stuck_kind = spy
for seed in sorted(p for p in root.iterdir() if p.is_dir()):
    r = json.load(open(seed / "eval-result.json")); tmp = pathlib.Path(tempfile.mktemp())
    tmp.write_bytes(gzip.open(seed / "console.txt.gz").read())
    for name, p in r["players"].items():
        rec.clear(); score.stuck_kinds_of(tmp, {name: p})
        for k, top in rec:
            if k == score.BUSY: print(seed.name, name, 3, top[0][0])
```

The totals match the summaries: busy-work 48 minutes on (16 spells) and 54 off (18 spells).
