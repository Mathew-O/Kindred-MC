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
 * that it stands where it was put, but in the test where it strolls, and is held in a boat while the line is laid.
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

    /**
     * As the first, with the librarian moved three blocks aside once the cart is down, as a villager wanders off a line
     * laid to where it stood: Hal lays the line again from where it now is, takes the cart up and puts it down again
     * behind it, and brings it home; every rail of both lines taken up.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringWandered", tickLimit = 14000)
    public void aLibrarianOffTheLineIsBroughtHomeFromWhereItStands(TestContext context) {
        scene(context, false, true);
    }

    /**
     * As the first, with a river three wide right across the field between the librarian and the hall: Hal bridges it
     * with dirt from the bank it is on, lays the line over the bridge, brings the librarian home, and takes the bridge
     * back after its rails, the river running as it did.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringRiver", tickLimit = 14000)
    public void aLibrarianOverARiverIsBroughtHomeOnABridgeOfItsOwn(TestContext context) {
        scene(context, false, false, true);
    }

    /**
     * As the second, with no iron at all but a stone pickaxe, four coal and a furnace, and a face of iron ore standing
     * on the ground beside it, as a cave's wall shows it: Hal mines the ore it needs, puts the furnace down and smelts
     * it, then makes the rails and the minecart and brings the librarian home.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringMined", tickLimit = 30000)
    public void theIronForTheRailsIsMinedAndSmeltedFirst(TestContext context) {
        scene(context, true, false, false, true);
    }

    /**
     * As the first, with the librarian's AI on, at work by a lectern of its own two blocks off the line in the working
     * hours (in its idle hours a villager wanders the field, and off to the beds of the hall), and an oak boat and a
     * stone pickaxe in Hal's hand: Hal puts the boat down beside the librarian, which takes it up and holds it where it
     * is; lays the line from there, with room for it riding high in its boat, the stone over the hall's doorway and the
     * cell's lintel taken out for it; and pushes the cart into the boat, which it takes up, librarian and all. In the
     * cell the lintel goes back, the boat and then the cart are broken, and the stone over the door goes back with the
     * door. The boat picked up again, the librarian unhurt.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringStrolling", tickLimit = 14000)
    public void aLibrarianThatStrollsIsHeldInABoatWhileTheLineIsLaid(TestContext context) {
        scene(context, false, false, false, false, true);
    }

    /**
     * As the last, with a lake eight wide right across the field between the librarian and the hall, and only four dirt
     * in Hal's hand, too few for a bridge of eight: Hal holds the librarian in its boat on its own side, gets in with it,
     * rows it over the ground into the water and across, and gets out on the far bank, the librarian left in the boat on
     * the water (let go of, it would wade back to its lectern); lays the line along the bank past it, and the cart, rolling
     * by, takes the boat up from the water and carries it home as the last.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringAcross", tickLimit = 16000)
    public void aLibrarianAcrossALakeIsRowedOverAndBroughtHome(TestContext context) {
        scene(context, false, false, false, false, true, true);
    }

    private static void scene(TestContext context, boolean make) {
        scene(context, make, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander) {
        scene(context, make, wander, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river) {
        scene(context, make, wander, river, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine) {
        scene(context, make, wander, river, mine, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling) {
        scene(context, make, wander, river, mine, strolling, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, mine ? 29500 : lake ? 15500 : make || wander || river || strolling ? 13500 : 11500);
        boolean[] moved = {false};
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
                    world.setTimeOfDay(strolling ? 3000 : 1000);
                    if (strolling) world.setBlockState(o.add(4, 2, 4).add(5, 0, 20), Blocks.LECTERN.getDefaultState());
                    // The river: water at the ground's top, a block deep, banked at either end of the field.
                    if (river) {
                        for (int x = -9; x <= 25; x++) for (int z = 15; z <= 17; z++) {
                            world.setBlockState(o.add(x, 0, z), Blocks.STONE.getDefaultState());
                            world.setBlockState(o.add(x, 1, z), x < -8 || x > 24 ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState());
                        }
                    }
                    // The lake: water at the ground's top, a block deep, eight wide, banked at either end of the field.
                    if (lake) {
                        for (int x = -9; x <= 25; x++) for (int z = 15; z <= 22; z++) {
                            world.setBlockState(o.add(x, 0, z), Blocks.STONE.getDefaultState());
                            world.setBlockState(o.add(x, 1, z), x < -8 || x > 24 ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState());
                        }
                    }
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
                    // No villager left about by a fixture that failed before it (they stand where they were put): one 13
                    // blocks off was the one Hal brought home instead of its own.
                    world.getEntitiesByClass(VillagerEntity.class, new net.minecraft.util.math.Box(origin[0]).expand(48), v -> true).forEach(VillagerEntity::discard);
                    // A librarian that has traded, twelve blocks south of the door, selling a Mending book.
                    var villager = EntityType.VILLAGER.create(world);
                    BlockPos at = origin[0].add(3, 0, 20);
                    villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    villager.setAiDisabled(!strolling);
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
                    if (mine) {
                        // A face of iron ore east of it, three high, its side toward it in plain sight: ore flush with a flat
                        // floor shows only its top, which the floor about it hides from a few blocks off.
                        for (int x = 13; x <= 20; x++) for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, 27), Blocks.IRON_ORE.getDefaultState());
                        inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                        inventory.setStack(2, new ItemStack(Items.STICK, 2));
                        inventory.setStack(5, new ItemStack(Items.COAL, 4));
                        inventory.setStack(6, new ItemStack(Items.FURNACE));
                        world.setBlockState(origin[0].add(9, 0, 18), Blocks.CRAFTING_TABLE.getDefaultState());
                    } else if (make) {
                        inventory.setStack(1, new ItemStack(Items.IRON_INGOT, 24));
                        inventory.setStack(2, new ItemStack(Items.STICK, 2));
                        world.setBlockState(origin[0].add(9, 0, 18), Blocks.CRAFTING_TABLE.getDefaultState());
                    } else {
                        inventory.setStack(1, new ItemStack(Items.RAIL, 64));
                        inventory.setStack(2, new ItemStack(Items.MINECART));
                    }
                    inventory.setStack(3, new ItemStack(Items.LECTERN));
                    inventory.setStack(4, new ItemStack(Items.DIRT, river ? 8 : 4));
                    if (strolling) {
                        inventory.setStack(7, new ItemStack(Items.OAK_BOAT));
                        // To take out the hall's stone over the line for the villager riding high in its boat.
                        inventory.setStack(6, new ItemStack(Items.STONE_PICKAXE));
                    }
                    hal.setHealth(20);
                    hal.getHungerManager().setFoodLevel(20);
                    // On the librarian's side of the water, by it.
                    BlockPos stand = origin[0].add(6, 0, lake ? 21 : 18);
                    hal.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> {
                    // Off the line, once the cart is down behind it: three blocks east.
                    if (wander && !moved[0] && !context.getWorld().getEntitiesByClass(net.minecraft.entity.vehicle.MinecartEntity.class,
                            new net.minecraft.util.math.Box(origin[0].add(3, 0, 22)).expand(1), c -> true).isEmpty()) {
                        var at = origin[0].add(6, 0, 20);
                        librarian[0].refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                        moved[0] = true;
                        KindredMod.LOGGER.info("Bring: the librarian moved off the line, to {}", at.subtract(origin[0]).toShortString());
                    }
                    context.assertTrue(f.runtime.brought(f.id).contains(librarian[0].getUuid()),
                            "Brought home: " + f.runtime.bringNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                  try {
                    var world = context.getWorld();
                    var cell = TradingHall.cells(3).stream().filter(c -> librarian[0].getBlockX() == origin[0].getX() + c.stand().x()).findFirst();
                    context.assertTrue(cell.isPresent(), "In a cell's column: at " + librarian[0].getBlockPos().subtract(origin[0]).toShortString());
                    BlockPos slot = origin[0].add(cell.get().jobBlock().x(), cell.get().jobBlock().y(), cell.get().jobBlock().z());
                    int z = librarian[0].getBlockZ() - origin[0].getZ();
                    context.assertTrue(z >= 1 && z <= cell.get().stand().z(), "On its cell's floor or bed: at " + librarian[0].getBlockPos().subtract(origin[0]).toShortString());
                    context.assertTrue(world.getBlockState(slot).isOf(Blocks.LECTERN), "Its lectern across the cell's front: " + world.getBlockState(slot));
                    BlockPos door = origin[0].add(TradingHall.width(3) / 2, 1, 7);
                    context.assertTrue(world.getBlockState(door).getBlock() instanceof net.minecraft.block.DoorBlock, "The hall's door back: " + world.getBlockState(door));
                    if (lake) {
                        // Across it by boat: no bridge put down, the water as it was.
                        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                        for (int rz = 15; rz <= 22; rz++) {
                            context.assertTrue(world.getBlockState(o.add(7, 1, rz)).isOf(Blocks.WATER), "The lake as it was: " + world.getBlockState(o.add(7, 1, rz)));
                        }
                        context.assertTrue(f.runtime.said(f.id).stream().noneMatch(line -> line.contains("plain blocks")), "Not stopped for want of a bridge");
                    }
                    if (river) {
                        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                        for (int rz = 15; rz <= 17; rz++) {
                            context.assertTrue(world.getBlockState(o.add(7, 1, rz)).isOf(Blocks.WATER), "The bridge taken back, the river as it was: "
                                    + world.getBlockState(o.add(7, 1, rz)) + " at " + o.add(7, 1, rz).subtract(origin[0]).toShortString());
                        }
                    }
                    var hal = f.player();
                    context.assertTrue(hal != null, "Hal still in play");
                    var inventory = hal.getInventory();
                    // All but the rail on the cell's floor, left to the cell.
                    int rails = make ? 31 : 63;
                    BlockPos floorRail = origin[0].add(cell.get().stand().x(), cell.get().stand().y(), cell.get().stand().z());
                    context.assertTrue(world.getBlockState(floorRail).isIn(net.minecraft.registry.tag.BlockTags.RAILS), "The rail on its cell's floor left: "
                            + world.getBlockState(floorRail));
                    context.assertTrue(count(inventory, Items.RAIL) == rails && count(inventory, Items.MINECART) == (strolling ? 0 : 1) && count(inventory, Items.LECTERN) == 0
                                    && count(inventory, Items.DIRT) == (river ? 8 : 4) && (!make || count(inventory, Items.IRON_INGOT) <= 7 && count(inventory, Items.STICK) == 0),
                            "Rails (but the cell floor's), cart (but one that strolls) and dirt all taken back, the lectern spent" + (make ? ", 17 iron and 2 sticks made into rails and a cart (what iron is left its own to use)" : "") + ": "
                                    + count(inventory, Items.RAIL) + " rails, " + count(inventory, Items.MINECART) + " carts, " + count(inventory, Items.LECTERN)
                                    + " lecterns, " + count(inventory, Items.DIRT) + " dirt, " + count(inventory, Items.IRON_INGOT) + " iron, "
                                    + count(inventory, Items.STICK) + " sticks");
                    if (mine) {
                        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                        int mined = 0;
                        for (int ox = 13; ox <= 20; ox++) for (int oy = 2; oy <= 4; oy++) if (!world.getBlockState(o.add(ox, oy, 27)).isOf(Blocks.IRON_ORE)) mined++;
                        context.assertTrue(mined >= 17 && count(inventory, Items.RAW_IRON) + count(inventory, Items.IRON_INGOT) <= mined - 17,
                                "The ore it mined smelted, seventeen ingots made into rails and a cart: " + mined + " mined, " + count(inventory, Items.RAW_IRON)
                                        + " raw and " + count(inventory, Items.IRON_INGOT) + " ingots left");
                    }
                    if (strolling) {
                        context.assertTrue(count(inventory, Items.OAK_BOAT) == 1, "The boat picked up again: " + count(inventory, Items.OAK_BOAT));
                        // One that strolls is never let loose with the front open: the cart left in its cell, under it.
                        context.assertTrue(!world.getEntitiesByClass(net.minecraft.entity.vehicle.MinecartEntity.class, new net.minecraft.util.math.Box(floorRail).expand(0.5, 1, 0.5),
                                c -> true).isEmpty(), "The cart left in its cell");
                        // The stone taken out over the line for its ride put back: over the doorway, and the cell's lintel.
                        for (var over : java.util.List.of(door.up(2), slot.up(2))) {
                            context.assertTrue(world.getBlockState(over).isOf(Blocks.COBBLESTONE), "Put back over the line: " + world.getBlockState(over)
                                    + " at " + over.subtract(origin[0]).toShortString());
                        }
                        context.assertTrue(librarian[0].getHealth() >= librarian[0].getMaxHealth(), "Unhurt by its ride: " + librarian[0].getHealth());
                    }
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I've brought the librarian home")), "Said: " + f.runtime.said(f.id));
                    librarian[0].discard();
                    f.runtime.stop();
                  } catch (net.minecraft.test.GameTestException failing) {
                    // What is not yet so, now and then: the test server says only the last of it, when the test ends.
                    if (context.getWorld().getTime() % 100 == 0) KindredMod.LOGGER.info("Bring fixture, not yet: {}", failing.getMessage());
                    throw failing;
                  }
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
