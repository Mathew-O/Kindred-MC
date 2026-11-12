import sys, re, json, random, collections
sys.argv = [sys.argv[0]] + sys.argv[1:]
exec(open('/tmp/claude-0/-home-user-Kindred-MC/22f45916-38a3-553c-8c09-1e84c77b4a57/scratchpad/netprog.py').read().split("for N in (8, 16, 24):")[0])
EXEMPT = re.compile(r"daylight|shelter|Dig in|Hole up|portal is built and lit|Fight|heal|Rest|Run from|Back off|Take cover|Close in|Sleep|Asleep|bed back up|asking for a way out|by hand|Place a torch|Get out of the water")
rows = []
for run in sys.argv[1:]:
    for seed_dir in sorted(p for p in pathlib.Path(run).iterdir() if (p / "eval-result.json").exists()):
        for n, (seq, stuck) in load(seed_dir).items():
            for a, b, labels in rspells(seq, 8):
                active = [l for l in labels if not EXEMPT.search(l)]
                if len(active) < 0.5 * len(labels) or any(a - W <= s <= b + W for s in stuck): continue
                top = collections.Counter(re.sub(r"-?\d+", "N", l) for l in active).most_common(4)
                pos = [(t, p) for t, k, p, s, l in seq if k == "pos" and a - W <= t <= b]
                rows.append({"run": run, "seed": seed_dir.name, "player": n, "from": int(a - W), "to": int(b), "minutes": round((b - a + W) / 1200, 1),
                             "start": pos[0][1] if pos else None, "end": pos[-1][1] if pos else None, "top": top, "kind": label_kind(active)})
json.dump(rows, open('/tmp/claude-0/-home-user-Kindred-MC/22f45916-38a3-553c-8c09-1e84c77b4a57/scratchpad/ronly.json', 'w'), indent=1)
print(len(rows))
