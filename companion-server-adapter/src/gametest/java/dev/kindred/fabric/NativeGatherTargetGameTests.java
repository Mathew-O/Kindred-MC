package dev.kindred.fabric;

import dev.kindred.core.ActionArbiter;
import dev.kindred.core.Companion;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * In plain terms: which block the goal goes to gather first, for what it knows and what it carries: rock, iron and
 * diamond in more than one kind of block each, and logs where only one of three is a tree's. The answer is pinned cell
 * by cell, so a faster search (M-06k: asking for the best tool once per kind of block in a search, not once per block)
 * is shown to choose exactly what it chose before.
 *
 * Technical: {@link PortalRunCampaign#gatherTargetForTest} is the gather's first {@code nearest} with
 * {@link PortalRunCampaign}'s {@code gatherable}, the rule the goal's gather and its walk-out stop both use. One
 * campaign and one bare body across the cases, the inventory changed between them, so an answer carried over from an
 * earlier search would show: the last case is the first case again.
 */
public final class NativeGatherTargetGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void theCellGatheredFirstIsTheSameForEveryKnowledgeAndInventory(TestContext context) {
        Companion identity = Companion.create(UUID.randomUUID(), UUID.randomUUID(), "Gatherer", UUID.randomUUID(),
                context.getWorld().getRegistryKey().getValue().toString()).withEntityId(UUID.randomUUID());
        for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y < 7; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
        var campaign = new PortalRunCampaign();
        var knowledge = campaign.knowledge();
        // Rock: the stone it stands on (never its own support), cobblestone, deepslate and stone.
        knowledge.remember(WorldKnowledge.Kind.STONE, context.getAbsolutePos(new BlockPos(2, 1, 2)));
        BlockPos cobble = place(context, 4, 2, 2, Blocks.COBBLESTONE, WorldKnowledge.Kind.STONE, knowledge);
        BlockPos deepslate = place(context, 2, 2, 5, Blocks.DEEPSLATE, WorldKnowledge.Kind.STONE, knowledge);
        place(context, 6, 2, 6, Blocks.STONE, WorldKnowledge.Kind.STONE, knowledge);
        // Iron and diamond, each in deepslate and in stone.
        BlockPos deepIron = place(context, 5, 2, 2, Blocks.DEEPSLATE_IRON_ORE, WorldKnowledge.Kind.IRON_ORE, knowledge);
        BlockPos iron = place(context, 2, 2, 6, Blocks.IRON_ORE, WorldKnowledge.Kind.IRON_ORE, knowledge);
        BlockPos diamond = place(context, 7, 2, 2, Blocks.DIAMOND_ORE, WorldKnowledge.Kind.DIAMOND_ORE, knowledge);
        place(context, 3, 2, 7, Blocks.DEEPSLATE_DIAMOND_ORE, WorldKnowledge.Kind.DIAMOND_ORE, knowledge);
        // Logs: a post against planks (built in), one held up by leaves, and a tree's trunk further off.
        context.setBlockState(1, 2, 4, Blocks.OAK_PLANKS);
        place(context, 2, 2, 4, Blocks.OAK_LOG, WorldKnowledge.Kind.LOG, knowledge);
        context.setBlockState(new BlockPos(4, 2, 4), Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true));
        place(context, 4, 3, 4, Blocks.OAK_LOG, WorldKnowledge.Kind.LOG, knowledge);
        BlockPos trunk = place(context, 6, 2, 4, Blocks.OAK_LOG, WorldKnowledge.Kind.LOG, knowledge);
        place(context, 6, 3, 4, Blocks.OAK_LOG, WorldKnowledge.Kind.LOG, knowledge);
        place(context, 6, 4, 4, Blocks.OAK_LOG, WorldKnowledge.Kind.LOG, knowledge);

        NativePlayerBody body = new NativePlayerBody(context.getWorld(), identity, context.getAbsolute(new Vec3d(2.5, 2, 2.5)),
                (lease, intent, target) -> false);
        body.bind(new ActionArbiter(identity, body));
        body.connect(Optional.empty(), false);
        context.runAtTick(5, () -> {
            try {
                Predicate<BlockPos> any = p -> true;
                Predicate<BlockPos> notNearest = p -> !p.equals(cobble) && !p.equals(deepIron);
                // Bare-handed: no rock or ore gives way to a hand; the tree does.
                carry(body, -1, null);
                expect(context, campaign, body, "bare hand", any, null, null, null, trunk);
                // A wooden pickaxe off the hotbar: rock, not iron.
                carry(body, 20, Items.WOODEN_PICKAXE);
                expect(context, campaign, body, "wooden pickaxe", any, cobble, null, null, trunk);
                carry(body, 0, Items.GOLDEN_PICKAXE);
                expect(context, campaign, body, "golden pickaxe", any, cobble, null, null, trunk);
                carry(body, 0, Items.STONE_PICKAXE);
                expect(context, campaign, body, "stone pickaxe", any, cobble, deepIron, null, trunk);
                carry(body, 3, Items.IRON_PICKAXE);
                expect(context, campaign, body, "iron pickaxe", any, cobble, deepIron, diamond, trunk);
                // The caller's own rule still applies: past the nearest rock and iron, the next of each.
                carry(body, 0, Items.STONE_PICKAXE);
                expect(context, campaign, body, "stone pickaxe, the nearest excluded", notNearest, deepslate, iron, null, trunk);
                // The first case again, on the same campaign: nothing found with a pickaxe is kept for a bare hand.
                carry(body, -1, null);
                expect(context, campaign, body, "bare hand again", any, null, null, null, trunk);
                context.complete();
            } finally {
                body.disconnect();
            }
        });
    }

    private static BlockPos place(TestContext context, int x, int y, int z, Block block, WorldKnowledge.Kind kind, WorldKnowledge knowledge) {
        context.setBlockState(x, y, z, block);
        BlockPos at = context.getAbsolutePos(new BlockPos(x, y, z));
        knowledge.remember(kind, at);
        return at;
    }

    private static void carry(NativePlayerBody body, int slot, Item item) {
        var inventory = body.entity().getInventory();
        inventory.clear();
        if (item != null) inventory.setStack(slot, new ItemStack(item));
    }

    private static void expect(TestContext context, PortalRunCampaign campaign, NativePlayerBody body, String with, Predicate<BlockPos> also,
                               BlockPos stone, BlockPos iron, BlockPos diamond, BlockPos log) {
        check(context, campaign, body, with, also, WorldKnowledge.Kind.STONE, stone);
        check(context, campaign, body, with, also, WorldKnowledge.Kind.IRON_ORE, iron);
        check(context, campaign, body, with, also, WorldKnowledge.Kind.DIAMOND_ORE, diamond);
        check(context, campaign, body, with, also, WorldKnowledge.Kind.LOG, log);
    }

    private static void check(TestContext context, PortalRunCampaign campaign, NativePlayerBody body, String with, Predicate<BlockPos> also,
                              WorldKnowledge.Kind kind, BlockPos expected) {
        var found = campaign.gatherTargetForTest(body, kind, 64, also);
        context.assertTrue(found.equals(Optional.ofNullable(expected)),
                "With a " + with + ", " + kind + ": " + (expected == null ? "none" : expected.toShortString()) + ", not " + found.map(BlockPos::toShortString));
    }
}
