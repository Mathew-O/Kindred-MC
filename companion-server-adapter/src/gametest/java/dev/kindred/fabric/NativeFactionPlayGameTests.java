package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.Personality;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: GameTests of an AI player playing a faction world (M-06i): on its own, with nobody telling it, it
 * founds a faction and claims the land it stands on; and, a fierce player beside an enemy's exposed land that it can
 * see, it walks into that land and captures it by the rule.
 *
 * Technical: M-06i's native fixtures. Each runs one native body through the runtime's own decision step
 * ({@code factionCandidates}); the second drives the decision at the start ({@code factionChooseForTest}) so the
 * sighting and the choice are asserted, then leaves the walk and the capture to the body. The border setting is at its
 * widest, since test positions lie millions of blocks from spawn.
 */
public final class NativeFactionPlayGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionPlayFound", tickLimit = 2400)
    public void anAiPlayerFoundsAFactionAndClaimsWhereItStandsOnItsOwn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        UUID[] ada = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    // Its operator sets it playing; nothing more is asked of it.
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.factionsTickForTest();
                })
                .createAndAdd(() -> {
                    var faction = f.runtime.projected().factions().of(ada[1]);
                    context.assertTrue(faction.isPresent() && faction.get().name().equals("Ada_Hold"), "Ada founded Ada_Hold herself: " + faction);
                })
                .createAndAdd(() -> {
                    var ledgers = f.runtime.projected();
                    String red = ledgers.factions().of(ada[1]).orElseThrow().id().toString();
                    long held = ledgers.claims().values().stream().filter(red::equals).count();
                    context.assertTrue(held >= 1, "Ada claimed land herself");
                    context.assertTrue(ledgers.coins().of(ada[1]) <= 400 * dev.kindred.core.factions.Coins.CENT, "Paid for it: " + ledgers.coins().of(ada[1]));
                })
                .completeIfSuccessful();
    }

    /**
     * Hungry with too little to last, in a faction world with its base to make (bases on): it hunts first, and founds its
     * faction only once it has food to last, as what keeps it alive comes before what the faction world asks of it.
     * Before, the faction world's choices came ahead of its life list: it founded and claimed at once, and in factions2's
     * ten seeds nothing it did kept it fed or in a bed. The ground and herd are the life-first hunger fixture's.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionPlayLife", tickLimit = 20000)
    public void hungryInAFactionWorldItHuntsBeforeItFoundsItsFaction(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 19500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(40, 7, 4), o.add(40, 7, 40)};
        BlockPos home = o.add(16, 7, 16);
        int[] atFound = {-1, -1};
        boolean[] noted = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    world.setTimeOfDay(1000);
                    for (int i = 0; i < 8; i++) {
                        var cow = net.minecraft.entity.EntityType.COW.create(world, null, o.add(8 + 2 * i, 7, 28 + (i % 3) * 2),
                                net.minecraft.entity.SpawnReason.COMMAND, false, false);
                        context.assertTrue(cow != null && world.spawnEntity(cow), "A cow");
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE_SWORD));
                    inventory.setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.BREAD, 1));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(8);
                    player.getHungerManager().setSaturationLevel(0);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    f.runtime.factionsWorldForTest(WIDE);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    boolean founded = f.runtime.projected().factions().of(player.getUuid()).isPresent();
                    if (!founded && f.runtime.lifeNote(f.id).startsWith("Hungry")) noted[0] = true;
                    if (founded && atFound[0] < 0) {
                        atFound[0] = NativeLifeFirstGameTests.stock(player);
                        atFound[1] = player.getStatHandler().getStat(net.minecraft.stat.Stats.KILLED, net.minecraft.entity.EntityType.COW);
                        KindredMod.LOGGER.info("Faction world, life first: founded with {} food in body and pack, {} cows killed", atFound[0], atFound[1]);
                    }
                    context.assertTrue(founded, "Its faction founded: " + f.runtime.lifeNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    context.assertTrue(noted[0], "It said why it waited: hungry");
                    context.assertTrue(atFound[1] >= 1, "It hunted before it founded: " + atFound[1] + " cows killed");
                    context.assertTrue(atFound[0] >= dev.kindred.core.survival.LifeFirst.FULL + dev.kindred.core.survival.LifeFirst.RESERVE,
                            "Food to last before its faction: " + atFound[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * With no goal of its own, in a faction world, it takes up the survival progression (the End portal here, the
     * server's dragon fight being off) and earns its first wooden tool from an empty inventory, from the trees about it:
     * playing the mode asks it to. Before, a player without a goal in a faction world gathered and crafted nothing
     * (factions2b: ten seeds, four game hours, not one break or craft). Its own goal stays unset throughout.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionPlayGoal", tickLimit = 9000)
    public void withNoGoalOfItsOwnInAFactionWorldItTakesUpTheProgressionAndEarnsAWoodenTool(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8800);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(40, 7, 4), o.add(40, 7, 40)};
        BlockPos home = o.add(16, 7, 16);
        var tools = java.util.Set.of(net.minecraft.item.Items.WOODEN_PICKAXE, net.minecraft.item.Items.WOODEN_AXE,
                net.minecraft.item.Items.WOODEN_SWORD, net.minecraft.item.Items.WOODEN_SHOVEL, net.minecraft.item.Items.WOODEN_HOE);
        long[] earnedAt = {-1};
        long[] start = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, trunks);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    player.getInventory().clear();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    var before = f.runtime.goalsForTest(f.id);
                    context.assertTrue(before[0] == dev.kindred.core.survival.SurvivalGoal.NONE && before[1] == dev.kindred.core.survival.SurvivalGoal.NONE,
                            "No goal outside a faction world: " + java.util.Arrays.toString(before));
                    f.runtime.factionsWorldForTest(WIDE);
                    var after = f.runtime.goalsForTest(f.id);
                    context.assertTrue(after[0] == dev.kindred.core.survival.SurvivalGoal.NONE && after[1] == dev.kindred.core.survival.SurvivalGoal.STRONGHOLD,
                            "In a faction world, the progression (the dragon fight off): " + java.util.Arrays.toString(after));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    start[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    var inventory = player.getInventory();
                    boolean holds = false;
                    for (int slot = 0; slot < inventory.size() && !holds; slot++) holds = tools.contains(inventory.getStack(slot).getItem());
                    if (holds && earnedAt[0] < 0) {
                        earnedAt[0] = context.getWorld().getServer().getTicks() - start[0];
                        KindredMod.LOGGER.info("Faction world, standing goal: a wooden tool earned {} ticks in", earnedAt[0]);
                    }
                    context.assertTrue(holds, "A wooden tool earned: " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var goals = f.runtime.goalsForTest(f.id);
                    context.assertTrue(goals[0] == dev.kindred.core.survival.SurvivalGoal.NONE, "Its own goal still unset: " + goals[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Its goal begun in a safe zone, with a tree inside the zone beside it and another outside it: it fells the one
     * outside and puts its crafting table down outside, never working the zone's tree. A safe zone takes no edit, and
     * the goal's choice of what to gather and where to work reads the same rule (TerritoryGuard) as the edit itself.
     * Before, it walked to the zone's logs, could not cut them, and wandered off (the e40a16 seeds in a faction world:
     * its first wooden tool at 14.5 game minutes, against 0.85 without the hub).
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionPlaySafeTrees", tickLimit = 2400)
    public void startingInASafeZoneItFellsATreeOutsideItAndWorksThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos inside = o.add(20, 7, 16), outside = o.add(40, 7, 40);
        BlockPos[] trunks = {inside, outside};
        BlockPos home = o.add(16, 7, 16);
        var zone = new java.util.TreeSet<String>();
        var tools = java.util.Set.of(net.minecraft.item.Items.WOODEN_PICKAXE, net.minecraft.item.Items.WOODEN_AXE,
                net.minecraft.item.Items.WOODEN_SWORD, net.minecraft.item.Items.WOODEN_SHOVEL, net.minecraft.item.Items.WOODEN_HOE);
        // Every goal step it takes about logs in the zone (a walk to them or a cut), watched each tick.
        var zoneSteps = new java.util.ArrayList<String>();
        var logsAt = java.util.regex.Pattern.compile("logs at (-?\\d+), (-?\\d+), (-?\\d+)");
        context.runAtEveryTick(() -> {
            if (zone.isEmpty() || !f.runtime.isReady()) return;
            String step = f.runtime.goalStepForTest(f.id);
            if (step == null) return;
            var m = logsAt.matcher(step);
            if (m.find() && zone.contains(dev.kindred.core.factions.SafeZones.chunkAt(W, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3))))
                    && (zoneSteps.isEmpty() || !zoneSteps.getLast().equals(step))) zoneSteps.add(step);
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, trunks);
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.factionsWorldForTest(WIDE);
                    // The safe zone: every chunk the field's near part touches, the player's start and the near tree among
                    // them; the far tree, sixteen blocks beyond its edge, is in another chunk.
                    for (int x = -12; x <= 24; x += 4) for (int z = -12; z <= 24; z += 4) {
                        var at = o.add(x, 0, z);
                        zone.add(dev.kindred.core.factions.SafeZones.chunkAt(W, at.getX(), at.getZ()));
                    }
                    context.assertTrue(zone.contains(dev.kindred.core.factions.SafeZones.chunkAt(W, inside.getX(), inside.getZ()))
                            && !zone.contains(dev.kindred.core.factions.SafeZones.chunkAt(W, outside.getX(), outside.getZ())), "One tree in the zone, one out");
                    f.runtime.record(dev.kindred.core.factions.SafeZones.zone("fixture", zone, null, server.getTicks(), System.currentTimeMillis()));
                    var player = f.player();
                    player.getInventory().clear();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "The zone written"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    var inventory = player.getInventory();
                    boolean holds = false;
                    for (int slot = 0; slot < inventory.size() && !holds; slot++) holds = tools.contains(inventory.getStack(slot).getItem());
                    context.assertTrue(holds, "A wooden tool earned: " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(zoneSteps.isEmpty(), "No goal step about the zone's logs: " + zoneSteps);
                    for (int y = 0; y < 6; y++) context.assertTrue(world.getBlockState(inside.up(y)).isOf(net.minecraft.block.Blocks.OAK_LOG),
                            "The zone's tree whole: " + inside.up(y).toShortString());
                    for (var cell : BlockPos.iterate(o.add(-12, 6, -12), o.add(24, 12, 24))) {
                        context.assertTrue(!world.getBlockState(cell).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE), "No crafting table in the zone: " + cell.toShortString());
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "factionPlayRaid", tickLimit = 2400)
    public void aFiercePlayerScoutsTheExposedBorderItSawAndCapturesItThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[2];
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        String[] keys = new String[3];
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Open ground east of the start, across the chunks Ada will walk, inside this test's own 80-block field
                    // (a corridor out past its own ground was another test's to build on): a floor, and air above it.
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = 0; x <= 60; x++) for (int z = 0; z <= 14; z++) {
                        world.setBlockState(o.add(x, 1, z), net.minecraft.block.Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    context.assertTrue(f.runtime.socialSetting(f.source, ada[0], null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    // Both founded more than 48 hours ago, past the new-faction shield.
                    f.runtime.factionsWorldForTest(WIDE);
                    ben[0] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, ada[1], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, ben[0].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
                    f.runtime.factions().clockForTest(0);
                })
                .createAndAdd(() -> {
                    var body = server.getPlayerManager().getPlayer(ada[1]);
                    var here = Territory.Chunk.at(W, body.getBlockX(), body.getBlockZ());
                    keys[0] = here.key();
                    keys[1] = here.offset(1, 0).key();
                    keys[2] = here.offset(2, 0).key();
                    var ledgers = f.runtime.projected();
                    String red = ledgers.factions().named("Red").orElseThrow().id().toString();
                    String blue = ledgers.factions().named("Blue").orElseThrow().id().toString();
                    // Red holds the chunk Ada stands in; Blue the two east of it, on power 1: one exposed.
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, ada[1], red, keys[0], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, keys[1], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, keys[2], Map.of()));
                    f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The land written");
                    var move = f.runtime.factionChooseForTest(ada[0]);
                    context.assertTrue(move != null && move.kind() == FactionPlay.Kind.SCOUT && move.target().equals(keys[1]),
                            "Ada goes to the exposed border she sees, not the land beyond it: " + move);
                })
                .createAndAdd(() -> {
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    context.assertTrue(red.equals(f.runtime.projected().claims().get(keys[1])), "Ada walked in and captured it: "
                            + f.runtime.projected().claims().get(keys[1]));
                    context.assertTrue(!red.equals(f.runtime.projected().claims().get(keys[2])), "The chunk beyond is not taken in the same breath");
                })
                .completeIfSuccessful();
    }

    /**
     * A place it can find no way to (boxed in bedrock, the exposed border it sees beyond): the walk there is refused, it
     * waits longer each time, and after three refusals it sets the place aside and stops asking, rather than asking
     * again every moment (the engine run's walks, 2026-11-08).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionPlayNoWay", tickLimit = 3000)
    public void aPlaceItCanFindNoWayToIsAskedThreeTimesThenSetAside(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[2];
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        String[] keys = new String[2];
        long[] third = {-1};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    // Boxed in bedrock where she stands: no way out.
                    var feet = server.getPlayerManager().getPlayer(ada[1]).getBlockPos();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
                        if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                        context.getWorld().setBlockState(feet.add(dx, dy, dz), net.minecraft.block.Blocks.BEDROCK.getDefaultState());
                    }
                    context.assertTrue(f.runtime.socialSetting(f.source, ada[0], null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.factionsWorldForTest(WIDE);
                    ben[0] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, ada[1], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, ben[0].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
                    f.runtime.factions().clockForTest(0);
                    var here = Territory.Chunk.at(W, feet.getX(), feet.getZ());
                    keys[0] = here.key();
                    keys[1] = here.offset(1, 0).key();
                    var ledgers = f.runtime.projected();
                    String red = ledgers.factions().named("Red").orElseThrow().id().toString();
                    String blue = ledgers.factions().named("Blue").orElseThrow().id().toString();
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, ada[1], red, keys[0], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, keys[1], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, here.offset(2, 0).key(), Map.of()));
                    f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    int refusals = f.runtime.factionRefusalsForTest(ada[0], keys[1]);
                    context.assertTrue(refusals >= 3, "Refused three times, waiting longer each time: " + refusals);
                    context.assertTrue(refusals == 3, "Not asked a fourth time: " + refusals);
                    if (third[0] < 0) third[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    context.assertTrue(server.getTicks() - third[0] >= 600, "Six hundred ticks on");
                    context.assertTrue(f.runtime.factionRefusalsForTest(ada[0], keys[1]) == 3, "Still three: set aside, not asked again: "
                            + f.runtime.factionRefusalsForTest(ada[0], keys[1]));
                    var move = f.runtime.factionMoveForTest(ada[0]);
                    context.assertTrue(move == null || !move.target().equals(keys[1]), "Not walking there: " + move);
                    String west = Territory.Chunk.of(keys[0]).offset(-1, 0).key();
                    context.assertTrue(f.runtime.factionRefusalsForTest(ada[0], west) <= 3, "No other place asked more than three times either: "
                            + f.runtime.factionRefusalsForTest(ada[0], west));
                })
                .completeIfSuccessful();
    }

    /**
     * Night in a pocket dug under the stone of a safe zone, its way in open to the side and a stack of cobblestone
     * carried: the way in is not sealed, the zone's cells being no one's to change, and it waits for daylight. In
     * RUNNER 2's factions run on `d5057a4` (factions-e40a16seeds), on 505, Kindred proposed "Seal the shelter at ..."
     * in another faction's land or a safe zone 29 times, each refused by the territory guard, since the night's seal
     * placed its block wherever the shelter's openings were. Staged: a field of stone, all of it one recorded safe zone,
     * a pocket three below its top with a shaft to the sky beside it, and the night held.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "factionPlaySafeShelter", tickLimit = 2400)
    public void atNightInAPocketInASafeZoneItDoesNotSealTheZonesCellsAndWaits(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int field = 12;
        BlockPos feet = o.add(0, 3, 0);
        var zone = new java.util.TreeSet<String>();
        var seals = new java.util.ArrayList<String>();
        int[] watched = new int[1];
        boolean[] waited = new boolean[1];
        context.runAtEveryTick(() -> {
            if (zone.isEmpty() || !f.runtime.isReady()) return;
            String step = f.runtime.goalStepForTest(f.id);
            if (step != null && step.startsWith("Seal the shelter") && (seals.isEmpty() || !seals.getLast().equals(step))) seals.add(step);
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Kindred"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-field, -2, -field), o.add(field, 14, field));
                    FixtureGround.simulated(context, o.add(-field, 0, -field), o.add(field, 0, field));
                    for (int x = -field; x <= field; x++) for (int z = -field; z <= field; z++) for (int y = -2; y <= 14; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 6 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The pocket, roofed by the stone over it; its way in one cell east at feet and head, and a shaft up from there.
                    for (int y = 3; y <= 4; y++) world.setBlockState(o.add(0, y, 0), Blocks.AIR.getDefaultState());
                    for (int y = 3; y <= 6; y++) world.setBlockState(o.add(1, y, 0), Blocks.AIR.getDefaultState());
                    world.setTimeOfDay(14000);
                    f.runtime.factionsWorldForTest(WIDE);
                    for (int x = -field; x <= field; x += 4) for (int z = -field; z <= field; z += 4) {
                        var at = o.add(x, 0, z);
                        zone.add(dev.kindred.core.factions.SafeZones.chunkAt(W, at.getX(), at.getZ()));
                    }
                    context.assertTrue(zone.contains(dev.kindred.core.factions.SafeZones.chunkAt(W, o.getX() + 1, o.getZ())), "The way in is in the zone");
                    f.runtime.record(dev.kindred.core.factions.SafeZones.zone("fixture", zone, null, server.getTicks(), System.currentTimeMillis()));
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "The zone written"))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(feet), "In its pocket"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(14000);
                    String step = f.runtime.goalStepForTest(f.id);
                    waited[0] |= step != null && step.equals("Wait for daylight");
                    context.assertTrue(seals.isEmpty(), "No seal of the zone's cells: " + seals);
                    context.assertTrue(++watched[0] >= 600, "Through the night");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(seals.isEmpty(), "No seal of the zone's cells: " + seals);
                    for (int y = 3; y <= 4; y++) context.assertTrue(world.getBlockState(o.add(1, y, 0)).isAir(), "Its way in left as it was");
                    context.assertTrue(waited[0], "Waiting for daylight: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
