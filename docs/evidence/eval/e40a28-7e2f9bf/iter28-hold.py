"""E-40 iteration 28 (lead, 20:02): on the seeds where the tightened hold count was above 5 in 26 or 27, the hemmed-in
spells and what each player does in the 600 ticks after a completed walk back that ended lower (the hold's arming).

A walk back, its begin, its end and "ended lower" are as in iter28-counts.py's tightened count. After each such end, the
first of these within 600 ticks decides the outcome:
- death: a death line for that player;
- new loop: a new walk-back task begun inside the hold's bounds (the walk back the hold refuses, from 28 on);
- otherwise, by where its last goal line in the window stands: out (higher than the walk back's chosen point, or more
  than 3 blocks from it horizontally, so the hold no longer applies) or still there (inside the bounds); each split by
  whether a "step up" or "clear ..." climb goal came in the window.
Usage: python3 -I iter28-hold.py <seeds,comma,separated> <build>...
"""
import re,sys,glob,json,collections,importlib.util
spec=importlib.util.spec_from_file_location('c',__file__.replace('iter28-hold.py','iter28-counts.py'))
src=open(spec.origin).read(); ns={}; exec(src[:src.index('for b in sys.argv[1:]:')],ns)
clock=ns['clock']; GOAL=ns['GOAL']; OUT=ns['OUT']; WB=ns['WB']
DEATH=re.compile(r'\]: (\w+) (was slain|was shot|was blown|was killed|died|drowned|fell|burned|went up in flames|tried to swim|walked into|suffocated|starved|froze|hit the ground|blew up|was squashed|was pricked|was impaled|withered|discovered)')
seeds=sys.argv[1].split(',')
print('| Build | Seed | Hemmed in, spells (min) | Holds armed | Death | Out, after a climb step | Out, directly | New loop | Still there, climbing | Still there |\n'+'| --- '*10+'|')
for b in sys.argv[2:]:
    summ=json.load(open(f'/home/user/Kindred-MC/run/eval/{b}/summary.json'))['perSeed']
    for seed in seeds:
        lines=open(f'/home/user/Kindred-MC/run/eval/{b}/{seed}/console.txt',errors='replace').read().splitlines()
        ev=[]  # (tick, who, kind, data)
        begin={}; pend={}
        for l,k in clock(lines):
            if k is None: continue
            d=DEATH.search(l)
            if d and ' goal at ' not in l: ev.append((k,d[1],'death',None)); continue
            o=OUT.search(l)
            if o:
                who=o[1]; lab=o[3] or ''
                if WB in lab and who in begin:
                    if o[2]=='COMPLETED': pend[who]=(begin[who],k)
                begin.pop(who,None); continue
            m=GOAL.search(l)
            if not m: continue
            who,x,y,z,what=m[1],int(m[2]),int(m[3]),int(m[4]),m[5]
            if who in pend:
                (bx,by,bz,bk),ek=pend.pop(who)
                if y<by: ev.append((ek,who,'armed',(bx,by,bz)))
            newwb=WB in what and who not in begin
            if newwb: begin[who]=(x,y,z,k)
            ev.append((k,who,'goal',(x,y,z,newwb,('step up' in what or ': clear ' in what) and 'Climb toward the surface' in what)))
        ev.sort(key=lambda e:e[0])
        c=collections.Counter()
        for i,(k,who,kind,dat) in enumerate(ev):
            if kind!='armed': continue
            c['armed']+=1; bx,by,bz=dat; climbed=False; res=None; inside=True
            for k2,w2,kd2,d2 in ev[i+1:]:
                if k2>k+600: break
                if w2!=who: continue
                if kd2=='death': res='death'; break
                if kd2=='goal':
                    x,y,z,newwb,climb=d2; inside=not (y>by or ((x-bx)**2+(z-bz)**2)**0.5>3)
                    if newwb and inside: res='loop'; break
                    if climb: climbed=True
            if res is None: res=('out climb' if climbed else 'out') if not inside else ('still climbing' if climbed else 'still')
            c[res]+=1
        hs=sum((p.get('stuckKinds') or {}).get('hemmed in',0) for p in summ[seed]['players'].values())
        print(f"| {b} | {seed} | {hs} ({3*hs}) | {c['armed']} | {c['death']} | {c['out climb']} | {c['out']} | {c['loop']} | {c['still climbing']} | {c['still']} |")
