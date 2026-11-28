"""E-40 iteration 30 (lead, 17:41): for each stuck spell of a build, which merged change's lines fired inside the
spell's window, and the top steps by line count.

A spell is a console line "Goal made no progress for N ticks near x, y, z" naming no player; it is the player whose
eval-result "stuck" entry is nearest in tick (within 400) and position. Its window is the N ticks before the line, by the console clock (ticks
between "thinking at tick N" lines, as in iter27-counts.py); the player's lines in the window are those that begin with
its name. Its family is score.py's (`stuck_kind`, from the player's last 60 lines before the spell).

A change's lines are the string fragments its merge added to the main sources that were not in the old jar's
sources, and that appear in none of the old build's consoles (so a line it only re-added does not count). Two lines
are added for #492, which logs no new phrase of its own when it acts: a hazard-spell line naming a diagonal cell
("northeast" and so on), and a native_retreat outcome whose label names a diagonal.
Usage: python3 -I iter30-spells.py <new-frags.tsv> <old build> <build> [seed ...]
"""
import re,sys,glob,json,collections,importlib.util
here=__file__.rsplit('/',1)[0]
src=open(here+'/iter27-counts.py').read(); ns={}; exec(src[:src.index('def night')],ns); clock=ns['clock']
sys.path.insert(0,'/tmp/claude-0/-home-user-Kindred-MC/18aa727d-695b-578d-a25a-ebede19d41be/scratchpad/wt-blocked/tools/eval')
import score
fr=collections.defaultdict(set)
for l in open(sys.argv[1]):
    pr,f=l.rstrip('\n').split('\t',1); fr[pr].add(f)
old=''.join(open(f,errors='replace').read() for f in glob.glob(f'/home/user/Kindred-MC/run/eval/{sys.argv[2]}/*/console.txt'))
fr={pr:{f for f in fs if f not in old} for pr,fs in fr.items()}; fr={pr:fs for pr,fs in fr.items() if fs}
DIAG=r'(north|south)(east|west)'
b=sys.argv[3]; seeds=sys.argv[4:] or sorted(p.split('/')[-2] for p in glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'))
SPELL=re.compile(r'Goal made no progress for (\d+) ticks near (-?\d+), (-?\d+), (-?\d+)')
STEP=re.compile(r'\]: (\w+) (?:goal at -?\d+ -?\d+ -?\d+: (.*)|(native_\w+) (?:COMPLETED|BLOCKED|CANCELLED|FAILED)\b.*?\[([^\]]*)\])')
rows=[]; per=collections.Counter(); none=0
print('Fragments kept, by change:', {pr:len(fs) for pr,fs in sorted(fr.items())}, '\n')
for seed in seeds:
    f=f'/home/user/Kindred-MC/run/eval/{b}/{seed}/console.txt'; er=json.load(open(f.replace('console.txt','eval-result.json')))
    spells=[(s['tick'],s['x'],s['y'],s['z'],n) for n,p in er['players'].items() for s in p.get('stuck',[])]
    lines=list(clock(open(f,errors='replace').read().splitlines()))
    for i,(l,k) in enumerate(lines):
        m=SPELL.search(l)
        if not m or k is None: continue
        n=int(m[1]); xyz=(int(m[2]),int(m[3]),int(m[4]))
        cand=sorted(spells,key=lambda t:(abs(t[0]-k)+1000*(abs(t[1]-xyz[0])+abs(t[2]-xyz[1])+abs(t[3]-xyz[2]))))
        who=cand[0][4] if cand and abs(cand[0][0]-k)<=400 else '?'
        if who=='?': rows.append((seed,'?','unmatched',f'{n/1200:g}','-','-')); continue
        win=[x for x,kk in lines[max(0,i-20000):i] if kk is not None and k-n<=kk<=k and re.search(rf'\]: {who}\b',x)]
        body=[re.sub(r'^\[[^]]*\] \[[^]]*\]: ','',x) for x in win]
        fam=score.stuck_kind(body[-60:]) if hasattr(score,'stuck_kind') else '?'
        hit=collections.Counter()
        for x in win:
            for pr,fs in fr.items():
                if any(s in x for s in fs): hit[pr]+=1
            if ('hazard spell at' in x and re.search(DIAG,x)) or (' native_retreat ' in x and re.search(DIAG,x.split('[')[-1])): hit['#492']+=1
        steps=collections.Counter()
        for x in win:
            s=STEP.search(x)
            if s: lab=(s[2] or s[4] or '').split(' - ')[-1]; steps[re.sub(r'-?\d+','N',lab)[:70]]+=1
        mins=n/1200
        if hit:
            for pr in hit: per[pr]+=mins
        else: none+=mins
        rows.append((seed,who,fam,f'{mins:g}',', '.join(f'{pr} {c}' for pr,c in hit.most_common()) or 'none','; '.join(f'{s} ({c})' for s,c in steps.most_common(3))))
print('| Seed | Player | Family | Minutes | Changes whose lines fired in the window (lines) | Top three steps (lines) |\n| --- | --- | --- | --- | --- | --- |')
for r in rows: print('| '+' | '.join(r)+' |')
print('\n| Change | Stuck minutes in spells where its lines fired |\n| --- | --- |')
for pr,m in per.most_common(): print(f'| {pr} | {m:g} |')
print(f'| no change\'s line | {none:g} |')
print(f'\nAll spells: {len(rows)}, {sum(float(r[3]) for r in rows):g} min. A spell can count under more than one change.')
