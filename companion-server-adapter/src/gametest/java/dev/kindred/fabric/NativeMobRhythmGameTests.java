package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

/** The rhythm against mobs (E-71e): fights and escapes from mobs, as a veteran makes them. */
public final class NativeMobRhythmGameTests implements FabricGameTest {
    static final int FIGHTS = 10;
    static final int UNTOUCHED_AT_LEAST = 8;
    static final int FIGHT_TICKS = 400;

    /**
     * Ten zombies, one after another, each coming at a player with an iron sword and no armour, full health each time:
     * E-71e's open-ground acceptance, a zombie fought so it never lands a blow in at least eight of the ten. Already met
     * on main before E-71e (30 of 30 untouched over three runs, the zombie never nearer than 2.3: the jump for a critical
     * at the edge of reach knocks it back and kills it in three); kept as the check that the fights get no worse. Each
     * fight is counted from the zombie's spawn to its death; a blow taken is the player's health falling. A helmet on
     * each zombie, so the sun does not kill it first.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "mobRhythm", tickLimit = FIGHTS * (FIGHT_TICKS + 40) + 200)
    public void aZombieIsKilledWithoutLandingABlowInEightOfTenFights(TestContext context) {
        scene(context, 1, 7.5, Items.IRON_SWORD);
    }




    private static void scene(TestContext context, int count, double startZ, net.minecraft.item.Item sword) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ZombieEntity[] zombie = new ZombieEntity[2];
        int[] fight = {0}, untouched = {0}, blows = {0}, ticks = {0}, spawned = {-1}, settle = {0};
        float[] health = {20}, minDist = {99};
        StringBuilder seen = new StringBuilder();
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rhythm"))
                .createAndAdd(() -> f.established(context));
        for (int n = 0; n < FIGHTS; n++) {
            final int number = n;
            runner = runner.createAndAdd(() -> {
                var player = f.player();
                if (spawned[0] == number) {
                    // Spawned already: only the fight asked for again, once the last one has let go.
                    context.assertTrue(f.runtime.nativeFight(f.source, f.id, zombie[0].getUuid(), 0) == 1, "Fight requested");
                    return;
                }
                spawned[0] = number;
                var inventory = player.getInventory();
                inventory.clear();
                inventory.setStack(0, new ItemStack(sword));
                inventory.selectedSlot = 0;
                player.setHealth(20);
                player.getHungerManager().setFoodLevel(20);
                player.getHungerManager().setSaturationLevel(5);
                player.refreshPositionAndAngles(context.getAbsolute(new Vec3d(3.5, 2.0, 3.5)), 0, 0);
                player.setVelocity(Vec3d.ZERO);
                context.getWorld().setTimeOfDay(1000);
                // Four blocks off, out of reach, coming at it.
                for (int z = 0; z < 2; z++) zombie[z] = null;
                for (int z = 0; z < count; z++) {
                    zombie[z] = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5 + z * 1.5, 2.0, startZ));
                    zombie[z].equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    zombie[z].setTarget(player);
                }
                health[0] = 20;
                blows[0] = 0;
                ticks[0] = 0;
                context.assertTrue(f.runtime.nativeFight(f.source, f.id, zombie[0].getUuid(), 0) == 1, "Fight requested");
            }).createAndAdd(() -> {
                var player = f.player();
                ZombieEntity live = zombie[0] != null && zombie[0].isAlive() ? zombie[0] : zombie[1] != null && zombie[1].isAlive() ? zombie[1] : null;
                if (live != null && live != zombie[0]) {
                    zombie[0] = live;
                    f.runtime.nativeFight(f.source, f.id, live.getUuid(), 0);
                }
                if (zombie[0].isAlive()) {
                    ticks[0]++;
                    if (player.getHealth() < health[0] - 0.01f) blows[0]++;
                    minDist[0] = Math.min(minDist[0], player.distanceTo(zombie[0]));
                    health[0] = player.getHealth();
                    // A fight that runs out is asked again, as the goal would.
                    if (ticks[0] % (NativeFightAction.MAX_TICKS + 20) == 0) f.runtime.nativeFight(f.source, f.id, zombie[0].getUuid(), 0);
                }
                context.assertTrue(!zombie[0].isAlive() || ticks[0] > FIGHT_TICKS, "Fight " + (fight[0] + 1) + " under way");
            }).createAndAdd(() -> {
                boolean killed = true;
                for (var z : zombie) if (z != null && z.isAlive()) { killed = false; z.discard(); }
                boolean clean = killed && blows[0] == 0;
                if (clean) untouched[0]++;
                fight[0]++;
                seen.append(fight[0]).append(": ").append(killed ? "killed" : "not killed").append(" in ").append(ticks[0])
                        .append(" ticks, ").append(blows[0]).append(" blows taken; ");
                KindredMod.LOGGER.info("Mob rhythm: fight {} {} in {} ticks, {} blows taken, closest {}", fight[0], killed ? "killed" : "not killed", ticks[0], blows[0], minDist[0]);
                minDist[0] = 99;
                settle[0] = 0;
            }).createAndAdd(() -> context.assertTrue(++settle[0] >= 10, "A moment between fights"));
        }
        runner.createAndAdd(() -> {
                    KindredMod.LOGGER.info("Mob rhythm: {} of {} untouched; {}", untouched[0], FIGHTS, seen);
                    context.assertTrue(untouched[0] >= UNTOUCHED_AT_LEAST, untouched[0] + " of " + FIGHTS + " zombies killed untouched: " + seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }





    /**
     * Outmatched in a small closed room (11 health, two zombies and a spider after it, a stone sword in its hotbar and
     * its only blocks, cobblestone, outside the hotbar): the block it asks for, to take cover, reaches its hotbar. In the
     * corpus's `factions-e40a16seeds-b7b4bec-707-kindred-21883` (failing alone on main 2 of 3), its walks off refused
     * and the plan taking cover, the one-tick swap of a block to hand was stopped "A hostile mob is attacking; fight
     * back first" 25 to 35 times a run; it never had a block to hand, fought on, and a zombie killed it. Staged: the
     * room, the mobs and its kit as that scene's. Fails once a block asked for is still not in its hotbar 20 ticks on.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "mobRhythmCoverBlock", tickLimit = 1400)
    public void outmatchedTheBlockItAsksForCoverIsTakenToHand(TestContext context) {
        goalScene(context, 11, Items.STONE_SWORD, true, true);
    }

    private static void goalScene(TestContext context, float startHealth, net.minecraft.item.Item weapon, boolean tunnel, boolean blocksInPack) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        ZombieEntity[] zombie = new ZombieEntity[1];
        long[] start = {0}, asked = {-1}, taken = {-1};
        String[] failed = {null};
        int[] blows = {0};
        float[] health = {startHealth};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bare"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    net.minecraft.util.math.BlockPos p = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(60, 2, 60)).toImmutable();
                    FixtureGround.keep(context, p.add(-24, -2, -24), p.add(24, 8, 24));
                    for (net.minecraft.util.math.BlockPos c : net.minecraft.util.math.BlockPos.iterate(p.add(-24, -2, -24), p.add(24, 8, 24))) {
                        int dy = c.getY() - p.getY();
                        boolean bore = blocksInPack
                                ? Math.abs(c.getX() - p.getX()) <= 2 && c.getZ() >= p.getZ() - 2 && c.getZ() <= p.getZ() + 4 && (dy == 0 || dy == 1)
                                : c.getX() == p.getX() && c.getZ() >= p.getZ() - 20 && c.getZ() <= p.getZ() + 6 && (dy == 0 || dy == 1);
                        var block = tunnel ? (dy == -2 || dy > 3 ? net.minecraft.block.Blocks.AIR : bore ? net.minecraft.block.Blocks.AIR : net.minecraft.block.Blocks.STONE)
                                : dy == -2 ? net.minecraft.block.Blocks.STONE : dy == -1 ? net.minecraft.block.Blocks.GRASS_BLOCK : net.minecraft.block.Blocks.AIR;
                        world.setBlockState(c, block.getDefaultState());
                    }
                    world.setTimeOfDay(14000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(blocksInPack ? 10 : 0, new ItemStack(blocksInPack ? Items.COBBLESTONE : Items.DIRT, blocksInPack ? 64 : 16));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 4));
                    if (weapon != null) inventory.setStack(2, new ItemStack(weapon));
                    player.setHealth(startHealth);
                    player.getHungerManager().setFoodLevel(18);
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    zombie[0] = context.spawnMob(EntityType.ZOMBIE, context.getRelative(new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 2.3)));
                    zombie[0].equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    zombie[0].setTarget(player);
                    if (blocksInPack) {
                        // Outmatched: three after it (a second zombie and a spider), as kindred-21883 was.
                        var second = context.spawnMob(EntityType.ZOMBIE, context.getRelative(new Vec3d(p.getX() + 2.5, p.getY(), p.getZ() + 3.5)));
                        second.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                        second.setTarget(player);
                        var spider = context.spawnMob(EntityType.SPIDER, context.getRelative(new Vec3d(p.getX() - 1.5, p.getY(), p.getZ() + 3.5)));
                        spider.setTarget(player);
                    }
                    start[0] = world.getTime();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    context.assertTrue(player != null && player.isAlive(), "Alive: " + blows[0] + " blows; " + seen);
                    if (player.getHealth() < health[0] - 0.01f) blows[0]++;
                    health[0] = player.getHealth();
                    long t = context.getWorld().getTime() - start[0];
                    String doing = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    if (blocksInPack) {
                        boolean inHand = false;
                        for (int slot = 0; slot < 9; slot++) inHand |= player.getInventory().getStack(slot).isOf(Items.COBBLESTONE);
                        if (inHand) asked[0] = -1;
                        else if (doing.toLowerCase(java.util.Locale.ROOT).contains("ready a block") && asked[0] < 0) asked[0] = t;
                        if (failed[0] == null && asked[0] >= 0 && t - asked[0] > 20) {
                            failed[0] = "A block asked for to hand still not in its hotbar " + (t - asked[0]) + " ticks on: " + doing + "; " + seen;
                        }
                        context.assertTrue(failed[0] == null, String.valueOf(failed[0]));
                        if (inHand && taken[0] < 0) taken[0] = t;
                    }
                    if (t % 60 == 0) seen.append(t).append(": ").append(f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress())
                            .append(" hp ").append(player.getHealth()).append(" zombie ").append(zombie[0].isAlive() ? String.format("%.1f", player.distanceTo(zombie[0])) : "dead").append("; ");
                    context.assertTrue(t >= 900, "Watching");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Mob rhythm cover: alive, {} blows taken, a block to hand at {}; {}", blows[0], taken[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
