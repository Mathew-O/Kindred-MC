import sys, re, json, importlib.util, pathlib, collections
spec = importlib.util.spec_from_file_location("score", "/home/user/kmc-table/tools/eval/score.py"); sc = importlib.util.module_from_spec(spec); spec.loader.exec_module(sc)
run, seed, player, a, b = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
seed_dir = pathlib.Path(run) / seed
clock, marks, prev, lines = 0, [], None, []
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
    if body.startswith(player + " ") or (" " + player + " ") in body[:40]: lines.append((when, body))
def tick_at(t):
    for (x, tx), (y, ty) in zip(marks, marks[1:]):
        if x <= t <= y: return tx + (ty - tx) * (t - x) / max(1e-9, y - x)
inside = [(tick_at(w), l) for w, l in lines if (tick_at(w) or -1) >= a and (tick_at(w) or 1e18) <= b]
print(len(inside), "lines")
kinds = collections.Counter(re.sub(r"-?\d+(\.\d+)?", "N", l)[:110] for _, l in inside)
for k, v in kinds.most_common(int(sys.argv[6]) if len(sys.argv) > 6 else 10): print(f"  {v:5d} {k}")
