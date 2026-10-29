"""Prints a compact outline of Java files: the top comment, the type declaration and member declarations."""
import re
import sys

MEMBER = re.compile(r'^\s{4}(?:(?:public|protected|private|static|final|abstract|synchronized|default|sealed)\s+)*'
                    r'(?:record|enum|class|interface|<[^>]+>\s+[\w<>\[\],.? ]+|[\w<>\[\],.? ]+)\s+\w+\s*[({]')
for path in sys.argv[1:]:
    text = open(path, encoding='utf-8').read()
    lines = text.split('\n')
    print(f'=== {path.split("/dev/kindred/")[-1]} ({len(lines)} lines)')
    in_doc = False
    for i, line in enumerate(lines):
        s = line.strip()
        if s.startswith('package') or s.startswith('import') or not s:
            continue
        top_level = not line.startswith(' ')
        if in_doc or (top_level and s.startswith('/**')):
            print('  ' + s[:150])
            in_doc = not s.endswith('*/')
            continue
        if top_level and re.match(r'(public |final |abstract |sealed )*(class|record|interface|enum) ', s):
            print('  ' + s[:160])
            continue
        if MEMBER.match(line) and not re.match(r'\s*(return|new|if|for|while|throw)\b', line):
            prev = lines[i - 1].strip()
            doc = ''
            if prev.endswith('*/'):
                j = i - 1
                while not lines[j].strip().startswith('/**') and j > 0:
                    j -= 1
                doc = ' '.join(l.strip(' */') for l in lines[j:i]).strip()
            print(f'    {i + 1}: {s[:140]}' + (f'   // {doc[:140]}' if doc else ''))
