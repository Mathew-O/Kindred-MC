package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * In plain terms: the {@code /faction} command a person types (create, invite, join, leave, kick, promote, demote,
 * leader, ally, truce, enemy, sethome, home, colour, disband, info, list, power), and {@code /faction as <AI player> ...}
 * for an operator to make the same request for an AI player.
 *
 * Technical: Brigadier wiring only (M-06c); every request goes through {@link PlayerRuntime#faction}, the same method for
 * people and AI players. An AI player is entered by an operator, as it is entered in a match (D7); its own decisions
 * use the same method (M-06i).
 */
final class FactionCommands {
    static final String HELP = "/faction create <name> <tag> [colour] | invite|uninvite|kick|promote|demote|leader <player> | join <faction> | leave"
            + " | ally|truce|enemy <faction> | sethome | home | colour <dye> | disband | info [faction] | list | power [player]"
            + " | claim | unclaim | map | coins";

    private FactionCommands() {}

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("faction")
                .executes(context -> help(context.getSource()))
                .then(CommandManager.literal("admin").requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("hub").executes(context -> hub(context.getSource(), true))
                                .then(CommandManager.literal("zone").executes(context -> hub(context.getSource(), false)))))
                .then(CommandManager.literal("as").requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .then(CommandManager.argument("request", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            PlayerRuntime runtime = KindredMod.runtime();
                                            if (runtime == null) return unavailable(context.getSource());
                                            var player = context.getSource().getServer().getPlayerManager().getPlayer(StringArgumentType.getString(context, "player"));
                                            if (player == null) {
                                                context.getSource().sendError(Text.literal("No player online by that name"));
                                                return 0;
                                            }
                                            return run(runtime, context.getSource(), player.getUuid(), StringArgumentType.getString(context, "request"));
                                        }))))
                .then(CommandManager.argument("request", StringArgumentType.greedyString())
                        .executes(context -> {
                            PlayerRuntime runtime = KindredMod.runtime();
                            if (runtime == null) return unavailable(context.getSource());
                            var player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendError(Text.literal("Only a player makes faction requests; an operator uses /faction as <player> ..."));
                                return 0;
                            }
                            return run(runtime, context.getSource(), player.getUuid(), StringArgumentType.getString(context, "request"));
                        })));
    }

    private static int run(PlayerRuntime runtime, ServerCommandSource source, UUID who, String request) {
        List<String> words = Arrays.stream(request.trim().split("\\s+")).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) return help(source);
        return runtime.faction(source, who, words.getFirst(), words.subList(1, words.size())).accepted() ? 1 : 0;
    }

    private static int hub(ServerCommandSource source, boolean build) {
        PlayerRuntime runtime = KindredMod.runtime();
        return runtime == null ? unavailable(source) : runtime.factionsHub(source, build);
    }

    private static int help(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal(HELP), false);
        return 1;
    }

    private static int unavailable(ServerCommandSource source) {
        source.sendError(Text.literal("Kindred is not running"));
        return 0;
    }
}
