package dev.kindred.fabric;

import dev.kindred.core.Checkpoint;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.SkillBody;
import dev.kindred.core.SkillDescriptor;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * In plain terms: two simple jobs for the older-style AI body: chop a set number of logs from a patch it was
 * given, and put items into its assigned chest.
 *
 * Technical: Gather a counted resource from the designated patch, and deposit it into the assigned container.
 */
final class ChoreSkills {
    static final SkillDescriptor GATHER = new SkillDescriptor("gather_logs", Set.of("count"), Set.of("block"), Set.of("break", "pickup"),
            Set.of(EditPolicy.Kind.BREAK), Duration.ofMinutes(3), Duration.ofSeconds(20), 2);
    static final SkillDescriptor DEPOSIT = new SkillDescriptor("deposit", Set.of("count", "item"), Set.of(), Set.of("container"),
            Set.of(EditPolicy.Kind.CONTAINER), Duration.ofMinutes(2), Duration.ofSeconds(15), 2);

    /** Asked before a break or a transfer. False means the ledger row is not durable yet, so the world is not touched. */
    interface Gate {
        boolean prepared(String key, String kind, String preconditions);
        default void committed(String key, String outcome) {}
        default void rolledBack(String key, String reason) {}
    }

    private ChoreSkills() { }

    static final class Gather implements SkillBody {
        private final CompanionEntity body;
        private final WorkSetup setup;
        private final Supplier<EditPolicy.ServerPolicy> edits;
        private final Gate gate;
        private BlockPos target;
        private int initialInventory = -1;
        private int targetTotal = -1;

        Gather(CompanionEntity body, WorkSetup setup, Supplier<EditPolicy.ServerPolicy> edits, Gate gate) {
            this.body = body;
            this.setup = setup;
            this.edits = edits;
            this.gate = gate;
        }

        @Override
        public Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart) {
            int need = parse(arguments.get("count"), 1, 32);
            if (need < 0) return Step.blocked("gather count must be 1..32", true);
            Identifier blockId = Identifier.tryParse(arguments.getOrDefault("block", "minecraft:oak_log"));
            if (blockId == null || !Registries.BLOCK.containsId(blockId)) return Step.blocked("unknown block", true);
            Block block = Registries.BLOCK.get(blockId);
            Item item = block.asItem();
            if (item == net.minecraft.item.Items.AIR) return Step.blocked(blockId + " has no item to pick up", true);
            if (setup.patch() == null) return Step.blocked("no resource patch is assigned", true);
            if (!setup.patch().resourceId().equals(blockId.toString())) return Step.blocked("that block is not the patch's designated resource", true);

            int currentInventory = ItemMoves.count(body.inventory(), item);
            if (freshStart || initialInventory < 0) {
                if (resumeFrom.isPresent() && resumeFrom.get().progress().containsKey("initial")) {
                    initialInventory = Integer.parseInt(resumeFrom.get().progress().get("initial"));
                    targetTotal = Integer.parseInt(resumeFrom.get().progress().get("target"));
                } else {
                    initialInventory = currentInventory;
                    targetTotal = initialInventory + need;
                }
            }

            int remainingNeed = Math.max(0, targetTotal - currentInventory);
            if (remainingNeed > 0) {
                UUID pickupActionId = UUID.randomUUID();
                ItemMoves.PickupResult pickup = ItemMoves.attributedPickup(pickupActionId, body, item, remainingNeed);
                currentInventory = ItemMoves.count(body.inventory(), item);
                remainingNeed = Math.max(0, targetTotal - currentInventory);
            }

            int verifiedContribution = Math.max(0, currentInventory - initialInventory);
            Map<String, String> progress = Map.of(
                    "initial", String.valueOf(initialInventory),
                    "target", String.valueOf(targetTotal),
                    "collected", String.valueOf(verifiedContribution),
                    "remaining", String.valueOf(remainingNeed),
                    "need", String.valueOf(need)
            );

            if (currentInventory >= targetTotal) return Step.completed(progress);

            if (target == null || !body.getWorld().getBlockState(target).isOf(block)) target = nearest(block);
            if (target == null) return Step.blocked("no " + blockId + " left in the patch", true);
            var decision = EditPolicy.evaluate(setup, EditPolicy.Kind.BREAK, body.getWorld().getRegistryKey().getValue().toString(),
                    point(target), blockId.toString(), edits.get());
            if (!decision.allowed()) return Step.blocked(decision.reason(), true);
            if (body.getBlockPos().getSquaredDistance(target) > 9) {
                body.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                return Step.running(progress, true);
            }
            body.haltControls();
            String key = "break:" + target.toShortString();
            if (!gate.prepared(key, "break_block", blockId + " at " + target.toShortString())) return Step.running(progress, true);
            if (!((ServerWorld) body.getWorld()).breakBlock(target, true, body)) {
                gate.rolledBack(key, "the block could not be broken");
                return Step.blocked("the block could not be broken", false);
            }
            gate.committed(key, "broke " + blockId + " at " + target.toShortString());
            target = null;
            return Step.running(progress, true);
        }

