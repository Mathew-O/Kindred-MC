package dev.kindred.fabric;

import dev.kindred.core.ActionArbiter;
import dev.kindred.core.Companion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;

/**
 * A walk to a drop in a pocket of its own pit that no body can get into: the route search spends all of its positions
 * before it says so (F-22c74, staged for MOVEMENT's navigator).
 */
public final class NativePitPocketGameTests implements FabricGameTest {
    /**
     * As in NativeBaseGameTests' stone-tools cabin, when it fails (CI, 12 November: #259, #265 twice, #268 twice): a
     * player two down in the pit it dug for stone, a stair cut up out of it to the open ground, the cell beside its feet
     * mined with stone still over it (a pocket one high), and the cell under that mined too, its cobblestone lying there.
     * The lower cell is a stand, two high with stone under it, but no body gets into it: the one way in is through the
     * pocket above it, which is one high. Each walk to pick up a drop there was "No walkable route", most of them after
     * the search had expanded all 6,000 of its positions over the open ground above the pit ("expanded 6000, standing
     * true; the goal standable true, on stone, at air"). Staged: the pit, the stair, the pocket and the ground, and the
     * walk's own search, as `native_goto` makes it for "Pick up" (range 0.6, the default 6,000 positions, radius 96).
     * Fails while the search spends all of its positions on a goal it cannot get into; passes once it is refused sooner.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocket", tickLimit = 200)
    public void aWalkIntoAPocketOfItsPitNoBodyCanEnterIsRefusedWithoutSpendingTheWholeSearch(TestContext context) {
        BlockPos p = stage(context, false);
        BlockPos target = p.add(1, -1, 0);
        var found = search(context, p, target);
        KindredMod.LOGGER.info("Pit pocket, its head room stone: complete {}, expanded {}, ends at {}", found.complete(),
                found.expanded(), found.end() == null ? null : found.end().subtract(p).toShortString());
        context.assertTrue(!found.complete(), "No body gets into the cell under a pocket one high, yet the search found a way: " + found.nodes());
        context.assertTrue(found.expanded() < NativeNavigator.DEFAULT_MAX_NODES, "The search spent all " + found.expanded()
                + " of its positions over the open ground before refusing a walk into a pocket one high beside it");
        FixtureGround.restore(context);
        context.complete();
    }

    /**
     * The control: the same pit with the pocket's head room mined too, so that it is two high and the cell under it a
     * stand it steps down into. The search finds it at once: the refusal above comes from the head room alone.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocketOpen", tickLimit = 200)
    public void theSamePocketWithHeadRoomIsFoundAtOnce(TestContext context) {
        BlockPos p = stage(context, true);
        BlockPos target = p.add(1, -1, 0);
        var found = search(context, p, target);
        KindredMod.LOGGER.info("Pit pocket, its head room open: complete {}, expanded {}", found.complete(), found.expanded());
        context.assertTrue(found.complete() && target.equals(found.end()), "No way found into the pocket with head room: " + found.nodes());
        context.assertTrue(found.expanded() < 50, "The way into the pocket with head room took " + found.expanded() + " positions");
        FixtureGround.restore(context);
        context.complete();
    }

    /**
     * The pickup itself (F-22c74): the player in its pit with a stone pickaxe, a drop it keeps (raw iron, as the pit's
     * cobblestone was for its cabin) lying in the cell under the pocket. The stone over the pocket is opened first, a break it may make, and the walk asked only after.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocketPickup", tickLimit = 200)
    public void itsDropUnderThePocketHasItsHeadRoomOpenedFirst(TestContext context) {
        BlockPos p = stage(context, false);
        pickup(context, p, Blocks.STONE, task -> task.isPresent() && "native_break".equals(task.get().skill())
                && p.add(1, 1, 0).equals(position(task.get())) && task.get().label().startsWith("Open the head room over the drop"),
                "the stone over the pocket broken first");
    }

    /** The same with bedrock over the pocket: no way in, nor one to be made; set aside, no walk asked. */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocketBedrock", tickLimit = 200)
    public void itsDropUnderABedrockPocketIsSetAsideNotWalkedTo(TestContext context) {
        BlockPos p = stage(context, false);
        pickup(context, p, Blocks.BEDROCK, task -> task.isEmpty(), "set aside, no walk asked");
    }

