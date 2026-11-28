"""E-40 iteration 30 (lead, 17:47): stuck minutes inside "walk up to the lava" loops, per seed.

A stuck spell (windows as in iter30-cast.py) is inside the loop when the player's lines in its window hold at least
100 "native_goto COMPLETED [Cast: walk up to the lava]" lines: the walk completing at once and re-issued with no
new goal line, as on 202 Sol and 31102026 Echo in 30 batch 1.
Usage: python3 -I iter30-loop.py <build>...
"""
import re,sys,glob,collections
here=__file__.rsplit('/',1)[0]
src=open(here+'/iter30-cast.py').read(); ns={'__file__':here+'/iter30-cast.py'}; exec(src[:src.index("print('| Build")].replace("args=sys.argv[1:]","args=[]"),ns)
LOOP='native_goto COMPLETED [Cast: walk up to the lava]'
print('| Build | Seed | Stuck minutes | Of them in walk-up loops | Loop spells (player: COMPLETED lines) |\n| --- | --- | --- | --- | --- |')
for b in sys.argv[1:]:
    T=[0,0]
    for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        if not __import__('os').path.exists(f.replace('console.txt','eval-result.json')): continue
        seed=f.split('/')[-2]; lines,w=ns['windows'](f); tot=0; lm=0; sp=[]
        for who,a,e in w:
            mins=(e-a)/1200; tot+=mins
            n=sum(1 for l,k in lines if k is not None and a<=k<=e and LOOP in l and re.search(rf'\]: {who} ',l))
            if n>=100: lm+=mins; sp.append(f'{who}: {n}')
        T[0]+=tot; T[1]+=lm
        print(f"| {b} | {seed} | {tot:g} | {lm:g} | {', '.join(sp) or '-'} |")
    print(f"| {b} | all | {T[0]:g} | {T[1]:g} | |")
