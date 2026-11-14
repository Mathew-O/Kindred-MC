"""Lines of one corpus scene's player from a runCorpus log: python3 player.py LOG SCENE-ID OUT"""
import re, sys
log, scene, out = sys.argv[1:4]
text = open(log, errors='replace').read().splitlines()
pl = None
for i, l in enumerate(text):
    m = re.search(r'Corpus scene (\S+) placed at (-?\d+), (-?\d+), (-?\d+)', l)
    if m and m.group(1) == scene:
        x, z = int(m.group(2)), int(m.group(4)); start = i
for l in text[start:]:
    m = re.search(r'\]: (\S+?)\[Kindred native player\] logged in .* at \((\S+), (\S+), (\S+)\)', l) or re.search(r'\) (\S+?)\[Kindred native player\] logged in .* at \((\S+), (\S+), (\S+)\)', l)
    if m and abs(float(m.group(2)) - (x + 15.5)) < 2 and abs(float(m.group(4)) - (z + 15.5)) < 3:
        pl = m.group(1); break
lines = [l for l in text[start:] if pl and re.search(r'\b' + re.escape(pl) + r'\b', l)]
# stop at the next batch end
open(out, 'w').write('\n'.join(lines) + '\n')
print(scene, 'player', pl, len(lines), 'lines')
