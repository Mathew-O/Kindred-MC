package dev.kindred.fabric;

import dev.kindred.core.ActionArbiter;
import dev.kindred.core.ActionLease;
import dev.kindred.core.Actor;
import dev.kindred.core.Companion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.util.UUID;

/** Real native mechanics in controlled fixtures; not player-manager/client or survival-playthrough evidence. */
public final class NativePlayerBodyGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void eatingTakesVanillaTimeConsumesOneRealItemAndFeedsHungerWithoutDirectHealing(TestContext context) {
        Fixture f = new Fixture(context);
        f.player.getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
        f.player.getHungerManager().setFoodLevel(8);
        f.player.setHealth(8);
        context.assertTrue(f.body.eat(f.lease), "Current authority starts eating");
        context.assertTrue(f.player.isUsingItem(), "Vanilla item-use lifecycle starts");
        f.ticks(31);
        context.assertTrue(f.player.getInventory().getStack(0).getCount() == 2, "No instant food consumption");
        context.assertTrue(f.player.getHealth() == 8, "No direct healing substitute");
        f.ticks(2);
        context.assertTrue(f.player.getInventory().getStack(0).getCount() == 1, "Exactly one native apple consumed");
        context.assertTrue(f.player.getHungerManager().getFoodLevel() >= 12, "Native food affects hunger");
        context.assertTrue(f.player.getHealth() == 8, "Eating does not directly turn nutrition into health");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void stopCancelsEatingAndRejectsStaleAndForeignControls(TestContext context) {
        Fixture f = new Fixture(context);
        f.player.getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
        f.player.getHungerManager().setFoodLevel(8);
        f.body.eat(f.lease);
        f.ticks(10);
        f.arbiter.stop(Actor.console());
        context.assertFalse(f.player.isUsingItem(), "Stop ends item use synchronously");
        context.assertTrue(f.body.isSettled(f.lease), "Stop settles voluntary controls");
        f.ticks(40);
        context.assertTrue(f.player.getInventory().getStack(0).getCount() == 2, "Cancelled use never consumes later");
        context.assertFalse(f.body.eat(f.lease), "Stopped lease cannot restart item use");
        context.assertFalse(f.body.move(f.lease, 1, 0, true, true, 0), "Stopped lease cannot restart motion");
        f.arbiter.resume(Actor.console());
        ActionLease replacement = f.acquire();
        context.assertFalse(f.body.selectHotbar(f.lease, 1), "Earlier generation remains rejected after resume");
        var foreign = new ActionLease(UUID.randomUUID(), replacement.taskId(), replacement.actionId(),
                replacement.sessionEpoch(), replacement.bodyGeneration(), replacement.intentRevision(), replacement.taskRevision());
        context.assertFalse(f.body.selectHotbar(foreign, 1), "Another identity cannot use this body");
        context.assertTrue(f.body.selectHotbar(replacement, 1), "Current generation can use real hotbar");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void equipmentUsesNativeSlotsAndNbtPreservesComponentsHungerAndHealth(TestContext context) {
        Fixture f = new Fixture(context);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Earned tool fixture"));
        f.player.getInventory().setStack(2, pick);
        context.assertTrue(f.body.selectHotbar(f.lease, 2), "Select an existing owned slot");
        context.assertTrue(f.player.getMainHandStack() == f.player.getInventory().getStack(2), "Main hand is the real slot, never a copy");
        f.player.getMainHandStack().setDamage(7);
        f.player.getInventory().setStack(36, new ItemStack(Items.IRON_BOOTS));
        f.player.setHealth(11);
        f.player.getHungerManager().setFoodLevel(9);
        NbtCompound saved = f.player.writeNbt(new NbtCompound());
        Fixture restored = new Fixture(context, f.identity);
        restored.player.readNbt(saved);
        context.assertTrue(restored.player.getUuid().equals(f.player.getUuid()), "Native identity survives NBT");
        context.assertTrue(restored.player.getInventory().selectedSlot == 2, "Selected hotbar persists");
        context.assertTrue(restored.player.getMainHandStack().getDamage() == 7, "Actual tool wear persists");
        context.assertTrue(restored.player.getMainHandStack().get(DataComponentTypes.CUSTOM_NAME).getString().equals("Earned tool fixture"), "Components persist");
        context.assertTrue(restored.player.getInventory().getStack(36).isOf(Items.IRON_BOOTS), "Native armor slot persists");
        context.assertTrue(restored.player.getHealth() == 11 && restored.player.getHungerManager().getFoodLevel() == 9, "Survival state persists");
        context.assertFalse(restored.player.isCreative() || restored.player.isSpectator(), "Survival mode only");
        context.assertFalse(restored.player.getCommandSource().hasPermissionLevel(1), "AI receives no operator authority");
        context.assertFalse(restored.player.canUsePortals(false) || restored.player.canUsePortals(true), "Unqualified dimension travel cannot be enabled by body creation");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void miningUsesVanillaTimingAndToolWearAndStopPreventsLateBreak(TestContext context) {
        Fixture f = new Fixture(context);
        BlockPos target = context.getAbsolutePos(new BlockPos(2, 2, 4));
        context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
        f.player.getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
        context.assertTrue(f.body.mine(f.lease, target), "Visible reachable approved block starts native mining");
        f.ticks(1);
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "Log does not break instantly");
        f.arbiter.stop(Actor.console());
        f.ticks(60);
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "Stop aborts progress without a delayed break");
        context.assertTrue(f.player.getMainHandStack().getDamage() == 0, "Aborted mining spends no tool durability");
        context.assertTrue(f.body.miningReceipt().isEmpty(), "Cancelled mining creates no completion receipt");
        f.arbiter.resume(Actor.console());
        ActionLease next = f.acquire();
        context.assertTrue(f.body.mine(next, target), "New authority can retry");
        f.ticks(60);
        context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Native survival interaction completes earned break");
        context.assertTrue(f.player.getMainHandStack().getDamage() == 1, "Native mining damages the owned tool exactly once");
        var receipt = f.body.miningReceipt().orElseThrow();
        context.assertTrue(receipt.sequence() == 1 && receipt.lease().equals(next) && receipt.position().equals(target), "Receipt binds the actual break to fresh authority and exact target");
        context.assertTrue(receipt.playerId().equals(f.identity.id()) && receipt.nativeId().equals(f.player.getUuid()), "Receipt keeps logical/native identities distinct");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void miningRejectsReachObstructionChangedTargetsAndRevokedPolicy(TestContext context) {
        Fixture f = new Fixture(context);
        f.ticks(2);
        BlockPos target = context.getAbsolutePos(new BlockPos(2, 2, 4));
        context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
        BlockPos obstruction = context.getAbsolutePos(new BlockPos(2, 3, 3));
        context.getWorld().setBlockState(obstruction, Blocks.STONE.getDefaultState());
        context.assertFalse(f.body.mine(f.lease, target), "Cannot mine through a wall");
        context.getWorld().setBlockState(obstruction, Blocks.AIR.getDefaultState());
        context.assertFalse(f.body.mine(f.lease, target.add(40, 0, 0)), "Cannot mine outside native reach");
        context.assertTrue(f.body.mine(f.lease, target), "Visible target accepted");
        f.allowed = false;
        f.ticks(80);
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "Policy revocation prevents break");
        context.assertFalse(f.arbiter.isCurrent(f.lease), "Policy revocation cancels authority");
        f.allowed = true;
        ActionLease next = f.acquire();
        f.body.mine(next, target);
        context.getWorld().setBlockState(target, Blocks.STONE.getDefaultState());
        f.ticks(100);
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.STONE), "A replacement block is not the selected target");
        context.assertTrue(f.body.miningReceipt().isEmpty(), "External replacement is never an AI mining receipt");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nativeBreakEventRechecksPolicyWithoutAnotherInputTick(TestContext context) {
        Fixture f = new Fixture(context);
        BlockPos target = context.getAbsolutePos(new BlockPos(2, 2, 4));
        context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
        f.player.getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
        context.assertTrue(f.body.mine(f.lease, target), "Native mining selected under valid policy");
        f.allowed = false;
        context.assertFalse(f.player.interactionManager.tryBreakBlock(target), "Actual native game break callback rechecks policy before effects");
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG)
                && f.player.getMainHandStack().getDamage() == 0 && f.body.miningReceipt().isEmpty(), "Denied native effect preserves block/tool and creates no receipt");
        f.ticks(60);
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "No delayed bypass after denied break");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void externalDisappearanceIsNotMiningAndReceiptsDeduplicateActualBreak(TestContext context) {
        Fixture f = new Fixture(context);
        BlockPos target = context.getAbsolutePos(new BlockPos(2, 2, 4));
        var log = Blocks.OAK_LOG.getDefaultState();
        context.getWorld().setBlockState(target, log);
        f.player.getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
        context.assertTrue(f.body.mine(f.lease, target), "Native mining selected");
        NativePlayerBody.observedBreak(context.getWorld(), f.player, target, log);
        context.assertTrue(f.body.miningReceipt().isEmpty(), "An event while the original block remains is not a completed break");
        context.getWorld().setBlockState(target, Blocks.AIR.getDefaultState());
        f.ticks(10);
        context.assertTrue(f.body.miningReceipt().isEmpty() && f.player.getMainHandStack().getDamage() == 0, "External disappearance creates no success or tool use");
        context.getWorld().setBlockState(target, log);
        context.assertFalse(f.body.mine(f.lease, target), "External change revoked the prior mining authority");
        ActionLease retry = f.acquire();
        context.assertTrue(f.body.mine(retry, target), "Explicit new mining attempt with fresh authority");
        f.ticks(60);
        var receipt = f.body.miningReceipt().orElseThrow();
        context.assertTrue(receipt.sequence() == 1 && receipt.attempt() == 2 && receipt.lease().equals(retry)
                && receipt.previousBlockState() == net.minecraft.block.Block.getRawIdFromState(log), "Only actual native break creates the receipt");
        NativePlayerBody.observedBreak(context.getWorld(), f.player, target, log);
        context.assertTrue(f.body.miningReceipt().orElseThrow().sequence() == 1, "Duplicate observation cannot create a second completion");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 150)
    public void registeredWorldTicksProduceAnAttributedNativeBreak(TestContext context) {
        Fixture f = new Fixture(context);
        BlockPos target = context.getAbsolutePos(new BlockPos(2, 2, 4));
        context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
        f.player.getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
        f.body.connect(java.util.Optional.empty(), false);
        context.runAtTick(140, () -> f.body.disconnect());
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.body.mine(f.lease, target), "Registered native mining starts"))
                .createAndAdd(() -> context.assertTrue(f.body.miningReceipt().isPresent(), "Real world ticks and actual Fabric break event deliver a receipt"))
                .createAndAdd(() -> {
                    var receipt = f.body.miningReceipt().orElseThrow();
                    context.assertTrue(context.getWorld().getBlockState(target).isAir() && f.player.getMainHandStack().getDamage() == 1, "Native break and owned tool wear occurred");
                    context.assertTrue(receipt.lease().equals(f.lease) && receipt.position().equals(target) && receipt.sequence() == 1, "Exact fresh receipt");
                    f.body.disconnect();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nativePhysicsMovesWithoutTeleportingAndStopClearsInputs(TestContext context) {
        Fixture f = new Fixture(context);
        f.player.setOnGround(true);
        Vec3d start = f.player.getPos();
        context.assertTrue(f.body.move(f.lease, 1, 0, false, false, 0), "Current lease drives movement");
        f.ticks(5);
        context.assertTrue(f.player.getPos().z > start.z + 0.1, "Native physics advances the player");
        context.assertTrue(f.player.getPos().distanceTo(start) < 3, "No teleport shortcut");
        f.arbiter.stop(Actor.console());
        context.assertTrue(f.body.isSettled(f.lease), "Stop clears voluntary input synchronously");
        context.assertFalse(f.player.isSprinting() || f.player.isUsingItem(), "Stop clears sprint/use");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void revokingPolicyCancelsTimedFoodBeforeItCanConsume(TestContext context) {
        Fixture f = new Fixture(context);
        f.player.getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
        f.player.getHungerManager().setFoodLevel(8);
        f.body.eat(f.lease);
        f.ticks(30);
        f.allowed = false;
        f.ticks(10);
        context.assertFalse(f.player.isUsingItem(), "Revoked activity cancels item use before native consumption");
        context.assertTrue(f.player.getInventory().getStack(0).getCount() == 2, "Revocation preserves physical food");
        context.assertFalse(f.arbiter.isCurrent(f.lease), "Revocation invalidates the lease");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nativeCollisionPhysicsAppliesFallDamageWithoutClientPackets(TestContext context) {
        Fixture f = new Fixture(context);
        f.ticks(65);
        Vec3d position = f.player.getPos();
        f.player.refreshPositionAndAngles(position.x, position.y + 7, position.z, 0, 0); // Fixture setup only.
        f.player.setOnGround(false);
        f.player.setVelocity(Vec3d.ZERO);
        f.ticks(40);
        context.assertTrue(f.player.isOnGround(), "Native collision lands on the fixture floor");
        context.assertTrue(f.player.getHealth() < 20 && f.player.getHealth() > 0, "Fall damage needs no client report");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void workerCannotReadOrControlNativeBody(TestContext context) {
        Fixture f = new Fixture(context);
        // Intentional negative boundary probe: the worker must fail before accessing Minecraft state.
        var rejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { f.body.selectHotbar(f.lease, 1); return false; }
            catch (IllegalStateException expected) { return true; }
        });
        context.createTimedTaskRunner().createAndAdd(() -> {
            context.assertTrue(rejected.isDone(), "Worker probe finished without blocking a tick");
            context.assertTrue(rejected.getNow(false), "Off-thread body access is rejected");
            context.assertTrue(f.player.getInventory().selectedSlot == 0, "Worker did not mutate native inventory");
        }).completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void bodyRemainsDamageableAndRejectsDeadBodyAndDuplicateUuid(TestContext context) {
        Fixture f = new Fixture(context);
        f.ticks(65); // Ordinary join grace is retained, not bypassed.
        float before = f.player.getHealth();
        context.assertTrue(f.player.damage(context.getWorld().getDamageSources().generic(), 4), "Native survival body accepts damage after join grace");
        context.assertTrue(f.player.getHealth() < before, "Damage changes actual health");
        context.getWorld().onPlayerConnected(f.player);
        try {
            boolean rejected = false;
            try { new Fixture(context, f.identity); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "An already-loaded native UUID cannot be duplicated");
            f.player.setHealth(0);
            context.assertFalse(f.body.move(f.lease, 1, 0, false, false, 0), "Dead player cannot accept controls");
        } finally {
            context.getWorld().removePlayer(f.player, Entity.RemovalReason.DISCARDED);
        }
        context.complete();
    }

    private static final class Fixture {
        final NativePlayerBody body;
        final Companion identity;
        final ServerPlayerEntity player;
        final ActionArbiter arbiter;
        final ActionLease lease;
        boolean allowed = true;

        Fixture(TestContext context) {
            this(context, Companion.create(UUID.randomUUID(), UUID.randomUUID(), "Native fixture", UUID.randomUUID(),
                    context.getWorld().getRegistryKey().getValue().toString()).withEntityId(UUID.randomUUID()));
        }

        Fixture(TestContext context, Companion identity) {
            this.identity = identity;
            for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) {
                context.setBlockState(x, 1, z, Blocks.STONE);
                for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
            }
            body = new NativePlayerBody(context.getWorld(), identity, context.getAbsolute(new Vec3d(2.5, 2, 2.5)),
                    (lease, intent, target) -> allowed);
            player = body.entity();
            arbiter = new ActionArbiter(identity, body);
            body.bind(arbiter);
            lease = acquire();
        }

        ActionLease acquire() { return arbiter.acquire(Actor.console(), UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(1)); }
        void ticks(int count) { for (int tick = 0; tick < count; tick++) player.tick(); }
    }
}
