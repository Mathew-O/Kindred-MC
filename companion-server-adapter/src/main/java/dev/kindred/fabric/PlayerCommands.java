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
        root.then(CommandManager.literal("gui").executes(context -> {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) return unavailable(context.getSource());
            return runtime.openControlGui(context.getSource());
        }));
        root.then(CommandManager.literal("settings").then(CommandManager.literal("seedlocator")
                .then(CommandManager.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                    PlayerRuntime runtime = KindredMod.runtime();
                    if (runtime == null) return unavailable(context.getSource());
                    return runtime.setSeedLocatorEnabled(context.getSource(), null,
                            BoolArgumentType.getBool(context, "enabled"));
                }).then(CommandManager.argument("target", StringArgumentType.string())
                        .suggests((context, builder) -> suggestBots(builder))
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            UUID id = resolveBotTarget(context, "target", runtime);
                            if (id == null) return 0;
                            return runtime.setSeedLocatorEnabled(context.getSource(), id,
                                    BoolArgumentType.getBool(context, "enabled"));
                        })))));
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

    private static int unavailable(ServerCommandSource source) {
        source.sendError(Text.literal("Kindred is not ready yet."));
        return 0;
    }
}
