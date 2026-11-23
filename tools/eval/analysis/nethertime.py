"""nethertime.py <console.txt> <eval-result.json>: per player who entered the Nether, where the game time after entry
went, by the step each goal line names, from the console alone (the console clock, as endidle.py: interpolated between
'/tick sprint' (0), 'thinking at tick N' and 'N ticks run'). An interval is from one of the player's goal lines to its
next (or the run's end), charged to the earlier line's step. A position jump of more than 48 blocks between two lines
is reported (a portal crossing would show one)."""
import re, sys, json
from collections import Counter
CLOCK = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
GOAL = re.compile(r'INFO\]: (\w+) goal at (-?\d+) (-?\d+) (-?\d+): (.*)$')
def bucket(step):
    s = step
    if re.search(r'waiting for a feasible step|started nothing', s): return 'idle: waiting for a feasible step'
    if re.search(r'daylight|shelter|Dig in|sleep|\bbed\b|for the night', s, re.I): return 'idle: night'
    if re.search(r'retreat|urgent|flee|escape|get away|back off', s, re.I): return 'retreat'
    if re.search(r'fight back|Fight |\bfight\b|Shoot|Strike', s): return 'fight'
    if 'inventory full' in s: return 'inventory'
    if re.search(r'Find a Nether fortress', s):
        for k in ('Cut through', 'Explore', 'Climb above the lava sea', 'Make a way', 'Look round'):
            if k in s: return 'search: ' + k
        return 'search: other'
    if 'Fight blaze' in s or 'blaze' in s.lower(): return 'blaze'
    if re.search(r'pearl|enderm', s, re.I): return 'pearls'
    if re.search(r'gold', s, re.I): return 'gold'
    if re.search(r'food|eat|animals|hoglin', s, re.I): return 'food'
    return 'other: ' + re.sub(r'-?\d+', 'N', s.split(' - ')[0])[:60]
def main(console, result):
    res = json.load(open(result)); players = res['players']; end = res['ticksRun']
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
        g = GOAL.search(raw)
        if g and g[1] in players: ev.append((t, g[1], (int(g[2]), int(g[3]), int(g[4])), g[5]))
    marks.sort()
    def tick(t):
        for (a, ta), (b, tb) in zip(marks, marks[1:]):
            if a <= t <= b: return ta + (tb - ta) * (t - a) / max(1e-9, b - a)
        return marks[-1][1] if marks and t > marks[-1][0] else 0
    out = {}
    for p, v in players.items():
        entered = v['stages'].get('nether_entered')
        if entered is None: continue
        pe = [(tick(t), pos, step) for t, n, pos, step in ev if n == p]
        pe = [e for e in pe if e[0] >= entered]
        spent, jumps = Counter(), []
        for (a, pa, step), (b, pb, _) in zip(pe, pe[1:] + [(end, None, '')]):
            spent[bucket(step)] += b - a
            if pb is not None and max(abs(pa[0] - pb[0]), abs(pa[2] - pb[2])) > 48:
                jumps.append((round(b), pa, pb))
        total = end - entered
        out[p] = {'entered': entered, 'minutes': round(total / 1200, 1),
                  'byStep': {k: round(100 * x / total, 1) for k, x in spent.most_common()},
                  'jumps': jumps[:5], 'jumpsCount': len(jumps)}
    print(json.dumps(out, indent=1))
main(sys.argv[1], sys.argv[2])
