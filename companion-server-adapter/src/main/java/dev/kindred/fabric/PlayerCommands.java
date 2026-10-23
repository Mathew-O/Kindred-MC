package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.Personality;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PlayerCommands {
    private PlayerCommands() { }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = CommandManager.literal("aiplayer").executes(context -> {
            context.getSource().sendFeedback(() -> Text.literal(
                    "Kindred AI players: native create <name>; native list; native status|stop|resume|connect|disconnect <UUID>; "
                    + "native wait <1..4>|eat <0..8>|equip <0..35> <UUID>; native walk <direction> <1..3> <UUID>; "
                    + "native patch <from> <to> <block> <UUID>; native mine <target> <hotbar slot> <UUID> (explicit edit policy required). "
                    + "Legacy bodies: /aiplayer create <name>; follow, stay, stop, resume, status [UUID]; "
                    + "talk <message>; talkto <UUID> <message>; social ambient|public <true|false> [UUID]; personality <trait> <0..100> [UUID]; "
                    + "quirks [UUID]; quirk <sprintjump|lively|curiousglance|steady> <auto|on|off> [UUID]; "
                    + "setup; home set; area set <x1> <z1> <x2> <z2>|default; storage set; patch set <from> <to> <block> [UUID]; "
                    + "goto <x> <y> <z>; gohome; wait <1..60>; pause; continue [UUID]; look [block] [UUID]; "
                    + "scope home|approved; goal list|add; memory list|add|forget; limit status|cap|offline|timezone; "
                    + "suspend [UUID]; recover [UUID]; remove [UUID]; journal [UUID]. Autonomous survival awaits qualification."), false);
            return 1;
        });
        root.then(CommandManager.literal("goal").then(CommandManager.argument("goal", StringArgumentType.word())
                .suggests((context, builder) -> { builder.suggest("nether"); builder.suggest("end"); builder.suggest("dragon"); builder.suggest("none"); return builder.buildFuture(); })
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = runtime.defaultBotId();
                    if (id == null) {
                        context.getSource().sendError(Text.literal("Name the AI player. Available: " + runtime.availableBotNames()));
                        return 0;
                    }
                    return runtime.startGoal(context.getSource(), id, goal(StringArgumentType.getString(context, "goal")));
                })
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.startGoal(context.getSource(), id, goal(StringArgumentType.getString(context, "goal")));
                        }))));
        // A build (E-08b, M-05, M-01b): /aiplayer build hut|hut-7x5x3|cabin|house|manor|<schematic> <x y z> [quarter turns] [player];
        // build stop [player];
        // build list.
        root.then(CommandManager.literal("build")
                .then(CommandManager.literal("list").executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    return runtime == null ? unavailable(context.getSource()) : runtime.listBlueprints(context.getSource());
                }))
                .then(CommandManager.literal("stop").executes(context -> build(context, false, false, false))
                        .then(CommandManager.argument("target", StringArgumentType.string()).suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> build(context, false, false, true))))
                .then(CommandManager.argument("blueprint", StringArgumentType.word())
                        .suggests((context, builder) -> { for (String s : new String[] {"hut", "hut-7x5x3", "cabin", "house", "manor"}) builder.suggest(s); return builder.buildFuture(); })
                        .then(CommandManager.argument("at", BlockPosArgumentType.blockPos())
                                .executes(context -> build(context, true, false, false))
                                .then(CommandManager.argument("turns", IntegerArgumentType.integer(0, 3))
                                        .executes(context -> build(context, true, true, false))
                                        .then(CommandManager.argument("target", StringArgumentType.string())
                                                .suggests((context, builder) -> suggestBots(builder))
                                                .executes(context -> build(context, true, true, true)))))));
        root.then(CommandManager.literal("ranch")
                .then(CommandManager.literal("stop").executes(context -> ranch(context, false, false))
                        .then(CommandManager.argument("target", StringArgumentType.string()).suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> ranch(context, false, true))))
                .then(CommandManager.argument("animal", StringArgumentType.word())
                        .suggests((context, builder) -> { for (var kind : dev.kindred.core.survival.Ranch.Kind.values()) builder.suggest(kind.noun()); return builder.buildFuture(); })
                        .then(CommandManager.argument("at", BlockPosArgumentType.blockPos())
                                .executes(context -> ranch(context, true, false))
                                .then(CommandManager.argument("target", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestBots(builder))
                                        .executes(context -> ranch(context, true, true))))));
        root.then(CommandManager.literal("skin")
                .then(CommandManager.literal("list").executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    var source = context.getSource();
                    KindredSkins.list(source.getServer(), message -> source.sendFeedback(() -> Text.literal(message), false));
                    return 1;
                }))
                .then(CommandManager.argument("skin", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest(dev.kindred.core.SkinFiles.DEFAULT_KEY);
                            KindredSkins.keys().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = runtime.defaultBotId();
                            if (id == null) {
                                context.getSource().sendError(Text.literal("Name the AI player. Available: " + runtime.availableBotNames()));
                                return 0;
                            }
                            return runtime.setSkin(context.getSource(), id, StringArgumentType.getString(context, "skin"));
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    PlayerRuntime runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.setSkin(context.getSource(), id, StringArgumentType.getString(context, "skin"));
                                }))));
        root.then(CommandManager.literal("edits").then(CommandManager.argument("enabled", StringArgumentType.word())
                .suggests((context, builder) -> { builder.suggest("on"); builder.suggest("off"); return builder.buildFuture(); })
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    String raw = StringArgumentType.getString(context, "enabled");
                    return runtime.setWorldEdits(context.getSource(), raw.equalsIgnoreCase("on") || raw.equalsIgnoreCase("true"));
                })));
        root.then(CommandManager.literal("dragonfight").then(CommandManager.argument("enabled", StringArgumentType.word())
                .suggests((context, builder) -> { builder.suggest("on"); builder.suggest("off"); return builder.buildFuture(); })
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    String raw = StringArgumentType.getString(context, "enabled");
                    return runtime.setDragonFight(context.getSource(), raw.equalsIgnoreCase("on") || raw.equalsIgnoreCase("true"));
                })));
        // The leave for AI players to build their own bases (M-01c): /aiplayer bases on|off.
        root.then(CommandManager.literal("bases").then(CommandManager.argument("enabled", StringArgumentType.word())
                .suggests((context, builder) -> { builder.suggest("on"); builder.suggest("off"); return builder.buildFuture(); })
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    String raw = StringArgumentType.getString(context, "enabled");
                    return runtime.setBases(context.getSource(), raw.equalsIgnoreCase("on") || raw.equalsIgnoreCase("true"));
                })));
        root.then(CommandManager.literal("gui").executes(context -> {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) return unavailable(context.getSource());
            return runtime.openControlGui(context.getSource());
        }));
        // Matches (M-00a): an AI player is entered by whoever administers it; a human joins only for itself (D7).
        root.then(CommandManager.literal("match")
                .then(CommandManager.literal("duel").then(CommandManager.argument("first", StringArgumentType.string())
                        .suggests((context, builder) -> suggestPlayers(context, builder))
                        .then(CommandManager.argument("second", StringArgumentType.string())
                                .suggests((context, builder) -> suggestPlayers(context, builder))
                                .executes(context -> {
                                    PlayerRuntime runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID first = resolveMatchPlayer(context, "first", runtime), second = resolveMatchPlayer(context, "second", runtime);
                                    if (first == null || second == null) return 0;
                                    return runtime.proposeDuel(context.getSource(), first, second);
                                }))))
                // A race from a fresh start to the dragon (M-03a): the runners' names, one to eight of them.
                .then(CommandManager.literal("speedrun").then(CommandManager.argument("runners", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggestPlayers(context,
                                builder.createOffset(builder.getStart() + builder.getRemaining().lastIndexOf(' ') + 1)))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            var runners = new java.util.ArrayList<UUID>();
                            for (String name : StringArgumentType.getString(context, "runners").trim().split("\\s+")) {
                                UUID runner = runtime.matchPlayerId(name);
                                if (runner == null) {
                                    context.getSource().sendError(Text.literal("No such player online: '" + name + "'."));
                                    return 0;
                                }
                                runners.add(runner);
                            }
                            return runtime.proposeSpeedrun(context.getSource(), runners);
                        })))
                // A manhunt (M-02a): the runners' names, then "vs", then the hunters'.
                .then(CommandManager.literal("manhunt").then(CommandManager.argument("players", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggestPlayers(context,
                                builder.createOffset(builder.getStart() + builder.getRemaining().lastIndexOf(' ') + 1)))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            String[] sides = StringArgumentType.getString(context, "players").trim().split("\\s+vs\\s+");
                            if (sides.length != 2) {
                                context.getSource().sendError(Text.literal("Name the runners, then 'vs', then the hunters: /aiplayer match manhunt Ada vs Bram Cy"));
                                return 0;
                            }
                            var runners = new java.util.ArrayList<UUID>();
                            var hunters = new java.util.ArrayList<UUID>();
                            for (int side = 0; side < 2; side++) for (String name : sides[side].trim().split("\\s+")) {
                                UUID player = runtime.matchPlayerId(name);
                                if (player == null) {
                                    context.getSource().sendError(Text.literal("No such player online: '" + name + "'."));
                                    return 0;
                                }
                                (side == 0 ? runners : hunters).add(player);
                            }
                            return runtime.proposeManhunt(context.getSource(), runners, hunters);
                        })))
                // A build battle (M-04a): the builders' names, then optionally "for <seconds>" and "theme <name>".
                .then(CommandManager.literal("build").then(CommandManager.argument("builders", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggestPlayers(context,
                                builder.createOffset(builder.getStart() + builder.getRemaining().lastIndexOf(' ') + 1)))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            var builders = new java.util.ArrayList<UUID>();
                            int seconds = 300;
                            dev.kindred.core.match.BuildBattle.Theme theme = null;
                            String[] words = StringArgumentType.getString(context, "builders").trim().split("\\s+");
                            for (int i = 0; i < words.length; i++) {
                                if (words[i].equalsIgnoreCase("for") && i + 1 < words.length) {
                                    try {
                                        seconds = Integer.parseInt(words[++i]);
                                    } catch (NumberFormatException bad) {
                                        context.getSource().sendError(Text.literal("'for' takes the build's seconds: for 300"));
                                        return 0;
                                    }
                                    continue;
                                }
                                if (words[i].equalsIgnoreCase("theme") && i + 1 < words.length) {
                                    String name = words[++i].toUpperCase(java.util.Locale.ROOT);
                                    theme = java.util.Arrays.stream(dev.kindred.core.match.BuildBattle.Theme.values()).filter(t -> t.name().equals(name)).findFirst().orElse(null);
                                    if (theme == null) {
                                        context.getSource().sendError(Text.literal("No such theme; there are: cottage, watchtower, pyramid, lighthouse"));
                                        return 0;
                                    }
                                    continue;
                                }
                                UUID player = runtime.matchPlayerId(words[i]);
                                if (player == null) {
                                    context.getSource().sendError(Text.literal("No such player online: '" + words[i] + "'."));
                                    return 0;
                                }
                                builders.add(player);
                            }
                            return runtime.proposeBuildBattle(context.getSource(), builders, seconds * 20, theme);
                        })))
                // A builder's vote on another's build, one to five.
                .then(CommandManager.literal("vote").then(CommandManager.argument("builder", StringArgumentType.string())
                        .suggests((context, builder) -> suggestPlayers(context, builder))
                        .then(CommandManager.argument("score", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5))
                                .executes(context -> {
                                    PlayerRuntime runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID builder = resolveMatchPlayer(context, "builder", runtime);
                                    if (builder == null) return 0;
                                    return runtime.voteBuild(context.getSource(), builder,
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "score"));
                                }))))
                .then(CommandManager.literal("join").then(CommandManager.argument("match", StringArgumentType.string())
                        .executes(context -> matchCommand(context, (runtime, id) -> runtime.joinMatch(context.getSource(), id)))))
                .then(CommandManager.literal("start").then(CommandManager.argument("match", StringArgumentType.string())
                        .executes(context -> matchCommand(context, (runtime, id) -> runtime.startMatch(context.getSource(), id)))))
                .then(CommandManager.literal("stop").then(CommandManager.argument("match", StringArgumentType.string())
                        .executes(context -> matchCommand(context, (runtime, id) -> runtime.stopMatch(context.getSource(), id)))))
                .then(CommandManager.literal("list").executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    var all = runtime.matches();
                    if (all.isEmpty()) context.getSource().sendFeedback(() -> Text.literal("No matches."), false);
                    for (var match : all) context.getSource().sendFeedback(() -> Text.literal(dev.kindred.core.match.MatchRegistry.shortId(match.id()) + " " + match.mode() + " " + match.state()
                            + " (" + match.joined().size() + " of " + match.invited().size() + " joined)"
                            + (match.result().isEmpty() ? "" : ": " + match.result())), false);
                    return all.size();
                })));
        root.then(CommandManager.literal("mode").then(CommandManager.argument("profile", StringArgumentType.word())
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    String raw = StringArgumentType.getString(context, "profile");
                    dev.kindred.core.progression.PlaystyleProfile profile = parseProfile(raw);
                    if (profile == null) {
                        context.getSource().sendError(Text.literal("Unknown mode. Use IDLE, OPTIMAL, or SPEEDRUN."));
                        return 0;
                    }
                    return runtime.setPlaystyleMode(context.getSource(), null, profile);
                }).then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            String raw = StringArgumentType.getString(context, "profile");
                            dev.kindred.core.progression.PlaystyleProfile profile = parseProfile(raw);
                            if (profile == null) {
                                context.getSource().sendError(Text.literal("Unknown mode. Use IDLE, OPTIMAL, or SPEEDRUN."));
                                return 0;
                            }
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.setPlaystyleMode(context.getSource(), id, profile);
                        }))));
        root.then(CommandManager.literal("create")
                .then(CommandManager.argument("name", StringArgumentType.greedyString()).executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    String name = StringArgumentType.getString(context, "name");
                    if (context.getSource().hasPermissionLevel(2) || context.getSource().getServer().isSingleplayer()) {
                        return runtime.createNative(context.getSource(), name);
                    }
                    return runtime.create(context.getSource(), name);
                })));
        var roster = CommandManager.literal("roster");
        roster.then(CommandManager.literal("spawn").executes(context -> {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) return unavailable(context.getSource());
            return runtime.spawnCanonicalRoster(context.getSource(), null);
        }).then(CommandManager.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> {
                    builder.suggest(dev.kindred.core.CanonicalRoster.KINDRED_NAME);
                    builder.suggest(dev.kindred.core.CanonicalRoster.SOL_NAME);
                    builder.suggest(dev.kindred.core.CanonicalRoster.VESP_NAME);
                    return builder.buildFuture();
                }).executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    return runtime.spawnCanonicalRoster(context.getSource(), StringArgumentType.getString(context, "name"));
                })));
        root.then(roster);
        for (String verb : new String[]{"follow", "stay", "stop", "resume", "status", "disconnect"}) {
            root.then(CommandManager.literal(verb).executes(context -> {
                PlayerRuntime runtime = KindredMod.runtime();
                if (runtime == null) return unavailable(context.getSource());
                return runtime.command(context.getSource(), verb, null);
            }).then(CommandManager.argument("target", StringArgumentType.string())
                    .suggests((context, builder) -> suggestBots(builder))
                    .executes(context -> {
                        PlayerRuntime runtime = KindredMod.runtime();
                        if (runtime == null) return unavailable(context.getSource());
                        UUID id = resolveBotTarget(context, "target", runtime);
                        if (id == null) return 0;
                        return runtime.command(context.getSource(), verb, id);
                    })));
        }
        var social = CommandManager.literal("social");
        for (String setting : new String[]{"ambient", "public"}) {
            social.then(CommandManager.literal(setting).then(CommandManager.argument("enabled", BoolArgumentType.bool())
                    .executes(context -> {
                        PlayerRuntime runtime = KindredMod.runtime();
                        if (runtime == null) return unavailable(context.getSource());
                        return runtime.socialSetting(context.getSource(), null, setting,
                                BoolArgumentType.getBool(context, "enabled"), null, 0);
                    }).then(CommandManager.argument("target", StringArgumentType.string())
                            .suggests((context, builder) -> suggestBots(builder))
                            .executes(context -> {
                                PlayerRuntime runtime = KindredMod.runtime();
                                if (runtime == null) return unavailable(context.getSource());
                                UUID id = resolveBotTarget(context, "target", runtime);
                                if (id == null) return 0;
                                return runtime.socialSetting(context.getSource(), id, setting,
                                        BoolArgumentType.getBool(context, "enabled"), null, 0);
                            }))));
        }
        root.then(social);
        var personality = CommandManager.literal("personality").executes(context -> {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) return unavailable(context.getSource());
            return runtime.personality(context.getSource(), null);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.personality(context.getSource(), id);
                }));
        for (Personality.Trait trait : Personality.Trait.values()) {
            for (String name : trait.commandNames()) {
                personality.then(CommandManager.literal(name)
                    .then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100)).executes(context -> {
                        PlayerRuntime runtime = KindredMod.runtime();
                        if (runtime == null) return unavailable(context.getSource());
                        return runtime.socialSetting(context.getSource(), null, null, false, trait,
                                IntegerArgumentType.getInteger(context, "value"));
                    }).then(CommandManager.argument("target", StringArgumentType.string())
                            .suggests((context, builder) -> suggestBots(builder))
                            .executes(context -> {
                                PlayerRuntime runtime = KindredMod.runtime();
                                if (runtime == null) return unavailable(context.getSource());
                                UUID id = resolveBotTarget(context, "target", runtime);
                                if (id == null) return 0;
                                return runtime.socialSetting(context.getSource(), id, null,
                                        false, trait, IntegerArgumentType.getInteger(context, "value"));
                            }))));
            }
        }
        root.then(personality);
        root.then(CommandManager.literal("quirks").executes(context -> {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) return unavailable(context.getSource());
            return runtime.quirks(context.getSource(), null);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.quirks(context.getSource(), id);
                })));
        var quirk = CommandManager.literal("quirk");
        for (Mannerisms.Quirk habit : Mannerisms.Quirk.values()) {
            var habitNode = CommandManager.literal(habit.commandName());
            for (Mannerisms.Setting setting : Mannerisms.Setting.values()) {
                habitNode.then(CommandManager.literal(setting.name().toLowerCase(java.util.Locale.ROOT)).executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    return runtime.quirk(context.getSource(), null, habit, setting);
                }).then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.quirk(context.getSource(), id, habit, setting);
                        })));
            }
            quirk.then(habitNode);
        }
        root.then(quirk);
        root.then(withOptionalId(CommandManager.literal("setup"), (runtime, context, id) -> runtime.setup(context.getSource(), id)));
        root.then(CommandManager.literal("home").then(withOptionalId(CommandManager.literal("set"),
                (runtime, context, id) -> runtime.homeSet(context.getSource(), id))));
        var area = CommandManager.literal("area");
        area.then(withOptionalId(CommandManager.literal("default"), (runtime, context, id) -> runtime.areaDefault(context.getSource(), id)));
        area.then(CommandManager.literal("set").then(CommandManager.argument("x1", IntegerArgumentType.integer())
                .then(CommandManager.argument("z1", IntegerArgumentType.integer())
                .then(CommandManager.argument("x2", IntegerArgumentType.integer())
                .then(withOptionalId(CommandManager.argument("z2", IntegerArgumentType.integer()), (runtime, context, id) ->
                        runtime.areaSet(context.getSource(), id, IntegerArgumentType.getInteger(context, "x1"), IntegerArgumentType.getInteger(context, "z1"),
                                IntegerArgumentType.getInteger(context, "x2"), IntegerArgumentType.getInteger(context, "z2"))))))));
        root.then(area);
        root.then(CommandManager.literal("storage").then(withOptionalId(CommandManager.literal("set"),
                (runtime, context, id) -> runtime.storageSet(context.getSource(), id))));
        // A container named by its place rather than looked at (E-08c): for a console or a command block.
        root.then(CommandManager.literal("storage").then(CommandManager.literal("at").then(CommandManager.argument("at", BlockPosArgumentType.blockPos())
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    return runtime.storageSetAt(context.getSource(), runtime.defaultBotId(), BlockPosArgumentType.getBlockPos(context, "at"));
                })
                .then(CommandManager.argument("target", StringArgumentType.string()).suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            return id == null ? 0 : runtime.storageSetAt(context.getSource(), id, BlockPosArgumentType.getBlockPos(context, "at"));
                        })))));
        root.then(CommandManager.literal("patch").then(CommandManager.literal("set")
                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                .then(withOptionalId(CommandManager.argument("block", IdentifierArgumentType.identifier()), (runtime, context, id) ->
                        runtime.patchSet(context.getSource(), id, BlockPosArgumentType.getBlockPos(context, "from"),
                                BlockPosArgumentType.getBlockPos(context, "to"), IdentifierArgumentType.getIdentifier(context, "block").toString())))))));
        var mode = CommandManager.literal("mode");
        mode.then(withOptionalId(CommandManager.literal("companion"), (runtime, context, id) -> runtime.mode(context.getSource(), id, true)));
        mode.then(withOptionalId(CommandManager.literal("together"), (runtime, context, id) -> runtime.mode(context.getSource(), id, true)));
        mode.then(withOptionalId(CommandManager.literal("independent"), (runtime, context, id) -> runtime.mode(context.getSource(), id, false)));
        root.then(mode);
        var scope = CommandManager.literal("scope");
        scope.then(withOptionalId(CommandManager.literal("home"), (runtime, context, id) -> runtime.scope(context.getSource(), id, true)));
        scope.then(withOptionalId(CommandManager.literal("approved"), (runtime, context, id) -> runtime.scope(context.getSource(), id, false)));
        root.then(scope);
        var goal = CommandManager.literal("goal");
        goal.then(withOptionalId(CommandManager.literal("list"), (runtime, context, id) -> runtime.listGoals(context.getSource(), id)));
        goal.then(CommandManager.literal("add")
                .then(CommandManager.argument("description", StringArgumentType.string())
                .then(CommandManager.argument("outcome", StringArgumentType.string())
                .then(withOptionalId(CommandManager.argument("priority", IntegerArgumentType.integer(0, 100)),
                        (runtime, context, id) -> runtime.addGoal(context.getSource(), id,
                                StringArgumentType.getString(context, "description"),
                                StringArgumentType.getString(context, "outcome"),
                                IntegerArgumentType.getInteger(context, "priority")))))));
        root.then(goal);
        var memory = CommandManager.literal("memory");
        memory.then(withOptionalId(CommandManager.literal("list"), (runtime, context, id) -> runtime.listMemories(context.getSource(), id)));
        memory.then(CommandManager.literal("add")
                .then(CommandManager.argument("kind", StringArgumentType.word())
                .then(withOptionalId(CommandManager.argument("text", StringArgumentType.string()),
                        (runtime, context, id) -> runtime.addMemory(context.getSource(), id,
                                StringArgumentType.getString(context, "kind"),
                                StringArgumentType.getString(context, "text"))))));
        memory.then(CommandManager.literal("forget")
                .then(withOptionalId(CommandManager.argument("memoryId", UuidArgumentType.uuid()),
                        (runtime, context, id) -> runtime.forgetMemory(context.getSource(), id, UuidArgumentType.getUuid(context, "memoryId")))));
        root.then(memory);
        var limit = CommandManager.literal("limit");
        limit.then(withOptionalId(CommandManager.literal("status"), (runtime, context, id) -> runtime.limitStatus(context.getSource(), id)));
        limit.then(CommandManager.literal("cap")
                .then(withOptionalId(CommandManager.argument("hours", IntegerArgumentType.integer(1, 24)),
                        (runtime, context, id) -> runtime.setLimitCap(context.getSource(), id, IntegerArgumentType.getInteger(context, "hours")))));
        limit.then(CommandManager.literal("offline")
                .then(withOptionalId(CommandManager.argument("allowed", BoolArgumentType.bool()),
                        (runtime, context, id) -> runtime.setLimitOffline(context.getSource(), id, BoolArgumentType.getBool(context, "allowed")))));
        limit.then(CommandManager.literal("timezone")
                .then(withOptionalId(CommandManager.argument("zone", StringArgumentType.string()),
                        (runtime, context, id) -> runtime.setLimitTimezone(context.getSource(), id, StringArgumentType.getString(context, "zone")))));
        root.then(limit);
        root.then(withOptionalId(CommandManager.literal("journal"), (runtime, context, id) -> runtime.journal(context.getSource(), id)));
        root.then(CommandManager.literal("gather").then(withOptionalId(CommandManager.argument("count", IntegerArgumentType.integer(1, 32)),
                (runtime, context, id) -> runtime.skill(context.getSource(), id, "gather_logs",
                        java.util.Map.of("count", String.valueOf(IntegerArgumentType.getInteger(context, "count")))))));
        root.then(CommandManager.literal("deposit").then(CommandManager.argument("item", IdentifierArgumentType.identifier())
                .then(withOptionalId(CommandManager.argument("count", IntegerArgumentType.integer(1, 64)),
                        (runtime, context, id) -> runtime.skill(context.getSource(), id, "deposit", java.util.Map.of(
                                "item", IdentifierArgumentType.getIdentifier(context, "item").toString(),
                                "count", String.valueOf(IntegerArgumentType.getInteger(context, "count"))))))));
        root.then(CommandManager.literal("goto").then(withOptionalId(CommandManager.argument("target", BlockPosArgumentType.blockPos()),
                (runtime, context, id) -> {
                    var pos = BlockPosArgumentType.getBlockPos(context, "target");
                    return runtime.skill(context.getSource(), id, "move_to", java.util.Map.of(
                            "x", String.valueOf(pos.getX()), "y", String.valueOf(pos.getY()), "z", String.valueOf(pos.getZ())));
                })));
        root.then(withOptionalId(CommandManager.literal("gohome"), (runtime, context, id) -> runtime.skill(context.getSource(), id, "return_home", java.util.Map.of())));
        root.then(CommandManager.literal("wait").then(withOptionalId(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 60)),
                (runtime, context, id) -> runtime.skill(context.getSource(), id, "wait",
                        java.util.Map.of("seconds", String.valueOf(IntegerArgumentType.getInteger(context, "seconds")))))));
        root.then(withOptionalId(CommandManager.literal("look"), (runtime, context, id) -> runtime.look(context.getSource(), id, null))
                .then(withOptionalId(CommandManager.argument("block", IdentifierArgumentType.identifier()), (runtime, context, id) ->
                        runtime.look(context.getSource(), id, IdentifierArgumentType.getIdentifier(context, "block").toString()))));
        root.then(withOptionalId(CommandManager.literal("pause"), (runtime, context, id) -> runtime.pauseSkill(context.getSource(), id)));
        root.then(withOptionalId(CommandManager.literal("continue"), (runtime, context, id) -> runtime.continueSkill(context.getSource(), id)));
        root.then(CommandManager.literal("talk").then(CommandManager.argument("message", StringArgumentType.greedyString())
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    return runtime.talk(context.getSource(), null, StringArgumentType.getString(context, "message"));
                })));
        root.then(CommandManager.literal("talkto").then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.talk(context.getSource(), id,
                            StringArgumentType.getString(context, "message"));
                }))));
        root.then(withOptionalId(CommandManager.literal("duel"),
                (runtime, context, id) -> runtime.duel(context.getSource(), id)));
        root.then(withOptionalId(CommandManager.literal("suspend"),
                (runtime, context, id) -> runtime.suspend(context.getSource(), id)));
        root.then(withOptionalId(CommandManager.literal("recover"),
                (runtime, context, id) -> runtime.recover(context.getSource(), id)));
        root.then(withOptionalId(CommandManager.literal("remove"),
                (runtime, context, id) -> runtime.requestRemoval(context.getSource(), id)));
        root.then(CommandManager.literal("confirm-remove")
                .then(CommandManager.argument("token", UuidArgumentType.uuid()).executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    return runtime.confirmRemoval(context.getSource(), UuidArgumentType.getUuid(context, "token"));
                })));
        var nativePlayers = CommandManager.literal("native");
        nativePlayers.then(CommandManager.literal("create").requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("name", StringArgumentType.string()).executes(context -> {
                    var runtime = KindredMod.runtime();
                    return runtime == null ? unavailable(context.getSource())
                            : runtime.createNative(context.getSource(), StringArgumentType.getString(context, "name"));
                })));
        nativePlayers.then(CommandManager.literal("list").executes(context -> {
            var runtime = KindredMod.runtime();
            return runtime == null ? unavailable(context.getSource()) : runtime.listNative(context.getSource());
        }));
        for (String verb : new String[]{"status", "stop", "resume", "connect", "disconnect"}) {
            nativePlayers.then(CommandManager.literal(verb).executes(context -> {
                var runtime = KindredMod.runtime();
                return runtime == null ? unavailable(context.getSource())
                        : runtime.nativeLifecycle(context.getSource(), verb, null);
            }).then(CommandManager.argument("target", StringArgumentType.string())
                    .suggests((context, builder) -> suggestBots(builder))
                    .executes(context -> {
                        var runtime = KindredMod.runtime();
                        if (runtime == null) return unavailable(context.getSource());
                        UUID id = resolveBotTarget(context, "target", runtime);
                        if (id == null) return 0;
                        return runtime.nativeLifecycle(context.getSource(), verb, id);
                    })));
        }
        for (String task : new String[]{"wait", "eat", "equip"}) {
            nativePlayers.then(CommandManager.literal(task)
                    .then(CommandManager.argument("value", IntegerArgumentType.integer(task.equals("wait") ? 1 : 0,
                            task.equals("wait") ? 4 : task.equals("equip") ? 35 : 8))
                            .executes(context -> {
                                var runtime = KindredMod.runtime();
                                return runtime == null ? unavailable(context.getSource()) : runtime.nativeSkill(context.getSource(),
                                        null, "native_" + task, IntegerArgumentType.getInteger(context, "value"));
                            })
                            .then(CommandManager.argument("target", StringArgumentType.string())
                                    .suggests((context, builder) -> suggestBots(builder))
                                    .executes(context -> {
                                        var runtime = KindredMod.runtime();
                                        if (runtime == null) return unavailable(context.getSource());
                                        UUID id = resolveBotTarget(context, "target", runtime);
                                        if (id == null) return 0;
                                        return runtime.nativeSkill(context.getSource(),
                                                id, "native_" + task, IntegerArgumentType.getInteger(context, "value"));
                                    }))));
        }
        nativePlayers.then(CommandManager.literal("walk").then(CommandManager.argument("direction", StringArgumentType.word())
                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(1, 3))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            return runtime == null ? unavailable(context.getSource()) : runtime.nativeWalk(context.getSource(),
                                    null, StringArgumentType.getString(context, "direction"), IntegerArgumentType.getInteger(context, "blocks"));
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.nativeWalk(context.getSource(),
                                            id, StringArgumentType.getString(context, "direction"), IntegerArgumentType.getInteger(context, "blocks"));
                                })))));
        nativePlayers.then(CommandManager.literal("traverse")
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal("Usage: /aiplayer native traverse <north|south|east|west> <1..3> [bot] - moves forward while navigating 1-block elevation changes."), false);
                    return 1;
                })
                .then(CommandManager.argument("direction", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("north");
                            builder.suggest("south");
                            builder.suggest("east");
                            builder.suggest("west");
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("blocks", IntegerArgumentType.integer(1, 3))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    return runtime == null ? unavailable(context.getSource()) : runtime.nativeTraverse(context.getSource(),
                                            null, StringArgumentType.getString(context, "direction"), IntegerArgumentType.getInteger(context, "blocks"));
                                })
                                .then(CommandManager.argument("target", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestBots(builder))
                                        .executes(context -> {
                                            var runtime = KindredMod.runtime();
                                            if (runtime == null) return unavailable(context.getSource());
                                            UUID id = resolveBotTarget(context, "target", runtime);
                                            if (id == null) return 0;
                                            return runtime.nativeTraverse(context.getSource(),
                                                    id, StringArgumentType.getString(context, "direction"), IntegerArgumentType.getInteger(context, "blocks"));
                                        })))));
        nativePlayers.then(CommandManager.literal("patch")
                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                        .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                                .then(CommandManager.argument("resource", IdentifierArgumentType.identifier())
                                        .executes(context -> {
                                            var runtime = KindredMod.runtime();
                                            return runtime == null ? unavailable(context.getSource()) : runtime.nativePatch(context.getSource(),
                                                    null, BlockPosArgumentType.getBlockPos(context, "from"),
                                                    BlockPosArgumentType.getBlockPos(context, "to"), IdentifierArgumentType.getIdentifier(context, "resource").toString());
                                        })
                                        .then(CommandManager.argument("target", StringArgumentType.string())
                                                .suggests((context, builder) -> suggestBots(builder))
                                                .executes(context -> {
                                                    var runtime = KindredMod.runtime();
                                                    if (runtime == null) return unavailable(context.getSource());
                                                    UUID id = resolveBotTarget(context, "target", runtime);
                                                    if (id == null) return 0;
                                                    return runtime.nativePatch(context.getSource(),
                                                            id, BlockPosArgumentType.getBlockPos(context, "from"),
                                                            BlockPosArgumentType.getBlockPos(context, "to"), IdentifierArgumentType.getIdentifier(context, "resource").toString());
                                                }))))));
        nativePlayers.then(CommandManager.literal("mine").then(CommandManager.argument("targetPos", BlockPosArgumentType.blockPos())
                .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 8))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            return runtime == null ? unavailable(context.getSource()) : runtime.nativeMine(context.getSource(),
                                    null, BlockPosArgumentType.getBlockPos(context, "targetPos"), IntegerArgumentType.getInteger(context, "slot"));
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.nativeMine(context.getSource(),
                                            id, BlockPosArgumentType.getBlockPos(context, "targetPos"), IntegerArgumentType.getInteger(context, "slot"));
                                })))));
        nativePlayers.then(CommandManager.literal("goto").then(CommandManager.argument("targetPos", BlockPosArgumentType.blockPos())
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    return runtime == null ? unavailable(context.getSource()) : runtime.nativeGoto(context.getSource(),
                            null, BlockPosArgumentType.getBlockPos(context, "targetPos"), 1.5);
                })
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.nativeGoto(context.getSource(), id, BlockPosArgumentType.getBlockPos(context, "targetPos"), 1.5);
                        }))));
        nativePlayers.then(CommandManager.literal("break").then(CommandManager.argument("targetPos", BlockPosArgumentType.blockPos())
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    return runtime == null ? unavailable(context.getSource()) : runtime.nativeBreak(context.getSource(),
                            null, BlockPosArgumentType.getBlockPos(context, "targetPos"));
                })
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.nativeBreak(context.getSource(), id, BlockPosArgumentType.getBlockPos(context, "targetPos"));
                        }))));
        nativePlayers.then(CommandManager.literal("gather").then(CommandManager.argument("count", IntegerArgumentType.integer(1, 32))
                .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 8))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            return runtime == null ? unavailable(context.getSource()) : runtime.nativeGather(context.getSource(),
                                    null, IntegerArgumentType.getInteger(context, "count"), IntegerArgumentType.getInteger(context, "slot"));
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.nativeGather(context.getSource(),
                                            id, IntegerArgumentType.getInteger(context, "count"), IntegerArgumentType.getInteger(context, "slot"));
                                })))));
        nativePlayers.then(CommandManager.literal("craft").then(CommandManager.argument("recipe", StringArgumentType.string())
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    return runtime == null ? unavailable(context.getSource()) : runtime.nativeCraft(context.getSource(),
                            null, StringArgumentType.getString(context, "recipe"));
                })
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.nativeCraft(context.getSource(),
                                    id, StringArgumentType.getString(context, "recipe"));
                        }))));
        nativePlayers.then(CommandManager.literal("place")
                .then(CommandManager.argument("targetPos", BlockPosArgumentType.blockPos())
                        .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 8))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    return runtime == null ? unavailable(context.getSource()) : runtime.nativePlace(context.getSource(),
                                            null, BlockPosArgumentType.getBlockPos(context, "targetPos"),
                                            "up", IntegerArgumentType.getInteger(context, "slot"));
                                })
                                .then(CommandManager.argument("side", StringArgumentType.word())
                                        .executes(context -> {
                                            var runtime = KindredMod.runtime();
                                            return runtime == null ? unavailable(context.getSource()) : runtime.nativePlace(context.getSource(),
                                                    null, BlockPosArgumentType.getBlockPos(context, "targetPos"),
                                                    StringArgumentType.getString(context, "side"), IntegerArgumentType.getInteger(context, "slot"));
                                        })
                                        .then(CommandManager.argument("target", StringArgumentType.string())
                                                .suggests((context, builder) -> suggestBots(builder))
                                                .executes(context -> {
                                                    var runtime = KindredMod.runtime();
                                                    if (runtime == null) return unavailable(context.getSource());
                                                    UUID id = resolveBotTarget(context, "target", runtime);
                                                    if (id == null) return 0;
                                                    return runtime.nativePlace(context.getSource(),
                                                            id, BlockPosArgumentType.getBlockPos(context, "targetPos"),
                                                            StringArgumentType.getString(context, "side"), IntegerArgumentType.getInteger(context, "slot"));
                                                }))))));
        nativePlayers.then(CommandManager.literal("smelt").executes(context -> {
            var runtime = KindredMod.runtime();
            return runtime == null ? unavailable(context.getSource()) : runtime.nativeSmelt(context.getSource(), null, null, null);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.nativeSmelt(context.getSource(), id, null, null);
                }))
                .then(CommandManager.argument("targetPos", BlockPosArgumentType.blockPos())
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            return runtime == null ? unavailable(context.getSource()) : runtime.nativeSmelt(context.getSource(),
                                    null, BlockPosArgumentType.getBlockPos(context, "targetPos"), null);
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.nativeSmelt(context.getSource(),
                                            id, BlockPosArgumentType.getBlockPos(context, "targetPos"), null);
                                }))));
        nativePlayers.then(CommandManager.literal("attack")
                .then(CommandManager.argument("targetEntity", UuidArgumentType.uuid())
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            return runtime == null ? unavailable(context.getSource()) : runtime.nativeAttack(context.getSource(),
                                    null, UuidArgumentType.getUuid(context, "targetEntity"), null);
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.nativeAttack(context.getSource(),
                                            id, UuidArgumentType.getUuid(context, "targetEntity"), null);
                                }))
                        .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 8))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    return runtime == null ? unavailable(context.getSource()) : runtime.nativeAttack(context.getSource(),
                                            null, UuidArgumentType.getUuid(context, "targetEntity"),
                                            IntegerArgumentType.getInteger(context, "slot"));
                                })
                                .then(CommandManager.argument("target", StringArgumentType.string())
                                        .suggests((context, builder) -> suggestBots(builder))
                                        .executes(context -> {
                                            var runtime = KindredMod.runtime();
                                            if (runtime == null) return unavailable(context.getSource());
                                            UUID id = resolveBotTarget(context, "target", runtime);
                                            if (id == null) return 0;
                                            return runtime.nativeAttack(context.getSource(),
                                                    id, UuidArgumentType.getUuid(context, "targetEntity"),
                                                    IntegerArgumentType.getInteger(context, "slot"));
                                        })))));
        nativePlayers.then(CommandManager.literal("block").executes(context -> {
            var runtime = KindredMod.runtime();
            return runtime == null ? unavailable(context.getSource()) : runtime.nativeBlock(context.getSource(), null, 20);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.nativeBlock(context.getSource(), id, 20);
                }))
                .then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 100))
                        .executes(context -> {
                            var runtime = KindredMod.runtime();
                            return runtime == null ? unavailable(context.getSource()) : runtime.nativeBlock(context.getSource(),
                                    null, IntegerArgumentType.getInteger(context, "ticks"));
                        })
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .suggests((context, builder) -> suggestBots(builder))
                                .executes(context -> {
                                    var runtime = KindredMod.runtime();
                                    if (runtime == null) return unavailable(context.getSource());
                                    UUID id = resolveBotTarget(context, "target", runtime);
                                    if (id == null) return 0;
                                    return runtime.nativeBlock(context.getSource(),
                                            id, IntegerArgumentType.getInteger(context, "ticks"));
                                }))));
        nativePlayers.then(CommandManager.literal("progression").executes(context -> {
            var runtime = KindredMod.runtime();
            return runtime == null ? unavailable(context.getSource()) : runtime.nativeProgression(context.getSource(), null);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.nativeProgression(context.getSource(), id);
                })));
        var apikey = CommandManager.literal("apikey")
                .executes(context -> {
                    KindredApiKeyConfig.displayStatusAndPrompts(context.getSource());
                    return 1;
                })
                .then(CommandManager.literal("status").executes(context -> {
                    KindredApiKeyConfig.displayStatusAndPrompts(context.getSource());
                    return 1;
                }))
                .then(CommandManager.argument("provider", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("anthropic");
                            builder.suggest("openai");
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("key", StringArgumentType.greedyString())
                                .executes(context -> {
                                    if (!context.getSource().hasPermissionLevel(2) && !context.getSource().getServer().isSingleplayer()) {
                                        context.getSource().sendError(Text.literal("Only an operator or singleplayer host may set API keys."));
                                        return 0;
                                    }
                                    String provider = StringArgumentType.getString(context, "provider");
                                    String key = StringArgumentType.getString(context, "key");
                                    if (!provider.equalsIgnoreCase("anthropic") && !provider.equalsIgnoreCase("openai")) {
                                        context.getSource().sendError(Text.literal("Unknown provider '" + provider + "'. Use 'anthropic' or 'openai'."));
                                        return 0;
                                    }
                                    boolean ok = KindredApiKeyConfig.setKey(provider, key);
                                    if (ok) {
                                        context.getSource().sendFeedback(() -> Text.literal("§aSaved " + provider + " API key: "
                                                + KindredApiKeyConfig.mask(key) + ". Autonomous reasoning is now active."), false);
                                        return 1;
                                    } else {
                                        context.getSource().sendError(Text.literal("Failed to write key to config."));
                                        return 0;
                                    }
                                })));
        root.then(apikey);
        root.then(CommandManager.literal("key").executes(context -> {
            KindredApiKeyConfig.displayStatusAndPrompts(context.getSource());
            return 1;
        }));
        root.then(CommandManager.literal("progression").executes(context -> {
            var runtime = KindredMod.runtime();
            return runtime == null ? unavailable(context.getSource()) : runtime.nativeProgression(context.getSource(), null);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    var runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return runtime.nativeProgression(context.getSource(), id);
                })));
        root.then(nativePlayers);
        var primary = dispatcher.register(root);
        dispatcher.register(CommandManager.literal("companion").executes(primary.getCommand()).redirect(primary));
    }

    @FunctionalInterface
    private interface Handler {
        int run(PlayerRuntime runtime, CommandContext<ServerCommandSource> context, UUID id);
    }

    private static CompletableFuture<Suggestions> suggestBots(SuggestionsBuilder builder) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime != null) {
            String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (String name : runtime.availableBotNames()) {
                if (name.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }

    /** AI players and the humans online, for a match's players. */
    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
        PlayerRuntime runtime = KindredMod.runtime();
        var names = new java.util.LinkedHashSet<String>();
        if (runtime != null) names.addAll(runtime.availableBotNames());
        for (var player : context.getSource().getServer().getPlayerManager().getPlayerList()) names.add(player.getName().getString());
        for (String name : names) if (name.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) builder.suggest(name);
        return builder.buildFuture();
    }

    /** A match's player by name: an AI player or a human online, as its player id. */
    private static UUID resolveMatchPlayer(CommandContext<ServerCommandSource> context, String argName, PlayerRuntime runtime) {
        String raw = StringArgumentType.getString(context, argName);
        UUID player = runtime.matchPlayerId(raw);
        if (player == null) context.getSource().sendError(Text.literal("No such player online: '" + raw + "'."));
        return player;
    }

    private static int matchCommand(CommandContext<ServerCommandSource> context, java.util.function.BiFunction<PlayerRuntime, UUID, Integer> action) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime == null) return unavailable(context.getSource());
        var match = runtime.findMatch(StringArgumentType.getString(context, "match"));
        if (match.isEmpty()) {
            context.getSource().sendError(Text.literal("No match with that id; /aiplayer match list shows them."));
            return 0;
        }
        return action.apply(runtime, match.get().id());
    }

    private static dev.kindred.core.survival.SurvivalGoal goal(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "nether", "portal", "nether_portal" -> dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL;
            case "end", "stronghold", "end_portal" -> dev.kindred.core.survival.SurvivalGoal.STRONGHOLD;
            case "dragon", "ender_dragon", "beat", "beat_the_game" -> dev.kindred.core.survival.SurvivalGoal.DRAGON;
            default -> dev.kindred.core.survival.SurvivalGoal.NONE;
        };
    }

    private static UUID resolveBotTarget(CommandContext<ServerCommandSource> context, String argName, PlayerRuntime runtime) {
        try {
            String raw = StringArgumentType.getString(context, argName);
            UUID id = runtime.resolveBotId(raw);
            if (id == null) {
                context.getSource().sendError(Text.literal("Unknown AI player '" + raw + "'. Available: " + runtime.availableBotNames()));
                return null;
            }
            return id;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static dev.kindred.core.progression.PlaystyleProfile parseProfile(String raw) {
        return switch (raw.toUpperCase(java.util.Locale.ROOT)) {
            case "IDLE", "IDLE_OBSERVING" -> dev.kindred.core.progression.PlaystyleProfile.IDLE_OBSERVING;
            case "SPEEDRUN", "SPEEDRUN_ANY_PERCENT" -> dev.kindred.core.progression.PlaystyleProfile.SPEEDRUN_ANY_PERCENT;
            case "OPTIMAL", "OPTIMAL_SURVIVAL" -> dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL;
            default -> null;
        };
    }

    /** Executes the node itself (own companion / single bot) and with an explicit bot name or UUID. */
    private static <T extends ArgumentBuilder<ServerCommandSource, T>> T withOptionalId(T node, Handler handler) {
        node.executes(context -> {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) return unavailable(context.getSource());
            return handler.run(runtime, context, null);
        }).then(CommandManager.argument("target", StringArgumentType.string())
                .suggests((context, builder) -> suggestBots(builder))
                .executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    UUID id = resolveBotTarget(context, "target", runtime);
                    if (id == null) return 0;
                    return handler.run(runtime, context, id);
                }));
        return node;
    }

    private static int build(CommandContext<ServerCommandSource> context, boolean start, boolean turned, boolean named) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime == null) return unavailable(context.getSource());
        UUID id = named ? resolveBotTarget(context, "target", runtime) : runtime.defaultBotId();
        if (id == null) {
            if (!named) context.getSource().sendError(Text.literal("Name the AI player. Available: " + runtime.availableBotNames()));
            return 0;
        }
        if (!start) return runtime.startBuild(context.getSource(), id, null, null, 0);
        String name = StringArgumentType.getString(context, "blueprint");
        BlockPos at = BlockPosArgumentType.getBlockPos(context, "at");
        int turns = turned ? IntegerArgumentType.getInteger(context, "turns") : 0;
        var parts = name.split("-", 2);
        if (!dev.kindred.core.construction.Generators.NAMES.contains(parts[0])) return runtime.startSchematicBuild(context.getSource(), id, name, at, turns);
        dev.kindred.core.construction.Blueprint blueprint;
        try {
            var sizes = new java.util.ArrayList<Integer>();
            if (parts.length > 1) for (String size : parts[1].split("x")) sizes.add(Integer.parseInt(size));
            blueprint = dev.kindred.core.construction.Generators.named(parts[0], sizes);
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendError(Text.literal(invalid.getMessage() == null ? "Not a blueprint: " + name : invalid.getMessage()));
            return 0;
        }
        return runtime.startBuild(context.getSource(), id, blueprint, at, turns);
    }

    private static int ranch(CommandContext<ServerCommandSource> context, boolean start, boolean named) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime == null) return unavailable(context.getSource());
        UUID id = named ? resolveBotTarget(context, "target", runtime) : runtime.defaultBotId();
        if (id == null) {
            if (!named) context.getSource().sendError(Text.literal("Name the AI player. Available: " + runtime.availableBotNames()));
            return 0;
        }
        if (!start) return runtime.startRanch(context.getSource(), id, null, null);
        String animal = StringArgumentType.getString(context, "animal").toUpperCase(java.util.Locale.ROOT);
        var kind = java.util.Arrays.stream(dev.kindred.core.survival.Ranch.Kind.values()).filter(k -> k.name().equals(animal)).findFirst();
        if (kind.isEmpty()) {
            context.getSource().sendError(Text.literal("A ranch is for cows, sheep, pigs or chickens: cow, sheep, pig, chicken"));
            return 0;
        }
        return runtime.startRanch(context.getSource(), id, BlockPosArgumentType.getBlockPos(context, "at"), kind.get());
    }

    private static int unavailable(ServerCommandSource source) {
        source.sendError(Text.literal("Kindred is not ready yet."));
        return 0;
    }
}
