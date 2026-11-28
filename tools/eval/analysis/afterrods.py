"""afterrods.py <console> <result> <player> <from_tick> [bucket_min]: the player's game time from a tick to the run's
end, in buckets of game minutes, by the step its goal lines name (stage and step, numbers taken out)."""
import re, sys, json
from collections import Counter, defaultdict
console, result, p, t0 = sys.argv[1:5]; t0 = int(t0); B = int(sys.argv[5]) if len(sys.argv) > 5 else 10
CLOCK = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
res = json.load(open(result)); end = res['ticksRun']
marks, ev, prev, day = [], [], None, 0
for raw in open(console, errors='replace'):
    m = CLOCK.match(raw)
    if not m: continue
    t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
    if prev is not None and t < prev - 3600: day += 86400
    prev = t; t += day
    if 'Evaluation run: /tick sprint' in raw: marks.append((t, 0))
    k = re.search(r'Evaluation run: thinking at tick (\d+)', raw) or re.search(r'Evaluation run: (\d+) ticks run', raw)
    if k: marks.append((t, int(k[1])))
    g = re.search(r'INFO\]: ' + p + r' goal at (-?\d+) (-?\d+) (-?\d+): (.*)$', raw)
    if g: ev.append((t, (int(g[1]), int(g[3])), g[4]))
marks.sort()
def tick(t):
    for (a, ta), (b, tb) in zip(marks, marks[1:]):
        if a <= t <= b: return ta + (tb - ta) * (t - a) / max(1e-9, b - a)
    return marks[-1][1]
pe = [(tick(t), pos, step) for t, pos, step in ev]
pe = [e for e in pe if e[0] >= t0]
buckets = defaultdict(Counter); where = {}
for (a, pos, step), (b, _, _) in zip(pe, pe[1:] + [(end, None, '')]):
    key = re.sub(r'-?\d+', 'N', ' - '.join(step.split(' - ')[:2]))[:80]
    bk = int((a - t0) // (1200 * B))
    buckets[bk][key] += b - a
    where.setdefault(bk, pos)
for bk in sorted(buckets):
    tot = sum(buckets[bk].values())
    top = ', '.join(f'{k} {100 * v / tot:.0f}%' for k, v in buckets[bk].most_common(3))
    print(f'min {bk * B:>3}-{bk * B + B:<3} at {where[bk]}: {top}')
