#!/usr/bin/env python3
"""Run one build of Kindred MC unattended on many seeds (F-26).

Each seed gets a fresh Fabric server in run/eval/<build>/<seed>/ with the build's jar and the pinned Fabric API, its
world made from the seed, and KINDRED_EVAL set to the scenario. The mod plays the scenario on its own and stops the
server; this script waits, keeps the server's whole console (console.txt), logs/latest.log, logs/eval-result.json and
the jar's name (run.json), and removes the world.

Python 3 standard library only. Usage:
  tools/eval/run.py --ref HEAD --seeds 1,2,3 --scenario "goal=nether; ticks=144000" --parallel 2
  tools/eval/run.py --jar path/to/kindred-mc.jar --name mybuild --seed-file seeds.txt
Keys for the optional model players pass from your environment to a server only when its scenario says
strategist=on or voice=on; they are never written to a file.
"""
import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# The optional model players' key and settings: passed to a server only when its scenario asks for them.
MODEL_NAMES = ("ANTHROPIC_API_KEY", "OPENAI_API_KEY")
MODEL_PREFIX = "KINDRED_STRATEGIST"


def properties(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def pinned():
    gradle = properties(ROOT / "gradle.properties")
    return gradle["minecraft_version"], gradle["loader_version"], gradle["fabric_version"]


def gradle_home():
    return Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))


def fabric_api_jar(version):
    base = gradle_home() / "caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api" / version
    for jar in sorted(base.glob("*/fabric-api-%s.jar" % version)):
        return jar
    sys.exit("The pinned Fabric API %s is not in the Gradle cache; run ./gradlew build once first" % version)


def fetch(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".partial")
    with urllib.request.urlopen(url, timeout=120) as response, open(partial, "wb") as out:
        shutil.copyfileobj(response, out)
    partial.replace(target)


def launcher(cache, minecraft, loader):
    """The Fabric server launcher at the pinned loader, fetched once."""
    found = sorted(cache.glob("fabric-server-%s-%s-*.jar" % (minecraft, loader)))
    if found:
        return found[-1]
    with urllib.request.urlopen("https://meta.fabricmc.net/v2/versions/installer", timeout=60) as response:
        installer = next(v["version"] for v in json.load(response) if v.get("stable"))
    target = cache / ("fabric-server-%s-%s-%s.jar" % (minecraft, loader, installer))
    fetch("https://meta.fabricmc.net/v2/versions/loader/%s/%s/%s/server/jar" % (minecraft, loader, installer), target)
    return target


def vanilla_server(cache, minecraft):
    """The vanilla server jar: Loom's copy if it has one, else Mojang's, fetched once."""
    loom = gradle_home() / "caches/fabric-loom" / minecraft / "minecraft-server.jar"
    if loom.is_file():
        return loom
    target = cache / ("minecraft-server-%s.jar" % minecraft)
    if not target.is_file():
        with urllib.request.urlopen("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", timeout=60) as response:
            manifest = json.load(response)
        entry = next(v for v in manifest["versions"] if v["id"] == minecraft)
        with urllib.request.urlopen(entry["url"], timeout=60) as response:
            version = json.load(response)
        fetch(version["downloads"]["server"]["url"], target)
    return target


