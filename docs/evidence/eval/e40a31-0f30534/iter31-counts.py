"""E-40 iteration 31 (lead, 2026-11-28 04:06): #515's WAITING lines grouped by reason, #520's marker lines, and torch
place-and-take-back laps (#523's loop).

- WAITING: each "WAITING <P> at x, y, z tick N, ... ; steps: <STEP> (<label>): <refusal>[, tunnel: <DIR> <why>; ...]; ..."
  line. Grouped by its set of steps with their refusals (numbers dropped), and separately by each tunnel heading's
  reason ("a drop ahead", "dug corridor, no fresh face", "liquid by", "no step"), counting a reason once per line.
- Marker: "F-25c4dr-marker: <P> at ..., its fill of the lava at ... refused there, no walk up that moves it: <why>",
  grouped by <why> with numbers dropped.
- Torch lap: a player's "native_place COMPLETED [Place a torch at P]" followed by that player's "native_break COMPLETED
  [... P]" for the same cell P, before that player places a torch at P again. Laps are counted per player and cell;
  the busiest cell is shown with its lap count and the label of the break.
Usage: python3 -I iter31-counts.py <build>...
"""
import re,sys,glob,collections
WAIT=re.compile(r'\]: WAITING (\w+) at .*?; steps: (.*)$')
MARK=re.compile(r'F-25c4dr-marker: (\w+) at .*?no walk up that moves it: (.*)$')
PLACE=re.compile(r'\]: (\w+) native_place COMPLETED \[Place a torch at (-?\d+, -?\d+, -?\d+)\]')
BREAK=re.compile(r'\]: (\w+) native_break COMPLETED \[([^\]]*?)(-?\d+, -?\d+, -?\d+)\]')
N=lambda s: re.sub(r'-?\d+','N',s).strip()
groups=collections.Counter(); heads=collections.Counter(); marks=collections.Counter(); rows=[]
for b in sys.argv[1:]:
    for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        seed=f.split('/')[-2]; w=0; mk=0; placed={}; laps=collections.Counter(); lab={}
        for l in open(f,errors='replace'):
            m=WAIT.search(l)
            if m:
                w+=1; steps=[s.strip() for s in re.split(r'; (?=[A-Z_]+ \()',m[2].rstrip('; \n'))]
                groups[' | '.join(N(re.sub(r', tunnel: .*','',s)) for s in steps)]+=1
                why=set()
                for s in steps:
                    if ', tunnel: ' in s:
                        for h in s.split(', tunnel: ',1)[1].split(';'):
                            h=h.strip()
                            if h: why.add(N(re.sub(r'^(NORTH|SOUTH|EAST|WEST) ','',h)))
                for x in why: heads[x]+=1
                continue
            m=MARK.search(l)
            if m: mk+=1; marks[N(m[2])]+=1; continue
            m=PLACE.search(l)
            if m: placed[(m[1],m[2])]=True; continue
            m=BREAK.search(l)
            if m and placed.get((m[1],m[3])): placed[(m[1],m[3])]=False; laps[(m[1],m[3])]+=1; lab[(m[1],m[3])]=N(m[2])
        top=laps.most_common(1)
        per=collections.Counter()
        for (p,_),n in laps.items(): per[p]+=n
        rows.append((b,seed,w,mk,sum(laps.values()),', '.join(f'{p} {per[p]}' for p in ('Kindred','Sol','Echo') if per[p]) or '-',
            f'{top[0][0][0]} at {top[0][0][1]} x{top[0][1]} ({lab[top[0][0]]}...)' if top else '-'))
print('| Build | Seed | WAITING lines | Marker lines | Torch laps | Laps by player | Busiest cell (break label) |\n'+'| --- '*7+'|')
for r in rows: print('| '+' | '.join(map(str,r))+' |')
print('\n| WAITING steps and refusals (tunnel reasons dropped) | Lines |\n| --- | --- |')
for g,n in groups.most_common(): print(f'| {g} | {n} |')
print('\n| Tunnel heading reason (once per line) | Lines |\n| --- | --- |')
for g,n in heads.most_common(): print(f'| {g} | {n} |')
print('\n| Marker reason | Lines |\n| --- | --- |')
for g,n in marks.most_common(): print(f'| {g} | {n} |')
