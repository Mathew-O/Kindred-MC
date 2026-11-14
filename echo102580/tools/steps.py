"""A scene player's goal and action lines, coordinates made scene-relative: python3 steps.py LOG SCENE PLAYERFILE"""
import re,sys
log,scene,pf=sys.argv[1:4]
m=re.search(scene+r' placed at (-?\d+), (-?\d+), (-?\d+)',open(log,errors='replace').read())
o=list(map(int,m.groups()))
def rel(mm):
    nums=[int(v) for v in mm.group(0).replace(',',' ').split()]
    return '('+' '.join(str(n-oo) for n,oo in zip(nums,o))+')'
for l in open(pf):
    if 'goal at' not in l and ' native_' not in l: continue
    l=re.sub(r'^\[\S+\] \[[^]]*\] \(\w+\) \S+ ','',l.strip())
    l=re.sub(r'-?\d{5,},? -?\d+,? -?\d{5,}',rel,l)
    print(l)
