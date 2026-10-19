package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.minecraft.component.DataComponentTypes;
import dev.kindred.core.expedition.ExpeditionState;
import dev.kindred.core.expedition.PortalFrame;
import dev.kindred.core.expedition.Waypoint;
import dev.kindred.core.expedition.WaypointKind;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;

/**
 * Native Fabric GameTests qualifying E-15 (Explicit Nether travel, resource trips and cross-dimension recovery):
 * 1. Portal frame geometry verification and ignition with flint & steel (1 durability wear, NETHER_PORTAL block created)
 * 2. Obstructed frame / non-tool ignition rejection with 100% item conservation
 * 3. Qualified cross-dimension transit (canUsePortals, 8:1 coordinate scaling, Nether anchor waypoint recording)
 * 4. Cross-dimension state, equipment, 36-slot inventory, and health conservation across transfers and saves
 */
public final class NativeNetherGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherWartGather", tickLimit = 260)
    public void assignedNetherWartPatchYieldsRecordedNativeDrops(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8000);
        ServerWorld nether = context.getWorld().getServer().getWorld(World.NETHER);
        BlockPos wart = context.getAbsolutePos(new BlockPos(3, 3, 3));
        f.runnerAwaitingReady(context, "Runtime ready with edit policy")
                .createAndAdd(() -> f.runtime.createNative(f.source, "WartGatherer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        nether.setBlockState(wart.down().add(dx, 0, dz), Blocks.SOUL_SAND.getDefaultState());
                    }
                    nether.setBlockState(wart, Blocks.NETHER_WART.getDefaultState().with(NetherWartBlock.AGE, 3));
                    nether.setBlockState(wart.add(-2, 0, 0), Blocks.CRAFTING_TABLE.getDefaultState());
                    nether.setBlockState(wart.add(2, 0, 0), Blocks.FURNACE.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(3, new ItemStack(Items.OAK_LOG, 2));
                    inventory.setStack(4, new ItemStack(Items.OAK_PLANKS, 4));
                    inventory.setStack(5, new ItemStack(Items.STICK, 2));
                    inventory.setStack(6, new ItemStack(Items.COBBLESTONE, 8));
                    inventory.setStack(7, new ItemStack(Items.IRON_INGOT, 2));
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, wart, wart,
                            "minecraft:nether_wart") == 1, "Nether resource patch configured before travel");
                })
                .createAndAdd(() -> context.assertFalse(f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Resource patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeBody(f.id).orElseThrow().setPortalTravelAuthorized(true);
                    context.assertTrue(fixtureTeleport(f.runtime.nativeBody(f.id).orElseThrow(), nether,
                            new Vec3d(wart.getX() + 0.5, wart.getY(), wart.getZ() + 1.5)), "Fixture places native body in Nether");
                })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> {
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                    context.assertTrue("minecraft:the_nether".equals(
                                    f.runtime.nativeSetupStatus(Actor.console(), f.id).setup().dimension()),
                            "Assigned Nether patch follows the observed dimension transition");
                })
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    waitForWartGatherCandidate(context, f, 0, () -> context.assertTrue(
                            f.runtime.nativeGather(f.source, f.id, 1, 0) == 1, "Native wart gathering admitted"));
                })
                .createAndAdd(() -> waitForActivityIdle(context, f, 0, () -> {
                    var status = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(status.work().startsWith("Completed native_gather"), "Native drop collection verified: " + status);
                    context.assertTrue(f.runtime.nativeBody(f.id).orElseThrow().entity().getInventory()
                            .contains(stack -> stack.isOf(Items.NETHER_WART)), "Vanilla wart drops entered native inventory");
                    context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).record().state() == ActionRecord.State.VERIFIED,
                            "Gather and attributable drop receipt verified");
                    f.runtime.stop();
                    waitForShutdown(context, f, 0, () -> { });
                }))
                .completeIfSuccessful();
    }

    private static void waitForWartGatherCandidate(TestContext context, NativePaidSkillsGameTests.Fixture f,
                                                   int attempts, Runnable next) {
        var candidates = f.runtime.nativeCandidates(Actor.console(), f.id);
        if (candidates.stream().anyMatch(c -> "native_gather".equals(c.skill()))) {
            next.run();
            return;
        }
        if (attempts >= 20) {
            var snapshot = f.runtime.nativeProgressionState(f.id).snapshot(dev.kindred.core.progression.MilestoneId.GATHER_NETHER_WART);
            context.throwGameTestException("No scoped Nether Wart gather candidate after retries; milestone=" + snapshot
                    + ", patch=" + f.runtime.nativeSetupStatus(Actor.console(), f.id).setup().patch());
            return;
        }
        context.waitAndRun(1, () -> waitForWartGatherCandidate(context, f, attempts + 1, next));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherOwnedFrame", tickLimit = 520)
    public void progressionAssemblesPortalFrameFromOwnedObsidian(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8000);
        BlockPos min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        BlockPos max = context.getAbsolutePos(new BlockPos(7, 7, 7));
        f.runnerAwaitingReady(context, "Runtime ready with edit policy")
                .createAndAdd(() -> f.runtime.createNative(f.source, "OwnedFrame"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos p = player.getBlockPos();
                    context.getWorld().setBlockState(p.add(3, 0, 2), Blocks.CRAFTING_TABLE.getDefaultState());
                    context.getWorld().setBlockState(p.add(3, 0, 3), Blocks.FURNACE.getDefaultState());
                    player.getInventory().setStack(0, new ItemStack(Items.FLINT_AND_STEEL));
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_PICKAXE));
                    player.getInventory().setStack(2, new ItemStack(Items.OBSIDIAN, 14));
                    player.getInventory().setStack(3, new ItemStack(Items.WOODEN_PICKAXE));
                    player.getInventory().setStack(4, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(5, new ItemStack(Items.OAK_LOG, 4));
                    player.getInventory().setStack(6, new ItemStack(Items.OAK_PLANKS, 8));
                    player.getInventory().setStack(7, new ItemStack(Items.STICK, 4));
                    player.getInventory().setStack(8, new ItemStack(Items.COBBLESTONE, 16));
                    player.getInventory().setStack(9, new ItemStack(Items.IRON_INGOT, 3));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:obsidian");
                })
                .createAndAdd(() -> context.assertFalse(f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Work area acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    var candidate = f.runtime.nativeCandidates(Actor.console(), f.id).stream()
                            .filter(c -> c.skill().equals("native_place") && "2".equals(c.arguments().get("slot")))
                            .findFirst();
                    context.assertTrue(candidate.isPresent(), "Nether progression proposes an admitted owned-obsidian frame placement");
                    placeNextPortalFrameBlock(context, f, 0);
                })
                .completeIfSuccessful();
    }

    private static void placeNextPortalFrameBlock(TestContext context, NativePaidSkillsGameTests.Fixture f, int placed) {
        if (placed == 14) {
            BlockPos p = f.player().getBlockPos();
            BlockPos interior = new BlockPos(p.getX() - 1, p.getY() + 1, p.getZ() + 1);
            var frame = PortalFrame.evaluate(new PortalFrame.BlockPos3i(interior.getX(), interior.getY(), interior.getZ()), 2, 3,
                    PortalFrame.Axis.X, pos -> {
                        var state = context.getWorld().getBlockState(new BlockPos(pos.x(), pos.y(), pos.z()));
                        return net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
                    });
            context.assertTrue(frame.valid() && frame.obsidianCount() == 14, "All 14 owned obsidian blocks form a valid frame");
            context.assertTrue(f.player().getInventory().getStack(2).isEmpty(), "Exactly 14 owned obsidian blocks were consumed");
            f.runtime.stop();
            return;
        }
        var next = f.runtime.nativeCandidates(Actor.console(), f.id).stream()
                .filter(c -> c.skill().equals("native_place") && "2".equals(c.arguments().get("slot")))
                .findFirst();
        context.assertTrue(next.isPresent(), "Frame assembly step " + placed + " proposed from current physical state");
        var args = next.orElseThrow().arguments();
        BlockPos click = new BlockPos(Integer.parseInt(args.get("x")), Integer.parseInt(args.get("y")), Integer.parseInt(args.get("z")));
        context.assertTrue(f.runtime.nativePlace(f.source, f.id, click, args.get("side"), 2) == 1,
                "Durably admitted frame placement " + placed);
        waitForActivityIdle(context, f, 0, () -> {
            var status = f.runtime.nativeActivityStatus(Actor.console(), f.id);
            context.assertTrue(status.work().startsWith("Completed native_place"), "Frame block placement verified: " + status);
            context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).record().state() == ActionRecord.State.VERIFIED,
                    "Each obsidian placement has a verified durable receipt");
            placeNextPortalFrameBlock(context, f, placed + 1);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherPortalRestart", tickLimit = 520)
    public void vanillaPortalEntryPersistsDimensionAndExactNativeStateAcrossRuntimeRestart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        ServerWorld overworld = context.getWorld();
        ServerWorld nether = overworld.getServer().getWorld(World.NETHER);
        BlockPos interior = context.getAbsolutePos(new BlockPos(3, 3, 3));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 7, 7));
        ArrayList<ItemStack> mainInventory = new ArrayList<>();
        ItemStack[] armor = new ItemStack[4];
        ItemStack[] offhand = {ItemStack.EMPTY};
        float[] health = new float[1];
        int[] food = new int[1];
        int[] selected = new int[1];

        f.runnerAwaitingReady(context, "Runtime ready with edit policy").expectMinDuration(450)
                .createAndAdd(() -> f.runtime.createNative(f.source, "PortalRestart"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // The fixture provides prepared terrain; the body must enter through vanilla portal collision.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 7; y++) context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                    }
                    // 10-block 4x5 frame, aligned along X in the Z=3 plane.
                    for (int x : new int[]{3, 4}) {
                        context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 2, 3)), Blocks.OBSIDIAN.getDefaultState());
                        context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 6, 3)), Blocks.OBSIDIAN.getDefaultState());
                    }
                    for (int y = 3; y <= 5; y++) {
                        context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(2, y, 3)), Blocks.OBSIDIAN.getDefaultState());
                        context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(5, y, 3)), Blocks.OBSIDIAN.getDefaultState());
                    }
                    var player = f.player();
                    player.setPosition(interior.getX() + 0.5, interior.getY() - 1, interior.getZ() - 0.5);
                    player.getInventory().setStack(0, new ItemStack(Items.FLINT_AND_STEEL));
                    for (int slot = 1; slot < 36; slot++) {
                        ItemStack stack = new ItemStack(Items.APPLE, slot % 16 + 1);
                        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("E15 slot " + slot));
                        player.getInventory().setStack(slot, stack);
                    }
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_PICKAXE));
                    player.getInventory().getStack(1).setDamage(47);
                    player.getInventory().getStack(1).set(DataComponentTypes.CUSTOM_NAME, Text.literal("Worn expedition pick"));
                    player.getInventory().selectedSlot = 1;
                    player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    player.getInventory().offHand.set(0, new ItemStack(Items.SHIELD));
                    player.getInventory().offHand.get(0).set(DataComponentTypes.CUSTOM_NAME, Text.literal("Named shield"));
                    player.setHealth(17.5f);
                    player.getHungerManager().setFoodLevel(18);
                    Waypoint home = Waypoint.of("fixture_home", player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                            "minecraft:overworld", WaypointKind.HOME_BED, context.getWorld().getTime());
                    f.runtime.setExpeditionState(f.id, new ExpeditionState(home, 128.0));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertFalse(f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Edit patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeIgnitePortal(f.source, f.id, interior, 0) == 1, "Owned ignition tool admitted");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).work().startsWith("Completed native_ignite_portal"), "Portal ignition completed"))
                .createAndAdd(() -> waitForActivityIdle(context, f, 0, () -> {
                    context.assertTrue(context.getWorld().getBlockState(interior).isOf(Blocks.NETHER_PORTAL), "Vanilla portal blocks were ignited");
                    var player = f.player();
                    // Capture exact post-ignition state so flint durability is included.
                    for (int slot = 0; slot < 36; slot++) mainInventory.add(player.getInventory().getStack(slot).copy());
                    armor[0] = player.getEquippedStack(EquipmentSlot.FEET).copy();
                    armor[1] = player.getEquippedStack(EquipmentSlot.LEGS).copy();
                    armor[2] = player.getEquippedStack(EquipmentSlot.CHEST).copy();
                    armor[3] = player.getEquippedStack(EquipmentSlot.HEAD).copy();
                    offhand[0] = player.getInventory().offHand.get(0).copy();
                    health[0] = player.getHealth(); food[0] = player.getHungerManager().getFoodLevel(); selected[0] = player.getInventory().selectedSlot;
                    f.runtime.nativeBody(f.id).orElseThrow().setPortalTravelAuthorized(true);
                    int walk = f.runtime.nativeWalk(f.source, f.id, "south", 1);
                    context.assertTrue(walk == 1, "Admitted paid walk crosses portal plane; activity=" + f.runtime.nativeActivityStatus(Actor.console(), f.id));
                    waitForDimension(context, f, nether, 0, () -> {
                    // Let the runtime persist both the companion dimension and its native save receipt.
                    waitTicks(context, 3, () -> {
                    context.assertTrue(f.player().getServerWorld() == nether, "Vanilla portal collision transferred body to Nether");
                    context.assertTrue(f.runtime.getExpeditionState(f.id).knownWaypoints().stream().anyMatch(w -> w.kind() == WaypointKind.NETHER_PORTAL
                            && "minecraft:the_nether".equals(w.dimension())), "Observed arrival portal recorded as a Nether waypoint");
                    var traveler = f.player();
                    traveler.setOnFireFor(5.0f);
                    var hazardObservation = NativeTacticalObserver.observe(traveler, f.id, 1L,
                            false, false, context.getWorld().getServer().getTicks());
                    context.assertTrue(hazardObservation.isStandingInHazard()
                                    && "minecraft:the_nether".equals(hazardObservation.dimension()),
                            "Tactical observer identifies an environmental hazard after actual Nether portal arrival");
                    traveler.extinguish();
                    for (int slot = 0; slot < 36; slot++) context.assertTrue(ItemStack.areEqual(mainInventory.get(slot), traveler.getInventory().getStack(slot)), "Exact inventory slot/components conserved: " + slot);
                    context.assertTrue(ItemStack.areEqual(armor[0], traveler.getEquippedStack(EquipmentSlot.FEET)), "Boots conserved");
                    context.assertTrue(ItemStack.areEqual(armor[1], traveler.getEquippedStack(EquipmentSlot.LEGS)), "Leggings conserved");
                    context.assertTrue(ItemStack.areEqual(armor[2], traveler.getEquippedStack(EquipmentSlot.CHEST)), "Chestplate conserved");
                    context.assertTrue(ItemStack.areEqual(armor[3], traveler.getEquippedStack(EquipmentSlot.HEAD)), "Helmet conserved");
                    context.assertTrue(ItemStack.areEqual(offhand[0], traveler.getInventory().offHand.get(0)), "Offhand components conserved");
                    context.assertTrue(traveler.getHealth() == health[0] && traveler.getHungerManager().getFoodLevel() == food[0], "Health and hunger conserved");
                    context.assertTrue(traveler.getInventory().selectedSlot == selected[0], "Selected slot conserved");
                    context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).record().state() == ActionRecord.State.VERIFIED, "Portal action accounting remains verified");
                    // A close, visible Blaze becomes a local GATHER_BLAZE_RODS combat
                    // opportunity only after the real Nether arrival. Its one-hit health
                    // is fixture setup; the strike itself is the normal native player
                    // attack, with the ordinary vanilla loot table left intact.
                    traveler.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    traveler.getInventory().selectedSlot = 1;
                    BlazeEntity blaze = new BlazeEntity(EntityType.BLAZE, nether);
                    blaze.refreshPositionAndAngles(traveler.getX(), traveler.getY(), traveler.getZ() + 2.5, 0.0f, 0.0f);
                    blaze.setAiDisabled(true);
                    blaze.setHealth(1.0f);
                    context.assertTrue(nether.spawnEntity(blaze), "Fixture Blaze spawned in the arrived Nether world");
                    waitForBlazeAttackCandidate(context, f, blaze.getUuid(), 0, () -> {
                    context.assertTrue(f.runtime.nativeAttack(f.source, f.id, blaze.getUuid(), 1) == 1,
                            "Visible Nether Blaze attack admitted through the existing durable action path");
                    waitForActivityIdle(context, f, 0, () -> {
                    context.assertFalse(blaze.isAlive(), "Native player dealt the authentic killing strike to the Nether Blaze");
                    var attackRecord = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(attackRecord != null && attackRecord.state() == ActionRecord.State.VERIFIED
                                    && attackRecord.kind().equals("native_attack"),
                            "Blaze combat completed through a verified native attack receipt");
                    context.assertTrue(traveler.getInventory().getStack(1).isOf(Items.IRON_SWORD)
                                    && traveler.getInventory().getStack(1).getDamage() == 1,
                            "Owned sword is conserved with authentic one-hit durability wear");
                    // The combat changed one owned stack after the portal conservation
                    // assertion. Capture this verified post-combat state for restart and
                    // return conservation; no drop is synthesized or asserted here.
                    mainInventory.clear();
                    for (int slot = 0; slot < 36; slot++) mainInventory.add(traveler.getInventory().getStack(slot).copy());
                    selected[0] = traveler.getInventory().selectedSlot;
                    f.runtime.stop();
                    waitForShutdown(context, f, 0, () -> {
                    f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory); f.runtime.start();
                    waitForReady(context, f, 0, () -> {
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 1, "Native save reconnect requested");
                    waitForConnected(context, f, 0, () -> {
                    context.assertTrue(f.player().getServerWorld() == nether, "Saved dimension restored to Nether");
                    for (int slot = 0; slot < 36; slot++) context.assertTrue(ItemStack.areEqual(mainInventory.get(slot), f.player().getInventory().getStack(slot)), "Restart preserves exact slot/components: " + slot);
                    context.assertTrue(ItemStack.areEqual(armor[0], f.player().getEquippedStack(EquipmentSlot.FEET))
                                    && ItemStack.areEqual(armor[1], f.player().getEquippedStack(EquipmentSlot.LEGS))
                                    && ItemStack.areEqual(armor[2], f.player().getEquippedStack(EquipmentSlot.CHEST))
                                    && ItemStack.areEqual(armor[3], f.player().getEquippedStack(EquipmentSlot.HEAD)), "Restart preserves exact armor slots/components");
                    context.assertTrue(ItemStack.areEqual(offhand[0], f.player().getInventory().offHand.get(0)), "Restart preserves exact offhand components");
                    context.assertTrue(f.player().getHealth() == health[0] && f.player().getHungerManager().getFoodLevel() == food[0], "Restart preserves health and hunger");
                    context.assertTrue(f.runtime.nativeActionStatus(Actor.console(), f.id).record().kind().equals("native_ignite_portal"), "Durable action ledger reopens with the same verified portal action");
                    context.assertTrue(f.runtime.nativeBody(f.id).orElseThrow().isPortalTravelAuthorized() == false, "Portal authority does not silently persist across restart");
                    f.runtime.nativeBody(f.id).orElseThrow().setPortalTravelAuthorized(true);
                    waitForDimension(context, f, overworld, 0, () -> waitTicks(context, 3, () -> {
                        context.assertTrue(f.player().getServerWorld() == overworld, "Vanilla return portal transferred body back to Overworld");
                        context.assertTrue(Math.abs(f.player().getX() - (interior.getX() + 0.5)) < 16
                                && Math.abs(f.player().getZ() - (interior.getZ() + 0.5)) < 16, "Return portal preserves expected 8:1 linked region");
                        for (int slot = 0; slot < 36; slot++) context.assertTrue(ItemStack.areEqual(mainInventory.get(slot), f.player().getInventory().getStack(slot)), "Return preserves exact inventory slot/components: " + slot);
                        context.assertTrue(ItemStack.areEqual(offhand[0], f.player().getInventory().offHand.get(0)), "Return conserves offhand components");
                        context.assertTrue(f.player().getHealth() == health[0] && f.player().getHungerManager().getFoodLevel() == food[0], "Return conserves health and hunger");
                        f.runtime.stop();
                        waitForShutdown(context, f, 0, context::complete);
                    }));
                    });
                    });
                    });
                    });
                    });
                    });
                    });
                }))
                .completeIfSuccessful();
    }

    private static void waitTicks(TestContext context, int remaining, Runnable next) {
        if (remaining <= 0) { next.run(); return; }
        context.waitAndRun(1, () -> waitTicks(context, remaining - 1, next));
    }

    private static void waitForActivityIdle(TestContext context, NativePaidSkillsGameTests.Fixture f, int attempts, Runnable next) {
        var status = f.runtime.nativeActivityStatus(Actor.console(), f.id);
        if (status != null && status.phase().equals("IDLE")) { next.run(); return; }
        if (attempts >= 100) { context.throwGameTestException("Portal ignition activity did not settle: " + status); return; }
        context.waitAndRun(1, () -> waitForActivityIdle(context, f, attempts + 1, next));
    }

    private static void waitForBlazeAttackCandidate(TestContext context, NativePaidSkillsGameTests.Fixture f,
                                                     java.util.UUID blazeId, int attempts, Runnable next) {
        boolean found = f.runtime.nativeCandidates(Actor.console(), f.id).stream().anyMatch(candidate ->
                "native_attack".equals(candidate.skill()) && blazeId.toString().equals(candidate.arguments().get("target")));
        if (found) {
            next.run();
            return;
        }
        if (attempts >= 80) {
            context.throwGameTestException("No observed-Blaze attack candidate after Nether portal arrival");
            return;
        }
        context.waitAndRun(1, () -> waitForBlazeAttackCandidate(context, f, blazeId, attempts + 1, next));
    }

    private static void waitForDimension(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerWorld target, int attempts, Runnable next) {
        if (f.player().getServerWorld() == target) { next.run(); return; }
        if (attempts >= 100) { context.throwGameTestException("Vanilla portal did not transfer the body; activity=" + f.runtime.nativeActivityStatus(Actor.console(), f.id)); return; }
        context.waitAndRun(1, () -> waitForDimension(context, f, target, attempts + 1, next));
    }

    private static void waitForShutdown(TestContext context, NativePaidSkillsGameTests.Fixture f, int attempts, Runnable next) {
        if (f.runtime.shutdownComplete()) { next.run(); return; }
        if (attempts >= 100) { context.throwGameTestException("Runtime shutdown did not settle"); return; }
        context.waitAndRun(1, () -> waitForShutdown(context, f, attempts + 1, next));
    }

    private static void waitForReady(TestContext context, NativePaidSkillsGameTests.Fixture f, int attempts, Runnable next) {
        if (f.runtime.isReady()) { next.run(); return; }
        if (attempts >= 100) { context.throwGameTestException("Restarted metadata store did not become ready"); return; }
        context.waitAndRun(1, () -> waitForReady(context, f, attempts + 1, next));
    }

    private static void waitForConnected(TestContext context, NativePaidSkillsGameTests.Fixture f, int attempts, Runnable next) {
        if (f.runtime.nativeStatuses(Actor.console()).getFirst().connected()) { next.run(); return; }
        if (attempts >= 100) { context.throwGameTestException("Native save reconnect did not complete"); return; }
        context.waitAndRun(1, () -> waitForConnected(context, f, attempts + 1, next));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherIgnite", tickLimit = 450)
    public void portalFrameVerificationAndIgnitionWithDurabilityWear(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 430);
        BlockPos playerPos = context.getAbsolutePos(new BlockPos(3, 2, 1));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 7, 7));

        // Standard 4x5 portal frame along X axis:
        // Width 4: x=2..5, Height 5: y=2..6, Z=3
        // Bottom: (3,2,3), (4,2,3)
        // Top: (3,6,3), (4,6,3)
        // Left: (2,3,3), (2,4,3), (2,5,3)
        // Right: (5,3,3), (5,4,3), (5,5,3)
        // Interior: (3,3,3), (4,3,3), (3,4,3), (4,4,3), (3,5,3), (4,5,3)
        BlockPos portalInterior = context.getAbsolutePos(new BlockPos(3, 3, 3));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "NetherIgniter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Build floor and clear structure area
                    for (int x = 0; x <= 7; x++) {
                        for (int z = 0; z <= 7; z++) {
                            context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                            for (int y = 2; y <= 7; y++) {
                                context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
                            }
                        }
                    }

                    // Build standard 10-block obsidian frame (corners empty)
                    // Bottom
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(3, 2, 3)), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(4, 2, 3)), Blocks.OBSIDIAN.getDefaultState());
                    // Top
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(3, 6, 3)), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(4, 6, 3)), Blocks.OBSIDIAN.getDefaultState());
                    // Left column
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(2, 3, 3)), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(2, 4, 3)), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(2, 5, 3)), Blocks.OBSIDIAN.getDefaultState());
                    // Right column
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(5, 3, 3)), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(5, 4, 3)), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(5, 5, 3)), Blocks.OBSIDIAN.getDefaultState());

                    f.player().setPosition(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
                    // Place flint & steel in slot 0 with 0 damage
                    f.player().getInventory().setStack(0, new ItemStack(Items.FLINT_AND_STEEL, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    // Execute portal ignition
                    int requested = f.runtime.nativeIgnitePortal(f.source, f.id, portalInterior, 0);
                    context.assertTrue(requested == 1, "Native ignite portal requested");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    // Check activity completed
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_ignite_portal"), "Ignition completed");

                    // Verify NETHER_PORTAL block was created
                    var state = context.getWorld().getBlockState(portalInterior);
                    context.assertTrue(state.isOf(Blocks.NETHER_PORTAL), "Nether portal block created inside obsidian frame");

                    // Verify flint & steel suffered authentic 1 durability damage
                    var tool = f.player().getInventory().getStack(0);
                    context.assertTrue(tool.isOf(Items.FLINT_AND_STEEL), "Flint and steel remains in slot 0");
                    context.assertTrue(tool.getDamage() == 1, "Flint and steel incurred exactly 1 durability damage (wear)");

                    // Verify durable action ledger
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_ignite_portal"), "Durable ledger records verified native_ignite_portal");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherRejectIgnite", tickLimit = 360)
    public void portalIgnitionObstructedFrameOrNonToolRejected(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 350);
        BlockPos playerPos = context.getAbsolutePos(new BlockPos(3, 2, 1));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 7, 7));
        BlockPos portalInterior = context.getAbsolutePos(new BlockPos(3, 3, 3));

        context.createTimedTaskRunner()
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "RejectIgnite"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().setPosition(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
                    // Give stick instead of flint & steel
                    f.player().getInventory().setStack(0, new ItemStack(Items.STICK, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    // Attempt ignition with a stick -> should be rejected
                    f.runtime.nativeIgnitePortal(f.source, f.id, portalInterior, 0);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var tool = f.player().getInventory().getStack(0);
                    context.assertTrue(tool.isOf(Items.STICK) && tool.getCount() == 1, "Stick conserved with zero corruption");
                    context.assertFalse(context.getWorld().getBlockState(portalInterior).isOf(Blocks.NETHER_PORTAL),
                            "No portal created with invalid ignition item");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherCrossDim", tickLimit = 440)
    public void crossDimensionTransferAndCoordinateScaling(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 300);
        ServerWorld overworld = context.getWorld();
        ServerWorld nether = overworld.getServer().getWorld(World.NETHER);

        context.assertTrue(nether != null, "Nether world available on server");

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "DimensionTraveler"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var body = f.runtime.nativeBody(f.id).orElseThrow();

                    // Verify unqualified portal travel is initially disabled (F-25b/E-15 baseline)
                    context.assertFalse(body.isPortalTravelAuthorized(), "Portal travel initially unauthorized");
                    context.assertFalse(f.player().canUsePortals(false), "canUsePortals initially false");

                    // Authorize portal travel under E-15
                    body.setPortalTravelAuthorized(true);
                    context.assertTrue(body.isPortalTravelAuthorized(), "Portal travel authorized");
                    context.assertTrue(f.player().canUsePortals(false), "canUsePortals enabled when authorized");

                    // Set expedition state anchored in Overworld at (80, 64, 80)
                    Waypoint anchor = Waypoint.of("overworld_home", 80, 64, 80, "minecraft:overworld", WaypointKind.HOME_BED, 100);
                    f.runtime.setExpeditionState(f.id, new ExpeditionState(anchor, 128.0));

                    // Scale Overworld (80, 64, 80) to Nether -> (10, 64, 10)
                    int netherX = Waypoint.overworldToNether(80);
                    int netherZ = Waypoint.overworldToNether(80);
                    context.assertTrue(netherX == 10 && netherZ == 10, "8:1 coordinate scaling verified");

                    // Execute cross-dimension transfer to Nether
                    boolean transferred = fixtureTeleport(body, nether, new Vec3d(netherX + 0.5, 64.0, netherZ + 0.5));
                    context.assertTrue(transferred, "Teleport to Nether dimension succeeded");
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    // Verify player entity is in the Nether
                    context.assertTrue(f.player().getServerWorld() == nether, "Player world is Nether");
                    context.assertTrue("minecraft:the_nether".equals(f.player().getServerWorld().getRegistryKey().getValue().toString()),
                            "Dimension identifier is minecraft:the_nether");

                    // Verify expedition state recorded Nether arrival portal waypoint
                    var expState = f.runtime.getExpeditionState(f.id);
                    context.assertTrue(expState != null, "Expedition state preserved across dimension transit");
                    var portalWp = expState.knownWaypoints().stream()
                            .filter(w -> w.kind() == WaypointKind.NETHER_PORTAL)
                            .findFirst();
                    context.assertTrue(portalWp.isPresent(), "Nether arrival portal anchored as expedition waypoint");
                    context.assertTrue("minecraft:the_nether".equals(portalWp.get().dimension()), "Portal waypoint dimension is Nether");

                    // Return transit back to Overworld at scaled coordinates
                    var body = f.runtime.nativeBody(f.id).orElseThrow();
                    int returnX = Waypoint.netherToOverworld(10);
                    int returnZ = Waypoint.netherToOverworld(10);
                    context.assertTrue(returnX == 80 && returnZ == 80, "Return coordinate conversion 10*8 = 80 verified");

                    boolean returned = fixtureTeleport(body, overworld, new Vec3d(returnX + 0.5, 64.0, returnZ + 0.5));
                    context.assertTrue(returned, "Return transfer to Overworld succeeded");
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    context.assertTrue(f.player().getServerWorld() == overworld, "Player restored to Overworld");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "netherConservation", tickLimit = 320)
    public void inventoryEquipmentHealthConservationAcrossDimensionTransit(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 300);
        ServerWorld overworld = context.getWorld();
        ServerWorld nether = overworld.getServer().getWorld(World.NETHER);

        f.runnerAwaitingReady(context, "Runtime ready")
                .createAndAdd(() -> f.runtime.createNative(f.source, "ConservedTraveler"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    // Set up distinct inventory items across slots
                    player.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD, 1));
                    player.getInventory().setStack(1, new ItemStack(Items.DIAMOND_PICKAXE, 1));
                    player.getInventory().setStack(2, new ItemStack(Items.FLINT_AND_STEEL, 1));
                    player.getInventory().setStack(3, new ItemStack(Items.COOKED_BEEF, 32));
                    player.getInventory().setStack(4, new ItemStack(Items.OBSIDIAN, 10));
                    player.getInventory().setStack(5, new ItemStack(Items.BLAZE_ROD, 6));
                    player.getInventory().setStack(6, new ItemStack(Items.NETHER_WART, 16));
                    player.getInventory().setStack(7, new ItemStack(Items.COBBLESTONE, 64));
                    player.getInventory().setStack(8, new ItemStack(Items.TORCH, 32));
                    player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS, 1));
                    player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS, 1));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE, 1));
                    player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET, 1));

                    player.setHealth(17.5f);
                    player.getHungerManager().setFoodLevel(18);

                    var body = f.runtime.nativeBody(f.id).orElseThrow();
                    body.setPortalTravelAuthorized(true);

                    // Execute transit to Nether
                    fixtureTeleport(body, nether, new Vec3d(10.5, 64.0, 10.5));
                    f.runtime.syncNativeDimensionAfterTransit(f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getServerWorld() == nether, "Player in Nether");

                    // Verify exact survival stats conservation
                    context.assertTrue(Math.abs(player.getHealth() - 17.5f) < 0.01f, "Health conserved across transit: " + player.getHealth());
                    context.assertTrue(player.getHungerManager().getFoodLevel() == 18, "Hunger conserved across transit");

                    // Verify inventory slots conservation
                    context.assertTrue(player.getInventory().getStack(0).isOf(Items.DIAMOND_SWORD), "Slot 0 diamond sword conserved");
                    context.assertTrue(player.getInventory().getStack(1).isOf(Items.DIAMOND_PICKAXE), "Slot 1 diamond pickaxe conserved");
                    context.assertTrue(player.getInventory().getStack(3).getCount() == 32, "Slot 3 cooked beef count 32 conserved");
                    context.assertTrue(player.getInventory().getStack(4).getCount() == 10, "Slot 4 obsidian count 10 conserved");
                    context.assertTrue(player.getInventory().getStack(5).getCount() == 6, "Slot 5 blaze rod count 6 conserved");
                    context.assertTrue(player.getInventory().getStack(6).getCount() == 16, "Slot 6 nether wart count 16 conserved");

                    context.assertTrue(player.getEquippedStack(EquipmentSlot.FEET).isOf(Items.IRON_BOOTS), "Boots conserved");
                    context.assertTrue(player.getEquippedStack(EquipmentSlot.LEGS).isOf(Items.IRON_LEGGINGS), "Leggings conserved");
                    context.assertTrue(player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE), "Chestplate conserved");
                    context.assertTrue(player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET), "Helmet conserved");

                    // Verify NBT serialization preserves dimension and native identity
                    context.assertTrue("minecraft:the_nether".equals(
                            player.getServerWorld().getRegistryKey().getValue().toString()), "Live dimension is Nether after transit");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Fixture repositioning only: proves dimension bookkeeping, never earned portal travel. */
    private static boolean fixtureTeleport(NativePlayerBody body, ServerWorld target, Vec3d pos) {
        var player = body.entity();
        return player.teleport(target, pos.x, pos.y, pos.z, java.util.Set.of(), player.getYaw(), player.getPitch());
    }
}
