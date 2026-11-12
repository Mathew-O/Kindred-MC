import ast, json, sys
S = sys.argv[1]
rows = {}
for l in open(S + '/endidle-e40c3.txt'):
    seed, p, rest = l.split(' ', 2); rows.setdefault(seed, {})[p] = ast.literal_eval(rest)
j = json.load(open('run/eval/e40c3-2c67d67/summary.json'))
print('| Seed | Furthest | Scorer stuck min (all stages) | End: idle min (players) | Longest End: idle, ticks (player, from tick) |')
print('| --- | --- | --- | --- | --- |')
ts = ti = 0
for s in ['101', '202', '20261030', '303', '31102026', '404', '505', '606', '707', '8675309']:
    v = j['perSeed'][s]; st = sum(p['stuckMinutes'] for p in v['players'].values()); ts += st
    r = rows.get(s, {}); idle = sum(x['idleMinutes'] for x in r.values()); ti += idle
    who = ', '.join('%s %d' % (k, x['idleMinutes']) for k, x in r.items()) or 'no player in End:'
    lg = max(r.items(), key=lambda kv: kv[1]['longestIdleTicks']) if r else None
    longest = '%d (%s, %d)' % (lg[1]['longestIdleTicks'], lg[0], lg[1]['longestIdleFrom']) if lg else '-'
    print('| %s | %s | %d | %d (%s) | %s |' % (s, v['teamFurthest'], st, idle, who, longest))
print('| All | | %d | %d | |' % (ts, ti))
