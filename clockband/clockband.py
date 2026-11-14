"""Corpus batches as CorpusGameTests makes them, and each batch's clock-setter: clockband.py SCENES_DIR [RUN_LOG]"""
import json, math, os, re, sys
d = sys.argv[1]
scenes = {f[:-5]: json.load(open(os.path.join(d, f))) for f in os.listdir(d) if f.endswith('.json')}
def key(s):
    if 'timeOfDay' not in s: return '~'
    t = s['timeOfDay'] % 24000
    night = 13000 <= t < 23000
    return '%s/%s/%s/%s/%05d' % (s.get('difficulty', ''), str(bool(s.get('thundering'))).lower(), str(bool(s.get('raining'))).lower(), str(night).lower(), t)
order = sorted(scenes, key=lambda i: (key(scenes[i]), i))
batches, group, n = [], None, 10
for i in order:
    k = key(scenes[i]); g = k[:max(0, k.rfind('/'))]
    if n >= 10 or g != group: batches.append([]); n = 0; group = g
    n += 1; batches[-1].append(i)
def angle(t):
    f = (t / 24000.0 - 0.25) % 1.0; g = 0.5 - math.cos(f * math.pi) / 2.0; return (f * 2.0 + g) / 3.0
def dark(t, rain, thunder):
    x = 1.0 - (math.cos(angle(t) * math.pi * 2) * 2.0 + 0.5); x = min(max(x, 0.0), 1.0); x = 1.0 - x
    x *= 1.0 - rain * 5 / 16.0; x *= 1.0 - thunder * 5 / 16.0; return int((1.0 - x) * 11.0) >= 4
def night_in(t, ticks, rain, thunder):  # any tick of [t, t+ticks] at night, by vanilla isNight
    return any(dark((t + k) % 24000, rain, thunder) for k in range(0, ticks + 1, 20))
if len(sys.argv) > 2:  # check: placement order in a run log, by batch
    log = open(sys.argv[2], errors='replace').read()
    placed = re.findall(r"Running test batch '(corpus_\d+):0'|Corpus scene (\S+) placed", log)
    got, cur = [], None
    for b, sid in placed:
        if b: got.append([])
        elif sid: got[-1].append(sid)
    print('batches computed', len(batches), 'in log', len(got), 'same membership and order:', [b for b in batches if b] == got)
    sys.exit()
band = lambda t: 12000 <= t % 24000 < 13000
own_band = sorted(i for i in scenes if 'timeOfDay' in scenes[i] and band(scenes[i]['timeOfDay']))
rows = []
for bi, b in enumerate(batches):
    timed = [i for i in b if 'timeOfDay' in scenes[i]]
    if not timed: continue
    setter = timed[-1]; st = scenes[setter]['timeOfDay'] % 24000
    for i in timed:
        s = scenes[i]; t = s['timeOfDay'] % 24000; ticks = s.get('ticks', 1200)
        r, th = int(bool(s.get('raining'))), int(bool(s.get('thundering')))
        rows.append((bi, i, t, setter, st, ticks, night_in(t, ticks, r, th), night_in(st, ticks, r, th)))
print('scenes', len(scenes), 'batches', len(batches))
print('A. own recorded time in [12000,13000):', len(own_band))
for i in own_band: print('   ', i, scenes[i]['timeOfDay'] % 24000)
setb = [r for r in rows if band(r[4])]
print('B. scenes whose batch clock-setter is in [12000,13000):', len(setb), 'in', len({r[0] for r in setb}), 'batches')
for bi in sorted({r[0] for r in setb}):
    m = [r for r in setb if r[0] == bi]
    print('    corpus_%d setter %s (%d): %s' % (bi, m[0][3], m[0][4], ', '.join('%s (%d)' % (r[1], r[2]) for r in m)))
flip = [r for r in rows if r[6] != r[7]]
print('C. scenes whose night-or-not over their own ticks differs between their own time and their setter\'s:', len(flip))
for r in flip: print('    corpus_%d %s own %d -> setter %d (%s): %s' % (r[0], r[1], r[2], r[4], r[3], 'day->night' if r[7] else 'night->day'))
