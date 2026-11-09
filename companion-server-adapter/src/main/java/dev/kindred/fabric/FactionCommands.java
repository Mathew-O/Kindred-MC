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
            + " | claim | unclaim | capture | map | coins";

    private FactionCommands() {}

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // The market (M-06g): /shop sell hand|all [confirm], /shop buy <item> <count>, /shop price [item]; /market top,
        // /market history <item>, /market baseline (operators); /sellall as /shop sell all.
        for (String root : new String[] {"shop", "market"}) {
            dispatcher.register(CommandManager.literal(root)
                    .executes(context -> market(context.getSource(), "help"))
                    .then(CommandManager.argument("request", StringArgumentType.greedyString())
                            .executes(context -> market(context.getSource(), StringArgumentType.getString(context, "request")))));
        }
        // The auction house (M-06h): /ah [list|mine], /ah sell <start> [buyNow] [hours], /ah bid|buy|cancel <id> ...
        dispatcher.register(CommandManager.literal("ah")
                .executes(context -> auction(context.getSource(), "list"))
                .then(CommandManager.argument("request", StringArgumentType.greedyString())
                        .executes(context -> auction(context.getSource(), StringArgumentType.getString(context, "request")))));
        dispatcher.register(CommandManager.literal("sellall")
                .executes(context -> market(context.getSource(), "sell all"))
                .then(CommandManager.literal("confirm").executes(context -> market(context.getSource(), "sell all confirm"))));
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

    private static int market(ServerCommandSource source, String request) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime == null) return unavailable(source);
        var player = source.getPlayer();
        List<String> words = Arrays.stream(request.trim().split("\\s+")).filter(w -> !w.isEmpty()).toList();
        if (player == null && !(words.size() > 0 && List.of("price", "top", "history", "baseline").contains(words.getFirst()))) {
            source.sendError(Text.literal("Only a player sells and buys; an operator uses /faction as <player> shop ..."));
            return 0;
        }
        return runtime.marketRequest(source, player == null ? new UUID(0, 0) : player.getUuid(), words.isEmpty() ? "help" : words.getFirst(), words.isEmpty() ? List.of() : words.subList(1, words.size()));
    }

    private static int auction(ServerCommandSource source, String request) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime == null) return unavailable(source);
        var player = source.getPlayer();
        List<String> words = Arrays.stream(request.trim().split("\\s+")).filter(w -> !w.isEmpty()).toList();
        return runtime.auctionRequest(source, player == null ? new UUID(0, 0) : player.getUuid(), words.isEmpty() ? "list" : words.getFirst(),
                words.isEmpty() ? List.of() : words.subList(1, words.size()));
    }

    private static int run(PlayerRuntime runtime, ServerCommandSource source, UUID who, String request) {
        List<String> words = Arrays.stream(request.trim().split("\\s+")).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) return help(source);
        if ((words.getFirst().equals("shop") || words.getFirst().equals("market")) && words.size() > 1) {
            return runtime.marketRequest(source, who, words.get(1), words.subList(2, words.size()));
        }
        if (words.getFirst().equals("ah")) return runtime.auctionRequest(source, who, words.size() > 1 ? words.get(1) : "list", words.size() > 2 ? words.subList(2, words.size()) : List.of());
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
