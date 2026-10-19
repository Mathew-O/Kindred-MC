package dev.kindred.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kindred.core.Actor;
import dev.kindred.core.CanonicalRoster;
import dev.kindred.core.progression.PlaystyleProfile;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server half of the optional keybind panel. Every action runs through the same runtime entry points and
 * authority checks as the matching {@code /aiplayer} command, using the requesting player's command source.
 */
final class KindredPanelService {
    private KindredPanelService() { }

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(KindredPanelPayloads.Request.ID,
                (payload, context) -> sendState(context.player(), ""));
        ServerPlayNetworking.registerGlobalReceiver(KindredPanelPayloads.Action.ID,
                (payload, context) -> handle(context.player(), payload));
    }

    private static void handle(ServerPlayerEntity player, KindredPanelPayloads.Action action) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (runtime == null || !runtime.isReady()) {
            sendState(player, "Kindred is still loading saved records.");
            return;
        }
        // Capture the command feedback so the panel can show the outcome of what it asked for.
        var feedback = new StringBuilder();
        ServerCommandSource source = player.getCommandSource().withOutput(new net.minecraft.server.command.CommandOutput() {
            @Override public void sendMessage(Text message) {
                if (!feedback.isEmpty()) feedback.append(' ');
                feedback.append(message.getString());
            }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        });
        UUID id = parse(action.playerId());
        String argument = action.argument();
        switch (action.action()) {
            case "spawn" -> runtime.spawnCanonicalRoster(source, argument);
            case "connect", "disconnect", "stop", "resume" -> {
                if (id != null) runtime.nativeLifecycle(source, action.action(), id);
            }
            case "goal" -> {
                if (id != null) runtime.startGoal(source, id, SurvivalGoal.parse(argument));
            }
            case "playstyle" -> {
                if (id != null) runtime.setPlaystyleMode(source, id, playstyle(argument));
            }
            case "collaboration" -> {
                if (id != null) runtime.toggleCollaborationForBot(source, id);
            }
            case "cap" -> {
                if (id != null) {
                    try { runtime.nativeSetCap(source, id, Integer.parseInt(argument)); }
                    catch (NumberFormatException invalid) { feedback.append("Choose a whole number of hours."); }
                }
            }
            case "edits" -> runtime.setWorldEdits(source, Boolean.parseBoolean(argument));
            default -> feedback.append("Unknown panel action.");
        }
        // Lifecycle changes complete asynchronously; send the immediate outcome, and the client refreshes.
        sendState(player, feedback.toString());
    }

    static void sendState(ServerPlayerEntity player, String message) {
        PlayerRuntime runtime = KindredMod.runtime();
        if (!ServerPlayNetworking.canSend(player, KindredPanelPayloads.State.ID)) return;
        if (runtime == null || !runtime.isReady()) {
            ServerPlayNetworking.send(player, new KindredPanelPayloads.State("{\"ready\":false}",
                    message.isEmpty() ? "Kindred is still loading saved records." : message));
            return;
        }
        boolean operator = player.getCommandSource().hasPermissionLevel(2);
        Actor actor = new Actor(player.getUuid(), operator);
        List<PlayerRuntime.PanelBot> bots = runtime.panelBots(actor);
        Consumer<java.util.Map<UUID, Long>> reply = caps -> {
            if (player.isDisconnected()) return;
            JsonObject root = new JsonObject();
            root.addProperty("ready", true);
            root.addProperty("operator", operator);
            root.addProperty("worldEdits", runtime.worldEditsEnabled());
            JsonArray roster = new JsonArray();
            for (var preset : CanonicalRoster.TRIO) {
                JsonObject slot = new JsonObject();
                slot.addProperty("name", preset.name());
                slot.addProperty("exists", bots.stream().anyMatch(bot -> bot.name().equalsIgnoreCase(preset.name())));
                roster.add(slot);
            }
            root.add("roster", roster);
            JsonArray list = new JsonArray();
            for (var bot : bots) {
                JsonObject json = new JsonObject();
                json.addProperty("id", bot.id().toString());
                json.addProperty("name", bot.name());
                json.addProperty("connected", bot.connected());
                json.addProperty("phase", bot.phase());
                json.addProperty("stopped", bot.stopped());
                json.addProperty("busy", bot.busy());
                json.addProperty("playstyle", bot.playstyle());
                json.addProperty("collaboration", bot.collaboration());
                json.addProperty("goal", bot.goal().name());
                json.addProperty("goalLabel", bot.goal().label());
                json.addProperty("goalProgress", bot.goalProgress());
                json.addProperty("status", bot.status());
                json.addProperty("task", bot.task() == null ? "" : bot.task());
                json.addProperty("health", bot.health());
                json.addProperty("food", bot.food());
                json.addProperty("dimension", bot.dimension());
                if (bot.position() != null) {
                    json.addProperty("x", bot.position().getX());
                    json.addProperty("y", bot.position().getY());
                    json.addProperty("z", bot.position().getZ());
                }
                Long cap = caps.get(bot.id());
                if (cap != null) json.addProperty("capHours", cap / 3_600_000L);
                list.add(json);
            }
            root.add("bots", list);
            ServerPlayNetworking.send(player, new KindredPanelPayloads.State(root.toString(), message));
        };
        runtime.nativeCaps(bots.stream().map(PlayerRuntime.PanelBot::id).toList())
                .whenComplete((caps, failure) -> player.server.execute(() -> reply.accept(caps == null ? java.util.Map.of() : caps)));
    }

    private static UUID parse(String raw) {
        try { return raw == null || raw.isBlank() ? null : UUID.fromString(raw); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static PlaystyleProfile playstyle(String raw) {
        return switch (raw == null ? "" : raw.toUpperCase(java.util.Locale.ROOT)) {
            case "SPEEDRUN", "SPEEDRUN_ANY_PERCENT" -> PlaystyleProfile.SPEEDRUN_ANY_PERCENT;
            case "IDLE", "IDLE_OBSERVING" -> PlaystyleProfile.IDLE_OBSERVING;
            default -> PlaystyleProfile.OPTIMAL_SURVIVAL;
        };
    }
}
