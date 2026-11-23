"""lastwait.py <console> <result> <player>: the tick of the player's last 'waiting for a feasible step' line not followed
by any other goal line of its own, its place, and the game minutes from it to the run's end (console clock as endidle.py)."""
import re, sys, json
CLOCK = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
console, result, p = sys.argv[1:4]
res = json.load(open(result)); end = res['ticksRun']
marks, goals, prev, day = [], [], None, 0
for raw in open(console, errors='replace'):
    m = CLOCK.match(raw)
    if not m: continue
    t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
    if prev is not None and t < prev - 3600: day += 86400
    prev = t; t += day
    if 'Evaluation run: /tick sprint' in raw: marks.append((t, 0))
    k = re.search(r'Evaluation run: thinking at tick (\d+)', raw) or re.search(r'Evaluation run: (\d+) ticks run', raw)
    if k: marks.append((t, int(k[1])))
    g = re.search(r'INFO\]: ' + p + r' goal at (-?\d+ -?\d+ -?\d+): (.*)$', raw)
    if g: goals.append((t, g[1], g[2]))
marks.sort()
def tick(t):
    for (a, ta), (b, tb) in zip(marks, marks[1:]):
        if a <= t <= b: return ta + (tb - ta) * (t - a) / max(1e-9, b - a)
    return marks[-1][1]
i = len(goals)
while i > 0 and goals[i - 1][1] == goals[-1][1]: i -= 1
t0 = tick(goals[i][0])
steps = sorted(set(g[2][:70] for g in goals[i:]))
print(p, 'still at', goals[-1][1], 'from tick', round(t0), 'to the end:', round((end - t0) / 1200, 1), 'game min;', len(goals) - i, 'lines there:', steps[:3])
