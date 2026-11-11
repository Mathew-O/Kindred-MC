#!/usr/bin/env bash
# Runs one GameTest class kept apart from the regression suite (support.py's RUN_APART, F-22c61), on its own, and puts
# the gametest fabric.mod.json back as it was however the run ends, so a narrowed list is never left behind.
#
# usage: tools/run-apart.sh <Class> [-Dkindred.<setting>=<value> ...]
#   tools/run-apart.sh NativeDuelBenchmarkGameTests -Dkindred.duels=200     # the baseline-duel benchmark (E-41b)
#   tools/run-apart.sh NativeMeleeMechanicsGameTests -Dkindred.trials=20    # the melee mechanics trials (E-41b)
# The results are in companion-server-adapter/build/gametest-results.xml and the run's log.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
class=${1:?usage: tools/run-apart.sh <Class> [-Dkindred.x=y ...]}; shift
mod=$root/companion-server-adapter/src/gametest/resources/fabric.mod.json
python3 "$root/tools/support-batch-01/support.py" entrypoints > /dev/null || { echo "The entrypoint list is not whole; fix that first." >&2; exit 2; }
python3 - "$root" "$class" <<'PY' || exit 2
import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('support', Path(sys.argv[1]) / 'tools/support-batch-01/support.py')
support = importlib.util.module_from_spec(spec); spec.loader.exec_module(support)
if 'dev.kindred.fabric.' + sys.argv[2] not in support.RUN_APART:
    sys.exit(f'{sys.argv[2]} is not run apart (support.py RUN_APART); the suite runs it.')
PY
backup=$(mktemp)
cp "$mod" "$backup"
trap 'cp "$backup" "$mod"; rm -f "$backup"' EXIT
python3 - "$mod" "$class" <<'PY'
import json, sys
path, name = sys.argv[1], sys.argv[2]
data = json.load(open(path))
data['entrypoints']['fabric-gametest'] = ['dev.kindred.fabric.' + name]
json.dump(data, open(path, 'w'), indent=2)
PY
cd "$root"
./gradlew --no-daemon "$@" :companion-server-adapter:runGametest
