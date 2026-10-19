package dev.kindred.fabric;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import dev.kindred.core.ActionArbiter;
import dev.kindred.core.Actor;
import dev.kindred.core.BodyAdapter;
import dev.kindred.core.ActionLease;
import dev.kindred.core.Companion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

/** Native entity contracts; this does not substitute for an actual vanilla-client join test. */
public final class CompanionGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void metadataWriterStartsWithinNativeServer(TestContext context) {
        context.addFinalTask(() -> context.assertTrue(
                KindredMod.runtime() != null && KindredMod.runtime().isReady(),
                "Native server must initialize its real SQLite writer before accepting commands"));
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void companionUsesVanillaPresentation(TestContext context) {
        CompanionEntity companion = create(context);
        context.assertTrue(PolymerEntityUtils.isPolymerEntityType(KindredMod.COMPANION),
                "Kindred's custom type must be excluded from required client registry sync");
        context.assertTrue(companion.getPolymerEntityType(null) == EntityType.VILLAGER,
                "A vanilla viewer must receive a vanilla entity type");
        context.assertFalse(companion.canSynchronizeToPolymerClient(null),
                "Installing Polymer must not require Kindred's custom client type");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void trackedDataMatchesVanillaVillager(TestContext context) {
        DataTracker.Entry<?>[] source = PolymerEntityUtils.getDefaultTrackedData(KindredMod.COMPANION);
        DataTracker.Entry<?>[] fallback = PolymerEntityUtils.getDefaultTrackedData(EntityType.VILLAGER);
        context.assertTrue(source.length > 0, "Native tracked data must be present");
        for (DataTracker.Entry<?> entry : source) {
            boolean compatible = Arrays.stream(fallback).anyMatch(candidate ->
                    candidate.getData().id() == entry.getData().id()
                            && candidate.getData().dataType() == entry.getData().dataType());
            context.assertTrue(compatible,
                    "Vanilla villager must understand tracked data entry " + entry.getData().id());
        }
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nativeSavePreservesIdentityAndStop(TestContext context) {
        CompanionEntity original = create(context);
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        original.setUuid(id);
        original.bind(id, owner);
        original.ownerStopped(true);
        NbtCompound save = original.writeNbt(new NbtCompound());
        CompanionEntity restored = create(context);
        restored.readNbt(save);
        context.assertEquals(id, restored.getUuid(), "native entity UUID");
        context.assertEquals(id, restored.companionId(), "logical companion UUID");
        context.assertEquals(owner, restored.ownerId(), "owner UUID");
        context.assertTrue(restored.ownerStopped(), "Native save must retain owner stop");
        context.assertTrue(restored.getType() == KindredMod.COMPANION,
                "Presentation mapping must not replace the real server entity");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void hardStopClearsPendingMovement(TestContext context) {
        CompanionEntity companion = create(context);
        companion.getMoveControl().moveTo(4, 1, 4, 1);
        companion.getJumpControl().setActive();
        companion.getLookControl().lookAt(4, 1, 4);
        companion.haltControls();
        // The next native control tick must not restore an already queued jump/movement request.
        companion.getMoveControl().tick();
        companion.getJumpControl().tick();
        context.assertFalse(companion.getMoveControl().isMoving(), "Queued movement must remain revoked");
        context.assertFalse(companion.getNavigation().isFollowingPath(), "Navigation must remain stopped");
        context.assertTrue(companion.getMovementSpeed() == 0, "Voluntary movement speed must be zero");
        context.assertFalse(companion.getLookControl().isLookingAtSpecificPosition(), "Queued gaze must be revoked");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void followDestinationRejectsObstructionFluidAndHazards(TestContext context) {
        CompanionEntity companion = create(context);
        context.setBlockState(1, 1, 1, Blocks.STONE);
        context.setBlockState(1, 2, 1, Blocks.AIR);
        context.setBlockState(1, 3, 1, Blocks.AIR);
        Vec3d target = context.getAbsolute(new Vec3d(1.5, 2, 1.5));
        context.assertTrue(FollowBehavior.safeDestination(companion, target), "Clear solid ground must be eligible");
        context.setBlockState(1, 3, 1, Blocks.STONE);
        context.assertFalse(FollowBehavior.safeDestination(companion, target), "Do not step into a low ceiling");
        context.setBlockState(1, 3, 1, Blocks.AIR);
        context.setBlockState(1, 2, 1, Blocks.WATER);
        context.assertFalse(FollowBehavior.safeDestination(companion, target), "Do not select a flooded destination");
        context.setBlockState(1, 2, 1, Blocks.AIR);
        context.setBlockState(1, 1, 1, Blocks.MAGMA_BLOCK);
        context.assertFalse(FollowBehavior.safeDestination(companion, target), "Solid damaging ground is not safe");
        context.setBlockState(1, 1, 1, Blocks.AIR);
        context.assertFalse(FollowBehavior.safeDestination(companion, target), "A drop is not a standing destination");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void yieldingDoesNotAimThroughOwner(TestContext context) {
        Vec3d owner = Vec3d.ZERO;
        Vec3d companion = new Vec3d(0, 0, 1);
        context.assertTrue(FollowBehavior.clearsOwner(companion, new Vec3d(1, 0, 2), owner),
                "Moving aside and away should be allowed");
        context.assertFalse(FollowBehavior.clearsOwner(companion, new Vec3d(0, 0, -2), owner),
                "A target behind the owner must not be used as an escape from their personal space");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void stopFencesFollowMovementAndAttention(TestContext context) {
        var owner = context.createMockCreativeServerPlayerInWorld();
        Vec3d ownerPosition = context.getAbsolute(new Vec3d(1.5, 2, 1.5));
        owner.refreshPositionAndAngles(ownerPosition.x, ownerPosition.y, ownerPosition.z, 0, 0);
        CompanionEntity body = create(context);
        body.refreshPositionAndAngles(ownerPosition.x, ownerPosition.y, ownerPosition.z + 2.5, 0, 0);
        var companion = Companion.create(body.getUuid(), owner.getUuid(), "Test companion", UUID.randomUUID(),
                "minecraft:overworld");
        BodyAdapter adapter = new BodyAdapter() {
            @Override public void start(ActionLease lease) { body.haltControls(); }
            @Override public void cancel(ActionLease lease) { body.haltControls(); }
            @Override public boolean isSettled(ActionLease lease) { return body.getNavigation().isIdle(); }
        };
        ActionArbiter arbiter = new ActionArbiter(companion, adapter);
        Actor actor = Actor.player(owner.getUuid());
        ActionLease lease = arbiter.acquire(actor, UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(30));
        FollowBehavior follow = new FollowBehavior(body, owner);
        arbiter.executeIfCurrent(lease, () -> follow.tick(body, owner));
        context.assertFalse(body.getMoveControl().isMoving(), "Comfortable spacing should remain still");
        // A queued decorative look must be cancelled alongside movement, even if the next follow tick is stale.
        body.getLookControl().lookAt(owner);
        arbiter.stop(actor);
        owner.refreshPositionAndAngles(ownerPosition.x + 8, ownerPosition.y, ownerPosition.z, 0, 0);
        context.assertFalse(arbiter.executeIfCurrent(lease, () -> follow.tick(body, owner)),
                "Old follow callback must not run after stop");
        context.assertFalse(body.getLookControl().isLookingAtSpecificPosition(), "Stop must clear attention control");
        context.assertTrue(body.getNavigation().isIdle(), "No stale movement can start");
        context.complete();
    }

    private static CompanionEntity create(TestContext context) {
        CompanionEntity entity = KindredMod.COMPANION.create(context.getWorld());
        if (entity == null) throw new IllegalStateException("Registered companion factory returned null");
        return entity;
    }
}
