package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.util.Optional;
import java.util.function.Function;

/**
 * In plain terms: a GameTest: in a real test world, a player beside a librarian villager opens its trading
 * screen to see the offers, then sells its paper for emeralds and buys a Mending book, with exactly the items
 * each trade asks and gives, and says what it traded. Two more tests check that it leaves an offer the
 * villager has run out of (saying it is worth coming back for) and never pays for a book not worth its price,
 * trading nothing in either case.
 *
 * Technical: Trading at a villager's screen (E-45b). A librarian beside Hal, its offers set by the fixture as
 * vanilla's table would have them, held still (its AI off) so that it stays where it is; Hal holds 48 paper,
 * a book, a pickaxe and bread. Hal looks at what it offers by opening its screen, then sells what it can
 * spare for what is worth buying, the trades exactly conserved; an offer the villager is out of is left; one
 * not worth its price is never paid for, and nothing is sold to pay for nothing.
 */
public final class NativeTradeGameTests implements FabricGameTest {
    private static final BlockPos HAL = new BlockPos(5, 2, 5), LIBRARIAN = new BlockPos(7, 2, 5);

    private static ItemStack book(TestContext context, RegistryKey<net.minecraft.enchantment.Enchantment> key, int level) {
        var entry = context.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(key).orElseThrow();
        return EnchantedBookItem.forEnchantment(new EnchantmentLevelEntry(entry, level));
    }

    /** Hal's inventory; a failed check, not a crash, when its body was taken out for reconciliation. */
    private static net.minecraft.entity.player.PlayerInventory held(TestContext context, NativePaidSkillsGameTests.Fixture f) {
        var hal = f.player();
        context.assertTrue(hal != null, "Hal in play / " + f.runtime.nativeLastResult(f.id));
        return hal.getInventory();
    }

    private static int count(net.minecraft.entity.player.PlayerInventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    private static boolean holdsMending(net.minecraft.entity.player.PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            if (!stack.isOf(Items.ENCHANTED_BOOK)) continue;
            var stored = stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
            for (var each : stored.getEnchantmentEntries()) if (each.getKey().matchesKey(Enchantments.MENDING)) return true;
        }
        return false;
    }

