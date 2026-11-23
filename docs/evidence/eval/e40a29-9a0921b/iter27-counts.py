"""Per-seed console counts for E-40 iteration 27 (lead's list): E-40a1a hand-overs and F-25c4dl night seals."""
import re,sys,glob,collections,json
TS=re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
def clock(lines):
    """Yields (line, game tick) with the tick interpolated from 'thinking at tick N' lines; 'Done (' is tick 0."""
    pts=[];last=None;tl=[]
    for l in lines:
        m=TS.match(l)
        if not m: tl.append(None); continue
        t=int(m[1])*3600+int(m[2])*60+int(m[3])
        if last is not None and t<last-43200: t+=86400
        last=t; tl.append(t)
        if not pts and 'Done (' in l: pts.append((t,0))
        k=re.search(r'Evaluation run: thinking at tick (\d+)',l)
        if k: pts.append((t,int(k[1])))
    def tick(t):
        for (t0,k0),(t1,k1) in zip(pts,pts[1:]):
            if t<=t1: return k0+(k1-k0)*(t-t0)/max(1,t1-t0)
        (t0,k0),(t1,k1)=pts[-2],pts[-1]; return k0+(k1-k0)*(t-t0)/max(1,t1-t0)
    for l,t in zip(lines,tl):
        yield l,(tick(t) if t is not None and len(pts)>1 else None)
def night(k): return k is not None and 13000 <= k % 24000 < 23000
for b in sys.argv[1:]:
    print(f'\n### {b}\n\n| Seed | Let go (stayed off / six steps failed) | Handed over | Taken up again (by why) | Never taken up again | Shelter sealed for the night | Raw iron at night | Raw iron, all | Night share | First iron pickaxe (min) |\n'+'| --- '*10+'|')
    summ=json.load(open(f'/home/user/Kindred-MC/run/eval/{b}/summary.json'))['perSeed']
    T=collections.Counter()
    for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        seed=f.split('/')[-2]; lines=open(f,errors='replace').read().splitlines()
        lg=collections.Counter(); open_=collections.Counter(); ho=0; up=collections.Counter(); seal=0; ni=0; ai=0
        for l,k in clock(lines):
            m=re.search(r'\]: (\w+) lets go of handing over (.+?): (.*)$',l)
            if m: lg['six steps' if 'six of its steps' in m[3] else 'stayed off']+=1; open_[(m[1],m[2])]+=1; continue
            m=re.search(r'\]: (\w+) takes up handing over (.+?) again: (.*)$',l)
            if m:
                w=m[3]; up['nearer' if 'blocks off' in w else 'kit changed' if 'kit changed' in w else 'holds more' if 'holds more' in w else 'other']+=1
                if open_[(m[1],m[2])]>0: open_[(m[1],m[2])]-=1
                continue
            if re.search(r'\]: \w+ handed over ',l): ho+=1; continue
            if ': its shelter sealed for the night - ' in l: seal+=1; continue
            m=re.search(r'\]: \w+ picked up (\d+) minecraft:raw_iron\b',l)
            if m:
                ai+=int(m[1])
                if night(k): ni+=int(m[1])
        never=sum(open_.values())
        share=f'{100*ni/ai:.0f}%' if ai else '-'
        ps=[q.get('minutesTo',{}).get('iron_pickaxe') for q in summ[seed]['players'].values()]; ps=[x for x in ps if x is not None]
        pick=f'{min(ps):g}' if ps else 'not reached'
        print(f"| {seed} | {sum(lg.values())} ({lg['stayed off']} / {lg['six steps']}) | {ho} | {sum(up.values())} ({', '.join(f'{a} {c}' for a,c in up.items()) or '-'}) | {never} | {seal} | {ni} | {ai} | {share} | {pick} |")
        T['letgo']+=sum(lg.values()); T['ho']+=ho; T['up']+=sum(up.values()); T['never']+=never; T['seal']+=seal; T['ni']+=ni; T['ai']+=ai
    print(f"\nTotals: let go {T['letgo']}, handed over {T['ho']}, taken up again {T['up']}, never taken up again {T['never']}, shelter-sealed lines {T['seal']}, raw iron at night {T['ni']} of {T['ai']} ({100*T['ni']/max(1,T['ai']):.0f}%).")
