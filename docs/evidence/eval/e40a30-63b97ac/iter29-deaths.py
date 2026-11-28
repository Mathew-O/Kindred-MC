"""E-40 iteration 29 (lead, 2026-11-23 02:26): deaths read with #471's fight lines, and #475's climb starts.

Per build and seed:
- "Climb to find another way" starts: a goal line with that label after a goal line of that player without it.
- Each death (a vanilla death line for an AI player), and whether it fell in a fight: the player's latest
  "<P> fight starts with <target>: <state>" line with no "<P> fight ends" line between it and the death, or a fight
  that ended within 40 ticks before the death. For a death in a fight: the fight-start state as logged (health, food,
  saturation, armour, weapon, mobs within 8, light and Y), the target, and the "<P> meal held back: ..." lines of that
  player in the 400 ticks before the death.
Ticks come from the console clock, between "thinking at tick N" lines, as in iter27-counts.py.
Usage: python3 -I iter29-deaths.py <build>...
"""
import re,sys,glob,collections,importlib.util
src=open(__file__.replace('iter29-deaths.py','iter27-counts.py')).read(); ns={}; exec(src[:src.index('def night')],ns); clock=ns['clock']
GOAL=re.compile(r'\]: (\w+) goal at (-?\d+) (-?\d+) (-?\d+): (.*)$')
START=re.compile(r'\]: (\w+) fight starts with (.+?): (health .*)$')
END=re.compile(r'\]: (\w+) fight ends (.*)$')
MEAL=re.compile(r'\]: (\w+) meal held back: (.*)$')
DEATH=re.compile(r'\]: (\w+) (was slain by .*|was shot by .*|was blown up by .*|was killed .*|died.*|drowned.*|fell .*|hit the ground .*|burned to death.*|went up in flames.*|tried to swim in lava.*|walked into .*|suffocated .*|starved to death.*|froze to death.*|blew up.*|was squashed .*|was pricked .*|was impaled .*|withered away.*|discovered the floor was lava.*|was fireballed .*|experienced kinetic energy.*)$')
CAST={'Kindred','Sol','Echo'}
climb_rows=[]; death_rows=[]
for b in sys.argv[1:]:
    for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        seed=f.split('/')[-2]; lines=open(f,errors='replace').read().splitlines()
        inclimb={}; starts=0; fight={}; ended={}; meals=collections.defaultdict(list); n=collections.Counter()
        for l,k in clock(lines):
            m=GOAL.search(l)
            if m:
                c='Climb to find another way' in m[5]
                if c and not inclimb.get(m[1]): starts+=1
                inclimb[m[1]]=c; continue
            m=START.search(l)
            if m: fight[m[1]]=(k,m[2],m[3]); ended.pop(m[1],None); continue
            m=END.search(l)
            if m and m[1] in fight: ended[m[1]]=(k,fight.pop(m[1]),m[2]); continue
            if m: continue
            m=MEAL.search(l)
            if m: meals[m[1]].append((k,m[2])); continue
            m=DEATH.search(l)
            if m and m[1] in CAST and ' goal at ' not in l and not re.search(r'\]: \w+ died at ', l):
                who=m[1]; n['deaths']+=1; how=m[2]
                if who in fight: fk,tgt,state=fight.pop(who); open_=True
                elif who in ended and k is not None and ended[who][0] is not None and k-ended[who][0]<=40: fk,tgt,state=ended[who][1]; open_=False
                else:
                    gap=f'not in a fight (last fight ended {round(k-ended[who][0])} ticks before)' if who in ended and k is not None and ended[who][0] is not None else 'not in a fight (no fight before it)'
                    death_rows.append((b,seed,who,round(k or 0),how,gap,'','','')); continue
                ended.pop(who,None); n['in fight']+=1
                ms=[t for kk,t in meals[who] if k is not None and kk is not None and k-400<=kk<=k]
                death_rows.append((b,seed,who,round(k or 0),how,'in a fight' if open_ else 'fight ended just before',tgt,state,'; '.join(ms) if ms else 'none'))
        climb_rows.append((b,seed,starts,n['deaths'],n['in fight']))
print('| Build | Seed | "Climb to find another way" starts | Deaths | In a fight |\n| --- | --- | --- | --- | --- |')
for r in climb_rows: print('| '+' | '.join(map(str,r))+' |')
print('\n| Build | Seed | Player | Tick | Death | Fight | Target | State at the fight\'s start | Meal held back in the 400 ticks before |\n'+'| --- '*9+'|')
for r in death_rows: print('| '+' | '.join(str(x).replace('|','/') for x in r)+' |')
