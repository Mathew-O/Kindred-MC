import re,sys,gzip
pat=re.compile(r'^\[(\d\d):(\d\d):(\d\d)\].*INFO\]: (\w+) native_goto (BLOCKED: No walkable route|BLOCKED: Route to)')
for p in sys.argv[1:]:
    op=gzip.open if p.endswith('.gz') else open
    last={};n={};near={};kinds={}
    for line in op(p,'rt',encoding='utf-8',errors='replace'):
        if '[from factionCandidates' not in line: continue
        m=pat.search(line)
        if not m: continue
        t=int(m[1])*3600+int(m[2])*60+int(m[3]); who=m[4]
        if who in last:
            d=(t-last[who])%86400
            if d<=2: near[who]=near.get(who,0)+1
        last[who]=t; n[who]=n.get(who,0)+1
        kinds[(who,m[5])]=kinds.get((who,m[5]),0)+1
    print(p); 
    for w in sorted(n): print(f"  {w}: refusals {n[w]} (No walkable route {kinds.get((w,'BLOCKED: No walkable route'),0)}, Route to {kinds.get((w,'BLOCKED: Route to'),0)}), within 2 s of previous {near.get(w,0)}")
