import sys, re, collections
exec(open('/tmp/claude-0/-home-user-Kindred-MC/22f45916-38a3-553c-8c09-1e84c77b4a57/scratchpad/netprog.py').read().split("for N in (8, 16, 24):")[0])
sand, climb = [], []
for run in sys.argv[1:]:
    for seed_dir in sorted(p for p in pathlib.Path(run).iterdir() if (p / "eval-result.json").exists()):
        for n, (seq, stuck) in load(seed_dir).items():
            for a, b, labels in rspells(seq, 8):
                if any(a - W <= s <= b + W for s in stuck): continue
                waits = sum(1 for l in labels if "Wait for daylight" in l or "daylight" in l)
                share = waits / max(1, len(labels))
                ident = f"{run.split('-')[0]}{'-'+run.split('-')[1] if run.startswith('p157') else ''} {seed_dir.name} {n} {int(a - W)}-{int(b)}"
                if any("Drop surplus sand" in l for l in labels): sand.append((ident, round(share, 2)))
                elif any(re.search(r"Climb toward the surface for (logs|food)", l) for l in labels) and share < 0.5: climb.append((ident, round(share, 2)))
print("Drop surplus sand:", len(sand), "spans; with at least half their lines night waits:", sum(1 for _, s in sand if s >= 0.5))
for i, s in sand: print("  ", i, "night share", s)
print("Climb toward the surface for logs/food, under half night waits:", len(climb))
for i, s in climb: print("  ", i, "night share", s)
