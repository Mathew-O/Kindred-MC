"""E-40 rung c probe e40c5 (lead, 2026-11-23 07:54): per player, timed against its Nether entry tick (eval-result's
"nether_entered" stage). From the console, with ticks between "thinking at tick N" lines as in iter27-counts.py.

Columns, from the player's goal lines after its entry, each line lasting until its next goal line (capped at 200
ticks; time past the cap, and time with no goal line, counts as idle):
- fights: a label with "Fight ", "fight back" or "Back off from";
- retreats: time inside a hazard spell ("hazard spell at" to "hazard spell over"), whatever the goal;
- search: "Explore", "Make a way", "Cut through", "Look for", "Look round", "Walk to the fortress", "Find a Nether";
- idle: "waiting for a feasible step", a "Wait" label, and the uncovered time above;
- other: any other label.
Freeze: the player's last 600 ticks or more of goal lines within 1 block of one spot to the run's end, with the
feet and floor of its last "hazard spell at" line. Urgent-retreat cancels: outcome lines with "urgent retreat
required", and whether a native_retreat outcome follows within 100 ticks. Search: farthest horizontal reach from the
first goal position after entry, the horizontal distance between successive goal positions, and the number of times
the direction word in "Explore/Make a way/Cut through <dir>" changes. First fortress: the player's first "sees a
fortress" line, or its first goal line "Walk to the fortress" or "... about the fortress" (as e40c4 counted a fortress reached). First rod: eval-result's "blaze_rods" stage. Fights by mob: native_fight outcome lines "[Fight <mob>]"
after entry. Pearls: "picked up N minecraft:ender_pearl" lines. RETURN: a goal line with "Back through the portal";
walked back through: a COMPLETED outcome with that label.
Usage: python3 -I e40c5-counts.py <build>
"""
import re,sys,glob,json,collections
src=open(__file__.replace('e40c5-counts.py','iter27-counts.py')).read(); ns={}; exec(src[:src.index('def night')],ns); clock=ns['clock']
GOAL=re.compile(r'\]: (\w+) goal at (-?\d+) (-?\d+) (-?\d+): (.*)$')
HZ=re.compile(r'\]: (\w+) hazard spell at (.*?) in .*?feet ([^,;]+(?:, [^,;]+)?), floor ([^;]+);')
HZO=re.compile(r'\]: (\w+) hazard spell over ')
OUT=re.compile(r'\]: (\w+) (native_\w+) (COMPLETED|BLOCKED|CANCELLED|FAILED)\b(.*)$')
DIR=re.compile(r'(?:Explore|Make a way|Cut through) (north|south|east|west|up|down)')
CAST=['Kindred','Sol','Echo']
b=sys.argv[1]
rows=[];fightrows=[]
for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
    seed=f.split('/')[-2]; ef=f.replace('console.txt','eval-result.json')
    import os
    if not os.path.exists(ef): rows.append((seed,'-','cut: no eval-result')+('',)*15); continue
    er=json.load(open(ef))
    lines=open(f,errors='replace').read().splitlines(); end=er.get('ticksRun',0)
    for who in CAST:
        st=er['players'].get(who,{}).get('stages',{}); ent=st.get('nether_entered')
        if ent is None: rows.append((seed,who,'not entered')+('',)*15); continue
        c=collections.Counter(); last=None; inhz=False; hzk=None; feet=floor=''; pos=[]; dirs=[]; urg=[]; retk=[]
        fort=None; ret=False; walked=False; tries=collections.Counter(); pearls=0; mobs=collections.Counter()
        for l,k in clock(lines):
            if k is None or k<ent: continue
            m=GOAL.search(l)
            if m and m[1]==who:
                if last: dt=k-last[0]; lab=last[1]; c[lab]+=min(dt,200); c['idle']+=max(0,dt-200)
                w=m[5]; lab=('fights' if re.search(r'Fight |fight back|Back off from',w) else
                    'search' if re.search(r'Explore|Make a way|Cut through|Look for|Look round|Walk to the fortress|Find a Nether',w) else
                    'idle' if re.search(r'waiting for a feasible step|\bWait\b',w) else 'other')
                if inhz: lab='retreats'
                last=(k,lab); pos.append((k,int(m[2]),int(m[3]),int(m[4])))
                d=DIR.search(w)
                if d and (not dirs or dirs[-1]!=d[1]): dirs.append(d[1])
                if 'Back through the portal' in w: ret=True
                if fort is None and re.search(r'Walk to the fortress|about the fortress',w): fort=k
                continue
            m=HZ.search(l)
            if m and m[1]==who: inhz=True; feet,floor=m[3],m[4]; continue
            m=HZO.search(l)
            if m and m[1]==who: inhz=False; continue
            m=OUT.search(l)
            if m and m[1]==who:
                if 'urgent retreat required' in m[4]: urg.append(k)
                if m[2]=='native_retreat': retk.append(k)
                if m[2]=='native_fight':
                    g=re.search(r'\[Fight (\w+)',m[4]); mobs[g[1] if g else '?']+=1
                if 'Back through the portal' in m[4]: tries[m[3]]+=1
                if m[3]=='COMPLETED' and 'Back through the portal' in m[4]: walked=True
                continue
            if fort is None and re.search(rf'\]: {who} sees a fortress at ',l): fort=k
            g=re.search(rf'\]: {who} picked up (\d+) minecraft:ender_pearl',l)
            if g: pearls+=int(g[1])
        if last: c[last[1]]+=min(end-last[0],200); c['idle']+=max(0,end-last[0]-200)
        frz=''
        if pos:
            lk,lx,ly,lz=pos[-1]; i=len(pos)-1
            while i>0 and abs(pos[i-1][1]-lx)<=1 and abs(pos[i-1][2]-ly)<=1 and abs(pos[i-1][3]-lz)<=1: i-=1
            if end-pos[i][0]>=600: frz=f'from {round(pos[i][0]-ent)} after entry at {lx} {ly} {lz}; feet {feet or "?"}, floor {floor or "?"}'
        x0,z0=(pos[0][1],pos[0][3]) if pos else (0,0)
        reach=max(((p[1]-x0)**2+(p[3]-z0)**2)**0.5 for p in pos) if pos else 0
        walk=sum(((a[1]-b2[1])**2+(a[3]-b2[3])**2)**0.5 for a,b2 in zip(pos,pos[1:]))
        fol=sum(1 for u in urg if any(0<=r-u<=100 for r in retk))
        mn=lambda t: f'{t/1200:.1f}'
        rod=st.get('blaze_rods')
        rows.append((seed,who,ent,mn(c['search']),mn(c['fights']),mn(c['retreats']),mn(c['idle']),mn(c['other']),frz or 'no',
            f'{len(urg)} ({fol} followed)',f'{reach:.0f}',f'{walk:.0f}',max(0,len(dirs)-1),
            (round(fort-ent) if fort is not None else 'no'),(rod-ent if rod is not None else 'no'),
            ', '.join(f'{k} {v}' for k,v in mobs.most_common()) or 'none',pearls,f"{'yes' if ret else 'no'} / {', '.join(f'{k2} {v2}' for k2,v2 in tries.items()) or 'none'}"))
print('| Seed | Player | Nether entry tick | Search (min) | Fights (min) | Retreats (min) | Idle (min) | Other (min) | Freeze to the end | Urgent-retreat cancels (retreat within 100) | Farthest reach | Distance walked | Heading changes | First fortress (ticks after entry) | First rod (ticks after entry) | Fights by mob | Pearls picked up | RETURN reached / walk-back attempts by outcome |\n'+'| --- '*18+'|')
for r in rows: print('| '+' | '.join(str(x) for x in r)+' |')
