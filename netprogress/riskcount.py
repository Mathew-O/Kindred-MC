import sys, re, collections
exec(open('/tmp/claude-0/-home-user-Kindred-MC/22f45916-38a3-553c-8c09-1e84c77b4a57/scratchpad/netprog.py').read().split("for N in (8, 16, 24):")[0])
EXEMPT = re.compile(r"daylight|shelter|Dig in|Hole up|portal is built and lit|Fight|heal|Rest|Run from|Back off|Take cover|Close in|Sleep|Asleep|bed back up|asking for a way out|^Done:|Done: |elsewhere|by hand|Place a torch|Get out of the water|fortress|Look round for a way on|Nether|End:|stronghold|blaze|pearl")
BUILD = re.compile(r"\bbuild\b|Build |cabin|hall|scaffold|for the build|Place .* \(build\)|Out of the ground for", re.I)
SMELT = re.compile(r"smelt|furnace|Cook|cook", re.I)
for N in (8, 16):
    c = collections.Counter(); bmin = 0
    examples = []
    for run in sys.argv[1:]:
        for seed_dir in sorted(p for p in pathlib.Path(run).iterdir() if (p / "eval-result.json").exists()):
            for n, (seq, stuck) in load(seed_dir).items():
                hours = 0
                for a, b, labels in rspells(seq, N):
                    active = [l for l in labels if not EXEMPT.search(l)]
                    if len(active) < 0.5 * len(labels): continue
                    hit = any(a - W <= s <= b + W for s in stuck)
                    nb = sum(1 for l in active if BUILD.search(l)); ns = sum(1 for l in active if SMELT.search(l))
                    tag = "agree" if hit else "R only"
                    c[tag] += 1
                    if not hit and nb >= 0.3 * len(active): c["R only, mostly build"] += 1; bmin += (b - a + W) / 1200; examples.append((run, seed_dir.name, n, int(a - W), int(b)))
                    if not hit and ns >= 0.3 * len(active): c["R only, mostly smelt/cook"] += 1
    print(N, dict(c), f"build-span minutes {bmin:.0f}")
    for e in examples[:8]: print("   ", e)
