package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Acceptance GameTests for Defensive Combat Execution, Entity Retreat, and Night Shelter (E-07c):
 * 1. Native attack with iron sword damages target, wears weapon durability, and conserves inventory.
 * 2. Native block raises shield in offhand, absorbs frontal mob damage, and lowers shield on completion.
 * 3. Ignited creeper triggers autonomous emergency retreat fleeing the blast radius.
 * 4. Nightfall with nearby hostiles triggers autonomous barricade placement under EditPolicy.
 */
public final class NativeCombatGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "combatBatch", tickLimit = 320)
    public void nativeAttackWithIronSwordDamagesTargetAndConservesDurability(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        ZombieEntity[] targetZombie = new ZombieEntity[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SwordFighter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Equip Iron Sword in hotbar slot 0
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().selectedSlot = 0;

                    // Fill other inventory slots to test item conservation
                    f.player().getInventory().setStack(1, new ItemStack(Items.APPLE, 5));
                    f.player().getInventory().setStack(10, new ItemStack(Items.COBBLESTONE, 16));

                    // Spawn a zombie 2 blocks in front of the player (relative coordinates)
                    ZombieEntity zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 5.0));
                    zombie.setAiDisabled(true);
                    zombie.setHealth(20.0f);
                    zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    targetZombie[0] = zombie;

                    // Pre-charge attack cooldown
                    f.player().resetLastAttackedTicks();
                    for (int i = 0; i < 25; i++) f.player().tick();

                    // Execute native attack
                    int res = f.runtime.nativeAttack(f.source, f.id, zombie.getUuid(), 0);
                    context.assertTrue(res == 1, "Attack requested");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_attack"),
                            "Native attack completes under paid activity session");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_attack"), "Durable action recorded and verified");

                    // Verify target took authentic weapon damage
                    context.assertTrue(targetZombie[0].getHealth() < 20.0f,
                            "Zombie took damage: " + targetZombie[0].getHealth());

                    // Verify weapon durability and exact inventory conservation
                    ItemStack sword = f.player().getInventory().getStack(0);
                    context.assertTrue(sword.isOf(Items.IRON_SWORD), "Held item is still iron sword");
                    context.assertTrue(sword.getDamage() == 1, "Sword durability damaged by exactly 1 on hit: " + sword.getDamage());
                    context.assertTrue(f.player().getInventory().getStack(1).getCount() == 5, "Apples in slot 1 conserved");
                    context.assertTrue(f.player().getInventory().getStack(10).getCount() == 16, "Cobblestone in slot 10 conserved");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "combatBatch", tickLimit = 320)
    public void nativeBlockRaisesShieldAndProtectsPlayer(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ShieldBearer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Equip Shield in offhand
                    f.player().equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    context.assertTrue(!f.player().isUsingItem(), "Shield initially not active");

                    // Face south towards target
                    f.player().setYaw(0.0f);
                    f.player().setPitch(0.0f);

                    // Start blocking for 2 ticks
                    int res = f.runtime.nativeBlock(f.source, f.id, 2);
                    context.assertTrue(res == 1, "Block requested");
                })
                .createAndAdd(() -> {
                    // Verify player is using shield item while active
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (activity.phase().equals("IDLE")) return;
                    context.assertTrue(f.player().isUsingItem(), "Player is using shield item");

                    // Spawn a mob in front and deal damage from front to test absorption
                    ZombieEntity zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 5.0));
                    zombie.setAiDisabled(true);

                    // Deal damage from front
                    f.player().damage(f.player().getDamageSources().mobAttack(zombie), 4.0f);
                    // Full health preserved by shield block
                    context.assertTrue(f.player().getHealth() == 20.0f, "Frontal damage fully absorbed by shield: " + f.player().getHealth());
                    // Shield durability damaged by absorbed hit
                    context.assertTrue(f.player().getOffHandStack().getDamage() > 0,
                            "Shield durability reduced by absorbed attack: " + f.player().getOffHandStack().getDamage());
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_block"),
                            "Native block completed after duration");
                    context.assertTrue(!f.player().isUsingItem(), "Shield lowered after block completion");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "combatBatch", tickLimit = 360)
    public void creeperThreatTriggersAutonomousEmergencyRetreat(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Vec3d[] initialPos = new Vec3d[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "CreeperSurvivor"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    initialPos[0] = f.player().getPos();

                    // Spawn an ignited creeper close to the player
                    CreeperEntity creeper = context.spawnMob(EntityType.CREEPER, new Vec3d(3.5, 2.0, 5.5));
                    creeper.ignite();
                    creeper.setAiDisabled(true);

                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_retreat"),
                            "Autonomous retreat dispatched and completed in response to creeper threat");
                    Vec3d currentPos = f.player().getPos();
                    context.assertTrue(currentPos.squaredDistanceTo(initialPos[0]) > 0.2,
                            "Player moved away from initial position fleeing creeper: " + currentPos.squaredDistanceTo(initialPos[0]));

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightCombatBatch", tickLimit = 360)
    public void autonomousBarricadePlacementAtNight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        BlockPos max = context.getAbsolutePos(new BlockPos(7, 7, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "NightDefender"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Set up work area covering the test structure
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Area setup acknowledged"))
                .createAndAdd(() -> {
                    // Give cobblestone in hotbar slot 0
                    f.player().getInventory().setStack(0, new ItemStack(Blocks.COBBLESTONE, 4));

                    // Set time to night
                    context.getWorld().setTimeOfDay(14000);

                    // Spawn hostile zombie 3 blocks south
                    ZombieEntity zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 6.0));
                    zombie.setAiDisabled(true);

                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    // Other tests in the batch set the shared clock; keep this one's night until the barricade is up.
                    context.getWorld().setTimeOfDay(14000);
                    boolean placed = false;
                    for (int x = 0; x <= 7 && !placed; x++) for (int z = 0; z <= 7 && !placed; z++) {
                        placed = context.getBlockState(new BlockPos(x, 2, z)).isOf(Blocks.COBBLESTONE);
                    }
                    context.assertTrue(placed, "Autonomous night barricade placement completed");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.COBBLESTONE) == 3,
                            "Exactly one cobblestone consumed for barricade");

                    context.getWorld().setTimeOfDay(1000L);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The weapon a goal's fight strikes with is the one that deals the most (E-39e): an iron sword's crits (9 every
     * 12.5 ticks) over a diamond axe's (13.5 every 20) and a wooden sword; but with the zombie down to 12 health, the
     * axe's one crit finishes it where the sword's would not. Stacks in the main inventory are not swapped in mid-fight.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "combatWeapon", tickLimit = 100)
    public void theFightsWeaponIsTheOneThatDealsTheMost(TestContext context) {
        var player = context.createMockCreativeServerPlayerInWorld();
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.DIAMOND_AXE));
        inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
        inventory.setStack(2, new ItemStack(Items.WOODEN_SWORD));
        inventory.setStack(20, new ItemStack(Items.NETHERITE_SWORD));
        ZombieEntity zombie = context.spawnEntity(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        zombie.setAiDisabled(true);
        var full = PortalRunCampaign.strike(player, zombie, "Fight zombie");
        context.assertTrue("1".equals(full.args().get("slot")), "The iron sword over the fight: " + full.args());
        zombie.setHealth(12);
        var finish = PortalRunCampaign.strike(player, zombie, "Fight zombie");
        context.assertTrue("0".equals(finish.args().get("slot")), "The axe's one crit to finish it: " + finish.args());
        inventory.setStack(1, ItemStack.EMPTY);
        inventory.setStack(0, ItemStack.EMPTY);
        var wooden = PortalRunCampaign.strike(player, zombie, "Fight zombie");
        context.assertTrue("2".equals(wooden.args().get("slot")), "What the hotbar holds, not the sword in the pack: " + wooden.args());
        zombie.discard();
        context.complete();
    }
}
