package dev.kindred.fabric;

import dev.kindred.core.construction.Pos;
import dev.kindred.core.survival.WheatPlot;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * In plain terms: a player working its small wheat plot: a wooden hoe made from its own wood if it has none, seeds
 * broken from the grass about it if it has none, the plot tilled and sown, and, when the wheat is ripe, harvested and
 * sown again. With all sown and growing, it stops and gets on with other things, and comes back when it is ripe.
 *
 * Technical: The wheat grown for a ranch (M-01a), on {@link WheatPlot}'s plan. Each step is one task through the same
 * skills as any work: crafting at a table, breaking grass, tilling, planting, breaking ripe wheat (its drops
 * collected). Nothing is given: short of wood for a hoe, or of seeds with no grass in sight, it stops and says so.
 */
final class WheatRunner {
    /**
     * What the plot grows: wheat from seeds broken from grass (cows', sheep's), or carrots or potatoes planted from one it
     * holds (pigs', M-01a), its seed being the food.
     */
    enum Crop {
        WHEAT(Items.WHEAT_SEEDS, Items.WHEAT, Blocks.WHEAT, "wheat"),
        CARROT(Items.CARROT, Items.CARROT, Blocks.CARROTS, "carrots"),
        POTATO(Items.POTATO, Items.POTATO, Blocks.POTATOES, "potatoes");

        final Item seed, food;
        final net.minecraft.block.Block block;
        final String name;

        Crop(Item seed, Item food, net.minecraft.block.Block block, String name) {
            this.seed = seed;
            this.food = food;
            this.block = block;
            this.name = name;
        }

        boolean seedIsFood() { return seed == food; }
    }

    private static final double REACH = 4.0;
    /** Steps at most on one visit to the plot: a hoe made, seeds broken, four cells tilled and sown, the wheat harvested. */
    private static final int MOST_STEPS = 400;
    private int steps;

    final WheatPlot plot;
    private final int wanted;
    private final String wood;
    private SeedRunner seeds;
    private boolean done, growing;
    private String outcome = "", progress = "";

    private final Crop crop;

    WheatRunner(BlockPos corner, int size, int wanted, String wood) {
        this(corner, size, wanted, wood, Crop.WHEAT);
    }

    WheatRunner(BlockPos corner, int size, int wanted, String wood, Crop crop) {
        this.crop = crop;
        this.plot = new WheatPlot(new Pos(corner.getX(), corner.getY(), corner.getZ()), size, crop.seedIsFood());
        this.wanted = wanted;
        this.wood = wood;
    }

    Crop crop() { return crop; }

    String outcome() { return outcome; }

    String progress() { return progress; }

    /** Whether it stopped with the wheat sown and growing, to be come back to. */
    boolean growing() { return growing; }

