"""detcmp.py <runA console> <runB console>: per player, the first 'goal at' / 'native_*' line that differs, with the
approximate tick (console clock interpolated between '/tick sprint' = tick 0 and 'N ticks run' = the run's end)."""
import re, sys
PAT = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\] \[Server thread/INFO\]: (\w+) (goal at .*|native_\w+ .*)$')
def load(p):
    seqs, t0, t1, end = {}, None, None, None
    prev, day = None, 0
    for raw in open(p, errors='replace'):
        m = re.match(r'^\[(\d\d):(\d\d):(\d\d)\]', raw)
        if not m: continue
        t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
        if prev is not None and t < prev - 3600: day += 86400
        prev = t; t += day
        if 'Evaluation run: /tick sprint' in raw: t0 = t
        e = re.search(r'Evaluation run: (\d+) ticks run', raw)
        if e: t1, end = t, int(e[1])
        k = PAT.match(raw.rstrip('\n'))
        if k: seqs.setdefault(k[4], []).append((t, k[5]))
    return seqs, t0, t1, end
def tick(t, t0, t1, end):
    if t0 is None or t1 is None or t1 == t0: return None
    return round((t - t0) / (t1 - t0) * end)
a, b = load(sys.argv[1]), load(sys.argv[2])
first = None
for p in sorted(set(a[0]) | set(b[0])):
    sa, sb = a[0].get(p, []), b[0].get(p, []); n = 0
    while n < min(len(sa), len(sb)) and sa[n][1] == sb[n][1]: n += 1
    if n == len(sa) == len(sb):
        print(f"{p}: identical, {n} lines"); continue
    la = sa[n] if n < len(sa) else None; lb = sb[n] if n < len(sb) else None
    ta = tick(la[0], *a[1:]) if la else None; tb = tick(lb[0], *b[1:]) if lb else None
    print(f"{p}: same for {n} lines; first difference at line {n + 1}, about tick {ta} / {tb}")
    print(f"   A: {la[1][:200] if la else '(ends)'}")
    print(f"   B: {lb[1][:200] if lb else '(ends)'}")
    if n > 0: print(f"   last common: {sa[n-1][1][:200]}")
    tt = min(x for x in (ta, tb) if x is not None) if (ta is not None or tb is not None) else None
    if tt is not None and (first is None or tt < first[0]): first = (tt, p)
print("FIRST:", first)
