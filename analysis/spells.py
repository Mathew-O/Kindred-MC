"""Every stuck spell of a batch: seed, player, ticks, bucket (score.py's own stuck_kind on its own window),
the player's 10 lines before the spell began, and the step labels inside it."""
import sys, re, json, importlib.util, pathlib
spec = importlib.util.spec_from_file_location("score", sys.argv[1]); sc = importlib.util.module_from_spec(spec); spec.loader.exec_module(sc)
run = pathlib.Path(sys.argv[2]); out = []
NOPROG = re.compile(r"Goal made no progress for (\d+) ticks near (-?\d+), (-?\d+), (-?\d+)")
for seed_dir in sorted(p for p in run.iterdir() if (p / "eval-result.json").exists()):
    res = json.loads((seed_dir / "eval-result.json").read_text())
    players = res["players"]
    clock, marks, watch, lines, prev = 0, [], {}, {n: [] for n in players}, None
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
        a = NOPROG.search(body)
        if a and sc.STUCK_AT.search(body):
            watch.setdefault((int(a[2]), int(a[3]), int(a[4])), []).append((when, int(a[1]), body.split(" ", 1)[0]))
            continue
        name = body.split(" ", 1)[0]
        if name in lines: lines[name].append((when, body))
    def moment(tick):
        for (a, ta), (b, tb) in zip(marks, marks[1:]):
            if ta <= tick <= tb: return a + (b - a) * (tick - ta) / max(1, tb - ta)
        return marks[-1][0] if marks else None
    def tick_at(t):
        for (a, ta), (b, tb) in zip(marks, marks[1:]):
            if a <= t <= b: return ta + (tb - ta) * (t - a) / max(1e-9, b - a)
        return None
    for name, p in players.items():
        for sp in p["stuck"]:
            seen = watch.get((sp["x"], sp["y"], sp["z"]))
            w = seen.pop(0) if seen else None
            when = w[0] if w else moment(sp["tick"])
            span = w[1] if w else 3600  # every watchdog line in both batches says 3600
            before60 = [b for t, b in lines[name] if t <= when][-sc.STUCK_WINDOW:]
            kind = sc.stuck_kind(before60)
            if span is not None:
                start_tick = sp["tick"] - span
                start = moment(start_tick)
            else:
                start_tick, start = None, None
            inside = [b for t, b in lines[name] if start is not None and start <= t <= when]
            before10 = [b for t, b in lines[name] if start is not None and t < start][-10:]
            labels = {}
            for b in inside:
                k = re.sub(r"\(tried elsewhere \d+ times?\)", "", re.sub(r"-?\d+", "N", sc.STEP.sub("", b))).strip()
                labels[k] = labels.get(k, 0) + 1
            out.append({"seed": seed_dir.name, "player": name, "tick": sp["tick"], "startTick": start_tick, "noProgressTicks": span,
                        "at": [sp["x"], sp["y"], sp["z"]], "kind": kind, "watchdogMatched": w is not None,
                        "before10": before10, "insideLines": len(inside), "firstStep": next((b for b in inside if " goal at " in b), None), "firstInside": inside[:3], "labels": sorted(labels.items(), key=lambda kv: -kv[1])})
json.dump(out, open(sys.argv[3], "w"), indent=1)
from collections import Counter
print(len(out), Counter(s["kind"] for s in out), "unmatched watchdog", sum(not s["watchdogMatched"] for s in out))
