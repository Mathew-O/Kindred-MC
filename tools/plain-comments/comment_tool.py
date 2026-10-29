"""Adds plain-language comments to Java files from a JSON spec, changing nothing but comments (see README.md).

Spec: {"path/File.java": {"class": "plain summary", "methods": [["unique text on the declaration line", "comment"], ...]}}

- "class": becomes the first paragraph of the top-level type's Javadoc ("In plain terms: ..."); an
  existing Javadoc is kept below it under "Technical:". A file already carrying "In plain terms" is left.
- "methods": a one-paragraph Javadoc is put above the declaration line containing the text (above its
  annotations), at its indentation, unless a Javadoc or comment already sits directly above it.
Every run checks the file with comments stripped is unchanged. Usage: python3 -I comment_tool.py spec.json
"""
import json
import re
import sys
import textwrap

WIDTH = 110
# A line that starts its own item (HTML, a tag, a numbered or bulleted point) is never merged into the one above.
LIST_ITEM = re.compile(r' \* (?:<|@|\d+[.)] |[-*•] )')


def strip_comments(src):
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            while i < n and src[i] != '\n':
                i += 1
        elif src.startswith('/*', i):
            j = src.index('*/', i + 2)
            i = j + 2
        elif src[i] in '"\'':
            if src.startswith('"""', i):
                j = src.index('"""', i + 3)
                out.append(src[i:j + 3]); i = j + 3; continue
            q = src[i]; j = i + 1
            while src[j] != q:
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        else:
            out.append(src[i]); i += 1
    return re.sub(r'\s+', ' ', ''.join(out)).strip()


def wrap(text, indent, first='', rest=''):
    lines = []
    for para in text.split('\n\n'):
        body = textwrap.wrap(para, WIDTH - len(indent) - 3, break_long_words=False, break_on_hyphens=False)
        lines.extend(body)
        lines.append('')
    while lines and lines[-1] == '':
        lines.pop()
    return [f'{indent} *' + (f' {l}' if l else '') for l in lines]


def check(text):
    for bad in ('*/', '\\u', '/*'):
        if bad in text:
            raise SystemExit(f'comment text may not contain {bad!r}: {text[:60]}')


TYPE_DECL = re.compile(r'^(?:(?:public|final|abstract|sealed|non-sealed|strictfp)\s+)*(?:class|record|interface|enum|@interface)\s+\w+')


def add_class_doc(lines, summary):
    check(summary)
    if any('In plain terms' in l for l in lines):
        return lines
    idx = next(i for i, l in enumerate(lines) if TYPE_DECL.match(l))
    # Annotations directly above the declaration stay between the Javadoc and the type.
    top = idx
    while top > 0 and lines[top - 1].lstrip().startswith('@'):
        top -= 1
    plain = wrap('In plain terms: ' + summary, '')
    if top > 0 and lines[top - 1].strip().endswith('*/'):
        end = top - 1
        start = end
        while not lines[start].lstrip().startswith('/**'):
            start -= 1
            if start < 0 or lines[start].strip() == '' :
                raise SystemExit('unexpected class comment shape')
        old = lines[start:end + 1]
        if len(old) == 1:  # /** text */
            body = old[0].strip()[3:-2].strip()
            tech = [f' * {l}' for l in textwrap.wrap(body, WIDTH - 3, break_long_words=False, break_on_hyphens=False)]
        else:
            first = old[0].strip()[3:].strip()
            tech = ([f' * {first}'] if first else []) + [l if l.strip() != '*/' else None for l in old[1:]]
            tech = [l for l in tech if l is not None]
            if tech and tech[-1].rstrip().endswith('*/'):
                last = tech[-1].rstrip()[:-2].rstrip()
                tech[-1] = last if last.strip() != '*' else None
                tech = [l for l in tech if l is not None]
        if tech:
            tech[0] = tech[0].replace(' * ', ' * Technical: ', 1) if tech[0].startswith(' * ') else tech[0]
        new = ['/**'] + plain + [' *'] + tech + [' */']
        return lines[:start] + new + lines[end + 1:]
    new = ['/**'] + plain + [' */']
    return lines[:top] + new + lines[top:]


def rewrap_technical(lines):
    """Rewraps the paragraph that starts with 'Technical:' in a class Javadoc when the prefix made it too long."""
    for i, l in enumerate(lines):
        if l.startswith(' * Technical: ') and len(l) > WIDTH:
            j = i
            while j + 1 < len(lines) and lines[j + 1].startswith(' * ') and not LIST_ITEM.match(lines[j + 1]) \
                    and lines[j + 1].strip() != '*/':
                j += 1
            words = ' '.join(x[3:].strip() for x in lines[i:j + 1])
            new = [' * ' + w for w in textwrap.wrap(words, WIDTH - 3, break_long_words=False, break_on_hyphens=False)]
            return lines[:i] + new + lines[j + 1:]
    return lines


def add_method_doc(lines, needle, text):
    check(text)
    hits = [i for i, l in enumerate(lines) if needle in l]
    if len(hits) != 1:
        raise SystemExit(f'{len(hits)} lines match {needle!r}')
    idx = hits[0]
    top = idx
    while top > 0 and lines[top - 1].lstrip().startswith('@'):
        top -= 1
    above = lines[top - 1].strip() if top > 0 else ''
    if above.endswith('*/') or above.startswith('//'):
        return lines  # already commented
    indent = re.match(r'\s*', lines[top]).group(0)
    body = textwrap.wrap(text, WIDTH - len(indent) - 3, break_long_words=False, break_on_hyphens=False)
    if len(body) == 1 and len(indent) + len(body[0]) + 7 <= WIDTH:
        new = [f'{indent}/** {body[0]} */']
    else:
        new = [f'{indent}/**'] + [f'{indent} * {l}' for l in body] + [f'{indent} */']
    return lines[:top] + new + lines[top:]


def main():
    spec = json.load(open(sys.argv[1], encoding='utf-8'))
    for path, parts in spec.items():
        src = open(path, encoding='utf-8').read()
        lines = src.split('\n')
        if parts.get('class'):
            lines = add_class_doc(lines, parts['class'])
        lines = rewrap_technical(lines)
        for needle, text in parts.get('methods', []):
            lines = add_method_doc(lines, needle, text)
        out = '\n'.join(lines)
        if strip_comments(out) != strip_comments(src):
            raise SystemExit(f'code changed in {path}; nothing written')
        open(path, 'w', encoding='utf-8').write(out)
        print('commented', path)


main()
