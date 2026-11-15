"""around.py <console> <HH:MM:SS> <seconds>: every console line within that many seconds of the time, continuation lines (stack traces) included."""
import sys
f, t, w = sys.argv[1], sys.argv[2], int(sys.argv[3])
h, m, s = map(int, t.split(':')); c = h * 3600 + m * 60 + s; keep = False
for raw in open(f, errors='replace'):
    if raw.startswith('[') and len(raw) > 9 and raw[3] == ':':
        try:
            hh, mm, ss = map(int, raw[1:9].split(':')); keep = abs(hh * 3600 + mm * 60 + ss - c) <= w
        except ValueError:
            pass
    if keep: sys.stdout.write(raw)