    Optional<PortalRunCampaign.Task> next(NativePlayerBody body, PortalRunCampaign campaign) {
        if (done) return Optional.empty();
        // A step that never comes off is not tried for ever (a till refused 5,092 times before the walk to the plot).
        if (++steps > MOST_STEPS) return stop(false, "Could not work the plot: " + progress.toLowerCase(java.util.Locale.ROOT));
        var player = body.entity();
        if (seeds != null) {
            var task = seeds.next(player);
            if (task.isPresent()) {
                progress = seeds.progress(player);
                return task;
            }
            seeds = null;
            if (player.getInventory().count(Items.WHEAT_SEEDS) == 0) return stop(false, "No seeds for the plot: " + seedsOutcome());
        }
        var step = plot.next(observe(player), wanted);
        return switch (step) {
            case WheatPlot.Step.NeedHoe need -> {
                progress = need.why();
                yield hoe(body, campaign);
            }
            case WheatPlot.Step.NeedSeeds need -> {
                progress = need.why();
                // Carrots and potatoes are planted from one it holds: none in hand, none to plant (none from nothing).
                if (crop.seedIsFood()) {
                    // What it just harvested lying in sight, not yet picked up: a moment to pick it up, not a stop.
                    var drop = lying(player);
                    if (drop.isPresent() && ++waits <= MOST_WAITS) {
                        yield Optional.of(PortalRunCampaign.go(drop.get(), 0.5, true, "Pick up the " + crop.name + " it harvested"));
                    }
                    yield stop(false, "No " + crop.name + " to plant");
                }
                seeds = new SeedRunner((int) plot.cells().size());
                lastSeeds = seeds;
                var task = seeds.next(player);
                yield task.isPresent() ? task : stop(false, "No seeds for the plot: " + seeds.outcome());
            }
            case WheatPlot.Step.Till till -> {
                progress = till.why();
                if (far(player, pos(till.at()))) yield Optional.of(PortalRunCampaign.go(pos(till.at()).up(), 2.0, true, "To the plot"));
                // A plant on the soil first, as a player clears it: a hoe tills nothing under grass or a flower (in a meadow
                // its plot sited on ground in sight, F-35m1, "Soil tilling produced no effect" every try).
                BlockPos over = pos(till.at()).up();
                var cover = player.getServerWorld().getBlockState(over);
                if (!cover.isAir() && cover.isReplaceable() && cover.getFluidState().isEmpty()) {
                    yield Optional.of(new PortalRunCampaign.Task("native_break", coords(new Pos(over.getX(), over.getY(), over.getZ()), Map.of()),
                            "Clear the plot for tilling"));
                }
                int slot = hotbar(player, stack -> stack.getItem() instanceof HoeItem);
                if (slot < 0) yield bring(player, stack -> stack.getItem() instanceof HoeItem, "Take the hoe in hand");
                yield Optional.of(new PortalRunCampaign.Task("native_till", coords(till.at(), Map.of("slot", str(slot), "roam", "true")), till.why()));
            }
            case WheatPlot.Step.Sow sow -> {
                progress = sow.why();
                if (far(player, pos(sow.at()))) yield Optional.of(PortalRunCampaign.go(pos(sow.at()).up(), 2.0, true, "To the plot"));
                int slot = hotbar(player, stack -> stack.isOf(crop.seed));
                if (slot < 0) yield bring(player, stack -> stack.isOf(crop.seed), "Take the " + (crop.seedIsFood() ? crop.name : "seeds") + " in hand");
                yield Optional.of(new PortalRunCampaign.Task("native_plant", coords(sow.at(), Map.of("slot", str(slot), "roam", "true")), sow.why()));
            }
            case WheatPlot.Step.Harvest harvest -> {
                progress = harvest.why();
                BlockPos crop = pos(harvest.at()).up();
                if (far(player, crop)) {
                    yield Optional.of(PortalRunCampaign.go(crop, 2.0, true, "To the ripe " + this.crop.name));
                }
                yield Optional.of(new PortalRunCampaign.Task("native_break", Map.of("x", str(crop.getX()), "y", str(crop.getY()), "z", str(crop.getZ())),
                        harvest.why()));
            }
            case WheatPlot.Step.Growing grow -> stop(true, grow.why());
            case WheatPlot.Step.Done d -> stop(false, d.outcome());
        };
    }

    private SeedRunner lastSeeds;
    /** Walks to a harvest's drops, at most a few, before it says it has none. */
    private static final int MOST_WAITS = 4;
    private int waits;

