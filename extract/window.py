"""window.py <console.txt> <player> <from tick> <to tick>: the player's console lines between two run ticks, the tick of
each line placed on the console clock between the run's marks ("/tick sprint" = 0, "thinking at tick N"), about +-100."""
import re, sys
CLOCK = re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
def load(path):
    rows, marks, day, prev = [], [], 0, None
    for raw in open(path, errors='replace'):
        m = CLOCK.match(raw)
        if not m: continue
        t = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
        if prev is not None and t < prev - 3600: day += 86400
        prev = t; t += day
        if 'Evaluation run: /tick sprint' in raw: marks.append((t, 0))
        k = re.search(r'thinking at tick (\d+)', raw) or re.search(r'Evaluation run: (\d+) ticks run', raw)
        if k: marks.append((t, int(k[1])))
        rows.append((t, raw.rstrip('\n')))
    return rows, marks
def wall(tick, marks):
    for (a, ta), (b, tb) in zip(marks, marks[1:]):
        if ta <= tick <= tb: return a + (b - a) * (tick - ta) / max(1, tb - ta)
    return marks[-1][0]
def window(rows, marks, player, t0, t1, pad=1):
    w0, w1 = wall(t0, marks) - pad, wall(t1, marks) + pad
    return [r for t, r in rows if w0 <= t <= w1 and (']: ' + player + ' ') in r]
if __name__ == '__main__':
    rows, marks = load(sys.argv[1])
    print('\n'.join(window(rows, marks, sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))))
