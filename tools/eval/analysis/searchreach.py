"""searchreach.py <console> <result> <player>: over the player's 'Find a Nether fortress' lines, its first place, the
farthest it got from there (horizontal), the net distance at its last such line, the path walked (sum of horizontal
steps between its lines), and how often the searched direction (north/south/east/west in the step) changed."""
import re, sys, json, math
console, result, p = sys.argv[1:4]
pts, dirs = [], []
for raw in open(console, errors='replace'):
    g = re.search(r'INFO\]: ' + p + r' goal at (-?\d+) (-?\d+) (-?\d+): (.*)$', raw)
    if not g or 'Find a Nether fortress' not in g[4]: continue
    pts.append((int(g[1]), int(g[3])))
    d = re.search(r'\b(north|south|east|west)\b', g[4])
    if d and (not dirs or dirs[-1] != d[1]): dirs.append(d[1])
if not pts: print(p, 'no search lines'); sys.exit()
x0, z0 = pts[0]
far = max(math.hypot(x - x0, z - z0) for x, z in pts)
walked = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))
from collections import Counter
print(p, f'from {x0},{z0}: farthest {far:.0f}, net {math.hypot(pts[-1][0]-x0, pts[-1][1]-z0):.0f}, walked {walked:.0f} over {len(pts)} lines; direction changes {len(dirs)-1}; by direction', dict(Counter(dirs)))
