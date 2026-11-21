package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Woken at night just after a death, its kit lying near, nothing hostile in sight: it goes back for the kit before it holes
 * up, rather than dig in and leave the kit to despawn (F-25c4di1f, slice 1). In E-40 iteration 24 ({@code e40a24-83321ac},
 * #384), 8675309 Sol died at night and, woken, dug in at once: the night's shelter is decided before the walk back to its
 * drops. A night outlasts a drop: an item lies 6,000 ticks, a night some 10,000.
 */
public final class NativeKitBeforeNightGameTests implements FabricGameTest {
    /** How far the fixture puts its spawn from where it dies: inside the campaign's bound for a walk back at night. */
    private static final int APART = 16;
    /** How long its drops have lain already when it wakes (staged); and how long it has after that to have its kit back. */
    private static final int LAIN = 3_000, BUDGET = 1_200;
    /** How long after its kit is back it has to dig in for the night. */
    private static final int SHELTER = 600;

    /** Where it dies, in the test's own ground. */
    private static final BlockPos DIES = new BlockPos(12, 2, 12);
    /** How far the ground runs beyond where it dies and wakes: vanilla throws a death's drops a few blocks, once six. */
    private static final int MARGIN = 10;

    /**
     * Stone underfoot and air over it from where it dies to where it wakes, {@link #APART} east, {@link #MARGIN} beyond on
     * every side, and a pad of rock five deep at each end to dig into.
     */
    private static void ground(TestContext context) {
        NativeNightsGameTests.pad(context, context.getAbsolutePos(DIES));
        NativeNightsGameTests.pad(context, context.getAbsolutePos(DIES.east(APART)));
        NativeNightsGameTests.floor(context, DIES.getX() - MARGIN, DIES.getX() + APART + MARGIN, DIES.getZ() - MARGIN, DIES.getZ() + MARGIN);
    }

    /** Its kit, worn and held: an iron chestplate on, an iron pickaxe in hand, bread and cobblestone in its pack. */
    private static void kit(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
        inventory.selectedSlot = 0;
        inventory.setStack(1, new ItemStack(Items.BREAD, 4));
        inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    /** Whether it has {@code item} on it, worn or in its pack. */
    private static boolean has(ServerPlayerEntity player, Item item) {
        if (player.getInventory().count(item) > 0) return true;
        for (ItemStack worn : player.getArmorItems()) if (worn.isOf(item)) return true;
        return false;
    }

    /** The drops round where it died, each aged to {@code age} ticks lain (staged: the time it would have lain by then). */
    private static java.util.List<ItemEntity> drops(TestContext context, BlockPos died) {
        return context.getWorld().getEntitiesByClass(ItemEntity.class, new Box(died).expand(8), ItemEntity::isAlive);
    }

    private static void age(ItemEntity drop, int age) {
        NbtCompound nbt = new NbtCompound();
        drop.writeNbt(nbt);
        nbt.putShort("Age", (short) age);
        drop.readNbt(nbt);
    }

    /**
     * Kills it where it stands (the fixture's death, not play), its spawn {@link #APART} east, and night held: woken there
     * with nothing, its kit by vanilla's own drop where it died.
     */
    private static void dieAndWake(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] dead, BlockPos died, BlockPos spawn) {
        var player = f.player();
        player.refreshPositionAndAngles(died.getX() + 0.5, died.getY(), died.getZ() + 0.5, -90, 0);
        kit(player);
        player.setSpawnPoint(context.getWorld().getRegistryKey(), spawn, -90, true, false);
        dead[0] = player;
        player.kill();
        context.assertFalse(player.isAlive(), "Killed");
    }

    /**
     * In plain terms: an AI player that has just died and woken at night not far from where it died, nothing hostile
     * about, walks back for its pickaxe and armour before it shelters for the night, instead of hiding at once and losing
     * them.
     *
     * Technical: F-25c4di1f slice 1, as e40a24's 8675309 Sol (above). STAGED: stone ground with no mob on it (peaceful),
     * running ten blocks beyond where it dies and wakes (a death's drops are thrown a few blocks, once six); its spawn {@value #APART} blocks east of where the fixture kills it, an iron chestplate on and an iron
     * pickaxe in hand (with bread and cobblestone); the Nether portal goal, as the E-40 runs play; night held all through; its drops, by vanilla
     * where it died, aged by the fixture to {@value #LAIN} ticks lain as it wakes (as if it had taken that long). Fails if
     * its chestplate or pickaxe is not on it within {@value #BUDGET} ticks of waking, if any of them despawns first, or if
     * it dies again; or if, within {@value #SHELTER} ticks of having them back, it has not begun to dig in for the night.
     * Passes with both back on it and dug in, alive.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "kitBeforeNight", tickLimit = LAIN + BUDGET + SHELTER + 3_400)
    public void wokenAtNightNearItsKitWithNothingAboutItGoesBackBeforeItHolesUp(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, LAIN + BUDGET + SHELTER + 3_200);
        BlockPos died = context.getAbsolutePos(DIES), spawn = died.east(APART);
        ServerPlayerEntity[] dead = {null};
        int[] deaths = {0}, woken = {0}, after = {0};
        StringBuilder seen = new StringBuilder(), lost = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    ground(context);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    context.getWorld().setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // Its goal's campaign under way before it dies, so that its death is the campaign's to mark.
                    context.assertTrue(f.runtime.campaignForTest(f.id) != null, "Its goal's campaign not yet under way");
                    dieAndWake(context, f, dead, died, spawn);
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    context.assertTrue(fresh.getBlockPos().getSquaredDistance(spawn) <= 2 * 2, "Woken at its spawn: " + fresh.getBlockPos().toShortString());
                    context.assertTrue(!has(fresh, Items.IRON_PICKAXE) && !has(fresh, Items.IRON_CHESTPLATE), "Woken with nothing");
                    var lying = drops(context, died);
                    context.assertTrue(lying.stream().anyMatch(d -> d.getStack().isOf(Items.IRON_PICKAXE))
                            && lying.stream().anyMatch(d -> d.getStack().isOf(Items.IRON_CHESTPLATE)), "Its kit not on the ground where it died: " + lying);
                    for (ItemEntity drop : lying) age(drop, LAIN);
                    f.runtime.observeRun(new RunObserver() {
                        @Override
                        public void died(String p, ServerPlayerEntity body, String cause, String stage, String stance, String habits, String task) {
                            deaths[0]++;
                        }

                        @Override
                        public void ended(String p, String skill, String state, String label) {
                            if (label != null && seen.length() < 1500) seen.append(label).append(' ').append(state).append("; ");
                        }
                    });
                })
                .createAndAdd(() -> {
                    // The first loss stands as the failure: the task is tried again each tick until the test's time is out.
                    if (lost.length() > 0) throw new net.minecraft.test.GameTestException(lost.toString());
                    context.getWorld().setTimeOfDay(14_000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its body gone: " + seen);
                    if (deaths[0] > 0 || !player.isAlive()) lost.append("It died again, ").append(woken[0]).append(" ticks after waking: ").append(seen);
                    boolean back = has(player, Items.IRON_PICKAXE) && has(player, Items.IRON_CHESTPLATE);
                    if (!back && lost.length() == 0) {
                        var lying = drops(context, died);
                        for (Item piece : new Item[] {Items.IRON_PICKAXE, Items.IRON_CHESTPLATE}) {
                            if (lost.length() == 0 && !has(player, piece) && lying.stream().noneMatch(d -> d.getStack().isOf(piece))) {
                                lost.append("Its ").append(piece == Items.IRON_PICKAXE ? "pickaxe" : "chestplate").append(" despawned on the ground, ")
                                        .append(woken[0]).append(" ticks after waking: ").append(seen);
                            }
                        }
                    }
                    if (lost.length() > 0) throw new net.minecraft.test.GameTestException(lost.toString());
                    context.assertTrue(++woken[0] <= BUDGET || back, "Its kit not back on it " + BUDGET + " ticks after waking: at "
                            + player.getBlockPos().subtract(died).toShortString() + " from where it died; " + seen);
                    context.assertTrue(back, "Not yet");
                })
                .createAndAdd(() -> {
                    // Then the night's shelter, as before.
                    context.getWorld().setTimeOfDay(14_000);
                    context.assertTrue(deaths[0] == 0 && f.player().isAlive(), "It died again: " + seen);
                    context.assertTrue(++after[0] <= SHELTER || seen.indexOf("Dig in for the night") >= 0, "Not dug in for the night " + SHELTER
                            + " ticks after its kit was back: " + seen);
                    context.assertTrue(seen.indexOf("Dig in for the night") >= 0, "Not yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Kit before the night: its kit back {} ticks after waking, dug in {} after that, deaths {}; {}", woken[0], after[0], deaths[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: the same, but with a monster standing in sight by its things: it shelters for the night as before,
     * and does not walk out to them.
     *
     * Technical: F-25c4di1f slice 1's bound on hostiles. STAGED as the test above (its goal too), but on easy (the server's own
     * difficulty put back as it ends) with a husk, its AI off, on the ground four blocks from where it died, in sight of
     * the drops and of where it wakes. Fails if, within {@value #BUDGET} ticks of waking, any walk back for its things
     * starts, or it dies. Passes after {@value #BUDGET} ticks without one: what it does meanwhile is what it does now.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "kitBeforeNightGuarded", tickLimit = BUDGET + 800)
    public void wokenAtNightWithAHostileInSightOfItsKitItSheltersAsBefore(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 700);
        BlockPos died = context.getAbsolutePos(DIES), spawn = died.east(APART);
        ServerPlayerEntity[] dead = {null};
        HuskEntity[] husk = {null};
        int[] back = {0}, woken = {0};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    ground(context);
                    var world = context.getWorld();
                    var server = world.getServer();
                    var difficulty = server.getSaveProperties().getDifficulty();
                    FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
                    server.setDifficulty(net.minecraft.world.Difficulty.EASY, true);
                    world.setWeather(12_000, 0, false, false);
                    world.setTimeOfDay(14_000);
                    var made = EntityType.HUSK.create(world);
                    BlockPos at = died.east(4);
                    made.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);
                    made.setAiDisabled(true);
                    made.setPersistent();
                    world.spawnEntity(made);
                    husk[0] = made;
                    FixtureGround.whenDone(context, made::discard);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // Its goal's campaign under way before it dies, so that its death is the campaign's to mark.
                    context.assertTrue(f.runtime.campaignForTest(f.id) != null, "Its goal's campaign not yet under way");
                    dieAndWake(context, f, dead, died, spawn);
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    for (ItemEntity drop : drops(context, died)) age(drop, LAIN);
                    f.runtime.observeRun(new RunObserver() {
                        @Override
                        public void ended(String p, String skill, String state, String label) {
                            if (label == null) return;
                            if (label.startsWith("Recover")) back[0]++;
                            if (seen.length() < 1500) seen.append(label).append(' ').append(state).append("; ");
                        }
                    });
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(14_000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "It died again: " + seen);
                    context.assertTrue(husk[0].isAlive(), "The husk is gone");
                    context.assertTrue(back[0] == 0, "It walked back for its things with the husk in sight of them: " + seen);
                    context.assertTrue(++woken[0] > BUDGET, "Not yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Kit before the night, guarded: no walk back in {} ticks after waking; {}", woken[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
