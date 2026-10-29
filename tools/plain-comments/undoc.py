"""Lists, per file, the method and constructor declarations that have no comment directly above them."""
import re
import sys

DECL = re.compile(r'^\s+(?:(?:public|protected|private|static|final|abstract|synchronized|default)\s+)*'
                  r'(?:<[^>]+>\s+)?[\w<>\[\],.? ]+\s+\w+\s*\([^;]*$')
SKIP = re.compile(r'^\s*(?:return|new|if|else|for|while|switch|case|throw|try|catch)\b')

for path in sys.argv[1:]:
    lines = open(path, encoding='utf-8').read().split('\n')
    out = []
    for i, line in enumerate(lines):
        if not DECL.match(line) or SKIP.match(line) or '=' in line.split('(')[0]:
            continue
        j = i
        while j > 0 and lines[j - 1].lstrip().startswith('@'):
            j -= 1
        above = lines[j - 1].strip() if j > 0 else ''
        if above.endswith('*/') or above.startswith('//'):
            continue
        out.append(f'  {i + 1}: {line.strip()[:130]}')
    print(f'== {path} ({len(out)} undocumented)')
    print('\n'.join(out))
