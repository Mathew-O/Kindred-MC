# Ordinary-client live checks

Test tooling for the live checks a feature card asks of an ordinary client. It is not shipped and is not a runtime dependency.

- `launch.py` starts an unmodified vanilla client of the pinned Minecraft version from the jar Fabric Loom caches. It fetches the version's libraries and asset index from Mojang, joins a server in offline mode under the given name, and writes `client.log` in its work directory.
  - Run it on a display, such as Xvfb with Mesa.
  - Sound needs the sound objects in `<dir>/assets/objects`. With no audio device, `ALSOFT_CONF` pointing at an OpenAL Soft config with `drivers=wave` writes the client's sound to a WAV file.
- `rcon.py <password> <command>...` runs server console commands over RCON (port 25575), as the operator.
- `Keys.java` presses keys and clicks on the client's display as a person would, for example `DISPLAY=:99 java Keys.java chat "/aiplayer match join 1a2b3c4d"`.

The server needs `online-mode=false` for offline names, and `enable-rcon=true` with a password of your own. Say in the evidence what was staged, such as placing players with `/tp`. See [M-00](../../docs/evidence/M-00.md) for a worked run.
