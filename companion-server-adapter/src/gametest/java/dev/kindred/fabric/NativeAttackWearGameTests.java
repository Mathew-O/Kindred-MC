package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * A tool that breaks on the blow of a single attack is wear, as the game counts it broken, not an outcome to reconcile
 * (F-25c4df).
 */
public final class NativeAttackWearGameTests implements FabricGameTest {

    /**
     * In plain terms: an AI player that strikes a sheep with its last, worn-out pickaxe, which breaks on the blow, carries
     * on, rather than stand still for good waiting for someone to sort out where the pickaxe went.
     *
     * Technical: F-25c4df, E-40 rung c (`e40c4-b23acc0`, #334) seed 101 Kindred: hunting sheep for wool with no sword, its
     * two pickaxes worn out digging since it picked them up, it struck with the last; the hotbar slot it struck from was
     * empty after the blow, {@code verifyAttackConservation} read the empty slot as a different item ("Attack item
     * conservation check failed; reconcile"), and it was held for reconciliation for the run's last ~64,500 ticks. Its save at
     * the stop holds no sword, axe or pickaxe, its selected slot empty. STAGED: a native player with a stone pickaxe one
     * blow from breaking (its wear set by the fixture) in hotbar slot 0 and nothing else; a sheep in reach in front of it,
     * its AI off; one {@code native_attack} on the sheep from slot 0. Passes if the attack's record ends VERIFIED, the player
     * is not held for reconciliation, the pickaxe is gone and the game counts one stone pickaxe broken, and a next task is
     * accepted.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "attackWear", tickLimit = 600)
    public void aPickaxeThatBreaksOnTheBlowIsWearAndThePlayerCarriesOn(TestContext context) {
        AtomicReference<ActionRecord.State> last = new AtomicReference<>();
        java.util.function.UnaryOperator<ActionAdmission.Writer> watch = writer -> new ActionAdmission.Writer() {
            @Override public CompletableFuture<ActionRecord> prepare(ActionRecord intent) { return writer.prepare(intent); }

            @Override
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                if ("native_attack".equals(next.kind())) last.set(next.state());
                return writer.transition(next, expectedRevision);
            }
        };
        var f = new NativePaidSkillsGameTests.Fixture(context, watch, true);
        net.minecraft.server.network.ServerPlayerEntity[] body = {null};
        SheepEntity[] sheep = {null};
        int[] brokenBefore = {-1};
        long[] at = {-1};
        boolean[] logged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hunter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = body[0] = f.player();
                    context.assertTrue(player.isOnGround(), "Standing");
                    player.getInventory().clear();
                    var pickaxe = new ItemStack(Items.STONE_PICKAXE);
                    pickaxe.setDamage(pickaxe.getMaxDamage() - 1);
                    player.getInventory().setStack(0, pickaxe);
                    player.getInventory().selectedSlot = 0;
                    var world = context.getWorld();
                    var made = EntityType.SHEEP.create(world);
                    sheep[0] = made;
                    // Two blocks south of its feet, where it faces (yaw 0), its body in reach of a blow.
                    made.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ() + 2, 180, 0);
                    made.setAiDisabled(true);
                    made.setPersistent();
                    world.spawnEntity(made);
                    player.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), 0, 10);
                    brokenBefore[0] = player.getStatHandler().getStat(Stats.BROKEN.getOrCreateStat(Items.STONE_PICKAXE));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_attack",
                            Map.of("target", sheep[0].getUuidAsString(), "slot", "0")) == 1, "Attack accepted: " + f.runtime.nativeLastResult(f.id));
                    at[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getTime() - at[0] >= 60, "Settling"))
                .createAndAdd(() -> {
                    var state = last.get();
                    boolean held = f.runtime.reconciliationBlockedForFixtures(f.id);
                    var player = body[0];
                    int broken = player.getStatHandler().getStat(Stats.BROKEN.getOrCreateStat(Items.STONE_PICKAXE)) - brokenBefore[0];
                    var inHand = player.getInventory().getStack(0);
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("Attack wear: record {}, held for reconciliation {}, slot 0 {}, stone pickaxes broken {}; {}", state, held,
                                inHand, broken, f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(state != null, "No attack recorded: " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(broken == 1 && inHand.isEmpty(), "The pickaxe did not break on the blow: slot 0 " + inHand + ", broken " + broken);
                    context.assertTrue(state == ActionRecord.State.VERIFIED, "The attack's record ended " + state + ", not VERIFIED");
                    context.assertTrue(!held, "Held for reconciliation: " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(f.player() == body[0], "The body was taken out of play");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "A next task refused: " + f.runtime.nativeLastResult(f.id));
                    sheep[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
    /**
     * In plain terms: the check still asks for reconciliation when something it cannot account for has happened.
     *
     * Technical: F-25c4df, the rule itself, on inventories made here (no world). Passes if a blow's wear passes; a weapon
     * gone passes only when the game counts exactly one broken by the blow; and a weapon gone uncounted, counted twice,
     * turned into another item, or gone with any other slot changed, each fails.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "attackWearRule", tickLimit = 20)
    public void aWeaponGoneWithoutTheGameCountingItBrokenStillAsksForReconciliation(TestContext context) {
        var sword = new ItemStack(Items.IRON_SWORD);
        var worn = sword.copy();
        worn.setDamage(1);
        var pickaxe = new ItemStack(Items.STONE_PICKAXE);
        pickaxe.setDamage(pickaxe.getMaxDamage() - 1);
        var bread = new ItemStack(Items.BREAD, 4);
        java.util.function.BiFunction<ItemStack, ItemStack, InventoryEvidence> kit = (hand, other) ->
                new InventoryEvidence(new ItemStack[]{hand, other, ItemStack.EMPTY}, "staged");
        context.assertTrue(NativeAttackAction.verifyAttackConservation(kit.apply(sword, bread), kit.apply(worn, bread.copy()), 0, 0),
                "A blow's wear refused");
        context.assertTrue(NativeAttackAction.verifyAttackConservation(kit.apply(pickaxe, bread), kit.apply(ItemStack.EMPTY, bread.copy()), 0, 1),
                "A pickaxe broken on the blow refused");
        context.assertFalse(NativeAttackAction.verifyAttackConservation(kit.apply(pickaxe, bread), kit.apply(ItemStack.EMPTY, bread.copy()), 0, 0),
                "A pickaxe gone, not counted broken, let through");
        context.assertFalse(NativeAttackAction.verifyAttackConservation(kit.apply(pickaxe, bread), kit.apply(ItemStack.EMPTY, bread.copy()), 0, 2),
                "A pickaxe gone, counted broken twice, let through");
        context.assertFalse(NativeAttackAction.verifyAttackConservation(kit.apply(pickaxe, bread), kit.apply(ItemStack.EMPTY, new ItemStack(Items.BREAD, 3)), 0, 1),
                "A pickaxe broken and a slot changed, let through");
        context.assertFalse(NativeAttackAction.verifyAttackConservation(kit.apply(pickaxe, bread), kit.apply(new ItemStack(Items.STICK), bread.copy()), 0, 1),
                "A pickaxe turned into a stick, let through");
        context.complete();
    }
}
