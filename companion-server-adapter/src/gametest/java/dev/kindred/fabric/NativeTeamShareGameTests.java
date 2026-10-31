package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.team.HandOvers;
import dev.kindred.core.team.SharedTask;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, one teammate in full iron suddenly holds eleven diamonds
 * while the other wears only an iron chestplate and leggings. The test checks the first tells the team how it
 * will share them out by what each lacks, crafts the pieces at a table, throws the boots and helmet over for
 * the other to put on, and that every diamond is accounted for and every promised piece was made and handed
 * over.
 *
 * Technical: A find shared out within a team and handed over (E-40a): eleven diamonds come to Mason, in full
 * iron with a diamond pickaxe; Wren, beside it, wears only an iron chestplate and leggings. Mason tells its
 * team what it found and who gets what, by what each lacks; makes each piece at the table; carries Wren's
 * over and tosses them across; and Wren puts them on. Every diamond is accounted for, and each promise is
 * kept: what was said is what was made and given.
 */
public final class NativeTeamShareGameTests implements FabricGameTest {
    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID nativeId) {
        return f.source.getServer().getPlayerManager().getPlayer(nativeId);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "teamShare", tickLimit = 3000)
    public void aFindIsSharedOutByWhatEachLacksSaidAndHandedOver(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        UUID[] ids = new UUID[2], players = new UUID[2]; // Mason, Wren
        int[] waited = {0};
        BlockPos table = new BlockPos(1, 2, 1);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> {
                    // A walled ring, so neither wanders off, and a crafting table in one corner.
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(table, Blocks.CRAFTING_TABLE);
                    f.runtime.createNative(f.source, "Mason");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Mason allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Mason") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var wren = player(f, players[1]);
                    context.assertTrue(mason.isOnGround() && wren.isOnGround(), "Both standing");
                    mason.getInventory().clear();
                    wren.getInventory().clear();
                    mason.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    mason.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    mason.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    mason.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    mason.getInventory().setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    mason.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    mason.getInventory().setStack(2, new ItemStack(Items.STICK, 4));
                    mason.getInventory().setStack(3, new ItemStack(Items.BREAD, 8));
                    wren.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    wren.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    wren.getInventory().setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    wren.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    wren.getInventory().setStack(2, new ItemStack(Items.BREAD, 8));
                    BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 2)), b = context.getAbsolutePos(new BlockPos(6, 2, 6));
                    mason.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    wren.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", ids[0]) == 1
                            && f.runtime.nativeLifecycle(f.source, "resume", ids[1]) == 1, "Both resumed");
                })
                // A few seconds of play first: what each holds then is no find.
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 60, "Settling"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.teamLine(ids[0]) == null, "Nothing said before a find");
                    dev.kindred.fabric.KindredMod.LOGGER.info("Team share: Mason at {}, Wren at {}, the table at {} is {}", player(f, players[0]).getBlockPos().toShortString(),
                            player(f, players[1]).getBlockPos().toShortString(), context.getAbsolutePos(table).toShortString(),
                            context.getWorld().getBlockState(context.getAbsolutePos(table)));
                    player(f, players[0]).getInventory().setStack(8, new ItemStack(Items.DIAMOND, 11));
                })
                .createAndAdd(() -> context.assertTrue(
                        "I've got 11 diamonds: diamond boots and a diamond helmet for Wren and a diamond sword for me.".equals(f.runtime.teamLine(ids[0])),
                        "Told its team the find and the share-out: " + f.runtime.teamLine(ids[0])))
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var wren = player(f, players[1]);
                    boolean booted = wren.getEquippedStack(EquipmentSlot.FEET).isOf(Items.DIAMOND_BOOTS);
                    boolean helmed = wren.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET);
                    boolean sword = mason.getInventory().count(Items.DIAMOND_SWORD) == 1;
                    context.assertTrue(booted && helmed && sword, "Wren in diamond boots and helmet, Mason with its diamond sword: "
                            + wren.getEquippedStack(EquipmentSlot.FEET) + ", " + wren.getEquippedStack(EquipmentSlot.HEAD) + ", sword " + sword
                            + " / " + f.runtime.nativeActivityStatus(Actor.console(), ids[0]).work());
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var wren = player(f, players[1]);
                    // Every diamond accounted for: four in the boots, five in the helmet, two in the sword, none left over or lying about.
                    context.assertTrue(mason.getInventory().count(Items.DIAMOND) == 0 && wren.getInventory().count(Items.DIAMOND) == 0, "No diamond left over");
                    var around = new Box(context.getAbsolutePos(new BlockPos(-2, 0, -2)).toCenterPos(), context.getAbsolutePos(new BlockPos(10, 6, 10)).toCenterPos());
                    context.assertTrue(context.getWorld().getEntitiesByClass(ItemEntity.class, around, e -> true).isEmpty(), "Nothing dropped and left lying");
                    context.assertTrue(mason.getInventory().count(Items.DIAMOND_BOOTS) == 0 && mason.getInventory().count(Items.DIAMOND_HELMET) == 0,
                            "Wren's pieces not kept back");
                    // Each promise kept: the share-out's tasks all done, by Mason.
                    var tasks = f.runtime.teamCoordinator().listProjects().stream().flatMap(p -> p.tasks().values().stream())
                            .filter(t -> HandOvers.Piece.of(t) != null).toList();
                    context.assertTrue(tasks.size() == 3 && tasks.stream().allMatch(t -> t.state() == SharedTask.TaskState.COMPLETED
                            && ids[0].equals(t.assignedPlayerId())), "The three pieces done: " + tasks.stream().map(t -> t.title() + " " + t.state()).toList());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A hand-over to a teammate it cannot get to is let go (E-40a1). A walk toward a teammate with no way to it ends at
     * once, where it can get no closer; it counted as done, and in the live pair on `a4df9b4` Sol walked so 7,389 times,
     * for twenty of its forty-four minutes, ahead of its goal. Staged as the fixture above, but Wren is walled into a cell
     * of stone in the ring, out of sight and reach; the diamonds are given as though found.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "teamShareWalled", tickLimit = 3000)
    public void aHandOverToATeammateItCannotGetToIsLetGo(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        UUID[] ids = new UUID[2], players = new UUID[2]; // Mason, Wren
        int[] waited = {0};
        BlockPos table = new BlockPos(1, 2, 1);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> {
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(table, Blocks.CRAFTING_TABLE);
                    f.runtime.createNative(f.source, "Mason");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Mason allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Mason") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var wren = player(f, players[1]);
                    context.assertTrue(mason.isOnGround() && wren.isOnGround(), "Both standing");
                    mason.getInventory().clear();
                    wren.getInventory().clear();
                    mason.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    mason.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    mason.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    mason.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    mason.getInventory().setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    mason.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    mason.getInventory().setStack(2, new ItemStack(Items.STICK, 4));
                    mason.getInventory().setStack(3, new ItemStack(Items.BREAD, 8));
                    wren.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    wren.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    wren.getInventory().setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    wren.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    wren.getInventory().setStack(2, new ItemStack(Items.BREAD, 8));
                    BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 2)), b = context.getAbsolutePos(new BlockPos(6, 2, 6));
                    mason.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    wren.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    // Wren's cell: stone all round its two blocks, and over them.
                    for (int x = 5; x <= 7; x++) for (int z = 5; z <= 7; z++) for (int y = 2; y <= 4; y++) {
                        if (x == 6 && z == 6 && y <= 3) continue;
                        context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", ids[0]) == 1
                            && f.runtime.nativeLifecycle(f.source, "resume", ids[1]) == 1, "Both resumed");
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 60, "Settling"))
                .createAndAdd(() -> player(f, players[0]).getInventory().setStack(8, new ItemStack(Items.DIAMOND, 11)))
                .createAndAdd(() -> context.assertTrue(
                        "I've got 11 diamonds: diamond boots and a diamond helmet for Wren and a diamond sword for me.".equals(f.runtime.teamLine(ids[0])),
                        "Told its team the find and the share-out: " + f.runtime.teamLine(ids[0])))
                // Wren's pieces made, then let go: no longer Mason's to hand over.
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var tasks = f.runtime.teamCoordinator().listProjects().stream().flatMap(p -> p.tasks().values().stream())
                            .filter(t -> HandOvers.Piece.of(t) != null && !HandOvers.Piece.of(t).to().equals(ids[0])).toList();
                    boolean made = mason.getInventory().count(Items.DIAMOND_BOOTS) == 1 && mason.getInventory().count(Items.DIAMOND_HELMET) == 1;
                    boolean letGo = !tasks.isEmpty() && tasks.stream().noneMatch(t -> ids[0].equals(t.assignedPlayerId())
                            && (t.state() == SharedTask.TaskState.CLAIMED || t.state() == SharedTask.TaskState.IN_PROGRESS));
                    context.assertTrue(made && letGo, "Wren's pieces made and let go: made " + made + "; "
                            + tasks.stream().map(t -> t.title() + " " + t.state()).toList() + " / " + f.runtime.nativeActivityStatus(Actor.console(), ids[0]).work());
                })
                .createAndAdd(() -> {
                    var wren = player(f, players[1]);
                    context.assertTrue(wren.getInventory().count(Items.DIAMOND_BOOTS) == 0, "Nothing reached Wren through the stone");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A hand-over to a teammate gone far off waits for them, rather than a walk across the map (E-40a1). The walk went
     * straight to the teammate; beyond one route's reach it was refused as it was made, never ran, and so never counted as
     * a failed step: in the live pair on `41f9de9` Kindred proposed it again and again for five minutes ahead of its goal.
     * Staged as the share-out fixture, Wren in the ring for the find; then Wren is moved 170 blocks off, out of the team's
     * range and beyond one route. The diamonds are given as though found.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "teamShareFar", tickLimit = 3000)
    public void aHandOverToATeammateFarOffWaitsRatherThanWalkAcrossTheMap(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        UUID[] ids = new UUID[2], players = new UUID[2]; // Mason, Wren
        int[] waited = {0}, watched = {0};
        String[] refused = {null};
        BlockPos table = new BlockPos(1, 2, 1);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> {
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(table, Blocks.CRAFTING_TABLE);
                    f.runtime.createNative(f.source, "Mason");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Mason allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Mason") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var wren = player(f, players[1]);
                    context.assertTrue(mason.isOnGround() && wren.isOnGround(), "Both standing");
                    mason.getInventory().clear();
                    wren.getInventory().clear();
                    mason.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    mason.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    mason.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    mason.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    mason.getInventory().setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    mason.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    mason.getInventory().setStack(2, new ItemStack(Items.STICK, 4));
                    mason.getInventory().setStack(3, new ItemStack(Items.BREAD, 8));
                    wren.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    wren.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    wren.getInventory().setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    wren.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    wren.getInventory().setStack(2, new ItemStack(Items.BREAD, 8));
                    BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 2)), b = context.getAbsolutePos(new BlockPos(6, 2, 6));
                    mason.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    wren.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", ids[0]) == 1
                            && f.runtime.nativeLifecycle(f.source, "resume", ids[1]) == 1, "Both resumed");
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 60, "Settling"))
                .createAndAdd(() -> player(f, players[0]).getInventory().setStack(8, new ItemStack(Items.DIAMOND, 11)))
                .createAndAdd(() -> context.assertTrue(
                        "I've got 11 diamonds: diamond boots and a diamond helmet for Wren and a diamond sword for me.".equals(f.runtime.teamLine(ids[0])),
                        "Told its team the find and the share-out: " + f.runtime.teamLine(ids[0])))
                // Wren gone far off once the share-out is made: out of the team's range, and beyond one route.
                .createAndAdd(() -> {
                    var wren = player(f, players[1]);
                    wren.refreshPositionAndAngles(wren.getX() + 170, wren.getY(), wren.getZ(), 180, 0);
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    context.assertTrue(mason.getInventory().count(Items.DIAMOND_BOOTS) == 1 && mason.getInventory().count(Items.DIAMOND_HELMET) == 1,
                            "Wren's pieces made: " + f.runtime.nativeActivityStatus(Actor.console(), ids[0]).work());
                })
                // Watched for thirty seconds after: never held by a walk across the map refused as it was made.
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(st -> st.id().equals(ids[0])).findFirst().orElseThrow();
                    String explanation = String.valueOf(status.explanation());
                    if (refused[0] == null && explanation.contains("too far for one route")) refused[0] = explanation;
                    context.assertTrue(++watched[0] >= 600, "Watched for thirty seconds");
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    context.assertTrue(refused[0] == null, "Held by a walk refused as it was made: " + refused[0]);
                    var tasks = f.runtime.teamCoordinator().listProjects().stream().flatMap(p -> p.tasks().values().stream())
                            .filter(t -> HandOvers.Piece.of(t) != null && !HandOvers.Piece.of(t).to().equals(ids[0])).toList();
                    context.assertTrue(!tasks.isEmpty() && tasks.stream().allMatch(t -> ids[0].equals(t.assignedPlayerId()) && t.state() == SharedTask.TaskState.CLAIMED),
                            "Still promised to Wren, waiting: " + tasks.stream().map(t -> t.title() + " " + t.state()).toList());
                    context.assertTrue(mason.squaredDistanceTo(context.getAbsolutePos(new BlockPos(2, 2, 2)).toCenterPos()) < 20 * 20,
                            "Not walked off after Wren: " + mason.getBlockPos());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