    /** Where the nearest of its crop lies on the ground about the plot, in sight. */
    private Optional<BlockPos> lying(ServerPlayerEntity player) {
        var c = plot.corner();
        var around = new net.minecraft.util.math.Box(c.x() - 3, c.y(), c.z() - 3, c.x() + 5, c.y() + 3, c.z() + 5);
        return player.getServerWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, around,
                        e -> e.getStack().isOf(crop.food) && player.canSee(e)).stream()
                .min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).map(net.minecraft.entity.Entity::getBlockPos);
    }

    private String seedsOutcome() { return lastSeeds == null ? "" : lastSeeds.outcome().toLowerCase(java.util.Locale.ROOT); }

    private Optional<PortalRunCampaign.Task> stop(boolean stillGrowing, String why) {
        done = true;
        growing = stillGrowing;
        outcome = why;
        progress = why;
        return Optional.empty();
    }

    /** A wooden hoe from two planks and two sticks, at a table: the sticks and planks first if short. */
    private Optional<PortalRunCampaign.Task> hoe(NativePlayerBody body, PortalRunCampaign campaign) {
        var inventory = body.entity().getInventory();
        Item plank = Registries.ITEM.get(Identifier.ofVanilla(wood + "_planks")), log = Registries.ITEM.get(Identifier.ofVanilla(wood + "_log"));
        int planks = inventory.count(plank), sticks = inventory.count(Items.STICK);
        String recipe;
        if (sticks < 2 && planks >= 2) recipe = "stick";
        else if (planks < 2) {
            if (inventory.count(log) == 0) return stop(false, "Short of " + wood + " wood for a hoe");
            recipe = wood + "_planks";
        } else recipe = "wooden_hoe";
        var made = campaign.craft(body, recipe);
        if (made.isPresent() || campaign.tableNear(body)) return made;
        if (inventory.count(Items.CRAFTING_TABLE) > 0) return campaign.placeTable(body);
        if (planks < 4) {
            if (inventory.count(log) == 0) return stop(false, "Short of " + wood + " wood for a crafting table");
            return campaign.craft(body, wood + "_planks");
        }
        return campaign.craft(body, "crafting_table");
    }

    private WheatPlot.Observation observe(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        var soil = new HashMap<Pos, WheatPlot.Soil>();
        for (var cell : plot.cells()) {
            BlockPos at = pos(cell);
            var state = world.getBlockState(at);
            var above = world.getBlockState(at.up());
            WheatPlot.Soil kind;
            if (state.isOf(Blocks.FARMLAND)) {
                if (above.isOf(crop.block)) kind = ((CropBlock) above.getBlock()).isMature(above) ? WheatPlot.Soil.RIPE : WheatPlot.Soil.SOWN;
                else kind = above.isAir() ? WheatPlot.Soil.FARMLAND : WheatPlot.Soil.OTHER;
            } else if ((state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK)) && (above.isAir() || above.isReplaceable() && above.getFluidState().isEmpty())) {
                kind = WheatPlot.Soil.DRY;
            } else {
                kind = WheatPlot.Soil.OTHER;
            }
            soil.put(cell, kind);
        }
        var inventory = player.getInventory();
        boolean hoe = false;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).getItem() instanceof HoeItem) hoe = true;
        BlockPos feet = player.getBlockPos();
        return new WheatPlot.Observation(new Pos(feet.getX(), feet.getY(), feet.getZ()), soil, hoe, inventory.count(crop.seed),
                inventory.count(crop.food));
    }

    private static int hotbar(ServerPlayerEntity player, Predicate<ItemStack> wanted) {
        var inventory = player.getInventory();
        if (wanted.test(inventory.getStack(inventory.selectedSlot))) return inventory.selectedSlot;
        for (int slot = 0; slot < 9; slot++) if (wanted.test(inventory.getStack(slot))) return slot;
        return -1;
    }

    /** What {@code wanted} accepts brought from the pack into the hotbar. */
    private Optional<PortalRunCampaign.Task> bring(ServerPlayerEntity player, Predicate<ItemStack> wanted, String why) {
        var inventory = player.getInventory();
        for (int slot = 9; slot < 36; slot++) {
            if (wanted.test(inventory.getStack(slot))) {
                return Optional.of(new PortalRunCampaign.Task("native_swap", Map.of("source", str(slot), "target", "8"), why));
            }
        }
        return stop(false, "Nothing at hand for it: " + why.toLowerCase(java.util.Locale.ROOT));
    }

    private static Map<String, String> coords(Pos at, Map<String, String> extra) {
        var args = new java.util.LinkedHashMap<String, String>();
        args.put("x", str(at.x()));
        args.put("y", str(at.y()));
        args.put("z", str(at.z()));
        args.putAll(extra);
        return Map.copyOf(args);
    }

    private static BlockPos pos(Pos p) { return new BlockPos(p.x(), p.y(), p.z()); }

    /** Beyond the reach it works from. */
    private static boolean far(ServerPlayerEntity player, BlockPos at) {
        return player.getEyePos().distanceTo(Vec3d.ofCenter(at)) > REACH;
    }

    private static String str(int value) { return String.valueOf(value); }
}
