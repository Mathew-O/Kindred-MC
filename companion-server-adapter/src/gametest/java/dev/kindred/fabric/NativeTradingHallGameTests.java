package dev.kindred.fabric;

import dev.kindred.core.construction.Placement;
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
import java.util.TreeMap;

/**
 * The trading hall (E-45d): a hall of two cells built by Mason from exactly the blocks it takes, every block in its
 * state, its cells and corridor clear; then a librarian in a cell, its lectern across the cell's front, kept in by the
 * cell and keeping its profession, as the hall is meant to hold it. The villager is set in its cell by the fixture:
 * bringing one there is E-45e's.
 */
public final class NativeTradingHallGameTests implements FabricGameTest {
    private static void check(TestContext context, String[] first, boolean ok, String message) {
        if (ok) return;
        if (first[0] == null) first[0] = message;
        context.assertTrue(false, first[0]);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tradingHall", tickLimit = 16000)
    public void aHallOfCellsIsBuiltExactAndHoldsALibrarianAtItsLectern(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 15500);
        String[] first = {null};
        var hall = TradingHall.of(2, "oak", "cobblestone");
        var needed = new TreeMap<String, Integer>();
        // A door's upper half and a bed's head come with their partner's item, as the bill of materials counts them.
        for (var b : hall.blocks()) if (Placement.rule(b.spec()) != Placement.Rule.COMPANION) needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        BlockPos[] origin = new BlockPos[1];
        VillagerEntity[] villager = new VillagerEntity[1];
        int[] waited = {0}, ticks = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.simulated(context, o.add(-8, 0, -8), o.add(18, 0, 18));
                    for (int x = -8; x <= 18; x++) for (int z = -8; z <= 18; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 14; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 8));
                    for (var item : needed.entrySet()) {
                        int left = item.getValue();
                        while (left > 0) {
                            int count = Math.min(64, left);
                            inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), count));
                            left -= count;
                        }
                    }
                    inventory.setStack(slot, new ItemStack(Items.DIRT, 64));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 12.5, 0, 0);
                    origin[0] = o.add(4, 2, 4);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hall, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hall.placed(BuildRunner.pos(origin[0]), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " blocks not in their state: " + report);
                    for (var p : hall.placedClear(BuildRunner.pos(origin[0]), 0)) {
                        var state = world.getBlockState(BuildRunner.blockPos(p));
                        check(context, first, state.getCollisionShape(world, BuildRunner.blockPos(p)).isEmpty(), "Kept clear: " + p + " holds " + state);
                    }
                    // A villager with no job set in the first cell, on its floor before its bed, and a lectern across the cell's front.
                    var cell = TradingHall.cells(2).getFirst();
                    BlockPos stand = origin[0].add(cell.stand().x(), cell.stand().y(), cell.stand().z());
                    BlockPos job = origin[0].add(cell.jobBlock().x(), cell.jobBlock().y(), cell.jobBlock().z());
                    world.setBlockState(job, Blocks.LECTERN.getDefaultState());
                    var librarian = EntityType.VILLAGER.create(world);
                    librarian.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    librarian.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
                    world.spawnEntity(librarian);
                    villager[0] = librarian;
                })
                .createAndAdd(() -> {
                    if (++ticks[0] % 200 == 0) KindredMod.LOGGER.info("Trading hall: the villager at {}, {}, after {} ticks",
                            villager[0].getBlockPos().subtract(origin[0]).toShortString(), villager[0].getVillagerData().getProfession(), ticks[0]);
                    context.assertTrue(villager[0].getVillagerData().getProfession() == VillagerProfession.LIBRARIAN,
                            "Took up its lectern: " + villager[0].getVillagerData().getProfession());
                })
                .createAndAdd(() -> {
                    var cell = TradingHall.cells(2).getFirst();
                    BlockPos stand = origin[0].add(cell.stand().x(), 0, cell.stand().z());
                    var at = villager[0].getBlockPos();
                    if (waited[0] == 0) KindredMod.LOGGER.info("Trading hall: a librarian after {} ticks", ticks[0]);
                    if (waited[0] % 100 == 0) KindredMod.LOGGER.info("Trading hall: the librarian at {} ({}), {} ticks on",
                            villager[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()), villager[0].getVillagerData().getProfession(), waited[0]);
                    // Its floor or its bed: the cell is a block wide, its bed the two behind its floor. Once out, the test has failed.
                    check(context, first, first[0] == null && at.getX() == stand.getX() && at.getZ() >= stand.getZ() - 2 && at.getZ() <= stand.getZ(),
                            "Kept in its cell: at " + at.subtract(origin[0]).toShortString() + " after " + waited[0] + " ticks");
                    check(context, first, villager[0].getVillagerData().getProfession() == VillagerProfession.LIBRARIAN, "Still a librarian");
                    waited[0]++;
                    context.assertTrue(waited[0] >= 600, "Thirty seconds in its cell");
                })
                .createAndAdd(() -> {
                    context.assertTrue(first[0] == null && f.player() != null, first[0] == null ? "Mason still here" : first[0]);
                    var inventory = f.player().getInventory();
                    var left = new TreeMap<String, Integer>();
                    for (var item : needed.keySet()) {
                        int count = inventory.count(Registries.ITEM.get(Identifier.of(item)));
                        if (count != 0) left.put(item, count);
                    }
                    check(context, first, left.isEmpty(), "Every block it was given went into the hall: left " + left);
                    villager[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A block as a spec names it, its properties set as named: for setting a build already standing in a fixture. */
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

    /**
     * The trading hall begun of the player's own accord (E-45d): Mason's cabin standing as its base (set in place by the
     * fixture and recorded as its first stage, as M-01c would have left it), the operator's leave for bases on, and a
     * librarian nearby selling a Mending book. Mason looks at the librarian in passing (E-45b), finds it worth keeping
     * (E-45c), and with no hall to keep it in sites one on flat open ground by its cabin, clear of it, and builds it from
     * the blocks it holds: the hall's own bill, given as though gathered.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "tradingHallSite", tickLimit = 24000)
    public void aPlayerThatKeepsAVillagerBuildsATradingHallByItsBase(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 23500);
        String[] first = {null};
        var cabin = dev.kindred.core.construction.Houses.of(dev.kindred.core.construction.Houses.Stage.CABIN,
                dev.kindred.core.construction.BuildStyle.CLASSIC);
        var hall = TradingHall.of(3, "oak", "cobblestone");
        var needed = new TreeMap<String, Integer>();
        for (var b : hall.blocks()) if (Placement.rule(b.spec()) != Placement.Rule.COMPANION) needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos cabinAt = o.add(10, 7, 10), home = o.add(16, 7, 20), at = o.add(19, 7, 20);
        VillagerEntity[] librarian = new VillagerEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, new BlockPos[0]);
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // Its cabin standing, every block in its state, and recorded as its base, whole.
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()),
                                net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
                    }
                    String bases = "{\"player\":\"Mason\",\"anchor\":{\"dimension\":\"minecraft:overworld\",\"x\":" + home.getX() + ",\"y\":" + home.getY()
                            + ",\"z\":" + home.getZ() + "},\"built\":[{\"stage\":\"cabin\",\"dimension\":\"minecraft:overworld\",\"x\":" + cabinAt.getX()
                            + ",\"y\":" + cabinAt.getY() + ",\"z\":" + cabinAt.getZ() + ",\"complete\":true,\"variant\":0,\"turns\":0}]}";
                    try {
                        Files.createDirectories(f.directory.resolve("memory"));
                        Files.writeString(f.directory.resolve("memory").resolve(f.id + ".bases.json"), bases, StandardCharsets.UTF_8);
                    } catch (java.io.IOException error) {
                        throw new RuntimeException("The fixture's base record could not be written", error);
                    }
                    // A librarian selling a Mending book, its AI off so that it stays.
                    var villager = EntityType.VILLAGER.create(world);
                    villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);
                    villager.setAiDisabled(true);
                    villager.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.LIBRARIAN, 1));
                    var offers = new net.minecraft.village.TradeOfferList();
                    offers.add(new net.minecraft.village.TradeOffer(new net.minecraft.village.TradedItem(Items.PAPER, 24), new ItemStack(Items.EMERALD), 16, 2, 0.05f));
                    var mending = world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                            .getEntry(net.minecraft.enchantment.Enchantments.MENDING).orElseThrow();
                    offers.add(new net.minecraft.village.TradeOffer(new net.minecraft.village.TradedItem(Items.EMERALD, 2),
                            java.util.Optional.of(new net.minecraft.village.TradedItem(Items.BOOK)),
                            net.minecraft.item.EnchantedBookItem.forEnchantment(new net.minecraft.enchantment.EnchantmentLevelEntry(mending, 1)), 0, 12, 5, 0.2f));
                    villager.setOffers(offers);
                    world.spawnEntity(villager);
                    librarian[0] = villager;
                    // The hall's bill as though gathered, and tools and food.
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(slot++, new ItemStack(Items.STONE_AXE));
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 16));
                    for (var item : needed.entrySet()) {
                        int left = item.getValue();
                        while (left > 0) {
                            int count = Math.min(64, left);
                            inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), count));
                            left -= count;
                        }
                    }
                    inventory.setStack(slot, new ItemStack(Items.DIRT, 64));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, -90, 0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Mason playing"))
                .createAndAdd(() -> context.assertTrue(f.runtime.hall(f.id).isPresent(),
                        "A hall sited: " + f.runtime.baseNote(f.id) + " / villagers known " + f.runtime.villagersKnown(f.id)))
                .createAndAdd(() -> {
                    var site = f.runtime.hall(f.id).orElseThrow();
                    KindredMod.LOGGER.info("Trading hall: sited at {}, {}, {} by the cabin at {}", site.x(), site.y(), site.z(), cabinAt.toShortString());
                    check(context, first, site.cells() == 3 && site.wood().equals("oak"), "A hall of three in its base's wood: " + site);
                    // By its cabin and clear of it: the cabin's ground and the hall's do not meet.
                    int cabinMinX = Integer.MAX_VALUE, cabinMinZ = Integer.MAX_VALUE, cabinMaxX = Integer.MIN_VALUE, cabinMaxZ = Integer.MIN_VALUE;
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        cabinMinX = Math.min(cabinMinX, b.at().x());
                        cabinMinZ = Math.min(cabinMinZ, b.at().z());
                        cabinMaxX = Math.max(cabinMaxX, b.at().x());
                        cabinMaxZ = Math.max(cabinMaxZ, b.at().z());
                    }
                    int hallMaxX = site.x() + TradingHall.width(3) - 1, hallMaxZ = site.z() + TradingHall.DEPTH - 1;
                    boolean meets = site.x() <= cabinMaxX && hallMaxX >= cabinMinX && site.z() <= cabinMaxZ && hallMaxZ >= cabinMinZ;
                    check(context, first, !meets, "Clear of its cabin: the hall at " + site + ", the cabin " + cabinMinX + ".." + cabinMaxX + ", " + cabinMinZ + ".." + cabinMaxZ);
                    double centreX = (cabinMinX + cabinMaxX) / 2.0, centreZ = (cabinMinZ + cabinMaxZ) / 2.0;
                    double off = Math.hypot(site.x() + TradingHall.width(3) / 2.0 - centreX, site.z() + TradingHall.DEPTH / 2.0 - centreZ);
                    check(context, first, off <= 28, "By its cabin: " + off + " blocks off");
                    check(context, first, site.y() == cabinAt.getY(), "On the ground its cabin stands on: " + site.y());
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.hall(f.id).map(PlayerMemoryStore.Hall::complete).orElse(false),
                        "The hall stands: " + f.runtime.baseNote(f.id) + " / " + f.runtime.buildStatus(f.id).orElse("")))
                .createAndAdd(() -> {
                    var site = f.runtime.hall(f.id).orElseThrow();
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hall.placed(new dev.kindred.core.construction.Pos(site.x(), site.y(), site.z()), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " of the hall's blocks not in their state: " + report);
                    check(context, first, f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("A villager worth keeping, and a cell to keep it in")),
                            "Said why: " + f.runtime.said(f.id));
                    check(context, first, f.runtime.currentBase(f.id).map(base -> base.stage().equals("cabin") && base.complete()).orElse(false),
                            "Its cabin still its base, whole: " + f.runtime.currentBase(f.id));
                    context.assertTrue(first[0] == null, first[0]);
                    librarian[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Its trading hall kept whole as its base is (E-45d, E-31a): Mason's cabin and a hall of three standing as its own,
     * both recorded whole, a librarian shut in the first cell behind its lectern, and three of the hall's blocks gone, as a
     * creeper would leave it: a block of its front wall, the third cell's lintel and a plank of its roof. Mason puts back
     * those three from the blocks it holds and nothing else: the lectern across the first cell's front, which the hall's
     * plan keeps clear, stays, and the librarian with it.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "tradingHallRepair", tickLimit = 8000)
    public void aBrokenTradingHallIsRepairedAroundTheVillagerInItsCell(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 7500);
        String[] first = {null};
        var cabin = dev.kindred.core.construction.Houses.of(dev.kindred.core.construction.Houses.Stage.CABIN,
                dev.kindred.core.construction.BuildStyle.CLASSIC);
        var hall = TradingHall.of(3, "oak", "cobblestone");
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos cabinAt = o.add(10, 7, 10), hallAt = o.add(10, 7, 28), home = o.add(14, 7, 24);
        var cell = TradingHall.cells(3).getFirst();
        BlockPos stand = hallAt.add(cell.stand().x(), cell.stand().y(), cell.stand().z());
        BlockPos front = hallAt.add(cell.jobBlock().x(), cell.jobBlock().y(), cell.jobBlock().z());
        // The hall's front wall is its row 7, its roof four over its floor, the third cell's lintel over its front at row 4.
        var broken = java.util.List.of(hallAt.add(1, 2, 7), hallAt.add(5, 3, 4), hallAt.add(3, 4, 1));
        VillagerEntity[] librarian = new VillagerEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, new BlockPos[0]);
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    int flags = net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE;
                    // Its cabin and its hall standing, every block in its state, both recorded as its own and whole.
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), flags);
                    for (var b : hall.placed(BuildRunner.pos(hallAt), 0)) world.setBlockState(BuildRunner.blockPos(b.at()), state(b.spec()), flags);
                    String bases = "{\"player\":\"Mason\",\"anchor\":{\"dimension\":\"minecraft:overworld\",\"x\":" + home.getX() + ",\"y\":" + home.getY()
                            + ",\"z\":" + home.getZ() + "},\"built\":[{\"stage\":\"cabin\",\"dimension\":\"minecraft:overworld\",\"x\":" + cabinAt.getX()
                            + ",\"y\":" + cabinAt.getY() + ",\"z\":" + cabinAt.getZ() + ",\"complete\":true,\"variant\":0,\"turns\":0}],"
                            + "\"hall\":{\"dimension\":\"minecraft:overworld\",\"x\":" + hallAt.getX() + ",\"y\":" + hallAt.getY() + ",\"z\":" + hallAt.getZ()
                            + ",\"cells\":3,\"wood\":\"oak\",\"complete\":true}}";
                    try {
                        Files.createDirectories(f.directory.resolve("memory"));
                        Files.writeString(f.directory.resolve("memory").resolve(f.id + ".bases.json"), bases, StandardCharsets.UTF_8);
                    } catch (java.io.IOException error) {
                        throw new RuntimeException("The fixture's base record could not be written", error);
                    }
                    // A librarian shut in the first cell behind its lectern, as a bring leaves one; its AI off so that it stays.
                    world.setBlockState(front, Blocks.LECTERN.getDefaultState());
                    var villager = EntityType.VILLAGER.create(world);
                    villager.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    villager.setAiDisabled(true);
                    villager.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.LIBRARIAN, 1));
                    world.spawnEntity(villager);
                    librarian[0] = villager;
                    // Three of the hall's blocks gone, as a creeper would leave it.
                    for (var at : broken) world.setBlockState(at, Blocks.AIR.getDefaultState(), flags);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_AXE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 2));
                    inventory.setStack(4, new ItemStack(Items.OAK_PLANKS, 1));
                    inventory.setStack(5, new ItemStack(Items.DIRT, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Mason playing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (var at : broken) {
                        context.assertTrue(!world.getBlockState(at).isAir(), "Put back at " + at.subtract(hallAt).toShortString() + ": " + f.runtime.baseNote(f.id)
                                + " / " + f.runtime.buildStatus(f.id).orElse(""));
                    }
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hall.placed(BuildRunner.pos(hallAt), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " of the hall's blocks not in their state: " + report);
                    // Only what was gone: the lectern the plan keeps clear still shuts the librarian in.
                    check(context, first, world.getBlockState(front).isOf(Blocks.LECTERN), "Its lectern left across the cell's front: " + world.getBlockState(front));
                    var at = librarian[0].getBlockPos();
                    check(context, first, librarian[0].isAlive() && at.getX() == stand.getX() && at.getZ() >= stand.getZ() - 2 && at.getZ() <= stand.getZ(),
                            "The librarian still in its cell: at " + at.subtract(hallAt).toShortString());
                    var inventory = f.player().getInventory();
                    check(context, first, inventory.count(Items.COBBLESTONE) == 0 && inventory.count(Items.OAK_PLANKS) == 0,
                            "What it held went into the repair: " + inventory.count(Items.COBBLESTONE) + " cobblestone, " + inventory.count(Items.OAK_PLANKS) + " planks");
                    context.assertTrue(first[0] == null, first[0]);
                    KindredMod.LOGGER.info("Trading hall: repaired ({})", f.runtime.baseNote(f.id));
                    librarian[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
