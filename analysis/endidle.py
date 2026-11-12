"""endidle.py <run dir>: per seed and player, idle spells in End: stages (the End goal's Nether and later steps), from the
console alone. A player is in an End: stage from its first 'goal at ... : End:' line. Progress is a completed task
('<player> native_* COMPLETED') or a new position (a 'goal at X Y Z' unlike the last). An idle spell is each full
3600 ticks (the watchdog's 'no progress for 3600 ticks', three game minutes) between two progress events, or between the
last one and the run's end. A gap whose last step was the night's (waiting for daylight, a shelter, sleep or a bed)
is the night, not a stall, and is left out. Ticks come from the console clock, interpolated between '/tick sprint' (0), the
'thinking at tick N' marks and 'N ticks run'."""
import re, sys, os, glob, json
SPAN = 3600
CLOCK = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
GOAL = re.compile(r'INFO\]: (\w+) goal at (-?\d+) (-?\d+) (-?\d+): (.*)$')
DONE = re.compile(r'INFO\]: (\w+) (native_\w+) COMPLETED(.*)$')
NIGHT = re.compile(r'daylight|shelter|Dig in|sleep|asleep|\bbed\b|for the night', re.I)
def seed_report(folder):
    res = json.load(open(os.path.join(folder, 'eval-result.json'))); players = list(res['players'])
    marks, ev, prev, day = [], [], None, 0
    for raw in open(os.path.join(folder, 'console.txt'), errors='replace'):
        m = CLOCK.match(raw)
        if not m: continue
        t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
        if prev is not None and t < prev - 3600: day += 86400
        prev = t; t += day
        if 'Evaluation run: /tick sprint' in raw: marks.append((t, 0))
        k = re.search(r'Evaluation run: thinking at tick (\d+)', raw) or re.search(r'Evaluation run: (\d+) ticks run', raw)
        if k: marks.append((t, int(k[1])))
        g = GOAL.search(raw)
        if g and g[1] in players: ev.append((t, g[1], 'goal', (g[2], g[3], g[4]), g[5])); continue
        d = DONE.search(raw)
        if d and d[1] in players: ev.append((t, d[1], 'done', None, d[2] + d[3]))
    marks.sort()
    def tick(t):
        for (a, ta), (b, tb) in zip(marks, marks[1:]):
            if a <= t <= b: return ta + (tb - ta) * (t - a) / max(1e-9, b - a)
        return marks[-1][1] if marks and t > marks[-1][0] else 0
    end = res['ticksRun']; out = {}
    for p in players:
        pe = [e for e in ev if e[1] == p]
        start = next((i for i, e in enumerate(pe) if e[2] == 'goal' and e[4].startswith('End:')), None)
        if start is None: continue
        progress, last_pos, last_step = [], None, ''
        for e in pe[start:]:
            if e[2] == 'done' or (e[2] == 'goal' and e[3] != last_pos):
                progress.append((tick(e[0]), last_step if e[2] == 'done' else e[4], e[4] or ''))
            if e[2] == 'goal': last_pos = e[3]
            last_step = e[4] or last_step
        progress.append((end, '', ''))
        spells, longest, at, nights = 0, 0, None, 0
        for (a, _, step), (b, _, _) in zip(progress, progress[1:]):
            n = int((b - a) // SPAN)
            if n and NIGHT.search(step): nights += n; continue
            spells += n
            if b - a > longest: longest, at = b - a, a
        out[p] = {'endFrom': round(tick(pe[start][0])), 'idleSpells': spells, 'idleMinutes': 3 * spells,
                  'longestIdleTicks': round(longest), 'longestIdleFrom': round(at) if at is not None else None,
                  'nightSpellsLeftOut': nights}
    return out
for folder in sorted(glob.glob(os.path.join(sys.argv[1], '*', 'eval-result.json'))):
    folder = os.path.dirname(folder); r = seed_report(folder)
    for p, v in r.items(): print(os.path.basename(folder), p, v)