    /**
     * Hal and a librarian of {@code offers} on open ground, two blocks apart; Hal holding 48 paper, a book, a pickaxe and
     * bread, playing. Then each of {@code checks}, given the librarian.
     */
    @SafeVarargs
    private static void scene(TestContext context, NativePaidSkillsGameTests.Fixture f, Function<TestContext, TradeOfferList> offers,
                              java.util.function.Consumer<VillagerEntity>... checks) {
        VillagerEntity[] librarian = new VillagerEntity[1];
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 14; x++) for (int z = -4; z <= 14; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var villager = EntityType.VILLAGER.create(world);
                    BlockPos at = context.getAbsolutePos(LIBRARIAN);
                    villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);
                    villager.setAiDisabled(true);
                    villager.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.LIBRARIAN, 1));
                    villager.setOffers(offers.apply(context));
                    world.spawnEntity(villager);
                    librarian[0] = villager;
                    var hal = f.player();
                    BlockPos a = context.getAbsolutePos(HAL);
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, -90, 0);
                    var inventory = hal.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(2, new ItemStack(Items.PAPER, 48));
                    inventory.setStack(3, new ItemStack(Items.BOOK));
                    hal.setHealth(20);
                    hal.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"));
        for (var check : checks) runner = runner.createAndAdd(() -> check.accept(librarian[0]));
        runner.createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    librarian[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static TradeOffer paper() {
        return new TradeOffer(new TradedItem(Items.PAPER, 24), new ItemStack(Items.EMERALD), 16, 2, 0.05f);
    }

    private static TradeOffer bookOffer(ItemStack book, int emeralds, int uses) {
        return new TradeOffer(new TradedItem(Items.EMERALD, emeralds), Optional.of(new TradedItem(Items.BOOK)), book, uses, 12, 5, 0.2f);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tradeBuy", tickLimit = 1600)
    public void itLooksSellsWhatItCanSpareAndBuysWhatIsWorthItExactlyConserved(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        scene(context, f, c -> {
                    var list = new TradeOfferList();
                    list.add(paper());
                    list.add(bookOffer(book(c, Enchantments.MENDING, 1), 2, 0));
                    list.add(new TradeOffer(new TradedItem(Items.EMERALD, 9), new ItemStack(Items.BOOKSHELF), 12, 1, 0.05f));
                    return list;
                },
                librarian -> context.assertTrue(f.runtime.villagersKnown(f.id) == 1, "Looked at its offers / " + f.runtime.nativeLastResult(f.id)),
                librarian -> {
                    var inventory = held(context, f);
                    context.assertTrue(holdsMending(inventory), "The Mending book bought / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(count(inventory, Items.PAPER) == 0 && count(inventory, Items.BOOK) == 0 && count(inventory, Items.EMERALD) == 0
                            && count(inventory, Items.ENCHANTED_BOOK) == 1 && count(inventory, Items.BREAD) == 8 && count(inventory, Items.STONE_PICKAXE) == 1
                            && count(inventory, Items.BOOKSHELF) == 0, "Exactly what the trades asked and yielded, nothing else");
                    var offers = librarian.getOffers();
                    context.assertTrue(offers.get(0).getUses() == 2 && offers.get(1).getUses() == 1 && offers.get(2).getUses() == 0,
                            "The villager's own count: " + offers.get(0).getUses() + "/" + offers.get(1).getUses() + "/" + offers.get(2).getUses());
                    context.assertTrue(f.runtime.said(f.id).contains("I sold 48 paper for 2 emeralds and bought a Mending book for 2 emeralds and a book."),
                            "Said what it traded: " + f.runtime.said(f.id));
                });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tradeOut", tickLimit = 1600)
    public void anOfferTheVillagerIsOutOfIsLeftAndNothingIsSoldForIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        int[] waited = {0};
        scene(context, f, c -> {
                    var list = new TradeOfferList();
                    list.add(paper());
                    list.add(bookOffer(book(c, Enchantments.MENDING, 1), 2, 12));
                    return list;
                },
                librarian -> context.assertTrue(f.runtime.villagersKnown(f.id) == 1, "Looked at its offers / " + f.runtime.nativeLastResult(f.id)),
                librarian -> {
                    context.assertTrue(f.runtime.said(f.id).contains("The librarian here sells a Mending book for 2 emeralds and a book: worth it, once it has restocked."),
                            "Told what is worth coming back for: " + f.runtime.said(f.id));
                    context.assertTrue(++waited[0] >= 100, "Five seconds");
                    var inventory = held(context, f);
                    context.assertTrue(count(inventory, Items.PAPER) == 48 && count(inventory, Items.BOOK) == 1 && count(inventory, Items.EMERALD) == 0
                            && !holdsMending(inventory), "Nothing traded");
                    context.assertTrue(librarian.getOffers().get(0).getUses() == 0, "Its paper not sold for a book it is out of");
                });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tradeDear", tickLimit = 1600)
    public void aBookNotWorthItsPriceIsNeverPaidFor(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        int[] waited = {0};
        scene(context, f, c -> {
                    var list = new TradeOfferList();
                    list.add(paper());
                    list.add(bookOffer(book(c, Enchantments.BANE_OF_ARTHROPODS, 1), 2, 0));
                    return list;
                },
                librarian -> context.assertTrue(f.runtime.villagersKnown(f.id) == 1, "Looked at its offers / " + f.runtime.nativeLastResult(f.id)),
                librarian -> {
                    context.assertTrue(++waited[0] >= 100, "Five seconds");
                    var inventory = held(context, f);
                    context.assertTrue(count(inventory, Items.PAPER) == 48 && count(inventory, Items.BOOK) == 1 && count(inventory, Items.ENCHANTED_BOOK) == 0,
                            "Nothing traded");
                    context.assertTrue(f.runtime.said(f.id).stream().noneMatch(line -> line.startsWith("I sold") || line.startsWith("The librarian")),
                            "Nothing said of it: " + f.runtime.said(f.id));
                });
    }
}