def build_ref(ref, out):
    """Builds a git ref in a worktree of its own and returns (name, jar)."""
    sha = subprocess.run(["git", "rev-parse", "--short=10", ref], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
    tree = out / "src" / sha
    if not tree.is_dir():
        subprocess.run(["git", "worktree", "add", "--detach", str(tree), sha], cwd=ROOT, check=True)
    subprocess.run(["./gradlew", "--no-daemon", "-q", ":companion-server-adapter:remapJar"], cwd=tree, check=True)
    return sha, mod_jar(tree / "companion-server-adapter/build/libs")


def mod_jar(libs):
    jars = [j for j in sorted(libs.glob("kindred-mc-*.jar")) if not j.name.endswith(("-sources.jar", "-dev.jar"))]
    if not jars:
        sys.exit("No built jar in %s" % libs)
    return jars[-1]


def free_port():
    """A port no one listens on now: two servers at once on the default one, the second could not bind and stopped."""
    with socket.socket() as probe:
        probe.bind(("", 0))
        return probe.getsockname()[1]


def prepare(folder, seed, jar, api, vanilla):
    if folder.exists():
        shutil.rmtree(folder)
    (folder / "mods").mkdir(parents=True)
    shutil.copy2(jar, folder / "mods" / jar.name)
    shutil.copy2(api, folder / "mods" / api.name)
    (folder / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (folder / "fabric-server-launcher.properties").write_text("serverJar=%s\n" % vanilla, encoding="utf-8")
    (folder / "server.properties").write_text("\n".join([
        "level-seed=%s" % seed, "level-name=world", "server-port=%d" % free_port(), "online-mode=false", "view-distance=8", "simulation-distance=8",
        "spawn-protection=0", "max-players=8", "motd=Kindred evaluation", "enable-command-block=false", "sync-chunk-writes=false", ""]),
        encoding="utf-8")


def environment(scenario):
    """The server's environment: yours less every KINDRED_ setting and model key, then the scenario; the strategist's
    key and settings only when the scenario says strategist=on or voice=on (the voice speaks through the strategist)."""
    env = {k: v for k, v in os.environ.items() if k not in MODEL_NAMES and not k.startswith("KINDRED_")}
    wants_model = any(part.replace(" ", "").lower() in ("strategist=on", "voice=on") for part in scenario.split(";"))
    if wants_model:
        for name, value in os.environ.items():
            if name in MODEL_NAMES or name.startswith(MODEL_PREFIX):
                env[name] = value
    env["KINDRED_EVAL"] = scenario
    return env


def finish(folder, process, started):
    """Keeps the log and the result beside the run, and removes the world and the server's own files."""
    logs = folder / "logs"
    for name in ("latest.log", "eval-result.json"):
        if (logs / name).is_file():
            shutil.copy2(logs / name, folder / name)
    # Its deaths' replays (F-27), when it kept them: kept beside the result, as candidates for the suite.
    replays = folder / "kindred" / "replays"
    if replays.is_dir():
        shutil.copytree(replays, folder / "replays", dirs_exist_ok=True)
    # The jar it ran, kept with the seed: a seed run again on another jar is said so in its build's summary.
    jars = sorted(p.name for p in (folder / "mods").glob("*.jar") if not p.name.startswith("fabric-api")) if (folder / "mods").is_dir() else []
    for name in ("world", "libraries", ".fabric", "mods", "logs", "versions", "config", "kindred"):
        path = folder / name
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)
    result = folder / "eval-result.json"
    ticks = json.loads(result.read_text(encoding="utf-8")).get("ticksRun") if result.is_file() else None
    (folder / "run.json").write_text(json.dumps({"exit": process.returncode, "seconds": round(time.time() - started),
                                                  "result": result.is_file(), "ticksRun": ticks, "jar": jars[0] if jars else None}) + "\n", encoding="utf-8")
    return result.is_file(), ticks


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--ref", help="a git ref to build first, in a worktree of its own")
    source.add_argument("--jar", help="a built jar (companion-server-adapter/build/libs/kindred-mc-*.jar)")
    parser.add_argument("--name", help="the build's name for its folder (default: the ref's short hash or the jar's hash)")
    seeds = parser.add_mutually_exclusive_group(required=True)
    seeds.add_argument("--seeds", help="comma-separated seeds")
    seeds.add_argument("--seed-file", help="a file of seeds, one a line")
    parser.add_argument("--scenario", default="goal=nether; ticks=144000", help="the KINDRED_EVAL scenario")
    parser.add_argument("--parallel", type=int, choices=(1, 2), default=1, help="servers at once (each needs about 3 GB)")
    parser.add_argument("--memory", default="3G", help="each server's heap")
    parser.add_argument("--timeout", type=int, default=6 * 3600, help="seconds a server may run before it is stopped")
    parser.add_argument("--out", default=str(ROOT / "run/eval"), help="where the runs go")
    args = parser.parse_args()

    out = Path(args.out).resolve()
    cache = out / "cache"
    minecraft, loader, fabric = pinned()
    if args.ref:
        name, jar = build_ref(args.ref, out)
    else:
        jar = Path(args.jar).resolve()
        name = hashlib.sha256(jar.read_bytes()).hexdigest()[:10]
    name = args.name or name
    seed_list = ([s.strip() for s in args.seeds.split(",") if s.strip()] if args.seeds
                 else [s.strip() for s in Path(args.seed_file).read_text().splitlines() if s.strip() and not s.startswith("#")])
    start = launcher(cache, minecraft, loader)
    api = fabric_api_jar(fabric)
    vanilla = vanilla_server(cache, minecraft)
    (out / name).mkdir(parents=True, exist_ok=True)
    (out / name / "build.json").write_text(json.dumps({"build": name, "jar": jar.name, "scenario": args.scenario, "minecraft": minecraft,
                                                       "loader": loader, "fabricApi": fabric, "launcher": start.name}, indent=1) + "\n", encoding="utf-8")
    print("build %s, %d seeds, scenario: %s, parallel %d" % (name, len(seed_list), args.scenario, args.parallel), flush=True)
    pending, running = list(seed_list), []
    while pending or running:
        while pending and len(running) < args.parallel:
            seed = pending.pop(0)
            folder = out / name / seed
            prepare(folder, seed, jar, api, vanilla)
            console = open(folder / "console.txt", "w", encoding="utf-8")
            process = subprocess.Popen(["java", "-Xmx" + args.memory, "-jar", str(start), "nogui"], cwd=folder, env=environment(args.scenario),
                                       stdin=subprocess.DEVNULL, stdout=console, stderr=subprocess.STDOUT)
            running.append((seed, folder, process, console, time.time()))
            print("seed %s started" % seed, flush=True)
        time.sleep(5)
        for item in list(running):
            seed, folder, process, console, started = item
            if process.poll() is None and time.time() - started > args.timeout:
                process.terminate()
                try:
                    process.wait(120)
                except subprocess.TimeoutExpired:
                    process.kill()
            if process.poll() is None:
                continue
            console.close()
            ok, ticks = finish(folder, process, started)
            print("seed %s ended: exit %s, result %s, ticks run %s, %d s" % (seed, process.returncode, "kept" if ok else "MISSING", ticks,
                                                                             time.time() - started), flush=True)
            running.remove(item)
    print("done: %s" % (out / name), flush=True)


if __name__ == "__main__":
    main()
