import json,sys,re,subprocess
run,spells=sys.argv[1],sys.argv[2]
sp=json.load(open(spells)); summ=json.load(open(run+'/summary.json'))
for seed in ['505','31102026','303']:
    c=open(f'{run}/{seed}/console.txt',errors='replace').read().splitlines()
    tun=sum(' - Tunnel for water' in l for l in c)
    tunw=sum('native_goto COMPLETED [Tunnel for water' in l for l in c)
    back=sum('COMPLETED [Climb toward the surface for water: back the way it came' in l for l in c)
    climb=sum(' - Climb toward the surface for water' in l for l in c)
    fills=sum('native_bucket COMPLETED [Fill a bucket' in l for l in c)
    s=[x for x in sp if x['seed']==seed]; hp=[x for x in s if x['kind']=='hole or pit']
    hpw=[x for x in hp if any('Fill a bucket with water' in k for k,v in x['labels'][:5])]
    cyc=sum(1 for x in s if any('Tunnel for water' in k for k,v in x['labels'][:5]) and any('Climb toward the surface for water' in k for k,v in x['labels'][:5]))
    ps=summ['perSeed'][seed]
    ts=[v['minutesTo'].get('obsidian') for v in ps['players'].values() if v['minutesTo'].get('obsidian') is not None]
    obs=min(ts) if ts else 'not reached'
    print(f"{seed}: tunnel-steps {tun} (walks {tunw}), climb-for-water steps {climb}, climb-back completed {back}, fills {fills}, spells {len(s)} ({sum(3 for _ in s)} min), hole-or-pit {len(hp)} ({3*len(hp)} min; water-bucket {len(hpw)}), spells with tunnel<->climb cycle {cyc}, obsidian {obs}")
