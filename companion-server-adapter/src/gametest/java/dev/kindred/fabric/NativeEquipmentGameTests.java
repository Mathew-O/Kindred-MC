package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

/** Native equipped stacks originate in the owned inventory; fixture grants are not earned gear. */
public final class NativeEquipmentGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void ownedArmorAndShieldMoveExactlyAndSurviveNativeReconnect(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        int[] sources = {0, 10, 11, 12, 13};
        EquipmentSlot[] targets = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
        ItemStack[] stacks = {new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.IRON_CHESTPLATE),
                new ItemStack(Items.IRON_LEGGINGS), new ItemStack(Items.IRON_BOOTS), new ItemStack(Items.SHIELD)};
        for (int i = 0; i < stacks.length; i++) {
            stacks[i].setDamage(7 + i);
            stacks[i].set(DataComponentTypes.CUSTOM_NAME, Text.literal("Conserved gear " + i));
        }
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Equipped"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    for (int i = 0; i < sources.length; i++) f.player().getInventory().setStack(sources[i], stacks[i].copy());
                    context.assertTrue(f.runtime.nativeSkill(f.source.withLevel(0), f.id, "native_equip", 0) == 0, "Foreign equipment control denied");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 36) == 0, "Equipment source is restricted to owned main inventory");
                });
        for (int i = 0; i < sources.length; i++) {
            int index = i;
            runner.createAndAdd(() -> context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", sources[index]) == 1, "Owned gear request accepted"));
            runner.createAndAdd(() -> {
                var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_equip"), "Native transfer verified and settled");
                var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                context.assertTrue(action.record() != null && action.record().state() == dev.kindred.core.ActionRecord.State.VERIFIED
                        && action.record().beforeEvidence() != null && action.record().afterEvidence() != null, "Completion requires durable native before/after evidence");
                context.assertTrue(f.player().getInventory().getStack(sources[index]).isEmpty(), "Owned source depleted");
                assertStack(context, stacks[index], f.player().getEquippedStack(targets[index]));
                context.assertTrue(total(f) == 5, "All owned item counts conserved");
            });
        }
        runner.createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "disconnect", f.id);
                    f.runtime.nativeLifecycle(f.source, "connect", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Native file restored"))
                .createAndAdd(() -> {
                    for (int i = 0; i < sources.length; i++) {
                        context.assertTrue(f.player().getInventory().getStack(sources[i]).isEmpty(), "No source regenerated on reconnect");
                        assertStack(context, stacks[i], f.player().getEquippedStack(targets[i]));
                    }
                    context.assertTrue(total(f) == 5, "Exact equipped conservation across native save/reconnect");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 280)
    public void equipmentRejectsConflictsAndStopFencesPendingTransfer(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ItemStack requested = new ItemStack(Items.IRON_HELMET);
        ItemStack replacement = new ItemStack(Items.DIAMOND_HELMET);
        int[] age = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Careful gear"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, requested.copy());
                    player.playerScreenHandler.setCursorStack(new ItemStack(Items.STICK));
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 0) == 0, "Occupied cursor refused");
                    player.playerScreenHandler.setCursorStack(ItemStack.EMPTY);
                    player.getInventory().setStack(1, new ItemStack(Items.APPLE));
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 1) == 0, "Non-equipment refused");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 2) == 0, "Missing owned source refused");
                    player.getInventory().setStack(3, new ItemStack(Items.IRON_BOOTS, 2));
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 3) == 0, "Invalid stacked equipment refused");
                    f.runtime.nativeSkill(f.source, f.id, "native_equip", 0);
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    age[0] = player.age;
                })
                .createAndAdd(() -> context.assertTrue(f.player().age >= age[0] + 20, "Observe pending callback cancellation"))
                .createAndAdd(() -> {
                    assertStack(context, requested, f.player().getInventory().getStack(0));
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "Stopped pending admission never equips later");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 0) == 0, "Stopped equip refused");
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 0) == 1, "Explicit new request after resume");
                    f.player().getInventory().setStack(0, replacement.copy()); // Change before the first skill tick.
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Blocked:"), "Changed source blocks without transfer or retry");
                    assertStack(context, replacement, f.player().getInventory().getStack(0));
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.HEAD).isEmpty(), "Changed gear not silently substituted");
                    var cursed = new ItemStack(Items.LEATHER_HELMET);
                    cursed.addEnchantment(context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.BINDING_CURSE).orElseThrow(), 1);
                    f.player().equipStack(EquipmentSlot.HEAD, cursed);
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_equip", 0) == 0, "Occupied cursed equipment never displaced");
                    assertStack(context, cursed, f.player().getEquippedStack(EquipmentSlot.HEAD));
                    assertStack(context, replacement, f.player().getInventory().getStack(0));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void assertStack(TestContext context, ItemStack expected, ItemStack actual) {
        context.assertTrue(expected.getCount() == actual.getCount() && ItemStack.areItemsAndComponentsEqual(expected, actual), "Exact item/components/count retained");
    }
    private static int total(NativePaidSkillsGameTests.Fixture f) {
        int total = 0;
        for (int i = 0; i < f.player().getInventory().size(); i++) total += f.player().getInventory().getStack(i).getCount();
        return total;
    }
}
