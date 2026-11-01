package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, a player is hit hard by another player and the test
 * checks how it reacts: in a duel it cannot win (iron against diamond) it gets away, in a duel where its kit
 * is better it fights back, and outside any match it never hits back and gets away, each time saying why. A
 * light hit is checked not to be treated as an attack at all.
 *
 * Technical: Hit hard by a player (E-41g, E-41h): a single hit from another player of more than three hearts,
 * after armour, is lethal aggression. In a duel, outclassed (iron against diamond), Hal gets away; with the
 * better kit, it fights back; outside any match it never strikes back, whatever its kit, and gets away; a
 * lesser hit is no such thing. The hit is dealt as Rue's own attack would be, Rue held still, so that what
 * Hal does is all Hal's.
 */
public final class NativeAggressionGameTests implements FabricGameTest {
    /** Twelve blocks apart, so that the duel's own fight has not begun before Rue's hit lands. */
    private static final BlockPos HAL = new BlockPos(16, 2, 10), RUE = new BlockPos(28, 2, 10);

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        return f.source.getServer().getPlayerManager().getPlayer(status.nativeId());
    }

    private static void wear(ServerPlayerEntity player, Item weapon, Item head, Item chest, Item legs, Item feet, ItemStack extra) {
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(weapon));
        inventory.setStack(1, new ItemStack(Items.BREAD, 8));
        inventory.setStack(2, extra);
        inventory.selectedSlot = 0;
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(head));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(chest));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(legs));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(feet));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    private static void iron(ServerPlayerEntity p, Item weapon, ItemStack extra) {
        wear(p, weapon, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, extra);
    }

    private static void diamond(ServerPlayerEntity p) {
        wear(p, Items.DIAMOND_SWORD, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, ItemStack.EMPTY);
    }

    /**
     * Hal and Rue on open ground twelve blocks apart, kitted by {@code kits}; past a newly joined player's three seconds
     * of invulnerability; in a duel when {@code duel}; Rue held. Then Rue's hit of {@code raw} before armour on Hal, and
     * each of {@code checks} in turn. {@code ids} gets Hal's and Rue's
     * ids, {@code rueHealth} Rue's health as it hit.
     */
    private static void scene(TestContext context, NativePaidSkillsGameTests.Fixture f, boolean duel, java.util.function.BiConsumer<ServerPlayerEntity,
            ServerPlayerEntity> kits, float raw, UUID[] ids, float[] rueHealth, Runnable... checks) {
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        int[] settled = {0};
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    ids[0] = f.id;
                    for (var status : statuses) if (!status.id().equals(f.id)) ids[1] = status.id();
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -10; x <= 40; x++) for (int z = -10; z <= 40; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    kits.accept(player(f, ids[0]), player(f, ids[1]));
                    server.setPvpEnabled(true);
                })
                .createAndAdd(() -> context.assertTrue(++settled[0] >= 70, "Past joining's invulnerability"))
                .createAndAdd(() -> {
                    if (!duel) return;
                    context.assertTrue(f.runtime.proposeDuel(f.source, player(f, ids[0]).getUuid(), player(f, ids[1]).getUuid()) == 1, "Duel proposed");
                    context.assertTrue(f.runtime.startMatch(f.source, f.runtime.matches().getFirst().id()) == 1, "Started");
                })
                .createAndAdd(() -> {
                    if (duel) context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    // Placed where the scene needs them, whatever they did meanwhile; Rue held there.
                    var hal = player(f, ids[0]);
                    var rue = player(f, ids[1]);
                    BlockPos a = context.getAbsolutePos(HAL), b = context.getAbsolutePos(RUE);
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, -90, 0);
                    rue.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 90, 0);
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Rue held");
                })
                .createAndAdd(() -> {
                    // Rue's hit, as its own attack: what is left of it through Hal's armour is Hal's to weigh.
                    var hal = player(f, ids[0]);
                    var rue = player(f, ids[1]);
                    rueHealth[0] = rue.getHealth();
                    context.assertTrue(hal.damage(hal.getDamageSources().playerAttack(rue), raw), "Hit");
                });
        for (Runnable check : checks) runner = runner.createAndAdd(check);
        runner.createAndAdd(() -> {
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static String hardHit(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.said(id).stream().filter(line -> line.startsWith("Rue hit me hard, ")).findFirst().orElse("");
    }

    /** In a duel, iron against diamond: a hit of nearly four hearts, and it cannot win, so it gets away. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hitOutclassed", tickLimit = 1600)
    public void outclassedInADuelItGetsAwayFromAHardHit(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        UUID[] ids = new UUID[2];
        float[] rueHealth = new float[1];
        scene(context, f, true, (hal, rue) -> { iron(hal, Items.IRON_SWORD, ItemStack.EMPTY); diamond(rue); }, 12, ids, rueHealth,
                () -> {
                    context.assertTrue("GET_AWAY from Rue".equals(f.runtime.aggression(ids[0])), "Getting away: " + f.runtime.aggression(ids[0]));
                    String said = hardHit(f, ids[0]);
                    context.assertTrue(said.contains("I can't win this") && said.endsWith("I'm getting away."), "Said why: " + f.runtime.said(ids[0]));
                },
                () -> {
                    context.assertTrue(player(f, ids[0]).distanceTo(player(f, ids[1])) > 20, "Away from Rue: " + player(f, ids[0]).distanceTo(player(f, ids[1])));
                    // Away, not after felling Rue (who then comes back at its spawn): the duel runs on.
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING && player(f, ids[1]).isAlive(),
                            "The duel still running: " + f.runtime.matches().getFirst().state());
                });
    }

    /**
     * In a duel, a hit of four and a half hearts from Rue's axe; but Rue wears nothing, and Hal has a diamond sword and
     * golden apples to hold out on: it can win, so it fights back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hitWinning", tickLimit = 1600)
    public void withTheBetterKitInADuelItFightsBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        UUID[] ids = new UUID[2];
        float[] rueHealth = new float[1];
        scene(context, f, true, (hal, rue) -> {
                    iron(hal, Items.DIAMOND_SWORD, new ItemStack(Items.GOLDEN_APPLE, 3));
                    wear(rue, Items.IRON_AXE, Items.AIR, Items.AIR, Items.AIR, Items.AIR, ItemStack.EMPTY);
                }, 13.5f, ids, rueHealth,
                () -> {
                    String said = hardHit(f, ids[0]);
                    context.assertTrue(said.contains("I can win this") && said.endsWith("I'm fighting back."), "Said why: " + f.runtime.said(ids[0]));
                },
                () -> context.assertTrue(player(f, ids[1]).getHealth() < rueHealth[0], "Rue struck back at: " + player(f, ids[1]).getHealth()));
    }

    /** Outside any match it never strikes back, though its kit is the better: it gets away. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hitOutside", tickLimit = 1600)
    public void outsideAnyMatchItNeverStrikesBackAndGetsAway(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        UUID[] ids = new UUID[2];
        float[] rueHealth = new float[1];
        int[] waited = {0};
        scene(context, f, false, (hal, rue) -> { diamond(hal); iron(rue, Items.IRON_SWORD, ItemStack.EMPTY); }, 20, ids, rueHealth,
                () -> context.assertTrue(f.runtime.said(ids[0]).contains("Rue hit me hard, 4 hearts. I'm getting away."), "Said: " + f.runtime.said(ids[0])),
                () -> {
                    context.assertTrue(player(f, ids[1]).getHealth() == rueHealth[0], "Never struck back");
                    context.assertTrue(++waited[0] >= 100, "Five seconds");
                    context.assertTrue(player(f, ids[0]).distanceTo(player(f, ids[1])) > 20, "Away from Rue: " + player(f, ids[0]).distanceTo(player(f, ids[1])));
                });
    }

    /** A hit of a heart and a quarter is no lethal aggression: nothing is made of it. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hitLesser", tickLimit = 1600)
    public void aLesserHitIsNoLethalAggression(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        UUID[] ids = new UUID[2];
        float[] rueHealth = new float[1];
        int[] waited = {0};
        scene(context, f, false, (hal, rue) -> { iron(hal, Items.IRON_SWORD, ItemStack.EMPTY); iron(rue, Items.IRON_SWORD, ItemStack.EMPTY); }, 5, ids, rueHealth,
                () -> {
                    context.assertTrue(f.runtime.aggression(ids[0]) == null && f.runtime.said(ids[0]).stream().noneMatch(line -> line.contains("hit me hard")),
                            "Nothing made of it: " + f.runtime.said(ids[0]));
                    context.assertTrue(++waited[0] >= 60, "Three seconds");
                });
    }
}
