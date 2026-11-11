# Corpus gate reports, MOVEMENT (2026-11-11)

`tools/corpus/gate.sh 3` on idle cores, as report.py wrote them, with the per-run results (`results-N.xml`) and logs.

- `pr146-1a8ba20d/`: #146 (F-35d) at 1a8ba20d. Pass 168, intermittent 32, fail 75, of 275; 0 flagged.
- `pr126-2c2c2be4/`: #126 (F-35c) at 2c2c2be4. Pass 139, intermittent 35, fail 73, of 247; 0 flagged.

Both bases have `tools/corpus` at 32d4852b and do **not** include #164 (e835ed8a, F-27a: replays start as recorded,
mobs held, batches by world state, runtimes stopped).
- `pr157-99e536cc/`: #157 (F-35e/F-35f) at 99e536cc, main a60f9193 merged in (with #164). Pass 113, intermittent 61, fail 101, of 275; 1 flagged, `e40a17-7bc5b42-707-sol-48263`, which passed alone 3/3 on the branch and 3/3 on main a60f9193.
