"""E-40 iteration 30 (lead, 17:44): "Cast: walk up to the lava" and "Cast: step closer" lines per seed and player, in
stuck windows and overall; and for given spells the distinct targets in the window.

A line counts when it names the label: the player's goal line ("goal at ...: ... - Cast: walk up to the lava") or one
of its task outcome lines ("native_goto ... [Cast: walk up to the lava]"), with or without "(tried elsewhere ...)".
Stuck windows are found as in iter30-spells.py: a "Goal made no progress for N ticks" line, matched to the player
whose eval-result stuck entry is nearest in tick (within 400) and position, and the N ticks before it.
Targets: the "toward X, Y, Z" of the player's outcome lines with the label in the window.
Usage: python3 -I iter30-cast.py <build>... [--targets build:seed:player:tick ...]
"""
import re,sys,glob,json,collections
here=__file__.rsplit('/',1)[0]
src=open(here+'/iter27-counts.py').read(); ns={}; exec(src[:src.index('def night')],ns); clock=ns['clock']
LABS={'walk up':'Cast: walk up to the lava','step closer':'Cast: step closer'}
SPELL=re.compile(r'Goal made no progress for (\d+) ticks near (-?\d+), (-?\d+), (-?\d+)')
args=sys.argv[1:]; tg=[]
if '--targets' in args: i=args.index('--targets'); tg=args[i+1:]; args=args[:i]
def windows(f):
    er=json.load(open(f.replace('console.txt','eval-result.json')))
    spells=[(s['tick'],s['x'],s['y'],s['z'],n) for n,p in er['players'].items() for s in p.get('stuck',[])]
    lines=list(clock(open(f,errors='replace').read().splitlines())); w=[]
    for l,k in lines:
        m=SPELL.search(l)
        if not m or k is None: continue
        n=int(m[1]); x,y,z=int(m[2]),int(m[3]),int(m[4])
        c=sorted(spells,key=lambda t:abs(t[0]-k)+1000*(abs(t[1]-x)+abs(t[2]-y)+abs(t[3]-z)))
        if c and abs(c[0][0]-k)<=400: w.append((c[0][4],k-n,k))
    return lines,w
print('| Build | Seed | Player | "walk up to the lava" lines, all | in stuck windows | "step closer" lines, all | in stuck windows | Stuck spells |\n| --- | --- | --- | --- | --- | --- | --- | --- |')
want={(t.split(':')[0],t.split(':')[1]) for t in tg}
keep={}
for b in args:
    for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        seed=f.split('/')[-2]; lines,w=windows(f)
        if (b,seed) in want: keep[(b,seed)]=(lines,w)
        c=collections.Counter()
        for l,k in lines:
            for key,lab in LABS.items():
                if lab in l:
                    m=re.search(r'\]: (\w+) ',l); who=m[1] if m else '?'
                    c[(who,key,'all')]+=1
                    if k is not None and any(p==who and a<=k<=e for p,a,e in w): c[(who,key,'win')]+=1
        for who in ('Kindred','Sol','Echo'):
            ns_=sum(1 for p,a,e in w if p==who)
            print(f"| {b} | {seed} | {who} | {c[(who,'walk up','all')]} | {c[(who,'walk up','win')]} | {c[(who,'step closer','all')]} | {c[(who,'step closer','win')]} | {ns_} |")
for t in tg:
    b,seed,who,tick=t.split(':'); tick=int(tick); lines,w=keep[(b,seed)]
    sp=[x for x in w if x[0]==who and abs(x[2]-tick)<=1]
    if not sp: print(f'\n{t}: no such spell'); continue
    _,a,e=sp[0]; tc=collections.Counter(); goals=collections.Counter()
    for l,k in lines:
        if k is None or not (a<=k<=e) or not re.search(rf'\]: {who} ',l): continue
        if LABS['walk up'] in l or LABS['step closer'] in l:
            m=re.search(r'toward (-?\d+, -?\d+, -?\d+)',l)
            if m: tc[m[1]]+=1
            g=re.search(r'goal at (-?\d+ -?\d+ -?\d+):',l)
            if g: goals[g[1]]+=1
    print(f'\n{t} (window {round(a)} to {round(e)}): {len(tc)} distinct targets; top: '+', '.join(f'{p} x{n}' for p,n in tc.most_common(8))+f'; player stood at {len(goals)} distinct spots; top: '+', '.join(f'{p} x{n}' for p,n in goals.most_common(5)))
