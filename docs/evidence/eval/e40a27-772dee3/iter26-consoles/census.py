import re,sys,glob,os,collections
TS=re.compile(r'^\[(\d\d):(\d\d):(\d\d)\]')
def secs(l):
    m=TS.match(l); return int(m[1])*3600+int(m[2])*60+int(m[3]) if m else None
P=('Kindred','Sol','Echo')
for b in sys.argv[1:]:
    print(f'\n### {b}\n')
    print('| Seed | Player | Bed crafted (game min) | Wool picked up | Sheep attacks | Mutton picked up | Asleep in a bed |')
    print('| --- | --- | --- | --- | --- | --- | --- |')
    tot=collections.Counter()
    for d in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        seed=d.split('/')[-2]; lines=open(d,errors='replace').read().splitlines()
        anchors=[]; day0=None; last=None
        for l in lines:
            t=secs(l)
            if t is None: continue
            if last is not None and t<last-43200: t+=86400
            last=t
            if day0 is None and 'Done (' in l: anchors.append((t,0)); day0=t
            m=re.search(r'Evaluation run: thinking at tick (\d+)',l)
            if m: anchors.append((t,int(m[1])))
        def gm(t):
            a=anchors
            for (t0,k0),(t1,k1) in zip(a,a[1:]):
                if t<=t1: return (k0+(k1-k0)*(t-t0)/max(1,t1-t0))/1200
            (t0,k0),(t1,k1)=a[-2],a[-1]; return (k0+(k1-k0)*(t-t0)/max(1,t1-t0))/1200
        st={p:dict(craft=[],wool=collections.Counter(),att=0,mut=0,sleep=0) for p in P}
        last=None
        for l in lines:
            t=secs(l)
            if t is None: continue
            if last is not None and t<last-43200: t+=86400
            last=t
            m=re.search(r'\]: (Kindred|Sol|Echo) (.*)',l)
            if not m: continue
            p,r=m[1],m[2]
            if re.match(r'native_craft COMPLETED \[Craft [a-z_ ]*bed\]',r): st[p]['craft'].append(gm(t))
            w=re.match(r'picked up (\d+) minecraft:([a-z_]+)_wool',r)
            if w: st[p]['wool'][w[2]]+=int(w[1])
            if re.match(r'native_attack COMPLETED \[Hunt sheep',r): st[p]['att']+=1
            u=re.match(r'picked up (\d+) minecraft:mutton',r)
            if u: st[p]['mut']+=int(u[1])
            if re.match(r'native_(wait|sleep) COMPLETED \[(Asleep in its bed|Sleep in the bed)',r): st[p]['sleep']+=1
        for p in P:
            s=st[p]; c=', '.join(f'{x:.1f}' for x in s['craft']) or 'never'
            wl=', '.join(f'{k} {v}' for k,v in sorted(s['wool'].items())) or '0'
            print(f"| {seed} | {p} | {c} | {wl} | {s['att']} | {s['mut']} | {s['sleep']} |")
            tot['crafts']+=len(s['craft']); tot['wool']+=sum(s['wool'].values()); tot['att']+=s['att']; tot['mut']+=s['mut']; tot['sleep']+=s['sleep']; tot['players_bed']+=bool(s['craft']); tot['seeds_bed_'+seed]|=bool(s['craft'])
    seeds=sum(1 for k,v in tot.items() if k.startswith('seeds_bed_') and v)
    print(f"\nTotals: beds crafted {tot['crafts']} by {tot['players_bed']} of 30 players, on {seeds} of 10 seeds; wool picked up {tot['wool']}; sheep attacks {tot['att']}; mutton picked up {tot['mut']}; asleep-in-a-bed lines {tot['sleep']}.")
