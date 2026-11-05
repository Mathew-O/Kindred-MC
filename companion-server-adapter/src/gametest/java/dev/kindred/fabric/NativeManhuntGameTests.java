package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, two AI players play a manhunt. The hunter is handed the
 * tracking compass under the match's announced rule, follows it round a wall to the runner and fights it, the
 * runner's fall ends the hunt and the compass is taken back; a hunter with iron crafts its sword, shield and
 * armour before it hunts and strikes at nothing until then; a runner keeps away from a hunter it can see and
 * fights only once caught; and a hurt, hungry runner being chased never runs towards its home and eats only
 * once it is clear.
 *
 * Technical: A manhunt (M-02a, D6, D7): a runner and a hunter, both AI players. The hunter is handed the
 * announced tracking compass, follows its needle round a wall it cannot see through, finds the runner and
 * fights it; the runner's fall ends the hunt for the hunters, and the compass is taken back.
 */
public final class NativeManhuntGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "manhunt", tickLimit = 3000)
    public void aHunterFollowsItsCompassToTheRunnerAndTheHuntersWinAtItsFall(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2800);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        String[] pace = new String[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Open ground, and a wall between them with a way round its east end.
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -4; x <= 12; x++) for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, 8), Blocks.STONE.getDefaultState());
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    BlockPos a = context.getAbsolutePos(new BlockPos(4, 2, 2)), b = context.getAbsolutePos(new BlockPos(4, 2, 14));
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    rue.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    context.assertTrue(!hal.canSee(rue), "The wall hides the runner");
                    server.setPvpEnabled(true);
                    // In the morning: a hunter waits for daylight before it goes up for logs, and the test server keeps
                    // its day cycle running from test to test.
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.setDragonFightForTest(true);
                    pace[0] = bot(f, ids[0]).playstyle();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().joined().size() == 2, "Both entered by their administrator");
                    // Past the fresh start: the hunter's kit staged here, as earning it would take the whole run's iron.
                    var hal = player(f, players[0]);
                    hal.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    hal.getInventory().selectedSlot = 0;
                    hal.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    // A manhunt's runner beats the game reliably, not at speedrun pace (M-02b).
                    context.assertTrue(bot(f, ids[1]).playstyle().equals("OPTIMAL_SURVIVAL"), "Rue at a reliable pace: " + bot(f, ids[1]).playstyle());
                    // The runner holds still for this fixture, so the hunt is the hunter's alone.
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Rue held");
                })
                .createAndAdd(() -> {
                    // The announced rule's one item: a tracking compass, cursed to vanish, pointing at the runner.
                    var hal = player(f, players[0]);
                    var compass = compass(hal, match[0]);
                    context.assertTrue(compass != null, "Hal holds a tracking compass");
                    var curse = hal.getServerWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.VANISHING_CURSE).orElseThrow();
                    context.assertTrue(compass.getEnchantments().getLevel(curse) == 1, "It vanishes on death");
                    var target = compass.get(DataComponentTypes.LODESTONE_TRACKER).target().orElseThrow();
                    context.assertTrue(target.pos().isWithinDistance(player(f, players[1]).getBlockPos(), 2), "It points at Rue: " + target.pos());
                })
                .createAndAdd(() -> {
                    var hunt = f.runtime.matches().getFirst();
                    context.assertTrue(hunt.state() == MatchRegistry.State.ENDED, "Hunted down: " + hunt.state() + "; Rue " + player(f, players[1]).getHealth()
                            + ", Hal at " + player(f, players[0]).getBlockPos().subtract(context.getAbsolutePos(BlockPos.ORIGIN)).toShortString());
                })
                .createAndAdd(() -> {
                    var hunt = f.runtime.matches().getFirst();
                    KindredMod.LOGGER.info("Manhunt: {} ({}); the hunters won {}", hunt.state(), hunt.result(), hunt.won(players[0]));
                    context.assertTrue("hunted".equals(hunt.result()) && hunt.won(players[0]) && !hunt.won(players[1]), "The hunters won: " + hunt.result());
                    context.assertTrue(compass(player(f, players[0]), match[0]) == null, "The compass taken back");
                    var bot = bot(f, ids[0]);
                    context.assertTrue(bot.goal() == dev.kindred.core.survival.SurvivalGoal.NONE && bot.playstyle().equals(pace[0]),
                            "The hunt's goal over, its own pace back: " + bot.goal() + " " + bot.playstyle());
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A hunter makes its kit before it hunts (M-02a), and gears up from its iron as the hunt goes on (M-02b): with the
     * iron it has (here sixteen ingots, staged past the fresh start as though mined), an iron sword and a shield are
     * crafted at a table of its own, then an iron chestplate and helmet, worn, before it follows the needle.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "manhuntKit", tickLimit = 4200)
    public void aHunterMakesItsKitThenArmourFromItsIronBeforeItHunts(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 4000);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        boolean[] huntedUnready = {false}, struckUnarmoured = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -4; x <= 12; x++) for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, 8), Blocks.STONE.getDefaultState());
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Smith allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fox"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Smith") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    var fox = player(f, players[1]);
                    BlockPos a = context.getAbsolutePos(new BlockPos(4, 2, 2)), b = context.getAbsolutePos(new BlockPos(4, 2, 14));
                    smith.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    fox.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    smith.getInventory().clear();
                    fox.getInventory().clear();
                    server.setPvpEnabled(true);
                    // In the morning: a hunter waits for daylight before it goes up for logs, and the test server keeps
                    // its day cycle running from test to test.
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.setDragonFightForTest(true);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    // Past the fresh start: iron as though mined and smelted, sticks and planks; no sword, no shield.
                    var inventory = player(f, players[0]).getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_INGOT, 16));
                    inventory.setStack(1, new ItemStack(Items.STICK, 4));
                    inventory.setStack(2, new ItemStack(Items.OAK_PLANKS, 16));
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Fox held");
                })
                .createAndAdd(() -> {
                    // Kept: whether it ever struck at the runner without its kit.
                    var smith = player(f, players[0]);
                    var fox = player(f, players[1]);
                    if (fox.getHealth() < 20 && !PlayerRuntime.huntReady(smith)) huntedUnready[0] = true;
                    context.assertTrue(PlayerRuntime.huntReady(smith), "The kit made: " + f.runtime.panelBots(Actor.console()).stream()
                            .filter(b -> b.id().equals(ids[0])).findFirst().orElseThrow().goalProgress());
                })
                .createAndAdd(() -> {
                    // Kept: whether it struck at the runner before its armour was on.
                    var smith = player(f, players[0]);
                    context.assertTrue(smith != null, "Smith still in the hunt");
                    boolean armoured = smith.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE)
                            && smith.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET);
                    if (player(f, players[1]).getHealth() < 20 && !armoured) struckUnarmoured[0] = true;
                    context.assertTrue(armoured, "An iron chestplate and helmet worn: " + smith.getEquippedStack(EquipmentSlot.CHEST) + ", "
                            + smith.getEquippedStack(EquipmentSlot.HEAD));
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    context.assertTrue(smith.getInventory().count(Items.IRON_SWORD) == 1 && (smith.getInventory().count(Items.SHIELD) == 1
                            || smith.getOffHandStack().isOf(Items.SHIELD)), "An iron sword and a shield of its own making");
                    context.assertTrue(smith.getInventory().count(Items.IRON_INGOT) == 0,
                            "Sixteen ingots: two for the sword, one for the shield, eight for the chestplate, five for the helmet");
                    context.assertTrue(!huntedUnready[0], "No strike before the kit");
                    context.assertTrue(!struckUnarmoured[0], "No strike before the armour");
                })
                .createAndAdd(() -> {
                    var hunt = f.runtime.matches().getFirst();
                    context.assertTrue(hunt.state() == MatchRegistry.State.ENDED && "hunted".equals(hunt.result()), "Hunted down after: " + hunt.state());
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A runner keeps its distance from a hunter in sight and fights only once caught (M-02b). Rue runs from Hal, held
     * still ten blocks off across open ground, rather than cut the tree beside it that its goal wants first; then, with
     * Hal kept at its heels, it turns and strikes.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "manhuntEvade", tickLimit = 2400)
    public void aRunnerKeepsItsDistanceFromAHunterInSightAndFightsOnlyWhenCaught(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2200);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        double[] farthest = {0};
        long[] started = {-1};
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Open ground walled round.
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-20, 1, -20), o.add(30, 8, 30));
                    for (int x = -20; x <= 30; x++) for (int z = -20; z <= 30; z++) {
                        boolean edge = x == -20 || x == 30 || z == -20 || z == 30;
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), edge && y <= 4 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    // A tree beside the runner: the first thing its goal wants, within the hunter's reach.
                    for (int y = 2; y <= 5; y++) world.setBlockState(o.add(8, y, 9), Blocks.OAK_LOG.getDefaultState());
                    for (int x = 7; x <= 9; x++) for (int z = 8; z <= 10; z++) for (int y = 5; y <= 6; y++) {
                        if (x != 8 || z != 9 || y == 6) world.setBlockState(o.add(x, y, z), Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
                    }
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    hal.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    rue.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 10.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    // The hunter holds still, so how far apart they get is the runner's doing.
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[0]) == 1, "Hal held");
                    context.assertTrue(player(f, players[1]).canSee(player(f, players[0])), "Rue sees Hal");
                    started[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> {
                    // Within eight seconds: no wandering off on its goal's business does that, with its tree at hand.
                    double apart = player(f, players[1]).distanceTo(player(f, players[0]));
                    if (context.getWorld().getTime() - started[0] <= 160) farthest[0] = Math.max(farthest[0], apart);
                    context.assertTrue(farthest[0] >= 20 && f.runtime.evasions(players[1]) > 0, "Rue keeps its distance within eight seconds: "
                            + String.format("%.1f", farthest[0]) + " blocks at most, moving off " + f.runtime.evasions(players[1]) + " times");
                })
                .createAndAdd(() -> {
                    // Caught: Hal kept at Rue's heels, on the side toward the open middle of the field, until Rue strikes.
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    if (rue.equals(hal.getAttacker())) return;
                    Vec3d toMiddle = Vec3d.ofBottomCenter(o.add(5, 2, 5)).subtract(rue.getPos()).multiply(1, 0, 1);
                    toMiddle = toMiddle.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : toMiddle.normalize();
                    Vec3d heels = new Vec3d(rue.getX() + toMiddle.x * 1.6, rue.getY(), rue.getZ() + toMiddle.z * 1.6);
                    if (context.getWorld().isSpaceEmpty(hal, hal.getDimensions(hal.getPose()).getBoxAt(heels))) {
                        hal.refreshPositionAndAngles(heels.x, heels.y, heels.z, 0, 0);
                    }
                    context.assertTrue(false, "Rue fights back once caught: Hal " + hal.getHealth() + ", struck by " + hal.getAttacker());
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Manhunt evasion: Rue got {} blocks off, then struck Hal down to {}", String.format("%.1f", farthest[0]),
                            player(f, players[0]).getHealth());
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A hunter that sees a runner it cannot walk to makes a way up to it (M-02a): Rue stands on a stone tower four
     * high, in Hal's sight and out of a walk's reach; Hal's fight cannot close in, and it builds its way up with its own
     * cobblestone and strikes. Found in a live hunt, where a hunter in a hollow below a runner started the same fight
     * again every fifteen seconds and never reached it. Staged: Hal's cobblestone and kit handed after the fresh start, as
     * if mined and made; Rue held on the tower.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "manhuntTower", tickLimit = 2400)
    public void aHunterMakesAWayUpToARunnerItCannotWalkTo(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2200);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-10, 1, -10), o.add(20, 12, 20));
                    for (int x = -10; x <= 20; x++) for (int z = -10; z <= 20; z++) {
                        boolean edge = x == -10 || x == 20 || z == -10 || z == 20;
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 12; y++) world.setBlockState(o.add(x, y, z), edge && y <= 4 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    // A tower three by three and four high, its top at y 5.
                    for (int x = 4; x <= 6; x++) for (int z = 9; z <= 11; z++) for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    hal.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 3.5, 0, 0);
                    rue.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 6, o.getZ() + 10.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    server.setPvpEnabled(true);
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    hal.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    hal.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    hal.getInventory().setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    hal.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Rue held on the tower");
                })
                .createAndAdd(() -> {
                    var rue = player(f, players[1]);
                    var hunt = f.runtime.matches().getFirst();
                    context.assertTrue(hunt.state() == MatchRegistry.State.ENDED || rue.getHealth() < 20, "Hal reaches Rue on the tower and strikes: Hal at "
                            + player(f, players[0]).getBlockPos().subtract(o).toShortString() + ", " + player(f, players[0]).getInventory().count(Items.COBBLESTONE) + " cobblestone left");
                    KindredMod.LOGGER.info("Manhunt tower: Hal reached Rue with {} cobblestone left", player(f, players[0]).getInventory().count(Items.COBBLESTONE));
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The runners' head start (M-02b): an AI hunter and a human hunter (a stand-in, joined by its own command) are held
     * where they stand for the head start, the same way, and nobody may strike, while the AI runner, starting beside
     * them, moves away; once it is over both are let go and the AI hunter goes after the runner. Without it the AI hunter fights the
     * runner in sight at once.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "manhuntHeadStart", tickLimit = 1600)
    public void huntersAreHeldThroughTheRunnersHeadStartThenLetGo(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        ServerPlayerEntity[] mira = new ServerPlayerEntity[1];
        Vec3d[] halAt = new Vec3d[1];
        String[] during = {""};
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-20, 1, -20), o.add(30, 8, 30));
                    for (int x = -20; x <= 30; x++) for (int z = -20; z <= 30; z++) {
                        boolean edge = x == -20 || x == 30 || z == -20 || z == 30;
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), edge && y <= 4 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    // Side by side, as players start a hunt: Rue within a hunter's reach of Hal (found in a live hunt, where
                    // a runner that close tried to fight the held hunter through the whole head start).
                    hal.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 4.5, 0, 0);
                    rue.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 6.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    // Staged: a person stands in for a human hunter at an ordinary client.
                    mira[0] = MockPlayers.survival(context, "Mira", new BlockPos(23, 2, 24));
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0], mira[0].getUuid()), 20 * 8) == 1,
                            "Proposed with an eight-second head start");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.joinMatch(mira[0].getCommandSource(), match[0]) == 1, "Mira joined for herself");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    // Two seconds into the head start: both hunters held the same way, nobody may strike.
                    var hunt = f.runtime.matches().getFirst();
                    context.assertTrue(hunt.state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(server.getTicks() - hunt.countdownEnds() >= 40, "Two seconds in");
                    var hal = player(f, players[0]);
                    if (halAt[0] == null) halAt[0] = hal.getPos();
                    var speed = net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED;
                    during[0] = "held " + f.runtime.heldForTest(players[0]) + "/" + f.runtime.heldForTest(mira[0].getUuid())
                            + ", speed " + hal.getAttributeValue(speed) + "/" + mira[0].getAttributeValue(speed)
                            + ", strikes " + f.runtime.matchForbidsStrike(players[0], players[1]) + "/" + f.runtime.matchForbidsStrike(mira[0].getUuid(), players[1])
                            + ", Rue " + player(f, players[1]).getHealth();
                })
                .createAndAdd(() -> {
                    context.assertTrue(during[0].equals("held true/true, speed 0.0/0.0, strikes true/true, Rue 20.0"), "In the head start: " + during[0]);
                    var hunt = f.runtime.matches().getFirst();
                    // Still in it at seven seconds: Hal has not moved off its spot, while Rue has moved away.
                    context.assertTrue(server.getTicks() - hunt.countdownEnds() >= 140, "Seven seconds in");
                    context.assertTrue(f.runtime.heldForTest(players[0]), "Still held at seven seconds");
                    var hal = player(f, players[0]);
                    context.assertTrue(hal.getPos().squaredDistanceTo(halAt[0]) < 0.25, "Hal held where it stood: moved " + String.format("%.2f", Math.sqrt(hal.getPos().squaredDistanceTo(halAt[0]))));
                    context.assertTrue(player(f, players[1]).distanceTo(hal) > 10, "Rue moved off: " + String.format("%.1f", player(f, players[1]).distanceTo(hal)));
                })
                .createAndAdd(() -> {
                    // Over: both let go, strikes allowed across the sides, Hal after Rue.
                    var hal = player(f, players[0]);
                    var speed = net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED;
                    context.assertTrue(!f.runtime.heldForTest(players[0]) && hal.getAttributeValue(speed) > 0 && mira[0].getAttributeValue(speed) > 0, "Both let go");
                    context.assertTrue(!f.runtime.matchForbidsStrike(players[0], players[1]) && !f.runtime.matchForbidsStrike(mira[0].getUuid(), players[1])
                            && f.runtime.matchForbidsStrike(players[0], mira[0].getUuid()), "Strikes across the sides only");
                    context.assertTrue(hal.getPos().squaredDistanceTo(halAt[0]) > 4, "Hal on the move after the head start");
                    server.getPlayerManager().remove(mira[0]);
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Gearing beyond iron (M-02b): a kitted hunter in iron with thirteen diamonds makes a diamond sword and a diamond
     * chestplate, wears the chestplate, and keeps three diamonds back for the diamond pickaxe it does not have. Staged:
     * the kit, the iron armour and the diamonds handed to it after the hunt's fresh start, as if made and mined; the runner
     * held behind a wall.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "manhuntDiamond", tickLimit = 3000)
    public void aKittedHunterMakesDiamondGearAndKeepsDiamondsForAPickaxe(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2800);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -4; x <= 20; x++) for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, 8), Blocks.STONE.getDefaultState());
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Smith allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fox"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Smith") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    var fox = player(f, players[1]);
                    BlockPos a = context.getAbsolutePos(new BlockPos(4, 2, 2)), b = context.getAbsolutePos(new BlockPos(4, 2, 14));
                    smith.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    fox.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    smith.getInventory().clear();
                    fox.getInventory().clear();
                    server.setPvpEnabled(true);
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    // Past the fresh start: the kit and iron armour as though made, diamonds as though mined.
                    var smith = player(f, players[0]);
                    var inventory = smith.getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.DIAMOND, 13));
                    inventory.setStack(2, new ItemStack(Items.STICK, 4));
                    inventory.setStack(3, new ItemStack(Items.OAK_PLANKS, 16));
                    smith.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    smith.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    smith.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Fox held");
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    context.assertTrue(smith.getInventory().count(Items.DIAMOND_SWORD) == 1, "A diamond sword made: " + f.runtime.panelBots(Actor.console()).stream()
                            .filter(b -> b.id().equals(ids[0])).findFirst().orElseThrow().goalProgress());
                    context.assertTrue(smith.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE),
                            "A diamond chestplate worn: " + smith.getEquippedStack(EquipmentSlot.CHEST));
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    context.assertTrue(smith.getInventory().count(Items.DIAMOND) == 3, "Three diamonds kept for a pickaxe: " + smith.getInventory().count(Items.DIAMOND));
                    context.assertTrue(smith.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET), "No diamond helmet from the three kept");
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Blocking pursuit (M-02b): a runner in a tunnel one block wide, a hunter coming along it six blocks behind, shuts
     * the gap behind it with two of its own cobblestone before it runs on, and the hunter loses sight of it. Staged: the
     * runner is handed eight cobblestone after the hunt's fresh start, as if mined on the way, and the hunter is held
     * still so the gap it picks is the runner's own doing.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "manhuntShut", tickLimit = 1600)
    public void aRunnerShutsTheTunnelBehindItWithItsOwnBlocks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Solid stone, a tunnel one wide and two high along x at z 10 from x 0 to 20, then a room to run on into.
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-2, 1, 0), o.add(30, 7, 20));
                    for (int x = -2; x <= 30; x++) for (int z = 0; z <= 20; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 7; y++) {
                            boolean tunnel = z == 10 && x >= 0 && x <= 20 && y <= 3;
                            boolean room = x >= 21 && x <= 29 && z >= 2 && z <= 18 && y <= 6;
                            world.setBlockState(o.add(x, y, z), tunnel || room ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                        }
                    }
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    hal.refreshPositionAndAngles(o.getX() + 1.5, o.getY() + 2, o.getZ() + 10.5, -90, 0);
                    rue.refreshPositionAndAngles(o.getX() + 7.5, o.getY() + 2, o.getZ() + 10.5, -90, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[0]) == 1, "Hal held");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    // Staged: cobblestone in Rue's hotbar, as if mined on the way.
                    player(f, players[1]).getInventory().setStack(3, new ItemStack(Items.COBBLESTONE, 8));
                    context.assertTrue(player(f, players[1]).canSee(player(f, players[0])), "Rue sees Hal down the tunnel");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int shut = -1;
                    for (int x = 1; x <= 20 && shut < 0; x++) {
                        if (world.getBlockState(o.add(x, 2, 10)).isOf(Blocks.COBBLESTONE) && world.getBlockState(o.add(x, 3, 10)).isOf(Blocks.COBBLESTONE)) shut = x;
                    }
                    var rue = player(f, players[1]);
                    var hal = player(f, players[0]);
                    context.assertTrue(shut > 0, "The tunnel shut behind Rue: Rue at " + rue.getBlockPos().subtract(o).toShortString()
                            + ", " + rue.getInventory().count(Items.COBBLESTONE) + " cobblestone left");
                    context.assertTrue(shut > hal.getBlockX() - o.getX() && rue.getInventory().count(Items.COBBLESTONE) == 6,
                            "Between them, with two of its own: at x " + shut + ", " + rue.getInventory().count(Items.COBBLESTONE) + " left");
                    context.assertTrue(!hal.canSee(rue) && rue.getHealth() == 20, "Hal has lost sight of Rue, unhurt");
                    KindredMod.LOGGER.info("Manhunt: Rue shut the tunnel at x {} with two cobblestone, {} blocks ahead of Hal", shut,
                            String.format("%.1f", rue.distanceTo(hal)));
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Staying alive while evading (M-02c): a hurt, hungry runner with bread in its pack runs from a hunter in sight but
     * not toward its home, the bed its spawn is set by, which lies straight away from the hunter; it eats nothing
     * while the hunter is in sight within twenty blocks, and eats once it is clear.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "manhuntEvadeHome", tickLimit = 2400)
    public void aChasedRunnerNeverRunsTowardItsHomeAndEatsOnlyOnceClear(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2200);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        double[] wayHome = {2}, ateAt = {-1};
        int[] breadAtStart = {-1};
        Vec3d[] start = {null};
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 0, 20));
        BlockPos home = o.add(5, 2, 28);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Open ground walled round, wide enough to get clear of the hunter either way across it.
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-30, 1, -20), o.add(40, 8, 34));
                    for (int x = -30; x <= 40; x++) for (int z = -20; z <= 34; z++) {
                        boolean edge = x == -30 || x == 40 || z == -20 || z == 34;
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), edge && y <= 4 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    for (int cx = (o.getX() - 30) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
                        for (int cz = (o.getZ() - 20) >> 4; cz <= (o.getZ() + 34) >> 4; cz++) world.setChunkForced(cx, cz, true);
                    }
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(st -> st.phase() == StoredNativePlayer.Phase.ESTABLISHED && st.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    // Hal to the north, Rue south of it: straight away from Hal is south, where Rue's home lies.
                    hal.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    rue.refreshPositionAndAngles(o.getX() + 5.5, o.getY() + 2, o.getZ() + 8.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    rue.setSpawnPoint(context.getWorld().getRegistryKey(), home, 0, true, false);
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[0]) == 1, "Hal held");
                    // Hurt and hungry once the hunt is on, with bread in its hotbar: nothing to eat before it began.
                    var rue = player(f, players[1]);
                    rue.getInventory().setStack(0, new ItemStack(Items.BREAD, 6));
                    rue.setHealth(12);
                    rue.getHungerManager().setFoodLevel(10);
                    rue.getHungerManager().setSaturationLevel(0);
                    breadAtStart[0] = rue.getInventory().count(Items.BREAD);
                    context.assertTrue(rue.canSee(player(f, players[0])), "Rue sees Hal");
                    start[0] = rue.getPos();
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    double apart = rue.distanceTo(hal);
                    // Where it was when it first ate, taken at that tick: how far off Hal was, and its way off from where
                    // it stood against the way home.
                    if (ateAt[0] < 0 && rue.getInventory().count(Items.BREAD) < breadAtStart[0]) {
                        ateAt[0] = apart;
                        Vec3d moved = rue.getPos().subtract(start[0]).multiply(1, 0, 1);
                        Vec3d toHome = Vec3d.ofBottomCenter(home).subtract(start[0]).multiply(1, 0, 1);
                        wayHome[0] = moved.normalize().dotProduct(toHome.normalize());
                        KindredMod.LOGGER.info("Manhunt evasion home: Rue ran {} blocks at {} degrees from the way home, ate {} blocks off, {} bread left",
                                String.format("%.1f", moved.length()), String.format("%.0f", Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, wayHome[0]))))),
                                String.format("%.1f", apart), rue.getInventory().count(Items.BREAD));
                    }
                    context.assertTrue(ateAt[0] >= 0, "Rue fed: " + String.format("%.1f", apart) + " blocks off, " + rue.getInventory().count(Items.BREAD) + " bread left");
                })
                .createAndAdd(() -> {
                    // Nothing eaten with Hal in sight within twenty blocks: it ate once clear.
                    context.assertTrue(ateAt[0] >= 20, "Ate with Hal " + String.format("%.1f", ateAt[0]) + " blocks off");
                    // It ran across, not home: its way off more than sixty degrees from the way home.
                    context.assertTrue(wayHome[0] < 0.5, "Not toward home: " + String.format("%.2f", wayHome[0]));
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    private static ItemStack compass(ServerPlayerEntity player, UUID match) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) if (HuntCompass.of(inventory.getStack(slot), match)) return inventory.getStack(slot);
        return null;
    }

    private static PlayerRuntime.PanelBot bot(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
