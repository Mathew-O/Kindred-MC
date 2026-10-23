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

public final class KindredMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Kindred");
    public static final EntityType<CompanionEntity> COMPANION = Registry.register(
            Registries.ENTITY_TYPE, Identifier.of("kindred", "companion"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, CompanionEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.95f)).trackRangeChunks(8)
                    .disableSummon().build());
    private static PlayerRuntime runtime;

    static PlayerRuntime runtime() { return runtime; }

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
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, parameters) -> {
            PlayerRuntime receivingRuntime = runtime;
            var senderId = sender.getUuid();
            String content = message.getSignedContent();
            sender.getServer().execute(() -> {
                if (receivingRuntime != null && runtime == receivingRuntime) receivingRuntime.chat(senderId, content);
            });
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> {
            PlayerRuntime rt = runtime;
            if (rt != null) rt.onServerTick(server);
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            runtime = new PlayerRuntime(server);
            runtime.start();
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
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (runtime != null) runtime.stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (runtime != null) runtime.finishShutdown();
            runtime = null;
        });
    }
}
