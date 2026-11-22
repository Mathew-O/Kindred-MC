import json,re,sys,bisect
def ts(l):
    m=re.match(r'\[(\d+):(\d+):(\d+)\]',l); 
    return int(m[1])*3600+int(m[2])*60+int(m[3]) if m else None
def run(b,s):
    d=f'{b}/{s}'; lines=open(f'{d}/console.txt',errors='replace').read().splitlines()
    anchors=[]; day=0; prev=None; T=[]
    for l in lines:
        t=ts(l)
        if t is None: T.append(None); continue
        if prev is not None and t<prev-3600: day+=86400
        prev=t; t+=day; T.append(t)
        if 'Done (' in l and not anchors: anchors.append((t,0))
        m=re.search(r'thinking at tick (\d+)',l)
        if m: anchors.append((t,int(m[1])))
    xs=[a[0] for a in anchors]
    def gm(t):
        i=max(1,min(len(anchors)-1,bisect.bisect_left(xs,t)))
        (t0,k0),(t1,k1)=anchors[i-1],anchors[i]
        k=k0+(k1-k0)*(t-t0)/max(1,t1-t0); return round(k/1200,1)
    ps=json.load(open(f'{b}/summary.json'))['perSeed'][s]['players']
    res=json.load(open(f'{d}/eval-result.json'))['players']
    print(f'== {b} seed {s}  console: run/eval/{d}/console.txt')
    lit=[p['minutesTo'].get('portal_lit') for p in ps.values() if 'portal_lit' in p['minutesTo']]
    print('  portal lit:', min(lit) if lit else 'no')
    for n,p in ps.items():
        mt=p['minutesTo']; r=res[n]
        deaths=[(round(x['tick']/1200,1),x['cause'].replace(n+' ',''),x['stage']) for x in r['deaths']]
        steps=[];last=None
        for i,l in enumerate(lines):
            m=re.search(rf'\]: {n} goal at -?\d+ -?\d+ -?\d+: (.*)$',l)
            if m:
                g=re.sub(r'-?\d+, -?\d+, -?\d+','x,y,z',m[1]); g=re.sub(r': clear x,y,z.*','',g)
                if g!=last: steps.append((gm(T[i]),g)); last=g
        fails=[(gm(T[i]),re.sub(r'^.*?: ','',l)[:150]) for i,l in enumerate(lines) if re.search(rf'\]: {n} native_\w+ (BLOCKED|FAILED).*([Cc]ast|portal|[Ll]ight|flint)',l)]
        print(f'  {n}: obsidian {mt.get("obsidian","never")} min, flint_and_steel? portal_lit {mt.get("portal_lit","no")}; deaths {deaths or 0}; stuck {p.get("stuckMinutes")} min {p.get("stuckKinds") and {k:v for k,v in p["stuckKinds"].items() if v}}')
        print(f'    last 3 goal steps: {steps[-3:]}')
        if fails: print(f'    cast/light failures: {len(fails)}, last: {fails[-1]}')
for b,s in [(x,y) for x in ['e40a25-59f6276','e40a25r-59f6276'] for y in ['202','101','31102026','707']]: run(b,s)
