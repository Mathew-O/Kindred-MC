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
     * As the one before it, with the loaded cart's first roll home slowed once on its way north, so that it runs out of way short
     * of the climb into the hall and rolls back: Hal, after it along the line, meets it near where it lost its way and
     * pushes it on from there, and brings the librarian home. In CI on PR #36 (`abc4fbf`) the cart lost its way there
     * and rolled back to the line's start, where Hal waited for it; every push from there was as short, and Hal gave up
     * with the librarian aboard (F-22c25). Staged: the cart's speed cut to three tenths once, four or more blocks along.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringSlowed", tickLimit = 14000)
    public void aLoadedCartThatLosesItsWayIsSentOnFromNearWhereItRolledBack(TestContext context) {
        scene(context, false, true, false, false, false, false, true);
    }

    /**
     * As the one before it, with the loaded cart sent rolling back down the line fast from short of the climb into the
     * hall on its first roll home: Hal, as the cart rolls away, stops the line's open end with a rail of its own in the
     * cell it pushed from and a block of its own beyond; the cart rolling back stops there, still on its rails, and Hal
     * breaks the block, pushes the cart on from where it stood and puts the block back as the cart rolls away, brings the librarian home, and takes the rail and the block back with the rest.
     * In #70's CI (run 42533785406) the cart rolled back through Hal, waiting on the rails behind it, and off the line's
     * open end, off the rails; pushed from there it ran off over the field and Hal gave up (F-22c30). Staged: the cart's
     * roll turned back once, short of the climb, at half a block a tick.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringDerailed", tickLimit = 14000)
    public void aLoadedCartRollingBackAtTheLinesStartIsMetOnItsFirstRail(TestContext context) {
        scene(context, false, true, false, false, false, false, false, true);
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
     * cell the lintel goes back and the lectern goes in across the front, never left open with the librarian loose; the
     * boat is broken over the cart, which is left in the cell with the boat's item; and the stone over the door goes back
     * with the door. The librarian unhurt.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringStrolling", tickLimit = 14000)
    public void aLibrarianThatStrollsIsHeldInABoatWhileTheLineIsLaid(TestContext context) {
        scene(context, false, false, false, false, true);
    }

    /**
     * As the last, the front rail's drop set down at the front's north edge as it is born, where the boat over the front
     * from the cart has it inside its box (F-22c64). A block's drop is set down within a quarter of a block of its middle,
     * and the boat overhangs the front by about a fifth, so about one time in ten. Broken from the corridor with the boat
     * there, vanilla pushed it out north over the cart onto the cell's bed, out of reach, and the bring ended a rail short
     * (in CI on #236 and #261, and 1 in 20 here with a probe). Held: the boat broken first from the front, and the rail
     * under Hal's feet, its drop is picked up and all 63 come home.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringBornInBoat", tickLimit = 14000)
    public void aFrontRailsDropBornInsideTheBoatsBoxIsStillPickedUp(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, true, false);
    }

    /**
     * As the strolling bring, the front held with Hal's body as its rail comes up (F-22c64); then, from the moment Hal
     * steps out of the front until the job block is in, the librarian set against the front's open side and pressing out
     * at a walk. It is never out of its cell, and the job block goes in across the front.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringPressed", tickLimit = 14000)
    public void aLibrarianPressingAtTheFrontAsHalStepsOutIsHeld(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, true);
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

    /**
     * As the last, the librarian stepping into Hal's boat a moment late, after Hal's wait for it: Hal does not break the
     * boat that now holds it, but rows it across and brings it home. In CI on #72 (`a2c7baa`) Hal's break of that boat,
     * planned while it stood empty, was still under way when the librarian stepped in; it broke the boat under it and let
     * it go (F-22c32). Staged: the librarian seated in the boat, once, when it has stood empty past Hal's wait.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringSeated", tickLimit = 16000)
    public void aLibrarianThatStepsIntoTheBoatLateIsHeldInIt(TestContext context) {
        scene(context, false, false, false, false, true, true, false, false, true);
    }

    /**
     * As the lake's, the librarian taken into Hal's boat south of its own lectern, the lectern (with a fence either side,
     * four blocks across) between it and the water:
     * Hal rows it aside over the ground, round the lectern, then into the water and across. In CI on #145 (`adbbf79`,
     * run 42859042644) the strolling librarian was held there, and the boat, rowed straight at the water over land, was
     * pressed against the lectern four times and went nowhere ("I couldn't row the librarian across"); the bring's
     * second try ran past the runtime's stop ("Hal still in play"). Staged: the librarian, its AI on but slowed to a stand
     * until it sits in a boat, first on the bank, where the crossing is planned from, and moved behind its lectern as
     * Hal sets about holding it in a boat.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringBehind", tickLimit = 16000)
    public void aLibrarianHeldBehindItsLecternIsRowedRoundIt(TestContext context) {
        scene(context, false, false, false, false, true, true, false, false, false, true);
    }

    /**
     * As the first, Hal coming to take the hall's door off from beside the hall, in reach of the door but with the wall
     * beside it between: Hal steps round to the door's front and takes it off from there. In REFACTOR's runs of this class
     * on main 9972e80 (1 of 30), Hal's second try at a strolling librarian aimed its break of the door from where the
     * hall's own cobblestone stood in the way; refused three times as out of sight, the bring was given up, "door went
     * wrong three times over" (F-22c62). Staged: Hal's walk to the door ended there, once.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bringDoorAside", tickLimit = 12000)
    public void aDoorInReachButBehindTheHallsWallIsTakenOffFromItsFront(TestContext context) {
        scene(context, false, false, false, false, false, false, false, false, false, false, true);
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
        scene(context, make, wander, river, mine, strolling, lake, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed) {
        scene(context, make, wander, river, mine, strolling, lake, slowed, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed, boolean derailed) {
        scene(context, make, wander, river, mine, strolling, lake, slowed, derailed, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed, boolean derailed, boolean seated) {
        scene(context, make, wander, river, mine, strolling, lake, slowed, derailed, seated, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed, boolean derailed, boolean seated, boolean behind) {
        scene(context, make, wander, river, mine, strolling, lake, slowed, derailed, seated, behind, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed, boolean derailed, boolean seated, boolean behind, boolean doorAside) {
        scene(context, make, wander, river, mine, strolling, lake, slowed, derailed, seated, behind, doorAside, false, false);
    }

    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed, boolean derailed, boolean seated, boolean behind, boolean doorAside, boolean bornInBoat, boolean pressed) {
        boolean[] asideOnce = {false};
        // Each cell's front: a rail on it the tick before; the tick its rail came up; whether Hal stood in it then.
        java.util.Map<BlockPos, Boolean> frontRail = new java.util.HashMap<>();
        java.util.Map<BlockPos, Long> railUp = new java.util.HashMap<>();
        java.util.Set<BlockPos> heldWhenUp = new java.util.HashSet<>();
        java.util.Set<java.util.UUID> movedDrops = new java.util.HashSet<>();
        String[] stagedDrop = {null};
        long[] pressFrom = {-1};
        BlockPos[] pressedFront = {null};
        String[] escaped = {null};
        boolean[] jobBlockIn = {false};
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, mine ? 29500 : lake ? 15500 : make || wander || river || strolling ? 13500 : 11500);
        boolean[] moved = {false};
        boolean[] damped = {false};
        boolean[] derailedOnce = {false};
        BlockPos[] firstRail = {null};
        java.util.UUID[] seatedIn = {null};
        long[] seatedAt = {0};
        String[] seatLost = {null};
        boolean[] backAtEnd = {false};
        String[] offLine = {null};
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
                    // Its field is wider than its structure: put back as the test ends, passed or failed, so the tests
                    // placed beside it after are not laid on its hall and line.
                    FixtureGround.keep(context, o.add(-9, 0, -8), o.add(25, 16, 34));
                    FixtureGround.simulated(context, o.add(-8, 0, -8), o.add(24, 0, 34));
                    for (int x = -8; x <= 24; x++) for (int z = -8; z <= 34; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 10; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(strolling ? 3000 : 1000);
                    if (strolling) {
                        world.setBlockState(o.add(4, 2, 4).add(5, 0, 20), Blocks.LECTERN.getDefaultState());
                        // Behind: a fence either side of the lectern, four blocks across in all, so that a boat rowed at it over
                        // the ground meets it squarely (one met just off the lectern's middle slid by it).
                        if (behind) for (int x : new int[] {4, 6, 7}) world.setBlockState(o.add(4, 2, 4).add(x, 0, 20), Blocks.OAK_FENCE.getDefaultState());
                        // A fence round the field's edge, as a pen's: a librarian that strolls off it fell into the ground about.
                        for (int x = -8; x <= 24; x++) for (int z : new int[] {-8, 34}) world.setBlockState(o.add(x, 2, z), Blocks.OAK_FENCE.getDefaultState());
                        for (int z = -8; z <= 34; z++) for (int x : new int[] {-8, 24}) world.setBlockState(o.add(x, 2, z), Blocks.OAK_FENCE.getDefaultState());
                    }
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
                    // Behind: first on the bank by the water, where the crossing is planned from.
                    BlockPos at = behind ? origin[0].add(4, 0, 19) : origin[0].add(3, 0, 20);
                    villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    villager.setAiDisabled(!strolling);
                    // Behind: its AI on, as a strolling librarian's (the bring takes such a one by boat), but kept from
                    // walking off until it sits in Hal's boat.
                    if (behind) villager.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.SLOWNESS, 20 * 600, 9, false, false));
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
                    // Staged: the loaded cart's first roll home slowed once, so that it runs out of way short of the climb
                    // into the hall and rolls back, as it did in CI.
                    if (slowed && moved[0] && !damped[0] && librarian[0].getRootVehicle() instanceof net.minecraft.entity.vehicle.AbstractMinecartEntity cart
                            && cart.getVelocity().horizontalLength() > 0.1 && cart.getZ() - origin[0].getZ() < 18) {
                        cart.setVelocity(cart.getVelocity().multiply(0.3));
                        damped[0] = true;
                        KindredMod.LOGGER.info("Bring: the loaded cart slowed at {}", cart.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                    }
                    // Staged: the loaded cart, round the line's turn and on north short of the climb into the hall, sent rolling back
                    // down the line as one that ran out of way does, fast, at half a block a tick (F-22c30). Where the line's first rail is: east along the rails from the
                    // one the cart is on when it first rolls loaded.
                    if (derailed && moved[0] && librarian[0].getRootVehicle() instanceof net.minecraft.entity.vehicle.AbstractMinecartEntity cart) {
                        var world = context.getWorld();
                        if (firstRail[0] == null && cart.getVelocity().horizontalLength() > 0.1) {
                            BlockPos first = cart.getBlockPos();
                            while (world.getBlockState(first.east()).isIn(net.minecraft.registry.tag.BlockTags.RAILS)) first = first.east();
                            firstRail[0] = first;
                        }
                        if (firstRail[0] != null && !derailedOnce[0] && cart.getZ() - origin[0].getZ() < 11 && cart.getVelocity().z < -0.05) {
                            cart.setVelocity(0, 0, 0.5);
                            derailedOnce[0] = true;
                            KindredMod.LOGGER.info("Bring: the loaded cart sent rolling back at {}, the line's first rail at {}",
                                    cart.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()), firstRail[0].subtract(origin[0]).toShortString());
                        }
                        if (derailedOnce[0] && !backAtEnd[0] && cart.getX() > firstRail[0].getX() + 1.0) {
                            backAtEnd[0] = true;
                            KindredMod.LOGGER.info("Bring: the cart back past the line's first rail, at {}, on {}", cart.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()),
                                    world.getBlockState(cart.getBlockPos()));
                        }
                        // Kept on the rails: never past the line's first rail off them (on a rail Hal put beyond it, it is on the line).
                        if (derailedOnce[0] && cart.getX() > firstRail[0].getX() + 1.0 && !world.getBlockState(cart.getBlockPos()).isIn(net.minecraft.registry.tag.BlockTags.RAILS)
                                && offLine[0] == null) offLine[0] = "the cart at "
                                + cart.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()) + ", past the line's first rail at "
                                + firstRail[0].subtract(origin[0]).toShortString();
                    }
                    // Behind: the crossing planned from the bank and Hal about to hold it in a boat, it strolls off south of its
                    // lectern (at 5, 0, 20), in line with it and the water; there it is held.
                    if (behind && !moved[0]) {
                        if (String.valueOf(f.runtime.bringNote(f.id)).contains("[HOLD]")) {
                            var to = origin[0].add(5, 0, 25);
                            librarian[0].refreshPositionAndAngles(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 180, 0);
                            moved[0] = true;
                            KindredMod.LOGGER.info("Bring: the librarian strolled off behind its lectern, to {}", to.subtract(origin[0]).toShortString());
                        }
                    }
                    // Behind: once in the boat, free to walk again: let go of, it would wade home.
                    if (behind && librarian[0].hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS)
                            && librarian[0].getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity) {
                        librarian[0].removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
                        KindredMod.LOGGER.info("Bring: the librarian sits in the boat behind its lectern, at {}",
                                librarian[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                    }
                    // Staged, once, as Hal comes to take the hall's door off: its walk there ended beside the hall, at its east
                    // side a block back from its front (7, 0, 7), in reach of the door but with the hall's own wall between,
                    // and it looks again from there. Its walk under way is stopped and it is set going again where it stands.
                    if (doorAside && !asideOnce[0] && f.runtime.bringNote(f.id).contains("[DOOR]")) {
                        asideOnce[0] = true;
                        BlockPos door = origin[0].add(TradingHall.width(3) / 2, 1, 7);
                        BlockPos aside = origin[0].add(7, 0, 7);
                        var player = f.player();
                        context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Hal's walk to the door stopped");
                        player.refreshPositionAndAngles(aside.getX() + 0.5, aside.getY(), aside.getZ() + 0.5, 270, 0);
                        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing again");
                        KindredMod.LOGGER.info("Bring: Hal stood beside the hall at {}, the door at {}, the wall between {}",
                                aside.subtract(origin[0]).toShortString(), door.subtract(origin[0]).toShortString(),
                                context.getWorld().getBlockState(origin[0].add(6, 1, 7)));
                    }
                    // Staged: the boat Hal put down beside the librarian, stood empty past Hal's wait for it, stepped into by the
                    // librarian, as a strolling villager steps into one a moment late (F-22c32).
                    if (seated && seatedIn[0] == null && librarian[0].getVehicle() == null) {
                        var world = context.getWorld();
                        var empty = world.getEntitiesByClass(net.minecraft.entity.vehicle.BoatEntity.class, librarian[0].getBoundingBox().expand(2.5),
                                boat -> boat.isAlive() && !boat.hasPassengers() && boat.age >= 12);
                        if (!empty.isEmpty() && librarian[0].startRiding(empty.getFirst(), true)) {
                            seatedIn[0] = empty.getFirst().getUuid();
                            seatedAt[0] = world.getTime();
                            KindredMod.LOGGER.info("Bring: the librarian stepped into the boat Hal had put down, at {}",
                                    empty.getFirst().getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                        }
                    }
                    // Held by that boat: not broken under it in the moments after.
                    if (seatedIn[0] != null && seatLost[0] == null && context.getWorld().getTime() - seatedAt[0] <= 100
                            && !(context.getWorld().getEntity(seatedIn[0]) instanceof net.minecraft.entity.vehicle.BoatEntity held && held.isAlive())) {
                        seatLost[0] = "the boat it sat in broken " + (context.getWorld().getTime() - seatedAt[0]) + " ticks after, the librarian riding "
                                + librarian[0].getVehicle();
                    }
                    for (var c : TradingHall.cells(3)) {
                        var world = context.getWorld();
                        BlockPos front = origin[0].add(c.jobBlock().x(), c.jobBlock().y(), c.jobBlock().z());
                        boolean rail = world.getBlockState(front).isIn(net.minecraft.registry.tag.BlockTags.RAILS);
                        var hal = f.player();
                        if (Boolean.TRUE.equals(frontRail.put(front, rail)) && !rail) {
                            railUp.put(front, world.getTime());
                            if (hal != null && hal.getBoundingBox().intersects(new net.minecraft.util.math.Box(front))) heldWhenUp.add(front);
                        }
                        Long up = railUp.get(front);
                        // Staged (F-22c64): the front rail's fresh drop set where and as the caught failure's was born: at the
                        // front's north edge, inside the box of the boat over the front if there is one, thrown north.
                        if (bornInBoat && up != null && world.getTime() - up <= 2) {
                            for (var item : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(front).expand(0.5, 1, 0.5),
                                    i -> i.isAlive() && i.getStack().isOf(Items.RAIL) && i.age <= 2 && movedDrops.add(i.getUuid()))) {
                                // The caught failure's own drop at age 0 (probe, main 3196401, 1 in 20): from its front's corner
                                // 0.418, 0.556, 0.292, thrown 0.080, 0.200, -0.084.
                                item.setPosition(front.getX() + 0.418, front.getY() + 0.556, front.getZ() + 0.292);
                                item.setVelocity(0.080, 0.200, -0.084);
                                var boats = world.getEntitiesByClass(net.minecraft.entity.vehicle.BoatEntity.class, item.getBoundingBox(), net.minecraft.entity.Entity::isAlive);
                                stagedDrop[0] = "at " + item.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()) + (boats.isEmpty() ? ", no boat's box about it" : ", inside the boat's box");
                                KindredMod.LOGGER.info("Bring: the front rail's drop set down at the front's north edge {}", stagedDrop[0]);
                            }
                        }
                        // Staged: from the tick Hal, having held the front as its rail came up, steps out of it, until the job
                        // block is in, the librarian is against the front's open side and pressing out at a walk.
                        boolean jobIn = world.getBlockState(front).isOf(Blocks.LECTERN);
                        if (jobIn && heldWhenUp.contains(front)) jobBlockIn[0] = true;
                        if (pressed && heldWhenUp.contains(front) && !jobIn && hal != null && !hal.getBoundingBox().intersects(new net.minecraft.util.math.Box(front))) {
                            var v = librarian[0];
                            if (pressFrom[0] < 0 && v.getVehicle() == null) {
                                pressFrom[0] = world.getTime();
                                pressedFront[0] = front;
                                v.refreshPositionAndAngles(front.getX() + 0.5, front.getY(), front.getZ() - 0.31, 0, 0);
                                KindredMod.LOGGER.info("Bring: the librarian against the front's open side as Hal steps out, at {}",
                                        v.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                            }
                            if (pressFrom[0] >= 0) v.setVelocity(0, v.getVelocity().y, 0.1);
                        }
                        if (pressedFront[0] != null && escaped[0] == null && librarian[0].getBoundingBox().minZ >= pressedFront[0].getZ()) {
                            escaped[0] = "the librarian out of its cell into the front at " + librarian[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ())
                                    + ", " + (world.getTime() - pressFrom[0]) + " ticks after Hal stepped out";
                        }
                    }
                    if (context.getWorld().getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Bring: the librarian at {}, riding {}, health {}{}", librarian[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()),
                                librarian[0].getVehicle(), librarian[0].getHealth(),
                                librarian[0].getRecentDamageSource() == null ? "" : ", hurt by " + librarian[0].getRecentDamageSource().getName());
                    }
                    context.assertTrue(f.runtime.brought(f.id).contains(librarian[0].getUuid()),
                            "Brought home: " + f.runtime.bringNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                  try {
                    context.assertTrue(offLine[0] == null, "Kept on the rails, never rolled off the line's start: " + offLine[0]);
                    if (bornInBoat) context.assertTrue(stagedDrop[0] != null, "The front rail's drop staged at the front's north edge");
                    if (pressed) {
                        context.assertTrue(pressFrom[0] >= 0, "The librarian pressed at the front as Hal stepped out of it");
                        context.assertTrue(escaped[0] == null, "The librarian held in its cell: " + escaped[0]);
                        context.assertTrue(jobBlockIn[0], "The job block in across the front Hal held");
                    }
                    context.assertTrue(seatLost[0] == null, "Held in the boat it stepped into, never broken out of it: " + seatLost[0]);
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
                        // The boat broken in the cell, its wood left there out of reach, or picked up where it fell in reach.
                        int boatsLying = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(floorRail).expand(1, 1, 2),
                                item -> item.getStack().isOf(Items.OAK_BOAT)).size();
                        context.assertTrue(count(inventory, Items.OAK_BOAT) + boatsLying == 1, "The boat in hand or in the cell: " + count(inventory, Items.OAK_BOAT) + " / " + boatsLying);
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
                    // One that strolls keeps the cart in its cell, and its boat's item falls there: said.
                    if (strolling) {
                        context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I've brought the librarian home")
                                && line.contains("left the cart")), "Said what it left in the cell: " + f.runtime.said(f.id));
                    }
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
