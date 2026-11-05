package dev.kindred.fabric;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In plain terms: the mod's starting point. As the game loads it, this registers what the mod adds (the older
 * mob-style body, the control panel, skins and commands) and ties the AI players' runtime to the server: it
 * starts and stops with the server, hears chat, and is told when blocks are broken, players die and players
 * leave. It keeps the server's customs, learned only from people who opted in for themselves.
 */
public final class KindredMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Kindred");
    public static final EntityType<CompanionEntity> COMPANION = Registry.register(
            Registries.ENTITY_TYPE, Identifier.of("kindred", "companion"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, CompanionEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.95f)).trackRangeChunks(8)
                    .disableSummon().build());
    private static PlayerRuntime runtime;
    /** An unattended evaluation run, when {@code KINDRED_EVAL} asks for one (F-26): read here and nowhere else. */
    private static EvalBootstrap eval;
    /** Whether deaths are kept as replays (F-27): {@code KINDRED_REPLAYS=on|off}, on by default under an evaluation run. */
    private static boolean replays;
    /** Whether the players draw their own lessons from their deaths (E-46): {@code KINDRED_LESSONS=off} turns them off. */
    private static boolean lessons;
    /** Whether the players keep and use an atlas of the world (F-30): {@code KINDRED_ATLAS=off} turns it off. */
    private static boolean atlas;
    /** Whether a stuck player asks the strategist for a way out (F-29): {@code KINDRED_RESCUE=off} turns it off. */
    private static boolean rescue;
    /** The watch page (E-48), when {@code KINDRED_WATCH_PORT} and {@code KINDRED_WATCH_TOKEN} are set: off otherwise. */
    private static WatchServer watch;
    /** The server's customs (E-47), learned only from those who opted in for themselves: kept while the server runs. */
    private static ServerCustomsKeeper customs;

    static PlayerRuntime runtime() { return runtime; }

    static ServerCustomsKeeper customs() { return customs; }

    /** Sets everything up once, as the mod is loaded. */
    @Override
    public void onInitialize() {
        PolymerEntityUtils.registerType(COMPANION);
        FabricDefaultAttributeRegistry.register(COMPANION, MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48));
        KindredPanelPayloads.register();
        KindredPanelService.register();
        KindredSkinPayloads.register();
        KindredSkins.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                PlayerCommands.register(dispatcher));
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, position, state, blockEntity) ->
                NativePlayerBody.allowBreak(world, player, position, state));
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) ->
                NativePlayerBody.observedBreak(world, player, position, state));
        // An ore broken by a person who opted in teaches the players the depth this server mines it at (E-47).
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, position, state, blockEntity) -> {
            if (customs != null && player instanceof net.minecraft.server.network.ServerPlayerEntity person) customs.broke(world, person, position, state);
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, parameters) -> {
            PlayerRuntime receivingRuntime = runtime;
            var senderId = sender.getUuid();
            String content = message.getSignedContent();
            sender.getServer().execute(() -> {
                if (receivingRuntime != null && runtime == receivingRuntime) receivingRuntime.chat(senderId, content);
            });
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> {
            // Work handed back from other threads first, every runtime's, as each tick starts (F-26a).
            PlayerRuntime.runHandedBack(server);
            PlayerRuntime rt = runtime;
            if (rt != null) rt.onServerTick(server);
        });
        eval = EvalBootstrap.fromEnvironment().orElse(null);
        String replaysAsked = System.getenv("KINDRED_REPLAYS");
        replays = replaysAsked != null ? replaysAsked.equalsIgnoreCase("on") : eval != null;
        lessons = !"off".equalsIgnoreCase(String.valueOf(System.getenv("KINDRED_LESSONS")).trim());
        atlas = !"off".equalsIgnoreCase(String.valueOf(System.getenv("KINDRED_ATLAS")).trim());
        rescue = !"off".equalsIgnoreCase(String.valueOf(System.getenv("KINDRED_RESCUE")).trim());
        if (eval != null) {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> eval.tick(server, runtime));
        }
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (customs != null) customs.tick(server.getTicks());
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            runtime = new PlayerRuntime(server);
            runtime.start();
            customs = ServerCustomsKeeper.open(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("kindred"));
            runtime.customs(customs.customs());
            if (!lessons) {
                runtime.lessons(false);
                LOGGER.info("The players' own lessons from their deaths are off (KINDRED_LESSONS=off)");
            }
            if (!atlas) {
                runtime.atlas(false);
                LOGGER.info("The players' atlas of the world is off (KINDRED_ATLAS=off)");
            }
            if (!rescue) {
                runtime.rescue(false);
                LOGGER.info("Rescue plans are off (KINDRED_RESCUE=off)");
            }
            if (System.getenv("KINDRED_WATCH_PORT") != null) {
                try {
                    watch = WatchServer.fromEnvironment(server::isOnThread).orElse(null);
                } catch (LinkageError missing) {
                    LOGGER.warn("The watch page needs the JDK's jdk.httpserver module, which this Java lacks; it is off");
                }
                if (watch != null) runtime.watch(watch);
            }
            if (replays) {
                var run = server.getRunDirectory();
                runtime.captureReplays(new DeathReplays(run.resolve("kindred").resolve("replays").resolve(server.getSaveProperties().getLevelName()),
                        run.resolve("logs").resolve("latest.log")));
                LOGGER.info("Deaths are kept as replays under kindred/replays/");
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof CompanionEntity companion && runtime != null) runtime.loaded(companion);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof CompanionEntity companion && runtime != null) runtime.unloaded(companion);
        });
        // A player's fall ends a duel it was in (M-00a); a native player's own death is handled by its runtime as well.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof net.minecraft.server.network.ServerPlayerEntity fallen && runtime != null) runtime.playerFell(fallen.getUuid());
        });
        // A build battle's plots are their builders' alone while it runs (M-04a).
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                !(player instanceof net.minecraft.server.network.ServerPlayerEntity builder) || runtime == null || runtime.mayEditAt(builder, pos));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity builder) || runtime == null) return net.minecraft.util.ActionResult.PASS;
            boolean placing = !player.getStackInHand(hand).isEmpty() && player.getStackInHand(hand).getItem() instanceof net.minecraft.item.BlockItem;
            return !placing || runtime.mayEditAt(builder, hit.getBlockPos().offset(hit.getSide())) && runtime.mayEditAt(builder, hit.getBlockPos())
                    ? net.minecraft.util.ActionResult.PASS : net.minecraft.util.ActionResult.FAIL;
        });
        // Leaving the game forfeits a duel, or calls it off before its start (M-00a).
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            java.util.UUID left = handler.player.getUuid();
            if (server.isOnThread()) {
                if (runtime != null) runtime.playerLeft(left);
            } else {
                server.execute(() -> { if (runtime != null) runtime.playerLeft(left); });
            }
        });
        // A strike at a player refused by the match both are in (M-00, M-02b): its lobby, countdown or head start, the
        // same side, or a mode with no striking; the same for humans and AI players. Outside matches the server decides.
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (runtime == null || world.isClient() || !(player instanceof net.minecraft.server.network.ServerPlayerEntity attacker)
                    || !(entity instanceof net.minecraft.server.network.ServerPlayerEntity target)) return net.minecraft.util.ActionResult.PASS;
            return runtime.isReady() && runtime.matchForbidsStrike(attacker.getUuid(), target.getUuid())
                    ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
        });
        // A player joining is told the world's mode and its rules (M-00).
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var joined = handler.player;
            server.execute(() -> { if (runtime != null && !joined.isDisconnected()) runtime.playerJoined(joined); });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (eval != null) eval.stopping(server);
            if (runtime != null) runtime.stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (watch != null) {
                watch.close();
                watch = null;
            }
            if (runtime != null) runtime.finishShutdown();
            runtime = null;
            if (customs != null) {
                customs.close();
                customs = null;
            }
        });
    }
}
