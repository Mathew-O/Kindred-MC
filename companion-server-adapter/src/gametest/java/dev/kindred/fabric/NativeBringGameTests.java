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
    /**
     * Where its ground is laid from (F-22c119): in from its structure's corner as far as its ground runs the other way, its
     * field kept from 9 west and 8 north of here to 25 east and 34 south, so that all of it lies in its own structure. On
     * a structure as small as its plot, its ground ran into the plots of the tests placed round it, and on #475's CI its
     * tests left 11,360 blocks in other tests' running plots (21 restores "left as they are"), its own neighbours' among them.
     */
    private static BlockPos origin(TestContext context) {
        return context.getAbsolutePos(new BlockPos(9, 0, 8));
    }

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

    /**
     * The bring's last check needs Hal in play (F-22c99). Out of play as the test ends, it says so; {@code lastNotYet}, what
     * the check last found not yet so, if anything.
     */
    /**
     * F-22c84b, test-only: with the bring's dirt short at its last check, where the rest may be. It names every dirt block
     * standing in the fixture's kept ground, its field being stone, so any dirt there was put there, relative to the hall's
     * origin, the first eight and how many; and what Hal holds in each hand. Three CI runs came home "3 dirt" with no dirt
     * lying about, and nothing said where the fourth was. It changes nothing the check asks.
     */
    static String dirtWhere(net.minecraft.server.world.ServerWorld world, BlockPos o, BlockPos hall,
                            net.minecraft.server.network.ServerPlayerEntity hal) {
        java.util.List<String> at = new java.util.ArrayList<>();
        int n = 0;
        for (BlockPos p : BlockPos.iterate(o.add(-9, 0, -8), o.add(25, 16, 34))) {
            if (!world.getBlockState(p).isOf(Blocks.DIRT)) continue;
            if (n++ < 8) at.add(p.subtract(hall).toShortString());
        }
        return "; dirt blocks in its ground: " + n + (n == 0 ? "" : " " + at)
                + (hal == null ? "; Hal out of play" : "; in hand " + hal.getMainHandStack() + ", off hand " + hal.getOffHandStack());
    }

    /** F-22c123: the staged case's message, naming each bring given up before any push. */
    private static String stagedOffLine(java.util.List<String> beforeAnyPush) {
        return "Staged: the librarian's boat set off the line as the cart rolled"
                + (beforeAnyPush.isEmpty() ? "" : "; no push was ever reached: given up " + String.join(", then ", beforeAnyPush));
    }

    static void inPlay(TestContext context, net.minecraft.server.network.ServerPlayerEntity hal, String lastNotYet) {
        context.assertTrue(hal != null, lastNotYet == null ? "Hal still in play"
                : "Hal out of play as the test ended; before that, not yet: " + lastNotYet);
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bring", tickLimit = 12000)
    public void aKeptLibrarianIsBroughtHomeByMinecartIntoItsCell(TestContext context) {
        scene(context, false);
    }

    /**
     * As above, with no rails or minecart to hand but 24 iron ingots, 2 sticks and a crafting table by Hal: it makes the
     * two stacks of sixteen rails the line takes and a minecart, from 17 of the iron and both sticks, and brings the
     * librarian home with them, every rail and the cart taken back.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringMade", tickLimit = 14000)
    public void theRailsAndTheCartAreMadeFromItsOwnIron(TestContext context) {
        scene(context, true);
    }

    /**
     * As the first, with the librarian moved three blocks aside once the cart is down, as a villager wanders off a line
     * laid to where it stood: Hal lays the line again from where it now is, takes the cart up and puts it down again
     * behind it, and brings it home; every rail of both lines taken up.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringWandered", tickLimit = 14000)
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
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringSlowed", tickLimit = 14000)
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
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringDerailed", tickLimit = 14000)
    public void aLoadedCartRollingBackAtTheLinesStartIsMetOnItsFirstRail(TestContext context) {
        scene(context, false, true, false, false, false, false, false, true);
    }

    /**
     * As the first, with a river three wide right across the field between the librarian and the hall: Hal bridges it
     * with dirt from the bank it is on, lays the line over the bridge, brings the librarian home, and takes the bridge
     * back after its rails, the river running as it did.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringRiver", tickLimit = 14000)
    public void aLibrarianOverARiverIsBroughtHomeOnABridgeOfItsOwn(TestContext context) {
        scene(context, false, false, true);
    }

    /**
     * As the second, with no iron at all but a stone pickaxe, four coal and a furnace, and a face of iron ore standing
     * on the ground beside it, as a cave's wall shows it: Hal mines the ore it needs, puts the furnace down and smelts
     * it, then makes the rails and the minecart and brings the librarian home.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringMined", tickLimit = 30000)
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
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringStrolling", tickLimit = 14000)
    public void aLibrarianThatStrollsIsHeldInABoatWhileTheLineIsLaid(TestContext context) {
        scene(context, false, false, false, false, true);
    }

    /**
     * F-22c98b. As the last, the bring given up (STAGED: the librarian's boat set three blocks off the line each time the cart
     * rolls, until the bring gives up, as "kept wandering off the line", "no rail line" or "wouldn't take it up"), then all it put down taken back: the hall's door back in its
     * doorway, never "I couldn't put my hall's door back". In CI on #367, #372 and #355, and twice in local bring runs,
     * a give-up whose cart's item took several walks to pick up ended with the door left out.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringGivenUpDoorBack", tickLimit = 14000)
    public void givenUpWithTheCartDownHalStillPutsItsHallsDoorBack(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 6);
    }

    /**
     * F-22c123. As the last, the librarian walking off each line before any push (STAGED: its boat, the librarian in it, set
     * three blocks off the line each time the cart is put down on it, until the bring gives up, "kept wandering off the
     * line" or no line from where it sits), as CI's did on #492 (run 45132709566): given up before any push, all it put down taken back, the hall's door
     * back. A bring given up before any push is real bring behaviour; this case holds what it leaves, and shows the
     * staged case's message for one that never came to a push.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringGivenUpBeforeAnyPush", tickLimit = 14000)
    public void givenUpBeforeAnyPushHalStillPutsItsHallsDoorBack(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 10);
    }

    /**
     * F-22c103. As the last, the boat set off the line once, into a pit four deep, dug under it as it is set there
     * (STAGED), where no walk reaches it: in CI on #412 (run 44871475068) the boat set off the line dropped two blocks off
     * the fixture's ground, the take-back's walk to it had no route three times, the take-back ended there, and the hall's
     * door was left out ("I couldn't take everything back"). Held: the boat let be and said, the hall's door back.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringGivenUpBoatInPit", tickLimit = 14000)
    public void givenUpWithItsBoatInAPitHalLetsItBeAndPutsItsHallsDoorBack(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 7);
    }

    /**
     * As the last, the front rail's drop set down at the front's north edge as it is born, where the boat over the front
     * from the cart has it inside its box (F-22c64). A block's drop is set down within a quarter of a block of its middle,
     * and the boat overhangs the front by about a fifth, so about one time in ten. Broken from the corridor with the boat
     * there, vanilla pushed it out north over the cart onto the cell's bed, out of reach, and the bring ended a rail short
     * (in CI on #236 and #261, and 1 in 20 here with a probe). Held: the boat broken first from the front, and the rail
     * under Hal's feet, its drop is picked up and all 63 come home.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringBornInBoat", tickLimit = 14000)
    public void aFrontRailsDropBornInsideTheBoatsBoxIsStillPickedUp(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, true, false);
    }

    /**
     * As the last, the librarian held in its boat where CI had it on #429 (run 44936497582, F-22c98c): the boat's middle
     * 2.678 from the cart's along the line and 0.243 off it. Pushed from there, the cart's last move before it touched the
     * boat ended 0.207 short, past the fifth of a block a cart takes up within, so it stopped dead against the boat, and
     * three pushes from the same spot did the same: the bring was given up. Staged (the boat set there while the cart stands
     * for each push); the bring carries on to the cell and all of the line comes home.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringShortOfBoat", tickLimit = 14000)
    public void aCartStoppedShortOfTheHeldBoatIsPushedSoItTakesItUp(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 8);
    }

    /**
     * As the strolling bring, staged (F-22c64): from the first moment its front's rail is up with Hal out of the front,
     * until the job block is in, the librarian set against the front's open side and pressing out at a walk. It is never
     * out of its cell, and the job block goes in across the front.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringPressed", tickLimit = 14000)
    public void aLibrarianPressingAtTheFrontAsHalStepsOutIsHeld(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, true);
    }

    /**
     * As the strolling bring, staged (F-22c64): once its front's rail is up and the cell's boat, cart and drops are
     * taken back, Hal set where CI's run left it, up on the edge of the bed's foot behind the cell's floor (its box a fifth of a block over the
     * bed, a fifth west of the cell's middle), and the librarian held on the bed's head for three seconds, the floor clear.
     * In CI on #318 (`e924161a`, run 43641110830) Hal, shoved there by the librarian it walked in against, was refused its walk
     * out three times ("a clear, supported, loaded flat corridor": none starts up on a bed) and gave the bring up. CI's log
     * does not show how it came to be there, and the librarian staged in its way alone did not put it there (3 of 3 passed on
     * `e924161a`), so the state it gave up from is staged. Held: down on to the floor, out, and the librarian brought home.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringOnBedEdge", tickLimit = 14000)
    public void upOnTheEdgeOfTheBedsFootHalStepsDownAndOut(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 1);
    }

    /**
     * As the last, Hal set wholly on the bed's foot, out of the cell's front and floor (#318). With the cell's "inside" taken
     * as its front and floor alone, one block high, Hal there (or its feet a jump above the floor's block) was taken to be
     * out, and the job block went in across the front with Hal shut in behind it. Held: the job block never goes in with any
     * part of Hal's box in the cell.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringOnBed", tickLimit = 14000)
    public void whollyOnTheBedsFootHalIsNotShutInByTheJobBlock(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 2);
    }

    /**
     * As the strolling bring, staged (F-22c84, cause #2): the tick the cart in the cell under the shut trapdoor is broken, its
     * item lying on the cell's floor, Hal held, nothing in the cell picked up, until eighty ticks after the front's rail is up
     * a third of a block
     * east of the middle of its stand before the front, with the oak boat's item on the floor beside the cart's. In CI on
     * #354 (`c3d0cc4`, run 43719957093) and #355 (run 43720019149), Hal stood there walking to its stand 1,990 times while
     * both items lay, and they despawned at age 6000: the stand is asked for to within a quarter of a block, a walk to a
     * block's middle ends within 0.4 of it, and the while given under the trapdoor counts only from the step in. Held: both
     * items taken up within 600 ticks of Hal being let go, long before an item's lifetime.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringOffStand", tickLimit = 14000)
    public void aThirdOffItsStandHalStillTakesUpTheCartAndBoatFromTheCell(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 3);
    }

    /**
     * F-22c96a. In CI on #429 (run 44936497582) Hal stood in its librarian's front as the trapdoor was shut over it, the cart
     * taken back: CLOSE's walk out (F-22c96's "CLOSE's walk out refused" line) had no walkable route from under its own shut
     * trapdoor, three times in a second, and the bring was given up, "close went wrong three times over". Staged: the first tick
     * the bring is at CLOSE with Hal out of the front and at rest, Hal set in the middle of the front, under the shut trapdoor. It walks out and the librarian is
     * brought home.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringUnderTrapdoor", tickLimit = 14000)
    public void inItsFrontUnderItsShutTrapdoorHalStillWalksOutAndBringsItHome(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 8);
    }

    /**
     * As the strolling bring, staged (F-22c84, the dirt): the librarian put where CI's strolled to, by the hall's south-west
     * corner (-3, 0, 9), and slowed until it sits in Hal's boat. Held there, at (-3, 0, 8), the line's stop goes down
     * beside the hall's west wall (-3, 0, 3); as its last pickup ends, Hal is set a block south of it, where it picked the
     * stop's dirt up in CI's run, and climbs out to put the hall's door back by a stair of one block from the dirt it holds.
     * Both are staging, test-only: CI's job log (#380, `41a021c`, run 43783885635, job 133027070300, line 69272) shows
     * `aFrontRailsDropBornInsideTheBoatsBoxIsStillPickedUp` climbing out there by "Back up to the build: stairs up 1", the
     * step left standing and 3 dirt of 4 home, but only that once: its fourteen other climbs out walked on, and unstaged
     * here the climb took a stair in 3 of 6 runs. Held: every dirt Hal set down along the way comes home.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringClimbOut", tickLimit = 14000)
    public void climbingOutToPutTheDoorBackHalTakesItsStepHomeToo(TestContext context) {
        scene(context, false, false, false, false, true, false, false, false, false, false, false, false, false, 4);
    }

    /**
     * As the last, with a lake eight wide right across the field between the librarian and the hall, and only four dirt
     * in Hal's hand, too few for a bridge of eight: Hal holds the librarian in its boat on its own side, gets in with it,
     * rows it over the ground into the water and across, and gets out on the far bank, the librarian left in the boat on
     * the water (let go of, it would wade back to its lectern); lays the line along the bank past it, and the cart, rolling
     * by, takes the boat up from the water and carries it home as the last.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringAcross", tickLimit = 16000)
    public void aLibrarianAcrossALakeIsRowedOverAndBroughtHome(TestContext context) {
        scene(context, false, false, false, false, true, true);
    }

    /**
     * As the last, the librarian stepping into Hal's boat a moment late, after Hal's wait for it: Hal does not break the
     * boat that now holds it, but rows it across and brings it home. In CI on #72 (`a2c7baa`) Hal's break of that boat,
     * planned while it stood empty, was still under way when the librarian stepped in; it broke the boat under it and let
     * it go (F-22c32). Staged: the librarian seated in the boat, once, when it has stood empty past Hal's wait.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringSeated", tickLimit = 16000)
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
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringBehind", tickLimit = 16000)
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
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringDoorAside", tickLimit = 12000)
    public void aDoorInReachButBehindTheHallsWallIsTakenOffFromItsFront(TestContext context) {
        scene(context, false, false, false, false, false, false, false, false, false, false, true);
    }

    /**
     * F-22c99, test-only. On #380's CI (run 43783885635) a bring's last check was retried for a minute after "Brought home",
     * each time not yet so for want of a dirt ("… 3 dirt …"); then the runtime stopped as the test ran out, and its failure
     * said only "Hal still in play", the minute's reason gone. Here, Hal out of play with a reason last found not yet so: the
     * check's failure must name that reason. Anything else it throws is caught, so it cannot crash the server.
     */
    /**
     * F-22c84b, test-only. The bring's last check, short of dirt, says where dirt stands in its ground: a dirt block put
     * in the fixture's stone field is named, relative to the hall, and the text stays inside a lectern page's 900.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringDirtWhere", tickLimit = 40)
    public void shortOfDirtTheBringCheckSaysWhereDirtStandsInItsGround(TestContext context) {
        var world = context.getWorld();
        BlockPos o = origin(context);
        BlockPos hall = o.add(4, 2, 4);
        FixtureGround.keep(context, o.add(-9, 0, -8), o.add(25, 16, 34));
        for (BlockPos p : BlockPos.iterate(o.add(-9, 0, -8), o.add(25, 16, 34))) world.setBlockState(p, Blocks.AIR.getDefaultState());
        world.setBlockState(hall.add(-1, 0, 1), Blocks.DIRT.getDefaultState());
        String said = dirtWhere(world, o, hall, null);
        context.assertTrue(said.contains("dirt blocks in its ground: 1 [-1, 0, 1]") && said.length() < 900, "Where its dirt stands: " + said);
        context.complete();
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "bringOutOfPlay", tickLimit = 40)
    public void outOfPlayAsTheTestEndsTheBringCheckStillSaysWhatWasNotYetSo(TestContext context) {
        String reason = "Rails (but the cell floor's), the cart and dirt all taken back, the lectern spent: 63 rails, 1 carts, 0 lecterns, 3 dirt";
        String said;
        try {
            inPlay(context, null, reason);
            said = null;
        } catch (net.minecraft.test.GameTestException failing) {
            said = failing.getMessage();
        } catch (RuntimeException other) {
            said = "threw " + other;
        }
        String message = said;
        context.assertTrue(message != null && message.contains(reason), "The check out of play names what was last not yet so: " + message);
        context.complete();
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
        scene(context, make, wander, river, mine, strolling, lake, slowed, derailed, seated, behind, doorAside, bornInBoat, pressed, 0);
    }

    /**
     * {@code onBed}: 0 not staged; 1 Hal on the edge of the bed's foot, as CI's #318 left it; 2 Hal wholly on the bed's foot;
     * 3 Hal a third of a block off its stand before the front, the cart's and boat's items on the cell's floor (F-22c84);
     * 4 the librarian by the hall's south-west corner and Hal set by the line's stop, so that it climbs out to put the door back
     * by a stair (F-22c84, the dirt).
     * 6 the librarian's boat set aside off the line each time the cart rolls, until the bring is given up (F-22c98b).
     * 10 the librarian's boat set off each line as the cart is put down on it, before any push, until the bring is given
     * up (F-22c123).
     */
    private static void scene(TestContext context, boolean make, boolean wander, boolean river, boolean mine, boolean strolling, boolean lake,
                              boolean slowed, boolean derailed, boolean seated, boolean behind, boolean doorAside, boolean bornInBoat, boolean pressed,
                              int onBed) {
        boolean[] asideOnce = {false};
        // F-22c98b: whether the librarian's boat was set off the line for the push under way; how many times it was.
        boolean[] offLineSet = {false};
        double[] pitFloor = {Double.NaN};
        int[] offLineSets = {0};
        // F-22c98b: the hall's doorway and what was said the tick the given-up bring said its end; how much had been said
        // as the staging first set the boat off the line.
        String[] endedWith = {null};
        int[] saidBefore = {-1};
        // F-22c123: each bring given up before any push was staged, its line and the hall's doorway the tick it was said; how
        // much of what was said has been read for them.
        java.util.List<String> beforeAnyPush = new java.util.ArrayList<>();
        int[] saidRead = {0};
        // F-22c123, case 10: the bring's phase the tick before; whether the boat was set off the line it is on; how often.
        String[] lastPhase = {""};
        boolean[] wanderedOff = {false};
        int[] wanderings = {0};
        // Each cell's front: a rail on it the tick before; the tick its rail came up; whether Hal stood in it then.
        java.util.Map<BlockPos, Boolean> frontRail = new java.util.HashMap<>();
        java.util.Map<BlockPos, Long> railUp = new java.util.HashMap<>();
        java.util.Set<BlockPos> heldWhenUp = new java.util.HashSet<>();
        java.util.Set<java.util.UUID> movedDrops = new java.util.HashSet<>();
        String[] stagedDrop = {null};
        // F-22c98c: the held boat set for this push yet; the cart's speed the tick before; how many pushes were so staged.
        boolean[] shortStaged = {false};
        double[] shortSpeed = {0};
        int[] shortSets = {0};
        long[] pressFrom = {-1};
        // #318: Hal staged up on the cell's bed, the tick and front; Hal shut in its cell by the job block, in any bring.
        long[] bedFrom = {-1};
        BlockPos[] bedFront = {null};
        String[] shutIn = {null};
        // F-22c84 (cause #2): Hal held off its stand, the tick it was let go, and the cell's items still lying 600 ticks on.
        long[] offFrom = {-1};
        java.util.Set<BlockPos> cartInCell = new java.util.HashSet<>();
        BlockPos[] offFront = {null};
        BlockPos[] underShut = {null};
        String[] offLeft = {null};
        // F-22c84 (the dirt): Hal set beside the line's stop's block as its last pickup ends, once.
        boolean[] climbOnce = {false};
        java.util.Set<BlockPos> jobSeen = new java.util.HashSet<>();
        BlockPos[] pressedFront = {null};
        String[] escaped = {null};
        boolean[] jobBlockIn = {false};
        // F-22c64: a boat's box reaching into a front as its rail came up; the rail's drop born inside it is pushed out north.
        String[] boatOverFront = {null};
        boolean[] tradeDay = {false};
        // F-22c64: the librarian in its front between the front rail coming up and the job block going in.
        String[] inFrontLoose = {null};
        // F-22c99: what the last check last found not yet so, for its failure out of play as the test ends.
        String[] lastNotYet = {null};
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
                    BlockPos o = origin(context);
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
                    // Climbing out (F-22c84, the dirt): where CI's strolled to, by the hall's south-west corner.
                    BlockPos at = behind ? origin[0].add(4, 0, 19) : onBed == 4 ? origin[0].add(-3, 0, 9) : origin[0].add(3, 0, 20);
                    villager.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
                    villager.setAiDisabled(!strolling);
                    // Behind: its AI on, as a strolling librarian's (the bring takes such a one by boat), but kept from
                    // walking off until it sits in Hal's boat.
                    if (behind || onBed == 4) villager.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
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
                        // Staged: a trapdoor in hand, for its cell to be shut over with while the boat is broken (F-22c64). Six
                        // planks given instead went to a crafting table and sticks of its own before the bring planned.
                        inventory.setStack(8, new ItemStack(Items.OAK_TRAPDOOR));
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
                    if ((behind || onBed == 4) && librarian[0].hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS)
                            && librarian[0].getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity) {
                        librarian[0].removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
                        KindredMod.LOGGER.info("Bring: the librarian sits in the boat behind its lectern, at {}",
                                librarian[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                    }
                    // Staged (F-22c98c), each push fast enough to miss: CI on #429 (run 44936497582) had the cart's last move
                    // before it touched the librarian's boat begin 0.207 short of it, more than the fifth of a block a cart
                    // takes up within, at about 0.23 a tick, so it closed the gap into touching and stopped dead, three pushes
                    // alike, and the bring was given up ("The cart wouldn't take the librarian up"). Here, the first tick of a
                    // push the cart slows, Hal's push done, it moves its speed each tick, that falling 0.01 a tick: if its next
                    // move but one is longer than a fifth, the boat, librarian and all, is set on the line so that move begins
                    // midway between a fifth and its length short of touching, and 0.243 off the line as CI had it. A push
                    // slower than that meets the boat inside the fifth whatever the gap. Never anything given.
                    if (onBed == 8 && String.valueOf(f.runtime.bringNote(f.id)).contains("[PUSH]")
                            && librarian[0].getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity boat && !boat.hasVehicle()) {
                        var carts = context.getWorld().getEntitiesByClass(net.minecraft.entity.vehicle.AbstractMinecartEntity.class,
                                boat.getBoundingBox().expand(8), c -> c.isAlive() && !c.hasPassengers());
                        if (!carts.isEmpty()) {
                            var cart = carts.getFirst();
                            double speed = cart.getVelocity().horizontalLength();
                            double dx = boat.getX() - cart.getX(), dz = boat.getZ() - cart.getZ();
                            boolean alongX = Math.abs(dx) >= Math.abs(dz);
                            double toward = alongX ? Math.signum(dx) * cart.getVelocity().x : Math.signum(dz) * cart.getVelocity().z;
                            if (speed <= 0.01) {
                                shortStaged[0] = false;
                            } else if (!shortStaged[0] && toward > 0 && speed < shortSpeed[0] && speed - 0.01 > 0.2) {
                                shortStaged[0] = true;
                                double gap = (0.2 + speed - 0.01) / 2;
                                double along = speed + gap + 0.6875 + 0.49;
                                double x = alongX ? cart.getX() + Math.signum(dx) * along : cart.getX() - 0.243;
                                double z = alongX ? cart.getZ() - 0.243 : cart.getZ() + Math.signum(dz) * along;
                                boat.refreshPositionAndAngles(x, boat.getY(), z, boat.getYaw(), 0);
                                boat.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                                shortSets[0]++;
                                KindredMod.LOGGER.info("Bring: the held boat set for the push at {} a tick: its last move to begin {} short", String.format("%.3f", speed),
                                        String.format("%.3f", gap));
                            }
                            shortSpeed[0] = speed;
                        }
                    }
                    // Staged (F-22c98b), each push: the first tick the cart pushed at the librarian held in its boat rolls, the
                    // boat, librarian and all, set three blocks off the line, crosswise, as one that strolls off might be: the
                    // cart runs on past, the line is laid again from where it is, and after four of those the bring is given up
                    // ("kept wandering off the line"). Then, as it takes back what it put down, the cart broken and its item walked
                    // to; the hall's door is still to be put back. Never anything given.
                    if ((onBed == 6 || onBed == 7) && String.valueOf(f.runtime.bringNote(f.id)).contains("[PUSH]")
                            && librarian[0].getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity boat) {
                        var world = context.getWorld();
                        var carts = world.getEntitiesByClass(net.minecraft.entity.vehicle.AbstractMinecartEntity.class, boat.getBoundingBox().expand(8),
                                c -> c.isAlive() && !c.hasPassengers());
                        if (!carts.isEmpty()) {
                            var cart = carts.getFirst();
                            double v = cart.getVelocity().horizontalLength();
                            if (v <= 0.01) offLineSet[0] = false;
                            // Case 7 once only: set off again from down in its pit, it went into the pit's side and the
                            // librarian was hurt in the wall.
                            else if (!offLineSet[0] && !(onBed == 7 && offLineSets[0] > 0)) {
                                offLineSet[0] = true;
                                offLineSets[0]++;
                                if (saidBefore[0] < 0) saidBefore[0] = f.runtime.said(f.id).size();
                                boolean alongX = Math.abs(cart.getVelocity().x) >= Math.abs(cart.getVelocity().z);
                                double x = alongX ? boat.getX() : boat.getX() + 3, z = alongX ? boat.getZ() + 3 : boat.getZ();
                                boat.refreshPositionAndAngles(x, boat.getY(), z, boat.getYaw(), 0);
                                boat.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                                // F-22c103: and the ground under it and a block round dug out four deep, so that it lies in
                                // a pit no walk goes into (deeper than a safe drop of three), as no walk reached CI's on #412,
                                // dropped two blocks off the fixture's kept ground (run 44871475068). Two deep, inside the
                                // kept ground, Hal walked down into it after the boat and was then shut in it.
                                if (onBed == 7) {
                                    BlockPos under = BlockPos.ofFloored(x, boat.getY(), z);
                                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= 4; dy++)
                                        world.setBlockState(under.add(dx, -dy, dz), net.minecraft.block.Blocks.AIR.getDefaultState());
                                    // Set down on the pit's floor with the librarian in it (STAGED), not left to fall there: a boat
                                    // that falls more than three blocks onto land breaks, and on #427's CI (run 44934790203) it broke
                                    // in the fall, the librarian out of it at the pit's edge, so no boat held it to be let be.
                                    boat.refreshPositionAndAngles(x, boat.getY() - 4, z, boat.getYaw(), 0);
                                    boat.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                                    boat.fallDistance = 0;
                                    pitFloor[0] = boat.getY();
                                }
                                KindredMod.LOGGER.info("Bring: the librarian's boat set off the line ({}), at {}", offLineSets[0],
                                        boat.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                            }
                        }
                    }
                    // F-22c123: a bring given up before any push (so before the staging could set the boat off the line), its
                    // line and the doorway as it was said: the staging waits for the first push of whichever bring comes to one.
                    // Staged (F-22c123, case 10): as the cart is put down on a line, the boat with the librarian in it set three
                    // blocks off it, crosswise, once for each line laid, before any push.
                    if (onBed == 10) {
                        var note = String.valueOf(f.runtime.bringNote(f.id));
                        var tag = java.util.regex.Pattern.compile("\\[(\\w+)\\]$").matcher(note);
                        String phase = tag.find() ? tag.group(1) : "";
                        // Each line laid again after the boat was set off the last: the boat set off the new one too.
                        if (wanderedOff[0] && (phase.equals("LAY") || phase.equals("RECART"))) wanderedOff[0] = false;
                        var world = context.getWorld();
                        boolean cartStill = world.getEntitiesByClass(net.minecraft.entity.vehicle.AbstractMinecartEntity.class,
                                new net.minecraft.util.math.Box(origin[0]).expand(64), c -> c.isAlive() && c.getVelocity().horizontalLength() > 0.01).isEmpty();
                        if ((phase.equals("CART") || phase.equals("PUSH") && cartStill) && !wanderedOff[0]
                                && librarian[0].getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity boat) {
                            for (var d : new int[][] {{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
                                BlockPos to = BlockPos.ofFloored(boat.getX() + d[0], boat.getY(), boat.getZ() + d[1]);
                                if (!world.getBlockState(to).isAir() || !world.getBlockState(to.up()).isAir() || world.getBlockState(to.down()).isAir()
                                        || world.getBlockState(to.down()).isIn(net.minecraft.registry.tag.BlockTags.RAILS) || world.getBlockState(to).isIn(net.minecraft.registry.tag.BlockTags.RAILS)) continue;
                                boat.refreshPositionAndAngles(boat.getX() + d[0], boat.getY(), boat.getZ() + d[1], boat.getYaw(), 0);
                                boat.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                                wanderedOff[0] = true;
                                wanderings[0]++;
                                KindredMod.LOGGER.info("Bring: STAGED, the librarian's boat set off the line before any push ({}), at {}", wanderings[0],
                                        boat.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                                break;
                            }
                        }
                        lastPhase[0] = phase;
                    }
                    if ((onBed == 6 || onBed == 7 || onBed == 10) && offLineSets[0] == 0) {
                        var said = f.runtime.said(f.id);
                        for (int i = saidRead[0]; i < said.size(); i++) {
                            // A give-up, positively: a bring that ends says its line and notes the same line, with no phase (it
                            // is over), where one brought home notes "Brought home"; any other line said is not one.
                            String line = said.get(i);
                            if (!line.equals(f.runtime.bringNote(f.id))) continue;
                            BlockPos door = origin[0].add(TradingHall.width(3) / 2, 1, 7);
                            var at = context.getWorld().getBlockState(door);
                            String entry = "'" + said.get(i) + "' / " + (at.getBlock() instanceof net.minecraft.block.DoorBlock ? "door" : String.valueOf(at));
                            beforeAnyPush.add(entry);
                            KindredMod.LOGGER.info("Bring: given up before any push: {}", entry);
                        }
                        saidRead[0] = said.size();
                    }
                    // The tick the given-up bring says how it ended (its first line said since the staging), what the doorway is
                    // then: a bring begun again after may open it again.
                    if ((onBed == 6 || onBed == 7) && saidBefore[0] >= 0 && endedWith[0] == null
                            && f.runtime.said(f.id).subList(saidBefore[0], f.runtime.said(f.id).size()).stream().anyMatch(line -> !line.startsWith("the trading hall's"))) {
                        BlockPos door = origin[0].add(TradingHall.width(3) / 2, 1, 7);
                        var at = context.getWorld().getBlockState(door);
                        endedWith[0] = (at.getBlock() instanceof net.minecraft.block.DoorBlock ? "door" : String.valueOf(at)) + " / " + f.runtime.said(f.id);
                        KindredMod.LOGGER.info("Bring: given up and ended; the hall's doorway {}", endedWith[0]);
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
                    // Staged, once, as its last pickup ends (F-22c84, the dirt): the line's stop taken back, its dirt and every rail
                    // home and none lying, Hal set a block south of the stop's block by the hall's west wall (-3, 0, 4), where CI's
                    // run picked the stop's dirt up. Its climb out to put the hall's door back is planned from there, in the tick its
                    // bring turns to the door; let be, its drop drifted a block or part of one, and it climbed by a stair in 3 of 6 runs here.
                    if (onBed == 4 && !climbOnce[0] && f.runtime.bringNote(f.id).contains("[UNBRIDGE]") && f.player() != null
                            && count(f.player().getInventory(), Items.DIRT) == 4 && context.getWorld().getBlockState(origin[0].add(-3, 0, 3)).isAir()
                            && context.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin[0]).expand(24),
                                    item -> item.isAlive() && (item.getStack().isOf(Items.RAIL) || item.getStack().isOf(Items.DIRT))).isEmpty()) {
                        climbOnce[0] = true;
                        BlockPos by = origin[0].add(-3, 0, 4);
                        var player = f.player();
                        player.refreshPositionAndAngles(by.getX() + 0.5, by.getY(), by.getZ() + 0.5, 90, 0);
                        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        KindredMod.LOGGER.info("Bring: Hal set at {} as its last pickup ends, holding {} dirt", by.subtract(origin[0]).toShortString(),
                                count(player.getInventory(), Items.DIRT));
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
                            for (var boat : world.getEntitiesByClass(net.minecraft.entity.vehicle.BoatEntity.class, new net.minecraft.util.math.Box(front), net.minecraft.entity.Entity::isAlive)) {
                                if (boatOverFront[0] == null) boatOverFront[0] = "its box to z " + String.format("%.2f", boat.getBoundingBox().maxZ - origin[0].getZ())
                                        + " over the front at z " + (front.getZ() - origin[0].getZ()) + " to " + (front.getZ() + 1 - origin[0].getZ()) + ", the tick its rail came up";
                            }
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
                        // Staged: from the first tick its front's rail is up and Hal is out of the front, until the job block is
                        // in, the librarian (out of any boat or cart) is against the front's open side and pressing out at a walk.
                        boolean jobIn = world.getBlockState(front).isOf(Blocks.LECTERN);
                        if (up != null && !jobIn && inFrontLoose[0] == null && librarian[0] != null && librarian[0].getVehicle() == null
                                && librarian[0].getBoundingBox().intersects(new net.minecraft.util.math.Box(front))) {
                            inFrontLoose[0] = "at " + librarian[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()) + ", "
                                    + (world.getTime() - up) + " ticks after its front's rail came up";
                        }
                        if (jobIn && up != null) jobBlockIn[0] = true;
                        // Staged (F-22c96a): the first tick the bring is at CLOSE, Hal out of the front and at rest, its trapdoor shut over the front and the job
                        // block not yet in, Hal set in the middle of the front, under its own shut trapdoor, as CI's on #429 stood.
                        if (onBed == 8 && underShut[0] == null && hal != null && !jobIn
                                && world.getBlockState(front.up()).isIn(net.minecraft.registry.tag.BlockTags.WOODEN_TRAPDOORS)
                                && String.valueOf(f.runtime.bringNote(f.id)).contains("[CLOSE]")
                                // Out of the front and at rest, its walk out of it done: set in mid-walk, the walk itself
                                // went on and took it out again.
                                && hal.isOnGround() && hal.getVelocity().horizontalLengthSquared() < 1.0E-4
                                && !hal.getBoundingBox().intersects(new net.minecraft.util.math.Box(front))) {
                            hal.refreshPositionAndAngles(front.getX() + 0.5, front.getY(), front.getZ() + 0.5, hal.getYaw(), hal.getPitch());
                            hal.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                            underShut[0] = front;
                            KindredMod.LOGGER.info("Bring: Hal set in its front under the shut trapdoor, at {}",
                                    hal.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                        }
                        if (pressed && up != null && !jobIn && hal != null && !hal.getBoundingBox().intersects(new net.minecraft.util.math.Box(front))) {
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
                        // Staged (F-22c84, cause #2): the tick the cart in the cell under the shut trapdoor is broken, its item on the
                        // cell's floor, Hal held for ten ticks a third of a block east of the middle of its stand, two south of the
                        // front, out of reach of what lies; the boat's item, if Hal holds it and none lies, set on the floor beside
                        // the cart's (a move, not a copy).
                        var cellItems = new net.minecraft.util.math.Box(front).union(new net.minecraft.util.math.Box(front.north(3))).stretch(0, 2, 0).expand(0.5);
                        boolean shut = world.getBlockState(front.up()).isIn(net.minecraft.registry.tag.BlockTags.WOODEN_TRAPDOORS);
                        boolean cartThere = shut && !world.getEntitiesByClass(net.minecraft.entity.vehicle.AbstractMinecartEntity.class, cellItems,
                                net.minecraft.entity.Entity::isAlive).isEmpty();
                        boolean cartBroken = !cartThere && cartInCell.remove(front);
                        if (cartThere) cartInCell.add(front);
                        // Or, if the cart was broken before the trapdoor was shut, the first tick the trapdoor is shut with the cart's
                        // item lying in the cell (so a bring that takes another order is still staged).
                        // And if the cart's item went straight into Hal's hand as the cart was broken, before a tick here saw it lying
                        // (in CI on #376, run 43776656762, the staging never fired), it is put back on the cell's floor from Hal's
                        // hand (a move, not a copy), so the staging is the same either way.
                        if (onBed == 3 && offFront[0] == null && cartBroken && shut && !jobIn && hal != null
                                && world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems, i -> i.isAlive() && i.getStack().isOf(Items.MINECART)).isEmpty()) {
                            var inventory = hal.getInventory();
                            for (int slot = 0; slot < inventory.size(); slot++) {
                                if (!inventory.getStack(slot).isOf(Items.MINECART)) continue;
                                world.spawnEntity(new net.minecraft.entity.ItemEntity(world, front.getX() + 0.5, front.getY() + 0.1, front.north().getZ() + 0.5,
                                        inventory.getStack(slot).split(1), 0, 0, 0));
                                KindredMod.LOGGER.info("Bring: the cart's item taken up as the cart was broken, set back on the cell's floor");
                                break;
                            }
                        }
                        if (onBed == 3 && offFront[0] == null && (cartBroken || shut) && !jobIn && hal != null
                                && !world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems, i -> i.isAlive() && i.getStack().isOf(Items.MINECART)).isEmpty()) {
                            offFront[0] = front;
                            if (world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems,
                                    i -> i.isAlive() && i.getStack().isIn(net.minecraft.registry.tag.ItemTags.BOATS)).isEmpty()) {
                                var inventory = hal.getInventory();
                                for (int slot = 0; slot < inventory.size(); slot++) {
                                    if (!inventory.getStack(slot).isIn(net.minecraft.registry.tag.ItemTags.BOATS)) continue;
                                    var boatItem = new net.minecraft.entity.ItemEntity(world, front.getX() + 0.5, front.getY() + 0.1, front.north().getZ() + 0.3,
                                            inventory.getStack(slot).split(1), 0, 0, 0);
                                    world.spawnEntity(boatItem);
                                    break;
                                }
                            }
                            KindredMod.LOGGER.info("Bring: Hal held a third off its stand, the cell's items {}",
                                    world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems, net.minecraft.entity.Entity::isAlive).stream()
                                            .map(i -> i.getStack() + " at " + i.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ())).toList());
                        }
                        // Held there until eighty ticks after the front's rail is up, then let go: past the bound of the rail's
                        // break collecting its own drop (about 71 ticks), which, still under way as Hal was let go, walked it into
                        // reach of the cell's items (a probe: Hal moving north from the tick it was let go).
                        if (front.equals(offFront[0]) && up != null && offFrom[0] < 0) offFrom[0] = Math.max(up, world.getTime()) + 80;
                        if (hal != null && front.equals(offFront[0]) && (offFrom[0] < 0 || world.getTime() < offFrom[0])) {
                            BlockPos stand = front.south(2);
                            // On the stand's floor, at rest: a walk to a block's middle ends at once only on the ground and at rest,
                            // and one let go in the air had walked on into the quarter-block.
                            hal.refreshPositionAndAngles(stand.getX() + 0.5 + 0.3, stand.getY(), stand.getZ() + 0.5, hal.getYaw(), hal.getPitch());
                            hal.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                            // And nothing in the cell picked up while held (a break's collection had carried Hal to them between
                            // two holds, within a tick), so all of it still lies there as Hal is let go.
                            for (var item : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems, net.minecraft.entity.Entity::isAlive)) item.setPickupDelay(2);
                        }
                        if (hal != null && front.equals(offFront[0]) && offFrom[0] >= 0 && offLeft[0] == null && world.getTime() - offFrom[0] >= 600) {
                            var left = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems,
                                    i -> i.isAlive() && (i.getStack().isOf(Items.MINECART) || i.getStack().isIn(net.minecraft.registry.tag.ItemTags.BOATS)));
                            if (!left.isEmpty()) offLeft[0] = left.stream().map(i -> i.getStack() + " at " + i.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ())
                                    + " age " + i.getItemAge()).toList() + ", Hal at " + hal.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ());
                            else if (count(hal.getInventory(), Items.MINECART) < 1) offLeft[0] = "none in the cell, but Hal holds no minecart: "
                                    + world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellItems.expand(4), net.minecraft.entity.Entity::isAlive).stream()
                                            .map(i -> i.getStack() + " at " + i.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ())).toList();
                            else offLeft[0] = "";
                        }
                        // Staged (#318): the first tick, after its rail is up, that the cell holds no boat, cart, rail or cart
                        // item and Hal is out of the front (all of it broken and picked up, under the trapdoor), Hal set up on
                        // the bed's foot, and the librarian, out of any boat or cart, held on the bed's head for 60 ticks, the
                        // floor clear.
                        var cellBox = new net.minecraft.util.math.Box(front).union(new net.minecraft.util.math.Box(front.north(3))).stretch(0, 2, 0);
                        if ((onBed == 1 || onBed == 2) && up != null && !jobIn && hal != null && !hal.getBoundingBox().intersects(new net.minecraft.util.math.Box(front))
                                && world.getEntitiesByClass(net.minecraft.entity.vehicle.VehicleEntity.class, cellBox.expand(0.5), net.minecraft.entity.Entity::isAlive).isEmpty()
                                && world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellBox.expand(0.5),
                                        i -> i.isAlive() && (i.getStack().isOf(Items.RAIL) || i.getStack().isOf(Items.MINECART))).isEmpty()
                                && bedFrom[0] < 0 && librarian[0].getVehicle() == null
                                && world.getBlockState(front.up()).isIn(net.minecraft.registry.tag.BlockTags.WOODEN_TRAPDOORS)) {
                            bedFrom[0] = world.getTime();
                            bedFront[0] = front;
                            // The edge: CI's (0.3 east of the cell's west face, 0.9 north of the front's). Wholly: the foot's middle.
                            hal.refreshPositionAndAngles(front.getX() + (onBed == 1 ? 0.3 : 0.5), front.getY() + 0.5625, front.getZ() - (onBed == 1 ? 0.9 : 1.5), 0, 0);
                            hal.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                            KindredMod.LOGGER.info("Bring: Hal up on the bed's foot, at {}", hal.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()));
                        }
                        if (front.equals(bedFront[0]) && world.getTime() - bedFrom[0] < 60) {
                            var v = librarian[0];
                            v.refreshPositionAndAngles(front.getX() + 0.5, front.getY() + 0.5625, front.getZ() - 2.4, 0, 0);
                            v.setVelocity(0, v.getVelocity().y, 0);
                        }
                        // Never shut in: the tick the job block goes in, no part of Hal's box in the cell (its front to its
                        // bed's head, to its full height).
                        if (jobIn && up != null && jobSeen.add(front) && shutIn[0] == null && hal != null
                                && hal.getBoundingBox().intersects(cellBox)) {
                            shutIn[0] = "Hal at " + hal.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()) + " as the job block went in across the front at "
                                    + front.subtract(origin[0]).toShortString();
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
                    context.assertTrue(offLeft[0] == null || offLeft[0].isEmpty(),
                            "The cart's and boat's items taken up from the cell within 600 ticks of Hal let go off its stand: still lying " + offLeft[0]);
                    context.assertTrue(shutIn[0] == null, "The job block never put in with Hal in the cell: " + shutIn[0]);
                    if (onBed == 10) {
                        // F-22c123: given up before any push, the door back; the staged case's message as it would read.
                        context.assertTrue(wanderings[0] > 0, "Staged: the librarian's boat set off the line before any push");
                        context.assertTrue(!beforeAnyPush.isEmpty(), "Given up before any push, the librarian off each line: " + beforeAnyPush + " / " + f.runtime.bringNote(f.id));
                        context.assertTrue(beforeAnyPush.stream().allMatch(e -> e.endsWith(" / door") && !e.contains("couldn't put my hall's door back")),
                                "The hall's door put back as each bring before any push gave up: " + beforeAnyPush);
                        String message = stagedOffLine(beforeAnyPush);
                        KindredMod.LOGGER.info("Bring: the staged case's message for a bring that never came to a push: {}", message);
                        context.assertTrue(message.contains("no push was ever reached: given up '"), message);
                        return;
                    }
                    if (onBed == 6 || onBed == 7) {
                        // Given up, the bring ends with what it put down taken back: the hall's door back in its doorway, as
                        // it was the tick the bring ended (a new bring may begin after and open it again).
                        context.assertTrue(offLineSets[0] > 0, stagedOffLine(beforeAnyPush));
                        context.assertTrue(endedWith[0] != null, "The bring given up and ended: " + f.runtime.bringNote(f.id));
                        context.assertTrue(endedWith[0].startsWith("door / ") && !endedWith[0].contains("couldn't put my hall's door back"),
                                "The hall's door put back as it gave up: " + endedWith[0]);
                        // F-22c123: and a bring given up before any push put the door back too.
                        context.assertTrue(beforeAnyPush.stream().allMatch(e -> e.endsWith(" / door") && !e.contains("couldn't put my hall's door back")),
                                "The hall's door put back as each bring before any push gave up: " + beforeAnyPush);
                        // The boat in the pit let be, and said so, not walked to for ever.
                        if (onBed == 7) context.assertTrue(!Double.isNaN(pitFloor[0]), "Staged: the librarian's boat set down on its pit's floor");
                        if (onBed == 7) context.assertTrue(endedWith[0].contains("I left the boat with the librarian in it where I couldn't get to it."),
                                "The boat it couldn't get to let be, and said: " + endedWith[0]);
                        return;
                    }
                    context.assertTrue(f.runtime.brought(f.id).contains(librarian[0].getUuid()),
                            "Brought home: " + f.runtime.bringNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                  if (onBed == 6 || onBed == 7 || onBed == 10) {
                      // Given up as staged: nothing brought home to check.
                      librarian[0].discard();
                      f.runtime.stop();
                      return;
                  }
                  try {
                    // F-22c98c: its pushes met the boat at the gap CI's did, and the first bring was never given up for it
                    // (a second bring begun after a give-up got there with a push that never slowed, so the scene's end alone
                    // could not tell).
                    if (onBed == 8) {
                        context.assertTrue(shortSets[0] > 0, "A push met the held boat as CI's did");
                        var gaveUp = f.runtime.said(f.id).stream().filter(line -> line.contains("wouldn't take")).toList();
                        context.assertTrue(gaveUp.isEmpty(), "The bring never given up for a cart that would not take the librarian up: " + gaveUp
                                + "; pushes staged " + shortSets[0]);
                    }
                    context.assertTrue(offLine[0] == null, "Kept on the rails, never rolled off the line's start: " + offLine[0]);
                    context.assertTrue(inFrontLoose[0] == null, "The librarian kept out of its front until the job block is in: " + inFrontLoose[0]);
                    context.assertTrue(boatOverFront[0] == null, "No boat over a cell's front as its rail comes up: " + boatOverFront[0]);
                    if (bornInBoat) context.assertTrue(stagedDrop[0] != null, "The front rail's drop staged at the front's north edge");
                    if (onBed == 1 || onBed == 2) context.assertTrue(bedFrom[0] >= 0, "Hal staged up on the bed's foot with the front's rail up");
                    if (onBed == 3) {
                        context.assertTrue(offFront[0] != null && offFrom[0] >= 0, "Hal staged off its stand as the cart in the cell under the trapdoor was broken, and let go");
                        // A bring that ended before 600 ticks after Hal was let go is judged as it ended: nothing of the cart's or
                        // boat's left in the cell, and the cart in Hal's hand.
                        if (offLeft[0] == null && offFront[0] != null && f.player() != null) {
                            var cellEnd = new net.minecraft.util.math.Box(offFront[0]).union(new net.minecraft.util.math.Box(offFront[0].north(3))).stretch(0, 2, 0).expand(0.5);
                            var lying = context.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellEnd,
                                    i -> i.isAlive() && (i.getStack().isOf(Items.MINECART) || i.getStack().isIn(net.minecraft.registry.tag.ItemTags.BOATS)));
                            offLeft[0] = !lying.isEmpty() ? "as the bring ended: " + lying.stream().map(i -> i.getStack() + " at " + i.getPos()).toList()
                                    : count(f.player().getInventory(), Items.MINECART) < 1 ? "as the bring ended, none in the cell, but Hal holds no minecart" : "";
                        }
                        context.assertTrue(offLeft[0] != null && offLeft[0].isEmpty(), "The cart's and boat's items taken up within 600 ticks: " + offLeft[0]);
                    }
                    context.assertTrue(shutIn[0] == null, "The job block never put in with Hal in the cell: " + shutIn[0]);
                    if (pressed) {
                        context.assertTrue(pressFrom[0] >= 0, "The librarian pressed at the front with its rail up and Hal out of it");
                        context.assertTrue(escaped[0] == null, "The librarian held in its cell: " + escaped[0]);
                        context.assertTrue(jobBlockIn[0], "The job block in across the front");
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
                        BlockPos o = origin(context);
                        for (int rz = 15; rz <= 22; rz++) {
                            context.assertTrue(world.getBlockState(o.add(7, 1, rz)).isOf(Blocks.WATER), "The lake as it was: " + world.getBlockState(o.add(7, 1, rz)));
                        }
                        context.assertTrue(f.runtime.said(f.id).stream().noneMatch(line -> line.contains("plain blocks")), "Not stopped for want of a bridge");
                    }
                    if (river) {
                        BlockPos o = origin(context);
                        for (int rz = 15; rz <= 17; rz++) {
                            context.assertTrue(world.getBlockState(o.add(7, 1, rz)).isOf(Blocks.WATER), "The bridge taken back, the river as it was: "
                                    + world.getBlockState(o.add(7, 1, rz)) + " at " + o.add(7, 1, rz).subtract(origin[0]).toShortString());
                        }
                    }
                    var hal = f.player();
                    inPlay(context, hal, lastNotYet[0]);
                    var inventory = hal.getInventory();
                    // All but the rail on the cell's floor, left to the cell.
                    int rails = make ? 31 : 63;
                    BlockPos floorRail = origin[0].add(cell.get().stand().x(), cell.get().stand().y(), cell.get().stand().z());
                    context.assertTrue(world.getBlockState(floorRail).isIn(net.minecraft.registry.tag.BlockTags.RAILS), "The rail on its cell's floor left: "
                            + world.getBlockState(floorRail));
                    context.assertTrue(count(inventory, Items.RAIL) == rails && count(inventory, Items.MINECART) == 1 && count(inventory, Items.LECTERN) == 0
                                    && count(inventory, Items.DIRT) == (river ? 8 : 4) && (!make || count(inventory, Items.IRON_INGOT) <= 7 && count(inventory, Items.STICK) == 0),
                            "Rails (but the cell floor's), the cart and dirt all taken back, the lectern spent" + (make ? ", 17 iron and 2 sticks made into rails and a cart (what iron is left its own to use)" : "") + ": "
                                    + count(inventory, Items.RAIL) + " rails, " + count(inventory, Items.MINECART) + " carts, " + count(inventory, Items.LECTERN)
                                    + " lecterns, " + count(inventory, Items.DIRT) + " dirt, " + count(inventory, Items.IRON_INGOT) + " iron, "
                                    + count(inventory, Items.STICK) + " sticks" + (count(inventory, Items.MINECART) == 1 && count(inventory, Items.DIRT) == (river ? 8 : 4) ? "" : "; carts and dirt about: "
                                    + world.getEntitiesByClass(net.minecraft.entity.Entity.class, hal.getBoundingBox().expand(64), e -> e.isAlive()
                                            && (e instanceof net.minecraft.entity.vehicle.AbstractMinecartEntity || e instanceof net.minecraft.entity.ItemEntity item
                                                    && (item.getStack().isOf(Items.MINECART) || item.getStack().isOf(Items.DIRT))))
                                            .stream().map(e -> e.getType().getUntranslatedName() + " at " + e.getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ())).toList()
                                    + (count(inventory, Items.DIRT) < (river ? 8 : 4) ? dirtWhere(world, origin(context), origin[0], hal) : "")));
                    if (mine) {
                        BlockPos o = origin(context);
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
                        // The trapdoor its cell was shut over with (F-22c64): the one it held, now over the front's top half, as
                        // part of the cell; none held or lying, none more of it; no planks in or out.
                        int trapdoorsLying = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(origin[0]).expand(20),
                                item -> item.getStack().isOf(Items.OAK_TRAPDOOR)).size();
                        var frontTop = world.getBlockState(slot.up());
                        context.assertTrue(count(inventory, Items.OAK_TRAPDOOR) == 0 && trapdoorsLying == 0 && count(inventory, Items.OAK_PLANKS) == 0
                                        && frontTop.isOf(Blocks.OAK_TRAPDOOR) && frontTop.get(net.minecraft.state.property.Properties.BLOCK_HALF) == net.minecraft.block.enums.BlockHalf.TOP
                                        && !frontTop.get(net.minecraft.state.property.Properties.OPEN),
                                "Its trapdoor over the front's top half: " + count(inventory, Items.OAK_TRAPDOOR) + " held, " + trapdoorsLying + " lying, "
                                        + count(inventory, Items.OAK_PLANKS) + " planks, over the front " + frontTop);
                        // F-22c64: held by its trapdoor, not by the cart, one that strolls has its cart taken back: once its boat is
                        // broken it is free in the cart's box, and the two push apart (vanilla), the cart off its rail. None left in
                        // or by its cell: no cart, and no cart's item, within 2 blocks of the cell (its floor to its bed).
                        var cellAround = new net.minecraft.util.math.Box(floorRail).union(new net.minecraft.util.math.Box(floorRail.north())).union(new net.minecraft.util.math.Box(slot)).expand(2);
                        int cartsLeft = world.getEntitiesByClass(net.minecraft.entity.vehicle.AbstractMinecartEntity.class, cellAround, net.minecraft.entity.Entity::isAlive).size();
                        int cartItemsLeft = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, cellAround, item -> item.isAlive() && item.getStack().isOf(Items.MINECART)).size();
                        context.assertTrue(cartsLeft == 0 && cartItemsLeft == 0, "The cart taken back, none in or by its cell: " + cartsLeft + " carts, " + cartItemsLeft + " cart items");
                        // The stone taken out over the line for its ride put back: over the doorway, and the cell's lintel.
                        for (var over : java.util.List.of(door.up(2), slot.up(2))) {
                            context.assertTrue(world.getBlockState(over).isOf(Blocks.COBBLESTONE), "Put back over the line: " + world.getBlockState(over)
                                    + " at " + over.subtract(origin[0]).toShortString());
                        }
                        context.assertTrue(librarian[0].getHealth() >= librarian[0].getMaxHealth(), "Unhurt by its ride: " + librarian[0].getHealth());
                    }
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I've brought the librarian home")), "Said: " + f.runtime.said(f.id));
                    // One that strolls: what its cell holds, said, and only that (F-22c64): its trapdoor, and not the cart, taken back.
                    if (strolling) {
                        context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("I've brought the librarian home")
                                && line.contains("trapdoor") && !line.contains("the cart")), "Said what it left in the cell: " + f.runtime.said(f.id));
                    }
                    if (strolling) {
                        // Last, in the tick all else is first so: what the runtime does next does not move the scene first.
                        // Traded across, as a player in the corridor would (F-22c64). Staged: the time set once to the working day
                        // (2,000), and the librarian left to go where it goes in its cell. Once it is within a corridor player's reach
                        // with nothing of the cell's in the way of a look at it (outline shapes, as a client's crosshair), Hal is set
                        // there and its use click opens the trades.
                        if (!tradeDay[0]) {
                            world.setTimeOfDay(2000);
                            tradeDay[0] = true;
                            KindredMod.LOGGER.info("Bring fixture: staged, the time of day set to 2000 for the trade across the front");
                        }
                        var corridor = net.minecraft.util.math.Vec3d.ofBottomCenter(slot.south());
                        var eye = corridor.add(0, hal.getStandingEyeHeight(), 0);
                        var aim = librarian[0].getBoundingBox().getCenter();
                        var look = world.raycast(new net.minecraft.world.RaycastContext(eye, aim,
                                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, hal));
                        double reach = Math.sqrt(librarian[0].getBoundingBox().squaredMagnitude(eye));
                        context.assertTrue(reach <= 3 && look.getType() == net.minecraft.util.hit.HitResult.Type.MISS, "The librarian in a corridor player's reach and sight: "
                                + String.format("%.2f", reach) + " from its eyes, the look met "
                                + (look instanceof net.minecraft.util.hit.BlockHitResult hit && look.getType() != net.minecraft.util.hit.HitResult.Type.MISS
                                        ? world.getBlockState(hit.getBlockPos()) + " at " + hit.getBlockPos().subtract(origin[0]).toShortString() : "nothing"));
                        if (hal.currentScreenHandler != hal.playerScreenHandler) hal.closeHandledScreen();
                        hal.refreshPositionAndAngles(corridor.x, corridor.y, corridor.z, 180, 0);
                        hal.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, aim);
                        context.assertTrue(hal.canInteractWithEntity(librarian[0], 0), "The librarian within Hal's own reach from the corridor");
                        hal.interact(librarian[0], net.minecraft.util.Hand.MAIN_HAND);
                        boolean trades = hal.currentScreenHandler instanceof net.minecraft.screen.MerchantScreenHandler;
                        hal.closeHandledScreen();
                        context.assertTrue(trades, "Its trades open from the corridor: " + hal.currentScreenHandler);
                    }
                    librarian[0].discard();
                    f.runtime.stop();
                  } catch (net.minecraft.test.GameTestException failing) {
                    // What is not yet so, now and then: the test server says only the last of it, when the test ends.
                    if (context.getWorld().getTime() % 100 == 0) KindredMod.LOGGER.info("Bring fixture, not yet: {}", failing.getMessage());
                    // Kept for the failure out of play (F-22c99), never the out-of-play message itself.
                    if (f.player() != null) lastNotYet[0] = failing.getMessage();
                    throw failing;
                  }
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