    /** The control: its head room open, the pickup walks to the drop as before. */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocketPickupOpen", tickLimit = 200)
    public void itsDropUnderAnOpenPocketIsWalkedTo(TestContext context) {
        BlockPos p = stage(context, true);
        pickup(context, p, null, task -> task.isPresent() && "native_goto".equals(task.get().skill())
                && p.add(1, -1, 0).equals(position(task.get())), "a walk to the drop");
    }

    private static BlockPos position(PortalRunCampaign.Task task) {
        try {
            return new BlockPos(Integer.parseInt(task.args().get("x")), Integer.parseInt(task.args().get("y")), Integer.parseInt(task.args().get("z")));
        } catch (RuntimeException none) {
            return null;
        }
    }

    private static void pickup(TestContext context, BlockPos p, Block head, java.util.function.Predicate<Optional<PortalRunCampaign.Task>> expected, String what) {
        var world = context.getWorld();
        if (head != null) world.setBlockState(p.add(1, 1, 0), head.getDefaultState(), 2);
        Companion identity = Companion.create(UUID.randomUUID(), UUID.randomUUID(), "Digger", UUID.randomUUID(),
                world.getRegistryKey().getValue().toString()).withEntityId(UUID.randomUUID());
        NativePlayerBody body = new NativePlayerBody(world, identity, new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5), (lease, intent, target) -> false);
        body.bind(new ActionArbiter(identity, body));
        body.connect(Optional.empty(), false);
        body.entity().getInventory().clear();
        body.entity().getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
        BlockPos at = p.add(1, -1, 0);
        var drop = new ItemEntity(world, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, new ItemStack(Items.RAW_IRON, 3));
        drop.setVelocity(Vec3d.ZERO);
        world.spawnEntity(drop);
        context.runAtTick(5, () -> {
            try {
                // World edits allowed, as on the server whose cabin dug this pit; with them off, no head room is opened.
                var campaign = new PortalRunCampaign();
                campaign.policy(dev.kindred.core.EditPolicy.ServerPolicy.deferredD4().withEdits(true));
                var task = campaign.pickUpLooseForTest(body);
                KindredMod.LOGGER.info("Pit pocket pickup ({}): {}", what, task.map(t -> t.skill() + " " + t.args() + " [" + t.label() + "]").orElse("none"));
                context.assertTrue(expected.test(task), "Its drop under the pocket: " + what + ", not " + task.map(t -> t.skill() + " " + t.args() + " [" + t.label() + "]").orElse("none"));
                drop.discard();
                FixtureGround.restore(context);
                context.complete();
            } finally {
                body.disconnect();
            }
        });
    }

    private static NativeNavigator.Path search(TestContext context, BlockPos from, BlockPos target) {
        var navigator = new NativeNavigator(context.getWorld()).throughDoors();
        return navigator.find(from, target, feet -> Math.sqrt(feet.getSquaredDistance(target)) <= 0.61,
                NativeNavigator.DEFAULT_MAX_NODES, 96);
    }

    /**
     * Stone from two under the pit's floor to the open ground's top, 81 by 81 (more stands than the search's 6,000), air
     * above. The pit: its floor's cell and the one over it ({@code p}, two down from the ground), a stair cut west up to
     * the ground, the pocket east of its feet ({@code p} + 1 east) and the cell under the pocket. With
     * {@code headRoom}, the cell over the pocket is mined too.
     */
    private static BlockPos stage(TestContext context, boolean headRoom) {
        var world = context.getWorld();
        BlockPos p = context.getAbsolutePos(new BlockPos(60, 5, 60)).toImmutable();
        FixtureGround.keep(context, p.add(-40, -2, -40), p.add(40, 6, 40));
        for (BlockPos c : BlockPos.iterate(p.add(-40, -2, -40), p.add(40, 6, 40))) {
            world.setBlockState(c, (c.getY() <= p.getY() + 1 ? Blocks.STONE : Blocks.AIR).getDefaultState(), 2);
        }
        for (BlockPos c : new BlockPos[] {p, p.up(), p.add(-1, 1, 0), p.add(1, 0, 0), p.add(1, -1, 0)}) {
            world.setBlockState(c, Blocks.AIR.getDefaultState(), 2);
        }
        if (headRoom) world.setBlockState(p.add(1, 1, 0), Blocks.AIR.getDefaultState(), 2);
        return p;
    }
}
