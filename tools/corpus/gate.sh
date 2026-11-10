#!/usr/bin/env bash
# The failure corpus's merge gate (F-27a): run the corpus three times on this checkout and report its pass rate and any
# scene that passed in tools/corpus/baseline.json (the last run on main) and now fails in two or more of the three runs.
#
# usage: tools/corpus/gate.sh [RUNS]      (RUNS defaults to 3; the report and results go to build/corpus-gate/)
# Exit 0 when the gate passes, 1 when a scene that passed on main now fails, 2 when a run could not be made.
# Takes about 25 minutes a run on a 4-core container (224 scenes ten to a batch), so about 75 minutes for three.
# Run it with nothing else on the cores: the scenes' outcomes are timing-sensitive like any GameTest.
set -uo pipefail
cd "$(dirname "$0")/../.."
runs="${1:-3}"
out=build/corpus-gate
mkdir -p "$out"
results=()
for i in $(seq 1 "$runs"); do
  ok=""
  for attempt in 1 2 3; do
    ./gradlew --max-workers=2 :companion-server-adapter:runCorpus > "$out/run-$i.log" 2>&1
    if [ -f companion-server-adapter/build/corpus-results.xml ] && grep -q "GAME TESTS COMPLETE" "$out/run-$i.log"; then ok=1; break; fi
    # A download refused by a registry (rate limits) is tried again; anything else is a broken run.
    grep -q -E "429|Could not (GET|download|resolve)" "$out/run-$i.log" && { sleep $((attempt * 60)); continue; }
    break
  done
  [ -n "$ok" ] || { echo "Run $i could not be made; see $out/run-$i.log" >&2; exit 2; }
  cp companion-server-adapter/build/corpus-results.xml "$out/results-$i.xml"
  results+=("$out/results-$i.xml")
done
python3 tools/corpus/report.py "${results[@]}" --sha "$(git rev-parse --short HEAD)" --out "$out/report.md"
