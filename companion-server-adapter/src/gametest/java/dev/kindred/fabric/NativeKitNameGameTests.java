package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.NameForge;
import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.model.Strategist;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Named kit (E-16d): a player at the enchanting stage (an enchanting table of its own) names its best weapons and
 * tools at an anvil, a level each. With iron to spare and no anvil, it makes one at a crafting table (three blocks of
 * iron and four ingots), puts it down, and names its enchanted diamond sword and its diamond pickaxe names of its own,
 * not its plain iron sword; holding an anvil, it puts that down and takes the model's name, and its own when the model
 * names a name it has used; and short of the stage it names nothing and makes no anvil.
 */
public final class NativeKitNameGameTests implements FabricGameTest {
    /** A strategist that answers every name question with one name, and records the questions; it gives no advice. */
    private static final class Stub implements Strategist {
        final String name;
        final List<String> asked = new java.util.ArrayList<>();

        Stub(String name) { this.name = name; }

        @Override
        public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
            return CompletableFuture.completedFuture(new Outcome(Optional.empty(), 0, 0, 0, 0, Optional.of("a fixture's stub gives no advice")));
        }

        @Override
        public CompletableFuture<Answer> ask(Question question) {
            asked.add(question.text());
            return CompletableFuture.completedFuture(new Answer(Optional.empty(), Optional.of(name), "It splits the dark like first light.",
                    60, 0, 14, 5, Optional.empty()));
        }

