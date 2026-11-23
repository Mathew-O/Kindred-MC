"""Per-seed console counts for E-40 iteration 28 (lead's list), beside iter27-counts.py's: F-25c4at1's walk-back hold,
the hemmed-in spells, #455's cast reserve turn, #459's told-of lava and #460's bed take-up."""
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

# Walks back: a goal line with "back the way it came" after a goal line of that player without it, chosen where the line
# says. It ended at the player's next goal line without it; "ended lower" means that line's Y is below where it was
# chosen. "Taken again in the hold": a new walk back by that player within 600 ticks of the lower end, within 3 blocks
# horizontally of where the lower one was chosen and no higher. That is the path #469's hold refuses; its marker was
# removed after the corpus gate, so this is read from the lines. Goal-line counts are raw lines, not spells.
# Tightened (lead, 20:01): a walk back is a native_goto task labelled "...: back the way it came", one per outcome line
# (COMPLETED, BLOCKED, CANCELLED, FAILED); the consoles print no task start line and no position on the outcome line.
# Its begin is the first walk-back goal line of that player after its previous native task outcome (that line's
# position is where it was chosen); its end position is the player's next goal line after the outcome. It counts as
# ended lower only if COMPLETED and that end Y is below the chosen Y. "Taken again" is a later walk-back task whose
# begin is within 600 ticks of that completion, within 3 blocks horizontally of the earlier chosen point, no higher.
OUT=re.compile(r'\]: (\w+) native_\w+ (COMPLETED|BLOCKED|CANCELLED|FAILED)\b.*?(\[[^\]]*\])?$')
GOAL=re.compile(r'\]: (\w+) goal at (-?\d+) (-?\d+) (-?\d+): (.*)$')
WB='back the way it came'
for b in sys.argv[1:]:
    print(f'\n### {b}\n\n| Seed | Walk-back tasks (completed) | Ended lower, completed | Taken again in the hold | Raw proxy: walks back / ended lower / taken again | Hemmed in, spells (min) | Reserve turn lines | Reserve set aside | Lava told of ("Cast: walk to the lava <P> found at"), goal lines | "Lava here" said | Bed take-up lines | Bed left where it stands | Take-up refused |\n'+'| --- '*13+'|')
    summ=json.load(open(f'/home/user/Kindred-MC/run/eval/{b}/summary.json'))['perSeed']
    T=collections.Counter()
    for f in sorted(glob.glob(f'/home/user/Kindred-MC/run/eval/{b}/*/console.txt'),key=lambda p:p.split('/')[-2]):
        seed=f.split('/')[-2]; lines=open(f,errors='replace').read().splitlines()
        c=collections.Counter(); cur={}; holds={}
        begin={}; pend_end={}; thold={}
        for l,k in clock(lines):
            o=OUT.search(l)
            if o:
                who=o[1]; lab=o[3] or ''
                if WB in lab and who in begin:
                    bx,by,bz,bk=begin[who]; c['t']+=1
                    if o[2]=='COMPLETED': c['tc']+=1; pend_end[who]=(bx,by,bz,k)
                begin.pop(who,None)
                continue
            m=GOAL.search(l)
            if m and m[1] in pend_end:
                bx,by,bz,ek=pend_end.pop(m[1])
                if int(m[3])<by: c['tl']+=1; thold[m[1]]=(bx,by,bz,(ek or 0)+600)
            if m and WB in m[5] and m[1] not in begin:
                x,y,z=int(m[2]),int(m[3]),int(m[4]); begin[m[1]]=(x,y,z,k); h=thold.get(m[1])
                if h and k is not None and k<=h[3] and ((x-h[0])**2+(z-h[2])**2)**0.5<=3 and y<=h[1]: c['th']+=1
            if m:
                who,x,y,z,what=m[1],int(m[2]),int(m[3]),int(m[4]),m[5]
                wb=WB in what; prev=cur.get(who)
                if prev and not wb:
                    # the walk back ended here: lower than it was chosen?
                    if prev['y']>y: c['lower']+=1; holds[who]=(prev['x'],prev['y'],prev['z'],(k or 0)+600)
                    cur.pop(who)
                if wb and not prev:
                    c['wb']+=1
                    h=holds.get(who)
                    if h and k is not None and k<=h[3] and ((x-h[0])**2+(z-h[2])**2)**0.5<=3 and y<=h[1]: c['held']+=1
                    cur[who]={'x':x,'y':y,'z':z}
                if 'Up for wood while its last pickaxe still digs' in what: c['reserve']+=1
                if 'Cast: walk to the lava' in what and (' told of at ' in what or ' found at ' in what): c['told']+=1
                if 'Take its bed back up in the morning' in what or 'Back to its bed to take it up' in what: c['bed']+=1
                continue
            if 'found no wood for its reserve in' in l: c['aside']+=1
            elif 'Lava here, if anyone needs it for the cast.' in l: c['lavasaid']+=1
            elif 'left its bed where it stands at' in l: c['left']+=1
            if 'its take-up refused' in l: c['refused']+=1
        hs=sum((p.get('stuckKinds') or {}).get('hemmed in',0) for p in summ[seed]['players'].values())
        c['hem']=hs
        print(f"| {seed} | {c['t']} ({c['tc']}) | {c['tl']} | {c['th']} | {c['wb']} / {c['lower']} / {c['held']} | {hs} ({3*hs}) | {c['reserve']} | {c['aside']} | {c['told']} | {c['lavasaid']} | {c['bed']} | {c['left']} | {c['refused']} |")
        T.update(c)
    print(f"\nTotals: walk-back tasks {T['t']} ({T['tc']} completed), ended lower {T['tl']}, taken again in the hold {T['th']}; raw proxy (an upper bound): walks back {T['wb']}, ended lower {T['lower']}, taken again in the hold {T['held']}, hemmed in {T['hem']} spells ({3*T['hem']} min), reserve turn lines {T['reserve']}, reserve set aside {T['aside']}, lava told-of goal lines {T['told']}, \"Lava here\" {T['lavasaid']}, bed take-up lines {T['bed']}, bed left {T['left']}, take-up refused {T['refused']}.")
