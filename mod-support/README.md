# Mod support

One folder per supported mod. Kindred detects the mods a server has installed and, for each, loads the profile in
its folder here (bundled into the jar) or in the server's `config/kindred/mods/<modid>/`, which overrides it.
Design and rules: [docs/proposals/mod-support.md](../docs/proposals/mod-support.md).

Most mods need no folder at all: blocks, items, recipes, biomes and food that the game describes through tags,
components and attributes are understood on sight. A folder is for what the game cannot say: a block you sit on, a
station that takes a recipe type, a drink that quenches a thirst, a creature that explodes.

## Adding a mod

1. Copy `_template/` to `<modid>/` (the id as the mod declares it in its `fabric.mod.json`).
2. Fill in `profile.json`: only the fields the registry dump cannot derive; unknown fields are refused.
3. Write at least one fixture under `fixtures/` that runs on a test server with the mod installed, and make it pass
   three of three.
4. Record the run in `evidence.md`, the versions tested in `README.md`, and open a pull request.

A profile is data. It cannot grant a skill, bypass the edits policy, the ledger or the arbiter, or give anything
away; mods that need real new behaviour are integrations (`integrations/kindred-<modid>`), described in the design.

| Folder | Status | Versions | Notes |
| --- | --- | --- | --- |
| `_template/` | template | — | copy me |