        @Override public String provider() { return "Fixture"; }
        @Override public String model() { return "stub"; }
        @Override public String pace() { return "at once"; }
        @Override public void close() { }
    }

    private static ItemStack enchanted(TestContext context, Item item, Object... levels) {
        var stack = new ItemStack(item);
        var registry = context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        for (int i = 0; i < levels.length; i += 2) {
            @SuppressWarnings("unchecked") var key = (RegistryKey<Enchantment>) levels[i];
            stack.addEnchantment(registry.getEntry(key).orElseThrow(), (Integer) levels[i + 1]);
        }
        return stack;
    }

    private static int count(net.minecraft.inventory.Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    private static String named(ItemStack stack) {
        var name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    private static boolean anvilBy(TestContext context) {
        var world = context.getWorld();
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        for (BlockPos pos : BlockPos.iterate(o.add(-2, 1, -2), o.add(10, 4, 10))) if (world.getBlockState(pos).isIn(BlockTags.ANVIL)) return true;
        return false;
    }

    /**
     * Hal on open ground with a crafting table by it, holding {@code held} slot by slot, at {@code levels} levels,
     * playing; {@code stub} answers its questions, if given. Then {@code checks}.
     */
    private static void scene(TestContext context, NativePaidSkillsGameTests.Fixture f, Stub stub, ItemStack[] held, int levels, Runnable checks) {
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    if (stub != null) f.runtime.strategistForFixtures(stub);
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 10; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(context.getAbsolutePos(new BlockPos(5, 2, 3)), Blocks.CRAFTING_TABLE.getDefaultState());
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos at = context.getAbsolutePos(new BlockPos(3, 2, 3));
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (int slot = 0; slot < held.length; slot++) if (held[slot] != null) inventory.setStack(slot, held[slot]);
                    inventory.selectedSlot = 0;
                    player.setExperienceLevel(levels);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Playing, not looking on.
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(checks)
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "kitOwn", tickLimit = 3000)
    public void itMakesAnAnvilOfItsIronAndNamesItsBestSwordAndPickaxeNamesOfItsOwn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        ItemStack[] held = {
                enchanted(context, Items.DIAMOND_SWORD, Enchantments.SHARPNESS, 3, Enchantments.FIRE_ASPECT, 2),
                enchanted(context, Items.DIAMOND_PICKAXE, Enchantments.EFFICIENCY, 4),
                new ItemStack(Items.IRON_SWORD), new ItemStack(Items.ENCHANTING_TABLE), new ItemStack(Items.IRON_INGOT, 31),
                new ItemStack(Items.BREAD, 8)};
        var sword = held[0].copy();
        scene(context, f, null, held, 5, () -> {
            var inventory = f.player().getInventory();
            String swordName = named(inventory.getStack(0)), pickName = named(inventory.getStack(1));
            context.assertTrue(swordName != null && pickName != null, "Both named: " + swordName + ", " + pickName + " / " + f.runtime.nativeLastResult(f.id));
            context.assertTrue(("I named my diamond pickaxe " + pickName + ".").equals(f.runtime.lastSaid(f.id)), "Said what it named: " + f.runtime.lastSaid(f.id));
            // Names of its own pass the naming rules, and differ.
            new NameForge.Name(swordName, NameForge.Subject.TOOL, NameForge.Source.FORGE, 0);
            new NameForge.Name(pickName, NameForge.Subject.TOOL, NameForge.Source.FORGE, 0);
            context.assertTrue(!swordName.equalsIgnoreCase(pickName), "Two names");
            // The sword is the same sword, its name aside.
            var now = inventory.getStack(0).copy();
            now.remove(DataComponentTypes.CUSTOM_NAME);
            context.assertTrue(ItemStack.areEqual(now, sword), "Nothing else of the sword changed");
            context.assertTrue(named(inventory.getStack(2)) == null && inventory.getStack(2).isOf(Items.IRON_SWORD), "The plain iron sword is not named");
            context.assertTrue(f.player().experienceLevel == 3, "A level each: " + f.player().experienceLevel);
            context.assertTrue(count(inventory, Items.IRON_INGOT) == 0 && count(inventory, Items.IRON_BLOCK) == 0, "Its iron made the anvil");
            context.assertTrue(anvilBy(context), "Its anvil stands by it");
            context.assertTrue(count(inventory, Items.ENCHANTING_TABLE) == 1, "Its enchanting table kept");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "kitModel", tickLimit = 3000)
    public void itPutsDownTheAnvilItHoldsAndTakesTheModelsNameButNeverOneItHasUsed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        var stub = new Stub("Dawnsplitter");
        ItemStack[] held = new ItemStack[24];
        held[0] = enchanted(context, Items.DIAMOND_SWORD, Enchantments.SHARPNESS, 2);
        held[1] = new ItemStack(Items.DIAMOND_PICKAXE);
        held[2] = new ItemStack(Items.ENCHANTING_TABLE);
        held[20] = new ItemStack(Items.ANVIL);
        scene(context, f, stub, held, 2, () -> {
            var inventory = f.player().getInventory();
            context.assertTrue("Dawnsplitter".equals(named(inventory.getStack(0))), "The model's name for the sword: " + named(inventory.getStack(0))
                    + " / " + f.runtime.nativeLastResult(f.id));
            String pickName = named(inventory.getStack(1));
            context.assertTrue(pickName != null && !pickName.equals("Dawnsplitter"), "Its own for the pickaxe, the model's being used: " + pickName);
            context.assertTrue(stub.asked.size() == 2 && stub.asked.getFirst().startsWith("You are Hal, ")
                    && stub.asked.getFirst().contains("Name your diamond sword, with Sharpness II."), "Asked for each, with what it is: " + stub.asked);
            context.assertTrue(stub.asked.get(1).contains("You have named Dawnsplitter, your diamond sword."), "The second question says the first name");
            context.assertTrue(f.player().experienceLevel == 0, "Its two levels spent");
            context.assertTrue(count(inventory, Items.ANVIL) == 0 && anvilBy(context), "The anvil it held put down");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "kitNone", tickLimit = 3000)
    public void shortOfTheEnchantingStageItNamesNothingAndMakesNoAnvil(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        int[] after = {0};
        ItemStack[] held = {
                enchanted(context, Items.DIAMOND_SWORD, Enchantments.SHARPNESS, 3), new ItemStack(Items.IRON_INGOT, 31), new ItemStack(Items.BREAD, 8)};
        scene(context, f, null, held, 5, () -> {
            context.assertTrue(++after[0] >= 300, "Fifteen seconds");
            var inventory = f.player().getInventory();
            context.assertTrue(named(inventory.getStack(0)) == null, "Nothing named");
            context.assertTrue(count(inventory, Items.IRON_BLOCK) == 0 && count(inventory, Items.ANVIL) == 0 && !anvilBy(context), "No anvil made");
            context.assertTrue(f.player().experienceLevel == 5, "No levels spent");
        });
    }
}
