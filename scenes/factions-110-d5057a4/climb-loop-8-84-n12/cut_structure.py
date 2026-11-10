#!/usr/bin/env python3
"""Cut a box of blocks out of a world's Anvil region files into a structure SNBT, in the form the replays use
(DataVersion, size, data of {pos, state} with air left out, entities, palette). Standard library only.
Usage: cut_structure.py <region dir> x0 y0 z0 x1 y1 z1 <out.snbt> [data version]
"""
import struct
import sys
import zlib
from pathlib import Path


def nbt(buf, i, tag):
    if tag == 1: return struct.unpack_from('>b', buf, i)[0], i + 1
    if tag == 2: return struct.unpack_from('>h', buf, i)[0], i + 2
    if tag == 3: return struct.unpack_from('>i', buf, i)[0], i + 4
    if tag == 4: return struct.unpack_from('>q', buf, i)[0], i + 8
    if tag == 5: return struct.unpack_from('>f', buf, i)[0], i + 4
    if tag == 6: return struct.unpack_from('>d', buf, i)[0], i + 8
    if tag == 7:
        n = struct.unpack_from('>i', buf, i)[0]; i += 4
        return buf[i:i + n], i + n
    if tag == 8:
        n = struct.unpack_from('>H', buf, i)[0]; i += 2
        return buf[i:i + n].decode('utf-8', 'replace'), i + n
    if tag == 9:
        t = buf[i]; n = struct.unpack_from('>i', buf, i + 1)[0]; i += 5
        out = []
        for _ in range(n):
            v, i = nbt(buf, i, t); out.append(v)
        return out, i
    if tag == 10:
        out = {}
        while True:
            t = buf[i]; i += 1
            if t == 0: return out, i
            n = struct.unpack_from('>H', buf, i)[0]; i += 2
            name = buf[i:i + n].decode('utf-8', 'replace'); i += n
            out[name], i = nbt(buf, i, t)
    if tag == 11:
        n = struct.unpack_from('>i', buf, i)[0]; i += 4
        return list(struct.unpack_from('>%di' % n, buf, i)), i + 4 * n
    if tag == 12:
        n = struct.unpack_from('>i', buf, i)[0]; i += 4
        return list(struct.unpack_from('>%dq' % n, buf, i)), i + 8 * n
    raise ValueError('unknown tag %d' % tag)


def chunk(region_dir, cx, cz, cache={}):
    key = (cx, cz)
    if key in cache: return cache[key]
    path = Path(region_dir) / ('r.%d.%d.mca' % (cx >> 5, cz >> 5))
    result = None
    if path.is_file():
        data = path.read_bytes()
        loc = struct.unpack_from('>I', data, 4 * ((cx & 31) + (cz & 31) * 32))[0]
        offset = (loc >> 8) * 4096
        if offset:
            length, kind = struct.unpack_from('>IB', data, offset)
            raw = data[offset + 5: offset + 4 + length]
            if kind != 2: raise ValueError('compression %d not handled' % kind)
            body = zlib.decompress(raw)
            assert body[0] == 10
            n = struct.unpack_from('>H', body, 1)[0]
            result, _ = nbt(body, 3 + n, 10)
    cache[key] = result
    return result


def state_name(entry):
    props = entry.get('Properties') or {}
    name = entry['Name']
    return name + ('{' + ','.join('%s:%s' % (k, props[k]) for k in sorted(props)) + '}' if props else '')


def block(region_dir, x, y, z, sections={}):
    cx, cz, sy = x >> 4, z >> 4, y >> 4
    key = (cx, cz, sy)
    if key not in sections:
        c = chunk(region_dir, cx, cz)
        found = None
        if c is not None:
            for s in c.get('sections', []):
                if s.get('Y') == sy:
                    found = s.get('block_states'); break
        sections[key] = found
    bs = sections[key]
    if bs is None: return None  # not generated or not saved
    palette = bs['palette']
    if len(palette) == 1 or 'data' not in bs: return state_name(palette[0])
    bits = max(4, (len(palette) - 1).bit_length())
    per = 64 // bits
    idx = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
    word = bs['data'][idx // per] & 0xFFFFFFFFFFFFFFFF
    return state_name(palette[(word >> ((idx % per) * bits)) & ((1 << bits) - 1)])


def main():
    region_dir, x0, y0, z0, x1, y1, z1, out = sys.argv[1], *map(int, sys.argv[2:8]), sys.argv[8]
    version = int(sys.argv[9]) if len(sys.argv) > 9 else 3955
    size = [x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1]
    palette, data, missing = [], [], 0
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                s = block(region_dir, x, y, z)
                if s is None:
                    missing += 1; continue
                if s in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'): continue
                if s not in palette: palette.append(s)
                data.append('{pos: [%d, %d, %d], state: "%s"}' % (x - x0, y - y0, z - z0, s))
    text = ['{', 'DataVersion: %d,' % version, 'size: [%d, %d, %d],' % tuple(size), 'data: [', ',\n'.join(data), '],',
            'entities: [', '],', 'palette: [', ',\n'.join('"%s"' % p for p in palette), ']', '}']
    Path(out).write_text('\n'.join(text) + '\n', encoding='utf-8')
    print('size %s, %d blocks written, %d air, %d unsaved, palette %d' % (size, len(data), size[0] * size[1] * size[2] - len(data) - missing, missing, len(palette)))


if __name__ == '__main__':
    main()
