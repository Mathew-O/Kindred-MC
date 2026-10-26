package dev.kindred.fabric;

import dev.kindred.core.construction.TradingHall;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Bringing a villager home (E-45e). Hal's trading hall of three cells stands (set in place by the fixture and recorded
 * as its own, as E-45d leaves one), and twelve blocks south of its door a librarian that has traded, selling a Mending
 * book: worth keeping. Hal holds rails, a minecart, a lectern and some dirt. It looks at the librarian, finds it
 * worth keeping, takes the hall's door off, lays a line from behind the librarian to a cell's floor, pushes a cart into
 * the librarian, sees the cart roll into the cell, shuts the cell's front, breaks the cart, takes back what it can and
 * puts the lectern in the cell's front; then takes up its rails and puts the door back. The librarian's AI is off so
 * that it stands where it was put: one that wanders off the line is not yet followed.
 */
public final class NativeBringGameTests implements FabricGameTest {
    private static net.minecraft.block.BlockState state(dev.kindred.core.construction.BlockSpec spec) {
        var block = Registries.BLOCK.get(Identifier.of(spec.block()));
        var state = block.getDefaultState();
        for (var property : spec.properties().entrySet()) {
            var known = block.getStateManager().getProperty(property.getKey());
            if (known != null) state = with(state, known, property.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> net.minecraft.block.BlockState with(net.minecraft.block.BlockState state,
                                                                                  net.minecraft.state.property.Property<T> property, String value) {
        return property.parse(value).map(v -> state.with(property, v)).orElse(state);
    }

    private static int count(net.minecraft.entity.player.PlayerInventory inventory, net.minecraft.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bring", tickLimit = 12000)
    public void aKeptLibrarianIsBroughtHomeByMinecartIntoItsCell(TestContext context) {
        scene(context, false);
    }

    /**
     * As above, with no rails or minecart to hand but 24 iron ingots, 2 sticks and a crafting table by Hal: it makes the
     * two stacks of sixteen rails the line takes and a minecart, from 17 of the iron and both sticks, and brings the
     * librarian home with them, every rail and the cart taken back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringMade", tickLimit = 14000)
    public void theRailsAndTheCartAreMadeFromItsOwnIron(TestContext context) {
        scene(context, true);
    }

    private static void scene(TestContext context, boolean make) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, make ? 13500 : 11500);
        var hall = TradingHall.of(3, "oak", "cobblestone");
        BlockPos[] origin = new BlockPos[1];
        VillagerEntity[] librarian = new VillagerEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.simulated(context, o.add(-8, 0, -8), o.add(24, 0, 34));
                    for (int x = -8; x <= 24; x++) for (int z = -8; z <= 34; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 10; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    // The hall standing on the stone, its floor laid on top of it, every block in its state.
                    origin[0] = o.add(4, 2, 4);
                    for (var b : hall.placed(BuildRunner.pos(origin[0]), 0)) {
                        world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()),
                                net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                    }
                    String bases = "{\"player\":\"Hal\",\"built\":[],\"hall\":{\"dimension\":\"minecraft:overworld\",\"x\":" + origin[0].getX()
                            + ",\"y\":" + origin[0].getY() + ",\"z\":" + origin[0].getZ() + ",\"cells\":3,\"wood\":\"oak\",\"complete\":true}}";
                    try {
                        Files.createDirectories(f.directory.resolve("memory"));
                        Files.writeString(f.directory.resolve("memory").resolve(f.id + ".bases.json"), bases, StandardCharsets.UTF_8);
                    } catch (java.io.IOException error) {
                        throw new RuntimeException("The fixture's hall record could not be written", error);
                    }
                    // A librarian that has traded, twelve blocks south of the door, selling a Mending book.
                    var villager = EntityType.VILLAGER.create(world);
                    BlockPos at = origin[0].add(3, 0, 20);
                    villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    villager.setAiDisabled(true);
                    villager.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.LIBRARIAN, 1));
                    villager.setExperience(1);
                    var offers = new net.minecraft.village.TradeOfferList();
                    var paper = new net.minecraft.village.TradeOffer(new net.minecraft.village.TradedItem(Items.PAPER, 24), new ItemStack(Items.EMERALD), 16, 2, 0.05f);
                    paper.use();
                    offers.add(paper);
                    var mending = world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                            .getEntry(net.minecraft.enchantment.Enchantments.MENDING).orElseThrow();
                    offers.add(new net.minecraft.village.TradeOffer(new net.minecraft.village.TradedItem(Items.EMERALD, 2),
                            java.util.Optional.of(new net.minecraft.village.TradedItem(Items.BOOK)),
                            net.minecraft.item.EnchantedBookItem.forEnchantment(new net.minecraft.enchantment.EnchantmentLevelEntry(mending, 1)), 0, 12, 5, 0.2f));
                    villager.setOffers(offers);
                    world.spawnEntity(villager);
                    librarian[0] = villager;
                    var hal = f.player();
                    var inventory = hal.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    if (make) {
                        inventory.setStack(1, new ItemStack(Items.IRON_INGOT, 24));
                        inventory.setStack(2, new ItemStack(Items.STICK, 2));
                        world.setBlockState(origin[0].add(9, 0, 18), Blocks.CRAFTING_TABLE.getDefaultState());
                    } else {
                        inventory.setStack(1, new ItemStack(Items.RAIL, 64));
                        inventory.setStack(2, new ItemStack(Items.MINECART));
                    }
                    inventory.setStack(3, new ItemStack(Items.LECTERN));
                    inventory.setStack(4, new ItemStack(Items.DIRT, 4));
                    hal.setHealth(20);
                    hal.getHungerManager().setFoodLevel(20);
                    BlockPos stand = origin[0].add(6, 0, 18);
                    hal.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> context.assertTrue(f.runtime.brought(f.id).contains(librarian[0].getUuid()),
                        "Brought home: " + f.runtime.bringNote(f.id) + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var cell = TradingHall.cells(3).stream().filter(c -> librarian[0].getBlockX() == origin[0].getX() + c.stand().x()).findFirst();
                    context.assertTrue(cell.isPresent(), "In a cell's column: at " + librarian[0].getBlockPos().subtract(origin[0]).toShortString());
                    BlockPos slot = origin[0].add(cell.get().jobBlock().x(), cell.get().jobBlock().y(), cell.get().jobBlock().z());
                    int z = librarian[0].getBlockZ() - origin[0].getZ();
                    context.assertTrue(z >= 1 && z <= cell.get().stand().z(), "On its cell's floor or bed: at " + librarian[0].getBlockPos().subtract(origin[0]).toShortString());
                    context.assertTrue(world.getBlockState(slot).isOf(Blocks.LECTERN), "Its lectern across the cell's front: " + world.getBlockState(slot));
                    BlockPos door = origin[0].add(TradingHall.width(3) / 2, 1, 7);
                    context.assertTrue(world.getBlockState(door).getBlock() instanceof net.minecraft.block.DoorBlock, "The hall's door back: " + world.getBlockState(door));
                    var inventory = f.player().getInventory();
                    int rails = make ? 32 : 64;
                    context.assertTrue(count(inventory, Items.RAIL) == rails && count(inventory, Items.MINECART) == 1 && count(inventory, Items.LECTERN) == 0
                                    && count(inventory, Items.DIRT) == 4 && (!make || count(inventory, Items.IRON_INGOT) == 7 && count(inventory, Items.STICK) == 0),
                            "Rails, cart and dirt all taken back, the lectern spent" + (make ? ", 17 iron and 2 sticks made into rails and a cart" : "") + ": "
                                    + count(inventory, Items.RAIL) + " rails, " + count(inventory, Items.MINECART) + " carts, " + count(inventory, Items.LECTERN)
                                    + " lecterns, " + count(inventory, Items.DIRT) + " dirt, " + count(inventory, Items.IRON_INGOT) + " iron, "
                                    + count(inventory, Items.STICK) + " sticks");
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I've brought the librarian home")), "Said: " + f.runtime.said(f.id));
                    librarian[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
