package dev.kindred.fabric;

import dev.kindred.core.ActionArbiter;
import dev.kindred.core.Actor;
import dev.kindred.core.Companion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Registered native players and native file round-trip; no human client or playthrough claim. */
public final class NativePlayerLifecycleGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void threeNativePlayersRegisterAndWorldTicksPhysicsOnlyOnce(TestContext context) {
        var bodies = new ArrayList<NativePlayerBody>();
        var ages = new ArrayList<Integer>();
        try {
            for (int index = 0; index < 3; index++) {
                NativePlayerBody body = body(context, identity(context), index);
                body.connect(Optional.empty(), false);
                bodies.add(body);
                ages.add(body.entity().age);
                context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(body.entity().getUuid()) == body.entity(), "Player manager owns native identity");
                context.assertTrue(context.getWorld().getEntity(body.entity().getUuid()) == body.entity(), "Native player is world registered");
                for (int tick = 0; tick < 3; tick++) body.entity().networkHandler.tick();
                context.assertTrue(body.entity().age == ages.get(index), "Network ticks cannot advance native physics");
            }
        } catch (RuntimeException | Error failure) {
            bodies.forEach(NativePlayerBody::disconnect);
            throw failure;
        }
        long time = context.getWorld().getTime();
        context.runAtTick(6, () -> {
            try {
                long elapsed = context.getWorld().getTime() - time;
                for (int index = 0; index < bodies.size(); index++) {
                    int advanced = bodies.get(index).entity().age - ages.get(index);
                    context.assertTrue(advanced > 0 && Math.abs(advanced - elapsed) <= 1, "One world physics tick per server tick, got " + advanced + " / " + elapsed);
                }
            } finally { bodies.forEach(NativePlayerBody::disconnect); }
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 160)
    public void nativeFileReconnectPreservesExactItemsAndSurvivalState(TestContext context) {
        Companion identity = identity(context);
        NativePlayerBody first = body(context, identity, 0);
        first.connect(Optional.empty(), false);
        var tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamage(13);
        tool.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Native persisted tool"));
        first.entity().getInventory().setStack(3, tool);
        first.entity().getInventory().selectedSlot = 3;
        first.entity().getInventory().setStack(8, new ItemStack(Items.APPLE, 7));
        first.entity().getHungerManager().setFoodLevel(9);
        first.entity().setHealth(11);
        first.saveNow(); // Vanilla writer on the server thread before disconnect races async readers.
        first.disconnect();
        @SuppressWarnings("unchecked")
        CompletableFuture<Optional<NativePlayerSaves.Bytes>>[] read = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> read[0] = NativePlayerSaves.read(
                        context.getWorld().getServer().getSavePath(WorldSavePath.PLAYERDATA), identity.entityId()))
                .createAndAdd(() -> context.assertTrue(read[0].isDone() && !read[0].isCompletedExceptionally(), "Native save bytes read asynchronously"))
                .createAndAdd(() -> {
                    var saved = read[0].getNow(Optional.empty());
                    context.assertTrue(saved.isPresent(), "Native save file missing after disconnect");
                    var bytes = saved.orElseThrow();
                    NativePlayerBody restored = body(context, identity, 0);
                    try {
                        restored.connect(Optional.of(bytes.decode(context.getWorld().getServer())), true);
                        var actual = restored.entity().getMainHandStack();
                        context.assertTrue(actual.isOf(Items.IRON_PICKAXE) && actual.getDamage() == 13, "Owned selected tool and wear persist");
                        context.assertTrue(actual.get(DataComponentTypes.CUSTOM_NAME).getString().equals("Native persisted tool"), "Native components persist");
                        context.assertTrue(restored.entity().getInventory().getStack(8).getCount() == 7, "Exact physical food count persists");
                        context.assertTrue(restored.entity().getHealth() == 11 && restored.entity().getHungerManager().getFoodLevel() == 9, "Native health/hunger persist without replenishment");
                    } finally { restored.disconnect(); }
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void absentForeignAndWrongVersionSavesNeverRegisterAnExistingBody(TestContext context) {
        Companion identity = identity(context);
        NativePlayerBody body = body(context, identity, 0);
        rejects(context, () -> body.connect(Optional.empty(), true), "Missing existing save blocks");
        NbtCompound state = body.entity().writeNbt(new NbtCompound());
        state.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
        rejects(context, () -> body.connect(Optional.of(state), false), "Fresh creation cannot overwrite native state");
        state.putUuid("KindredNativeIdentity", UUID.randomUUID());
        rejects(context, () -> body.connect(Optional.of(state), true), "Foreign logical identity blocks");
        state.putUuid("KindredNativeIdentity", identity.id());
        state.putInt("DataVersion", -1);
        rejects(context, () -> body.connect(Optional.of(state), true), "Unknown data version blocks");
        state.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
        state.putFloat("Health", 0);
        rejects(context, () -> body.connect(Optional.of(state), true), "A dead body is not silently restored as a living replacement");
        context.assertFalse(body.connected(), "Rejected saves never register");
        context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(identity.entityId()) == null, "No replacement body was installed");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void nativeKickCleansUpRegistrationAndOldControls(TestContext context) {
        NativePlayerBody body = body(context, identity(context), 0);
        body.connect(Optional.empty(), false);
        UUID id = body.entity().getUuid();
        body.entity().networkHandler.disconnect(Text.literal("Native test kick"));
        context.createTimedTaskRunner().createAndAdd(() -> {
            context.assertFalse(body.connected(), "Closed native connection removes registration on its next world tick");
            context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer(id) == null, "Player manager releases native UUID");
        }).completeIfSuccessful();
    }

    private static void rejects(TestContext context, Runnable action, String reason) {
        boolean rejected = false;
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        context.assertTrue(rejected, reason);
    }

    private static Companion identity(TestContext context) {
        return Companion.create(UUID.randomUUID(), UUID.randomUUID(), "Native lifecycle", UUID.randomUUID(),
                context.getWorld().getRegistryKey().getValue().toString()).withEntityId(UUID.randomUUID());
    }

    private static NativePlayerBody body(TestContext context, Companion identity, int offset) {
        for (int x = 1; x < 7; x++) for (int z = 1; z < 7; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y < 6; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
        NativePlayerBody body = new NativePlayerBody(context.getWorld(), identity,
                context.getAbsolute(new Vec3d(2.5 + offset, 2, 2.5)), (lease, intent, target) -> false);
        ActionArbiter arbiter = new ActionArbiter(identity, body);
        body.bind(arbiter);
        return body;
    }
}
