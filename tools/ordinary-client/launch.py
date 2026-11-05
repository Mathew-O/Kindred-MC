#!/usr/bin/env python3
"""An ordinary (vanilla, unmodded) Minecraft client for live checks against a Kindred dev server.

It reads the version file Fabric Loom already cached for the pinned Minecraft version, fetches that version's
libraries and asset index from Mojang (no sound or language objects), and starts the vanilla client jar with
no mods on a display (Xvfb). It joins the given server at once in offline mode under the given name.

Usage: python3 tools/ordinary-client/launch.py --name Mira --server localhost:25565 --dir <work dir> [--display :99]
Test tooling only: nothing here is shipped or a runtime dependency.
"""
import argparse, json, os, pathlib, subprocess, sys, urllib.request, uuid

LOOM = pathlib.Path.home() / ".gradle/caches/fabric-loom"


def fetch(url, path):
    path = pathlib.Path(path)
    if path.exists() and path.stat().st_size > 0:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as response, open(str(path) + ".part", "wb") as out:
        out.write(response.read())
    os.replace(str(path) + ".part", path)
    return path


def allowed(rules):
    if not rules:
        return True
    ok = False
    for rule in rules:
        os_rule = rule.get("os")
        matches = os_rule is None or os_rule.get("name") == "linux"
        if "features" in rule:
            matches = False
        if matches:
            ok = rule["action"] == "allow"
    return ok


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="1.21.1")
    parser.add_argument("--name", required=True)
    parser.add_argument("--server", default="localhost:25565")
    parser.add_argument("--dir", required=True)
    parser.add_argument("--display", default=os.environ.get("DISPLAY", ":99"))
    parser.add_argument("--width", default="854")
    parser.add_argument("--height", default="480")
    args = parser.parse_args()

    info = json.load(open(LOOM / args.version / "mojang_minecraft_info.json"))
    work = pathlib.Path(args.dir).resolve()
    libraries = work / "libraries"
    classpath = []
    for library in info["libraries"]:
        if not allowed(library.get("rules")):
            continue
        artifact = library.get("downloads", {}).get("artifact")
        if artifact:
            classpath.append(str(fetch(artifact["url"], libraries / artifact["path"])))
    classpath.append(str(LOOM / args.version / "minecraft-client.jar"))
    assets = work / "assets"
    index = info["assetIndex"]
    fetch(index["url"], assets / "indexes" / (index["id"] + ".json"))
    game = work / "game"
    game.mkdir(parents=True, exist_ok=True)
    # Quiet and small: no music, no narrator prompt, no tutorial, so a screenshot shows the game.
    options = game / "options.txt"
    if not options.exists():
        options.write_text("onboardAccessibility:false\ntutorialStep:none\nsoundCategory_music:0.0\nrenderDistance:4\nsimulationDistance:5\npauseOnLostFocus:false\njoinedFirstServer:true\nskipMultiplayerWarning:true\n")
    player_uuid = str(uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + args.name))
    command = ["java", "-Xmx2G", "-cp", os.pathsep.join(classpath), info["mainClass"],
               "--username", args.name, "--version", args.version, "--gameDir", str(game),
               "--assetsDir", str(assets), "--assetIndex", index["id"], "--uuid", player_uuid,
               "--accessToken", "0", "--userType", "legacy", "--width", args.width, "--height", args.height,
               "--quickPlayMultiplayer", args.server]
    env = dict(os.environ, DISPLAY=args.display, LIBGL_ALWAYS_SOFTWARE="1")
    log = open(work / "client.log", "w")
    process = subprocess.Popen(command, cwd=game, env=env, stdout=log, stderr=subprocess.STDOUT)
    print(process.pid)


if __name__ == "__main__":
    main()