        private BlockPos nearest(Block block) {
            WorkSetup.ResourcePatch patch = setup.patch();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            BlockPos min = new BlockPos(patch.min().x(), patch.min().y(), patch.min().z());
            BlockPos max = new BlockPos(patch.max().x(), patch.max().y(), patch.max().z());
            for (BlockPos pos : BlockPos.iterate(min, max)) {
                if (body.getBlockPos().getSquaredDistance(pos) > 24 * 24) continue;
                BlockState state = body.getWorld().getBlockState(pos);
                if (!state.isOf(block)) continue;
                double distance = body.getBlockPos().getSquaredDistance(pos);
                if (distance < bestDistance) { bestDistance = distance; best = pos.toImmutable(); }
            }
            return best;
        }

        @Override public void halt() { body.haltControls(); }
        @Override public boolean settled() { return body.getNavigation().isIdle(); }
    }

    static final class Deposit implements SkillBody {
        private final CompanionEntity body;
        private final WorkSetup setup;
        private final Supplier<EditPolicy.ServerPolicy> edits;
        private final Gate gate;
        private int alreadyDeposited = -1;

        Deposit(CompanionEntity body, WorkSetup setup, Supplier<EditPolicy.ServerPolicy> edits, Gate gate) {
            this.body = body;
            this.setup = setup;
            this.edits = edits;
            this.gate = gate;
        }

        @Override
        public Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart) {
            int need = parse(arguments.get("count"), 1, 64);
            if (need < 0) return Step.blocked("deposit count must be 1..64", true);
            Identifier itemId = Identifier.tryParse(arguments.get("item"));
            if (itemId == null || !Registries.ITEM.containsId(itemId)) return Step.blocked("unknown item", true);
            Item item = Registries.ITEM.get(itemId);

            if (freshStart || alreadyDeposited < 0) {
                if (resumeFrom.isPresent() && resumeFrom.get().progress().containsKey("deposited")) {
                    alreadyDeposited = Integer.parseInt(resumeFrom.get().progress().get("deposited"));
                } else {
                    alreadyDeposited = 0;
                }
            }

            int remainingNeed = need - alreadyDeposited;
            if (remainingNeed <= 0) {
                return Step.completed(Map.of("deposited", String.valueOf(alreadyDeposited), "requested", String.valueOf(need)));
            }

            if (setup.storage() == null) return Step.blocked("no storage is assigned", true);
            BlockPos chest = new BlockPos(setup.storage().position().x(), setup.storage().position().y(), setup.storage().position().z());
            var verdict = StorageTargets.validate(body.getWorld(), setup.storage());
            if (verdict != StorageTargets.Verdict.OK) return Step.blocked(StorageTargets.explain(verdict), true);
            var decision = EditPolicy.evaluate(setup, EditPolicy.Kind.CONTAINER, body.getWorld().getRegistryKey().getValue().toString(),
                    setup.storage().position(), setup.storage().blockId(), edits.get());
            if (!decision.allowed()) return Step.blocked(decision.reason(), true);
            if (!(body.getWorld().getBlockEntity(chest) instanceof Inventory container)) return Step.blocked("assigned storage is not a container", true);

            int carrying = ItemMoves.count(body.inventory(), item);
            if (carrying <= 0) {
                if (alreadyDeposited == 0) return Step.blocked("not carrying any " + itemId, true);
                return Step.completed(Map.of("deposited", String.valueOf(alreadyDeposited), "requested", String.valueOf(need), "partial", "true"));
            }

            Map<String, String> currentProgress = Map.of("deposited", String.valueOf(alreadyDeposited), "requested", String.valueOf(need), "carrying", String.valueOf(carrying));
            if (body.getBlockPos().getSquaredDistance(chest) > 9) {
                body.getNavigation().startMovingTo(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5, 1.0);
                return Step.running(currentProgress, true);
            }
            body.haltControls();

            int moving = Math.min(remainingNeed, carrying);
            String key = "deposit:" + chest.toShortString();
            if (!gate.prepared(key, "deposit", moving + " " + itemId + " into " + chest.toShortString())) {
                return Step.running(currentProgress, true);
            }

            UUID actionId = UUID.randomUUID();
            ItemMoves.TransferResult result = ItemMoves.transfer(actionId, body.inventory(), container, item, moving);
            if (result.transferred() <= 0) {
                gate.rolledBack(key, "assigned storage is full or cannot accept items");
                return Step.blocked("assigned storage is full or cannot accept items", true);
            }

            alreadyDeposited += result.transferred();
            gate.committed(key, "deposited " + result.transferred() + " of " + itemId + " into " + chest.toShortString());
            Map<String, String> progress = Map.of("deposited", String.valueOf(alreadyDeposited), "requested", String.valueOf(need));

            return alreadyDeposited >= need || ItemMoves.count(body.inventory(), item) == 0 ? Step.completed(progress) : Step.running(progress, true);
        }

        @Override public void halt() { body.haltControls(); }
        @Override public boolean settled() { return body.getNavigation().isIdle(); }
    }

    private static WorkSetup.BlockPoint point(BlockPos pos) { return new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()); }

    private static int parse(String text, int min, int max) {
        try {
            int value = Integer.parseInt(text);
            return value < min || value > max ? -1 : value;
        } catch (NumberFormatException failure) { return -1; }
    }
}
