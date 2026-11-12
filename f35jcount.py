import re,sys,json,os,gzip
pat=re.compile(r'INFO\]: (\w+) (native_\w+) (COMPLETED|BLOCKED: No walkable route|BLOCKED: Route to|CANCELLED|BLOCKED)')
for run in sys.argv[1:]:
    seed=[d for d in os.listdir(run) if d.isdigit()][0]
    p=os.path.join(run,seed,'console.txt.gz')
    c={}
    for line in gzip.open(p,'rt',encoding='utf-8',errors='replace'):
        if '[from factionCandidates' not in line: continue
        m=pat.search(line)
        if not m: continue
        k=(m.group(2),m.group(3)); c[k]=c.get(k,0)+1
    g=lambda a,b:c.get((a,b),0)
    asked=g('native_goto','COMPLETED')+g('native_goto','BLOCKED: No walkable route')+g('native_goto','BLOCKED: Route to')+g('native_goto','CANCELLED')
    print(os.path.basename(run.rstrip('/')),seed,'asked',asked,'completed',g('native_goto','COMPLETED'),'noroute',g('native_goto','BLOCKED: No walkable route'),'routeblocked',g('native_goto','BLOCKED: Route to'),'cancelled',g('native_goto','CANCELLED'),'otherBLOCKED',g('native_goto','BLOCKED'),'faction_completed',g('native_faction','COMPLETED'))
    print('   all:',sorted(c.items()))
