"""Offline: a net-progress watchdog over the kept consoles, against the watchdog's own spells.
R(N): over a 3600-tick window, no gain (a stage change, a craft, a smelt, a pickup of a rarer route item) and every
position within N blocks of where the window began. Positions are the "goal at x y z" lines; ticks from the run's marks."""
import sys, re, json, importlib.util, pathlib, collections, math
spec = importlib.util.spec_from_file_location("score", "/home/user/kmc-table/tools/eval/score.py"); sc = importlib.util.module_from_spec(spec); spec.loader.exec_module(sc)
GOAL = re.compile(r"^(\w+) goal at (-?\d+) (-?\d+) (-?\d+): ([^:]+):(.*)$")
GAIN = re.compile(r"^(\w+) (?:native_craft COMPLETED|native_smelt COMPLETED|picked up \d+ minecraft:(?:raw_iron|iron_ingot|diamond|obsidian|flint|coal|gold_ingot|ender_pearl|blaze_rod|string|feather|white_wool|\w+_wool|bucket|water_bucket|lava_bucket))")
W = 3600
ACT = re.compile(r"^(\w+) native_\w+ (?:COMPLETED|BLOCKED|FAILED|CANCELLED)[^\[]*\[(.*)\]")
def load(seed_dir):
    res = json.loads((seed_dir / "eval-result.json").read_text()); players = res["players"]
    clock, marks, prev = 0, [], None
    ev = {n: [] for n in players}
    for raw in (seed_dir / "console.txt").read_text(encoding="utf-8", errors="replace").splitlines():
        st = sc.CLOCK.match(raw)
        if st:
            now = int(st[1]) * 3600 + int(st[2]) * 60 + int(st[3])
            if prev is not None and now < prev - 3600: clock += 86400
            prev = now
        when = clock + (prev or 0)
        body = raw.split("]: ", 1)[-1]
        if "Evaluation run: /tick sprint" in body: marks.append((when, 0))
        m = sc.TICK_MARK.search(body)
        if m: marks.append((when, int(m[1])))
        g = GOAL.match(body)
        if g and g[1] in ev: ev[g[1]].append((when, "pos", (int(g[2]), int(g[3]), int(g[4])), g[5].strip(), g[6].strip())); continue
        a = GAIN.match(body)
        if a and a[1] in ev: ev[a[1]].append((when, "gain", None, None, body)); continue
        c = ACT.match(body)
        if c and c[1] in ev: ev[c[1]].append((when, "act", None, None, c[2]))
    def tick_at(t):
        for (a, ta), (b, tb) in zip(marks, marks[1:]):
            if a <= t <= b: return ta + (tb - ta) * (t - a) / max(1e-9, b - a)
        return None
    out = {}
    for n, es in ev.items():
        seq = [(tick_at(w), k, p, s, l) for w, k, p, s, l in es]
        out[n] = ([e for e in seq if e[0] is not None], [s["tick"] for s in players[n].get("stuck", [])])
    return out
def rspells(seq, N):
    """Spans of ticks where the window behind is flagged by R(N)."""
    pos = [(t, p, s, l) for t, k, p, s, l in seq if k == "pos"]
    gains = [t for t, k, *_ in seq if k == "gain"]
    stage_change = [pos[i][0] for i in range(1, len(pos)) if pos[i][2] != pos[i - 1][2]]
    marks = sorted(gains + stage_change)
    flagged, j0, g = [], 0, 0
    import bisect
    for i, (t, p, s, l) in enumerate(pos):
        if t < W: continue
        while pos[j0][0] < t - W: j0 += 1
        if j0 == 0 and pos[0][0] > t - W + 600: continue  # no position at the window's start
        k = bisect.bisect_left(marks, t - W)
        if k < len(marks) and marks[k] <= t: continue
        start = pos[j0][1]
        far = max(math.dist(start, q[1]) for q in pos[j0:i + 1])
        if far < N: flagged.append((t, l))
    acts = [(t, l) for t, k, p, s, l in seq if k == "act"]
    spans = []
    for t, l in flagged:
        if spans and t - spans[-1][1] <= 200: spans[-1][1] = t; spans[-1][2].append(l)
        else: spans.append([t, t, [l]])
    for span in spans:
        span[2] = span[2] + [l for t, l in acts if span[0] - W <= t <= span[1]]
    return spans
def label_kind(labels):
    text = " | ".join(labels)
    for name, pat in [("night/shelter", r"daylight|shelter|Dig in|Hole up|nothing to do before daylight"), ("build", r"^Build|build|Place .* for the|cabin|hall"),
                      ("smelt/cook", r"smelt|Smelt|furnace|Cook|cook"), ("rescue/asking", r"asking for a way out"), ("waiting team", r"team|teammate"),
                      ("fight/heal", r"Fight|heal|Rest|Run from|Back off"), ("table loop", r"crafting table"), ("water", r"for water")]:
        if re.search(pat, text): return name
    return "other: " + collections.Counter(re.sub(r"-?\d+", "N", l) for l in labels).most_common(1)[0][0][:70]
for N in (8, 16, 24):
    tot = collections.Counter(); kinds = collections.Counter(); caught = collections.Counter(); minutes = 0.0; hours = 0.0
    for run in sys.argv[1:]:
        for seed_dir in sorted(p for p in pathlib.Path(run).iterdir() if (p / "eval-result.json").exists()):
            for n, (seq, stuck) in load(seed_dir).items():
                if seq: hours += (seq[-1][0] - seq[0][0]) / 72000
                spans = rspells(seq, N)
                for a, b, labels in spans:
                    hit = any(a - W <= s <= b + W for s in stuck)
                    tot["agree" if hit else "R only"] += 1
                    if not hit: kinds[label_kind(labels)] += 1; minutes += (b - a + W) / 1200
                for s in stuck:
                    caught["caught" if any(a - W <= s <= b + W for a, b, _ in spans) else "missed"] += 1
    print(f"N={N}: R spells {dict(tot)}; watchdog spells {dict(caught)}; R-only {minutes:.0f} game-min over {hours:.0f} player-hours")
    for k, v in kinds.most_common(12): print(f"   {v:4d}  {k}")

print("\n--- with the watchdog's own exemptions (night in, goal done, passive steps, sleep/bed) applied ---")
EXEMPT = re.compile(r"daylight|shelter|Dig in|Hole up|portal is built and lit|Fight|heal|Rest|Run from|Back off|Take cover|Close in|Sleep|Asleep|bed back up|asking for a way out|^Done:|Done: |elsewhere|by hand|Place a torch|Get out of the water")
for N in (8, 16):
    tot = collections.Counter(); kinds = collections.Counter(); minutes = 0.0; per = collections.Counter()
    for run in sys.argv[1:]:
        for seed_dir in sorted(p for p in pathlib.Path(run).iterdir() if (p / "eval-result.json").exists()):
            for n, (seq, stuck) in load(seed_dir).items():
                for a, b, labels in rspells(seq, N):
                    active = [l for l in labels if not EXEMPT.search(l)]
                    if len(active) < 0.5 * len(labels): continue
                    hit = any(a - W <= s <= b + W for s in stuck)
                    if hit: tot["agree"] += 1; continue
                    tot["R only"] += 1; minutes += (b - a + W) / 1200
                    kinds[label_kind(active)] += 1; per[(run[:12], seed_dir.name, n)] += 1
    print(f"N={N}: {dict(tot)}; R-only {minutes:.0f} game-min")
    for k, v in kinds.most_common(14): print(f"   {v:4d}  {k}")
