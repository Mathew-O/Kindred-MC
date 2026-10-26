package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import dev.kindred.core.ActionArbiter;
import dev.kindred.core.ActionLease;
import dev.kindred.core.BodyAdapter;
import dev.kindred.core.Companion;
import dev.kindred.core.logistics.ChestOrganizationPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.Item;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.network.ClientConnection;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.WorldSavePath;
import net.minecraft.network.NetworkState;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.SwordItem;
import net.minecraft.block.CropBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Native survival-mechanics backend; full player qualification remains F-25b.
 * The caller owns registration, saves, admission, claims and the existing arbiter. No autonomous
 * controller or work queue lives here. All methods, including native ticking, are server-thread only.
 */
final class NativePlayerBody implements BodyAdapter {
    enum Intent { MOVE, SELECT_SLOT, EAT, EQUIP, MINE, CRAFT, PLACE, SMELT, SWAP, ATTACK, BLOCK, TILL, PLANT, HARVEST, SLEEP, IGNITE_PORTAL, CHEST_ORGANIZE, TOSS, BUCKET, THROW, BOAT, FRAME, SHOOT, FEED, GATE, TAKE, DOOR, LOOK, RENAME, TRADE, CART }

    /** Rechecks server policy, paid activity, revisions/claims and the task's authorized target. */
    @FunctionalInterface
    interface MutationGate {
        boolean permits(ActionLease lease, Intent intent, BlockPos target);
    }

    private NativePlayer player; // A vanilla respawn replaces the entity, as it does for every player.
    private final Companion identity;
    private final MutationGate gate;
    private ActionArbiter arbiter;
    private ActionLease controls;
    private float forward;
    private float sideways;
    private boolean jump;
    private boolean sprint;
    /** Paddle inputs while in a boat, set on the boat each tick as a client sets them. */
    private boolean rowForward, rowLeft, rowRight;
    private BlockPos miningTarget;
    private BlockState miningState;
    private float miningProgress;
    private long miningAttempt;
    private MiningReceipt miningReceipt;
    private NativeConnection connection;
    private boolean connected;
    private boolean preparedLoadCalled;
    private UUID saveReceipt;
    private long foodUses;
    private boolean portalTravelAuthorized = false;
    private Runnable tickHook = () -> { };
    /** Ticks this body has run, for a status line that asks whether it is ticking at all. */
    private long ticksRun;
    /** What this player can know of others' health from the hits it saw land (E-41a); never the server's number. */
    private final dev.kindred.core.tactics.HealthBeliefs beliefs = new dev.kindred.core.tactics.HealthBeliefs();
    private boolean creativeAllowed;
    /**
     * Whether this player may attack another player (M-00a, D7): only a joined participant of a running match it is in.
     * The runtime sets it; until then every player target is refused.
     */
    private java.util.function.BiPredicate<UUID, UUID> pvp = (attacker, target) -> false;
    /** The beliefs of a viewer that is no native player: every creature at its kind's full health. Read only. */
    private static final dev.kindred.core.tactics.HealthBeliefs NO_BELIEFS = new dev.kindred.core.tactics.HealthBeliefs();
    private Runnable deathHook = () -> { };
    /** Another player's hit that got through to the body: who, and what it took after armour, absorption included. */
    interface HitHook { void hit(ServerPlayerEntity by, float taken); }
    private HitHook hitHook = (by, taken) -> { };
    private boolean respawning;

    /** Optional per-tick cap wired from {@link PlayerRuntime#MAX_BLOCK_CHECKS_PER_TICK}. */
    @FunctionalInterface
    interface BlockScanBudget {
        /** @return {@code true} when one block check was charged against the tick budget */
        boolean tryConsumeScan();
    }

    private BlockScanBudget blockScanBudget = () -> true;

    void bindBlockScanBudget(BlockScanBudget budget) {
        this.blockScanBudget = budget != null ? budget : () -> true;
    }

    /** Constructs dormant, unregistered mechanics; never replaces or loads an existing saved body. */
    NativePlayerBody(ServerWorld world, Companion identity, Vec3d position, MutationGate gate) {
        if (!world.getServer().isOnThread()) throw new IllegalStateException("Native player creation requires the server thread");
        this.identity = Objects.requireNonNull(identity);
        this.gate = Objects.requireNonNull(gate);
        Objects.requireNonNull(position);
        if (identity.entityId() == null) throw new IllegalArgumentException("A reconciled native body UUID is required");
        if (!world.getRegistryKey().getValue().toString().equals(identity.dimension())) {
            throw new IllegalArgumentException("Body dimension differs from metadata");
        }
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || !world.isChunkLoaded(BlockPos.ofFloored(position))
                || !world.getWorldBorder().contains(BlockPos.ofFloored(position))) {
            throw new IllegalArgumentException("Native body needs a finite position in an already loaded world area");
        }
        for (ServerWorld loaded : world.getServer().getWorlds()) {
            if (loaded.getEntity(identity.entityId()) != null) throw new IllegalStateException("Native body UUID is already loaded");
        }
        if (world.getServer().getPlayerManager().getPlayer(identity.entityId()) != null) {
            throw new IllegalStateException("Native body UUID is already connected");
        }
        // Stable profile name using canonical display name when valid; canonical roster skins use verified Mojang textures.
        String safeName = identity.displayName() != null && !identity.displayName().isBlank()
                ? identity.displayName().trim().replaceAll("[^a-zA-Z0-9_]", "")
                : "";
        if (safeName.length() > 16) safeName = safeName.substring(0, 16);
        if (safeName.isEmpty()) {
            safeName = "K_" + identity.entityId().toString().replace("-", "").substring(0, 14);
        }
        String profileName = safeName;
        player = new NativePlayer(world, RosterGameProfiles.forNativeBody(identity.entityId(), profileName, identity.displayName()));
        player.setCustomName(Text.literal(identity.displayName()));
        player.setCustomNameVisible(true);
        player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        player.changeGameMode(GameMode.SURVIVAL);
        haltControls();
    }

    ServerPlayerEntity entity() { requireThread(); return player; }
    long foodUses() { requireThread(); return foodUses; }

    record MiningReceipt(long sequence, long attempt, UUID playerId, UUID nativeId, String dimension,
                         ActionLease lease, BlockPos position, int previousBlockState) { }
    Optional<MiningReceipt> miningReceipt() { requireThread(); return Optional.ofNullable(miningReceipt); }

    /** Observation only. Fabric attributes the successful break to its actual native player. */
    static void observedBreak(net.minecraft.world.World world, net.minecraft.entity.player.PlayerEntity actor,
                              BlockPos position, BlockState previousState) {
        if (actor instanceof NativePlayer nativePlayer && nativePlayer.getWorld() == world) {
            nativePlayer.recordBreak(position, previousState);
        }
    }

    static boolean allowBreak(net.minecraft.world.World world, net.minecraft.entity.player.PlayerEntity actor, BlockPos position, BlockState state) {
        if (!(actor instanceof NativePlayer nativePlayer)) return true;
        return nativePlayer.getWorld() == world && nativePlayer.allowBreak(position, state);
    }

    private void recordMiningBreak(BlockPos position, BlockState previousState) {
        requireThread();
        if (!current(controls) || miningTarget == null || !miningTarget.equals(position) || miningState != previousState
                || player.getWorld().getBlockState(position) == previousState
                || (miningReceipt != null && miningReceipt.attempt() == miningAttempt)) return;
        miningReceipt = new MiningReceipt(miningReceipt == null ? 1 : Math.incrementExact(miningReceipt.sequence()),
                miningAttempt, identity.id(), player.getUuid(), identity.dimension(), controls, position.toImmutable(),
                net.minecraft.block.Block.getRawIdFromState(previousState));
    }

    void onTick(Runnable hook) { requireThread(); tickHook = Objects.requireNonNull(hook); }

    long ticksRun() { return ticksRun; }

    dev.kindred.core.tactics.HealthBeliefs beliefs() { return beliefs; }

    void pvp(java.util.function.BiPredicate<UUID, UUID> rule) { pvp = java.util.Objects.requireNonNull(rule); }

    /** Whether a strike at {@code target} is allowed at all: any creature but this player, a player only as {@link #pvp} allows. */
    boolean mayStrike(LivingEntity target) {
        return target != player && (!(target instanceof PlayerEntity) || pvp.test(player.getUuid(), target.getUuid()));
    }

    /**
     * The health {@code viewer} can believe {@code target} has (E-41a): worked out from the hits it saw land, as a player
     * would, never read from the server. The Dragon's is the exception, shown to every player on its boss bar.
     */
    static double believedHealth(ServerPlayerEntity viewer, LivingEntity target) {
        if (target instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity) return target.getHealth();
        var beliefs = viewer instanceof NativePlayer nativePlayer ? nativePlayer.body().beliefs : NO_BELIEFS;
        return beliefs.of(target.getUuid(), target.getMaxHealth(), target instanceof PlayerEntity, viewer.getServerWorld().getTime()).value();
    }

    /**
     * A strike just made on {@code target}, recorded if the player saw it land (the target's hurt flash, from this
     * player's blow) as what the ruleset says it deals: the weapon at its charge, critical or not, through the armour the
     * player can see, and held to its excess within the damage immunity of the player's last hit.
     */
    private void recordHit(LivingEntity target, ItemStack weapon, float charge, boolean critical) {
        var source = target.getRecentDamageSource();
        if (target.hurtTime != target.maxHurtTime || source == null || source.getAttacker() != player) return;
        int strength = player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH)
                ? player.getStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH).getAmplifier() + 1 : 0;
        double raw = dev.kindred.core.tactics.DamageModel.hit(Weapons.of(weapon).orElse(dev.kindred.core.tactics.DamageModel.Weapon.HAND),
                charge, critical, strength, player.fallDistance);
        double dealt = dev.kindred.core.tactics.CombatRuleset.afterArmour(raw, target.getArmor(),
                target.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ARMOR_TOUGHNESS));
        beliefs.hit(target.getUuid(), target.getMaxHealth(), target instanceof PlayerEntity, dealt, player.getServerWorld().getTime());
    }
    /** Runs when the body dies, before vanilla drops its inventory, so in-flight work settles against real contents. */
    void onDeath(Runnable hook) { requireThread(); deathHook = Objects.requireNonNull(hook); }

    /** Runs when another player's hit gets through to the body and it lives (E-41g): what the hit took, measured. */
    void onHitByPlayer(HitHook hook) { requireThread(); hitHook = Objects.requireNonNull(hook); }

    /**
     * Vanilla respawn of this dead, still-registered body: the same identity at its spawn point with whatever
     * vanilla keeps (an empty inventory unless keepInventory is on). Nothing is restored or recreated here.
     */
    void respawn() {
        requireThread();
        if (!connected()) throw new IllegalStateException("Only a registered native body can respawn");
        if (player.isAlive()) throw new IllegalStateException("The native body is alive");
        if (controls != null && arbiter != null) arbiter.cancel(controls);
        haltControls();
        controls = null;
        NativePlayer dead = player;
        var handler = dead.networkHandler;
        ServerPlayerEntity fresh;
        respawning = true;
        try {
            fresh = dead.server.getPlayerManager().respawnPlayer(dead, false, net.minecraft.entity.Entity.RemovalReason.KILLED);
        } finally {
            respawning = false;
        }
        if (!(fresh instanceof NativePlayer next)) throw new IllegalStateException("Respawn did not produce the native body");
        handler.player = next; // What a client's respawn packet handler does with the returned entity.
        player = next;
        next.setCustomName(Text.literal(identity.displayName()));
        next.setCustomNameVisible(true);
        next.changeGameMode(GameMode.SURVIVAL);
        haltControls();
    }
    void stopInputs() { requireThread(); haltControls(); }

    UUID saveNow() {
        requireThread();
        if (!connected()) throw new IllegalStateException("Only a registered native body can be saved");
        saveReceipt = UUID.randomUUID();
        writeNativePlayerFile();
        return saveReceipt;
    }

    /** Same compressed layout as vanilla {@code PlayerManager#savePlayerData}, without statistics/advancement side paths that fail for native bodies. */
    private void writeNativePlayerFile() {
        Path directory = player.server.getSavePath(WorldSavePath.PLAYERDATA);
        Path file = directory.resolve(player.getUuidAsString() + ".dat");
        Path temp = directory.resolve(player.getUuidAsString() + ".dat_" + UUID.randomUUID());
        try {
            Files.createDirectories(directory);
            NbtCompound nbt = player.writeNbt(new NbtCompound());
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                NbtIo.writeCompressed(nbt, output);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw new IllegalStateException("Native player save could not be written", failure);
        }
    }

    void verifySave(NbtCompound saved, UUID expectedReceipt) {
        requireThread();
        validateSave(saved);
        if (!saved.containsUuid("KindredNativeSaveReceipt")
                || !saved.getUuid("KindredNativeSaveReceipt").equals(expectedReceipt)) {
            throw new IllegalStateException("Native save receipt mismatch; reconciliation required");
        }
    }

    /** Caller must distinguish an explicit new creation from a required native-save restoration. */
    void connect(Optional<NbtCompound> saved, boolean existingBody) {
        requireThread();
        if (connected || player.isRemoved() || player.server.getPlayerManager().getPlayer(player.getUuid()) != null) {
            throw new IllegalStateException("Native player is already registered");
        }
        if (arbiter == null) throw new IllegalStateException("Bind body authority before registration");
        if (existingBody && saved.isEmpty()) throw new IllegalStateException("Native save is missing; reconciliation required");
        if (!existingBody && saved.isPresent()) throw new IllegalStateException("New creation conflicts with an existing native save");
        saved.ifPresent(this::validateSave);
        player.preparedState = saved.map(NbtCompound::copy);
        connection = new NativeConnection();
        try {
            player.server.getPlayerManager().onPlayerConnect(connection, player,
                    ConnectedClientData.createDefault(player.getGameProfile(), false));
            connected = player.server.getPlayerManager().getPlayer(player.getUuid()) == player;
            if (!connected) throw new IllegalStateException("Native player registration did not complete");
            if (!preparedLoadCalled) throw new IllegalStateException("Prepared native state hook did not run");
            player.changeGameMode(GameMode.SURVIVAL);
            player.onTeleportationDone();
            if (saved.map(tag -> tag.getBoolean("KindredNativeStopped")).orElse(false)
                    && !arbiter.snapshot().stopLatched()) arbiter.stop(dev.kindred.core.Actor.console());
            saveReceipt = saved.filter(tag -> tag.containsUuid("KindredNativeSaveReceipt"))
                    .map(tag -> tag.getUuid("KindredNativeSaveReceipt")).orElse(null);
            haltControls();
        } catch (RuntimeException failure) {
            // Only our validated physical state may have reached registration; save/remove it once.
            if (player.server.getPlayerManager().getPlayer(player.getUuid()) == player) {
                player.server.getPlayerManager().remove(player);
            } else if (player.getServerWorld().getEntity(player.getUuid()) == player) {
                player.getServerWorld().removePlayer(player, net.minecraft.entity.Entity.RemovalReason.DISCARDED);
            }
            connected = false;
            connection.open = false;
            throw failure;
        }
    }

    void disconnect() {
        requireThread();
        if (arbiter != null && controls != null) arbiter.cancel(controls);
        haltControls();
        controls = null;
        if (connected && player.server.getPlayerManager().getPlayer(player.getUuid()) == player) {
            writeNativePlayerFile();
            player.server.getPlayerManager().remove(player); // Physical state is already in the native player file.
        }
        connected = false;
        if (connection != null) connection.open = false;
    }

    boolean connected() {
        requireThread();
        return connected && player.server.getPlayerManager().getPlayer(player.getUuid()) == player;
    }

    boolean isPortalTravelAuthorized() {
        requireThread();
        return portalTravelAuthorized;
    }

    void setPortalTravelAuthorized(boolean authorized) {
        requireThread();
        this.portalTravelAuthorized = authorized;
    }

    private void validateSave(NbtCompound saved) {
        float health = saved.getFloat("Health");
        if (!Float.isFinite(health) || health <= 0) {
            throw new IllegalStateException("Dead or invalid native body requires qualified death recovery");
        }
        if (!saved.containsUuid("KindredNativeIdentity") || !saved.getUuid("KindredNativeIdentity").equals(identity.id())
                || !saved.containsUuid("UUID") || !saved.getUuid("UUID").equals(identity.entityId())) {
            throw new IllegalArgumentException("Native save identity mismatch; reconciliation required");
        }
        if (saved.getInt("DataVersion") != SharedConstants.getGameVersion().getSaveVersion().getId()) {
            throw new IllegalArgumentException("Native save data version is not the pinned version; explicit migration required");
        }
        if (!saved.getString("Dimension").equals(identity.dimension())) {
            throw new IllegalArgumentException("Native save dimension differs from metadata; reconcile before loading");
        }
        if (!saved.containsUuid("KindredWorldIdentity") || !saved.getUuid("KindredWorldIdentity").equals(identity.worldId())) {
            throw new IllegalArgumentException("Native save belongs to another world identity");
        }
        var position = saved.getList("Pos", net.minecraft.nbt.NbtElement.DOUBLE_TYPE);
        if (position.size() != 3) throw new IllegalArgumentException("Native save has no valid position");
        Vec3d pos = new Vec3d(position.getDouble(0), position.getDouble(1), position.getDouble(2));
        BlockPos block = BlockPos.ofFloored(pos);
        if (!Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)
                || !player.getServerWorld().isChunkLoaded(block) || !player.getServerWorld().isInBuildLimit(block)
                || !player.getServerWorld().getWorldBorder().contains(block)) {
            throw new IllegalStateException("Native saved area must be loaded and inside world bounds before reconnecting");
        }
    }

    /** Exactly one shared arbiter may be bound, before any lease is acquired. */
    void bind(ActionArbiter authority) {
        requireThread();
        if (arbiter != null) throw new IllegalStateException("Native body already has authority");
        Companion saved = Objects.requireNonNull(authority).snapshot();
        if (!saved.id().equals(identity.id()) || !saved.worldId().equals(identity.worldId())
                || !Objects.equals(saved.entityId(), player.getUuid())) {
            throw new IllegalArgumentException("Authority belongs to another identity/body");
        }
        arbiter = authority;
    }

    @Override public void start(ActionLease lease) {
        requireThread();
        if (arbiter == null || !arbiter.isCurrent(lease) || !lease.companionId().equals(identity.id())) {
            throw new IllegalStateException("Native controls require current shared authority");
        }
        haltControls();
        controls = lease;
    }

    @Override public void cancel(ActionLease lease) {
        requireThread();
        if (!Objects.equals(controls, lease)) return;
        haltControls();
        controls = null;
    }

    @Override public boolean isSettled(ActionLease lease) {
        requireThread();
        return forward == 0 && sideways == 0 && !jump && !sprint
                && miningTarget == null && !player.isUsingItem();
    }

    boolean move(ActionLease lease, float forward, float sideways, boolean jump, boolean sprint, float yaw) {
        requireThread();
        if (!Float.isFinite(forward) || !Float.isFinite(sideways) || !Float.isFinite(yaw)
                || Math.abs(forward) > 1 || Math.abs(sideways) > 1) throw new IllegalArgumentException("Invalid movement input");
        return execute(lease, Intent.MOVE, null, () -> {
            this.forward = forward;
            this.sideways = sideways;
            this.jump = jump;
            this.sprint = sprint;
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
        });
    }

    /**
     * As {@link #move} for walking, turning as a player turns rather than snapping: the yaw closes half the remaining
     * angle each tick (at least six degrees, at most 45), the pitch eases toward where the player looks along its way,
     * and the head turns with the body, so a route's corners are rounded and the view never jerks.
     */
    boolean walk(ActionLease lease, float forward, float sideways, boolean jump, boolean sprint, float yaw, float pitch) {
        requireThread();
        if (!Float.isFinite(forward) || !Float.isFinite(sideways) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(forward) > 1 || Math.abs(sideways) > 1) throw new IllegalArgumentException("Invalid movement input");
        return execute(lease, Intent.MOVE, null, () -> {
            this.forward = forward;
            this.sideways = sideways;
            this.jump = jump;
            this.sprint = sprint;
            float turn = net.minecraft.util.math.MathHelper.wrapDegrees(yaw - player.getYaw());
            float step = Math.min(Math.abs(turn), Math.min(45f, Math.max(6f, Math.abs(turn) * 0.5f)));
            player.setYaw(player.getYaw() + Math.signum(turn) * step);
            float tilt = pitch - player.getPitch();
            player.setPitch(player.getPitch() + Math.clamp(tilt * 0.35f, -10f, 10f));
            player.setHeadYaw(player.getYaw());
        });
    }

    /** How many degrees the yaw still has to turn to face {@code yaw}. */
    float turnLeft(float yaw) {
        return Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - player.getYaw()));
    }

    boolean selectHotbar(ActionLease lease, int slot) {
        requireThread();
        if (slot < 0 || slot >= 9) throw new IllegalArgumentException("Hotbar slot must be 0..8");
        return execute(lease, Intent.SELECT_SLOT, null, () -> {
            player.clearActiveItem();
            player.getInventory().selectedSlot = slot;
        });
    }

    boolean eat(ActionLease lease) {
        requireThread();
        var stack = player.getMainHandStack();
        var food = stack.get(DataComponentTypes.FOOD);
        if (food == null || !player.canConsume(food.canAlwaysEat())) return false;
        return execute(lease, Intent.EAT, null, () ->
                player.interactionManager.interactItem(player, player.getWorld(), stack, Hand.MAIN_HAND));
    }

    Optional<net.minecraft.entity.EquipmentSlot> equipmentDestination(int inventorySlot) {
        requireThread();
        if (inventorySlot < 0 || inventorySlot >= 36 || player.currentScreenHandler != player.playerScreenHandler
                || !player.playerScreenHandler.getCursorStack().isEmpty()) return Optional.empty();
        var stack = player.getInventory().getStack(inventorySlot);
        if (stack.getCount() != 1 || (!(stack.getItem() instanceof net.minecraft.item.ArmorItem)
                && !stack.isOf(net.minecraft.item.Items.SHIELD))) return Optional.empty();
        var target = player.getPreferredEquipmentSlot(stack);
        if (target == net.minecraft.entity.EquipmentSlot.MAINHAND || !player.canUseSlot(target)
                || !player.getEquippedStack(target).isEmpty()) return Optional.empty();
        int targetIndex = target == net.minecraft.entity.EquipmentSlot.OFFHAND ? 40 : 36 + target.getEntitySlotId();
        var from = player.playerScreenHandler.getSlotIndex(player.getInventory(), inventorySlot);
        var to = player.playerScreenHandler.getSlotIndex(player.getInventory(), targetIndex);
        if (from.isEmpty() || to.isEmpty() || !player.playerScreenHandler.getSlot(from.getAsInt()).canTakeItems(player)
                || !player.playerScreenHandler.getSlot(to.getAsInt()).canInsert(stack)) return Optional.empty();
        return Optional.of(target);
    }

    /**
     * The worn armour slot a piece in hotbar slot {@code hotbarSlot} would be swapped into, as a player swaps one with
     * the number key over the armour slot: an armour piece whose slot is already filled with one it may take off.
     */
    Optional<net.minecraft.entity.EquipmentSlot> wearDestination(int hotbarSlot) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || player.currentScreenHandler != player.playerScreenHandler
                || !player.playerScreenHandler.getCursorStack().isEmpty()) return Optional.empty();
        var stack = player.getInventory().getStack(hotbarSlot);
        if (stack.getCount() != 1 || !(stack.getItem() instanceof net.minecraft.item.ArmorItem)) return Optional.empty();
        var target = player.getPreferredEquipmentSlot(stack);
        if (target.getType() != net.minecraft.entity.EquipmentSlot.Type.HUMANOID_ARMOR || player.getEquippedStack(target).isEmpty()) return Optional.empty();
        var to = player.playerScreenHandler.getSlotIndex(player.getInventory(), 36 + target.getEntitySlotId());
        if (to.isEmpty() || !player.playerScreenHandler.getSlot(to.getAsInt()).canTakeItems(player)
                || !player.playerScreenHandler.getSlot(to.getAsInt()).canInsert(stack)) return Optional.empty();
        return Optional.of(target);
    }

    /** Swaps the piece in {@code hotbarSlot} with the one worn in its slot, with vanilla's own number-key swap. */
    boolean wearOwned(ActionLease lease, int hotbarSlot) {
        requireThread();
        var target = wearDestination(hotbarSlot);
        if (target.isEmpty()) return false;
        return execute(lease, Intent.EQUIP, null, () -> {
            int screenSlot = player.playerScreenHandler.getSlotIndex(player.getInventory(), 36 + target.get().getEntitySlotId()).orElseThrow();
            player.playerScreenHandler.onSlotClick(screenSlot, hotbarSlot, net.minecraft.screen.slot.SlotActionType.SWAP, player);
        });
    }

    boolean equipOwned(ActionLease lease, int inventorySlot) {
        requireThread();
        if (equipmentDestination(inventorySlot).isEmpty()) return false;
        return execute(lease, Intent.EQUIP, null, () -> {
            // Normal screen-handler transfer rules; never manufacture an equipped stack.
            int screenSlot = player.playerScreenHandler.getSlotIndex(player.getInventory(), inventorySlot).orElseThrow();
            player.playerScreenHandler.onSlotClick(screenSlot, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
        });
    }

    boolean swapSlots(ActionLease lease, int fromSlot, int toSlot) {
        requireThread();
        if (fromSlot < 0 || fromSlot >= 36 || toSlot < 0 || toSlot >= 36 || fromSlot == toSlot) return false;
        if (player.currentScreenHandler != player.playerScreenHandler
                || !player.playerScreenHandler.getCursorStack().isEmpty()) return false;
        var from = player.getInventory().getStack(fromSlot);
        var to = player.getInventory().getStack(toSlot);
        if (from.isEmpty() && to.isEmpty()) return false;
        return execute(lease, Intent.SWAP, null, () -> {
            var temp = player.getInventory().getStack(toSlot);
            player.getInventory().setStack(toSlot, player.getInventory().getStack(fromSlot));
            player.getInventory().setStack(fromSlot, temp);
            player.getInventory().markDirty();
        });
    }

    boolean attack(ActionLease lease, UUID targetId, int slot) {
        requireThread();
        if (targetId == null) return false;
        var entity = player.getServerWorld().getEntity(targetId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive() || target.isRemoved()) return false;
        // Another player only inside a running match both joined (M-00a, D7).
        if (!mayStrike(target)) return false;
        // A client's reach (E-41a): three blocks from the eyes to the target's hitbox, as the Dragon strike has it.
        if (!player.canInteractWithEntity(target, 0)) return false;
        if (!player.canSee(target)) return false;
        return execute(lease, Intent.ATTACK, null, () -> {
            if (slot >= 0 && slot < 9 && player.getInventory().selectedSlot != slot) {
                player.clearActiveItem();
                player.getInventory().selectedSlot = slot;
            }
            Vec3d diff = target.getEyePos().subtract(player.getEyePos());
            double diffXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
            float yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
            float pitch = (float) Math.toDegrees(-Math.atan2(diff.y, diffXZ));
            player.setYaw(yaw);
            player.setPitch(pitch);
            float charge = player.getAttackCooldownProgress(0.5f);
            boolean critical = dev.kindred.core.tactics.CombatRuleset.critical(new dev.kindred.core.tactics.CombatRuleset.Swing(charge,
                    player.fallDistance, player.isOnGround(), player.isClimbing(), player.isTouchingWater(),
                    player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS), player.hasVehicle(), player.isSprinting()));
            ItemStack held = player.getMainHandStack().copy();
            player.attack(target);
            player.swingHand(Hand.MAIN_HAND);
            recordHit(target, held, charge, critical);
        });
    }

    /**
     * Strikes a part of the Ender Dragon within reach (E-39d), as a player's click lands on the part it looks at: the
     * head takes the full blow, the rest a quarter. The parts are not entities the world can be asked for by id.
     */
    boolean strikeDragon(ActionLease lease, net.minecraft.entity.boss.dragon.EnderDragonPart part, int slot) {
        requireThread();
        if (part == null || part.owner == null || !part.owner.isAlive() || part.owner.getWorld() != player.getWorld()) return false;
        Vec3d at = part.getBoundingBox().getCenter();
        // Reach as the server allows a player's click on an entity: to its box, within the interaction range.
        if (!player.canInteractWithEntity(part, 0)) return false;
        return execute(lease, Intent.ATTACK, null, () -> {
            if (slot >= 0 && slot < 9 && player.getInventory().selectedSlot != slot) {
                player.clearActiveItem();
                player.getInventory().selectedSlot = slot;
            }
            face(at);
            player.attack(part);
            player.swingHand(Hand.MAIN_HAND);
        });
    }

    /**
     * Home from the End's exit portal (E-39d), as a client's request after the credits brings a player back: vanilla takes
     * the player out of every world and waits for that request, and a native body has no client to send it. The same
     * player, with all it carries, at its spawn point in the Overworld; nothing is restored or recreated here.
     */
    boolean returnFromCredits() {
        requireThread();
        if (!connected() || !player.notInAnyWorld) return false;
        if (controls != null && arbiter != null) arbiter.cancel(controls);
        haltControls();
        controls = null;
        NativePlayer gone = player;
        var handler = gone.networkHandler;
        gone.notInAnyWorld = false;
        ServerPlayerEntity fresh;
        respawning = true;
        try {
            fresh = gone.server.getPlayerManager().respawnPlayer(gone, true, net.minecraft.entity.Entity.RemovalReason.CHANGED_DIMENSION);
        } finally {
            respawning = false;
        }
        if (!(fresh instanceof NativePlayer next)) throw new IllegalStateException("The return home did not produce the native body");
        handler.player = next;
        player = next;
        next.setCustomName(Text.literal(identity.displayName()));
        next.setCustomNameVisible(true);
        net.minecraft.advancement.criterion.Criteria.CHANGED_DIMENSION.trigger(next, net.minecraft.world.World.END, net.minecraft.world.World.OVERWORLD);
        haltControls();
        return true;
    }

    /** Out of every world after the exit portal's credits, waiting for the request home. */
    boolean awayAfterCredits() {
        requireThread();
        return connected() && player.notInAnyWorld;
    }

    boolean block(ActionLease lease, boolean active) {
        requireThread();
        if (!active) {
            return execute(lease, Intent.BLOCK, null, player::clearActiveItem);
        }
        Hand hand = null;
        if (player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)) {
            hand = Hand.OFF_HAND;
        } else if (player.getMainHandStack().isOf(net.minecraft.item.Items.SHIELD)) {
            hand = Hand.MAIN_HAND;
        }
        if (hand == null) return false;
        // A shield an axe has put out of use cannot be raised, as vanilla refuses a player any item cooling down (E-41a).
        if (player.getItemCooldownManager().isCoolingDown(player.getStackInHand(hand).getItem())) return false;
        final Hand blockHand = hand;
        return execute(lease, Intent.BLOCK, null, () -> player.setCurrentHand(blockHand));
    }

    record CraftPlan(RecipeEntry<CraftingRecipe> recipe, int[] sourceSlots, ItemStack expectedResult,
                     boolean requiresWorkstation, BlockPos workstationPos) {
        int sourceSlotForGrid(int gridSlot) { return sourceSlots[gridSlot]; }
        int gridSize() { return requiresWorkstation ? 9 : 4; }
    }

    Optional<CraftPlan> planCraft2x2(String recipeName) {
        return planCraft(recipeName).filter(p -> !p.requiresWorkstation());
    }

    Optional<CraftPlan> planCraft(String recipeName) {
        return planCraft(recipeName, null);
    }

    Optional<CraftPlan> planCraft(String recipeName, BlockPos knownWorkstation) {
        requireThread();
        if (recipeName == null || recipeName.isBlank()
                || player.currentScreenHandler != player.playerScreenHandler
                || !player.playerScreenHandler.getCursorStack().isEmpty()
                || !player.playerScreenHandler.getCraftingInput().isEmpty()) {
            return Optional.empty();
        }
        var server = player.getServerWorld().getServer();
        var recipeManager = server.getRecipeManager();
        var registryManager = player.getServerWorld().getRegistryManager();

        Identifier id = recipeName.contains(":") ? Identifier.tryParse(recipeName) : Identifier.ofVanilla(recipeName);
        Optional<RecipeEntry<CraftingRecipe>> matchedEntry = Optional.empty();
        if (id != null) {
            var direct = recipeManager.get(id);
            if (direct.isPresent() && direct.get().value() instanceof CraftingRecipe cr && (cr.fits(2, 2) || cr.fits(3, 3))) {
                matchedEntry = Optional.of((RecipeEntry<CraftingRecipe>) (Object) direct.get());
            }
        }
        if (matchedEntry.isEmpty()) {
            for (var entry : recipeManager.listAllOfType(RecipeType.CRAFTING)) {
                if (!entry.value().fits(2, 2) && !entry.value().fits(3, 3)) continue;
                var resultStack = entry.value().getResult(registryManager);
                var resultId = net.minecraft.registry.Registries.ITEM.getId(resultStack.getItem());
                if (resultId.getPath().equals(recipeName) || entry.id().getPath().equals(recipeName)) {
                    matchedEntry = Optional.of(entry);
                    break;
                }
            }
        }
        if (matchedEntry.isEmpty()) return Optional.empty();

        var recipeEntry = matchedEntry.get();
        var craftingRecipe = recipeEntry.value();

        final boolean requiresWorkstation;
        final BlockPos workstationPos;
        final int gridSize;

        if (craftingRecipe.fits(2, 2)) {
            requiresWorkstation = false;
            workstationPos = null;
            gridSize = 4;
        } else if (craftingRecipe.fits(3, 3)) {
            var world = player.getServerWorld();
            Optional<BlockPos> resolvedTable = Optional.empty();
            if (knownWorkstation != null && world.isChunkLoaded(knownWorkstation)
                    && world.getBlockState(knownWorkstation).isOf(Blocks.CRAFTING_TABLE)
                    && player.getEyePos().squaredDistanceTo(
                            knownWorkstation.getX() + 0.5, knownWorkstation.getY() + 0.5, knownWorkstation.getZ() + 0.5) <= 20.25) {
                resolvedTable = Optional.of(knownWorkstation);
            }
            if (resolvedTable.isEmpty()) {
                resolvedTable = findNearbyBlock(Blocks.CRAFTING_TABLE, 4.5);
            }
            if (resolvedTable.isEmpty()) return Optional.empty();
            requiresWorkstation = true;
            workstationPos = resolvedTable.get();
            gridSize = 9;
        } else {
            return Optional.empty();
        }

        Ingredient[] gridIngredients = new Ingredient[gridSize];
        for (int i = 0; i < gridSize; i++) gridIngredients[i] = Ingredient.EMPTY;

        if (craftingRecipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            int gridWidth = requiresWorkstation ? 3 : 2;
            var ingredients = shaped.getIngredients();
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    gridIngredients[r * gridWidth + c] = ingredients.get(r * width + c);
                }
            }
        } else {
            var ingredients = craftingRecipe.getIngredients();
            if (ingredients.size() > gridSize) return Optional.empty();
            for (int i = 0; i < ingredients.size(); i++) {
                gridIngredients[i] = ingredients.get(i);
            }
        }

        var inv = player.getInventory();
        int[] reservedCount = new int[36];
        int[] sourceSlots = new int[gridSize];
        for (int i = 0; i < gridSize; i++) sourceSlots[i] = -1;

        for (int gridSlot = 0; gridSlot < gridSize; gridSlot++) {
            Ingredient ing = gridIngredients[gridSlot];
            if (ing.isEmpty()) continue;
            int foundSlot = -1;
            for (int s = 0; s < 36; s++) {
                var stack = inv.getStack(s);
                if (!stack.isEmpty() && stack.getCount() > reservedCount[s] && ing.test(stack)) {
                    foundSlot = s;
                    break;
                }
            }
            if (foundSlot == -1) return Optional.empty();
            reservedCount[foundSlot]++;
            sourceSlots[gridSlot] = foundSlot;
        }

        var expectedResult = craftingRecipe.getResult(registryManager).copy();
        if (expectedResult.isEmpty()) return Optional.empty();

        int spaceAvailable = 0;
        for (int s = 0; s < 36; s++) {
            var stack = inv.getStack(s);
            int effectiveCount = stack.getCount() - reservedCount[s];
            if (effectiveCount <= 0) {
                spaceAvailable += expectedResult.getMaxCount();
            } else if (ItemStack.areItemsAndComponentsEqual(stack, expectedResult)) {
                spaceAvailable += (expectedResult.getMaxCount() - effectiveCount);
            }
        }
        if (spaceAvailable < expectedResult.getCount()) return Optional.empty();

        return Optional.of(new CraftPlan(recipeEntry, sourceSlots, expectedResult, requiresWorkstation, workstationPos));
    }

    boolean craft2x2(ActionLease lease, CraftPlan plan) {
        return craft(lease, plan);
    }

    boolean craft(ActionLease lease, CraftPlan plan) {
        requireThread();
        if (plan == null) return false;
        if (!plan.requiresWorkstation()) {
            return craft2x2Internal(lease, plan);
        } else {
            return craft3x3Internal(lease, plan);
        }
    }

    private boolean craft2x2Internal(ActionLease lease, CraftPlan plan) {
        return execute(lease, Intent.CRAFT, null, () -> {
            var screen = player.playerScreenHandler;
            var craftingInput = screen.getCraftingInput();
            var inv = player.getInventory();

            if (screen != player.currentScreenHandler || !screen.getCursorStack().isEmpty() || !craftingInput.isEmpty()) {
                return;
            }

            for (int gridSlot = 0; gridSlot < 4; gridSlot++) {
                int sourceSlot = plan.sourceSlotForGrid(gridSlot);
                if (sourceSlot >= 0) {
                    var sourceStack = inv.getStack(sourceSlot);
                    if (sourceStack.isEmpty()) {
                        abortCraft2x2();
                        return;
                    }
                    var single = sourceStack.split(1);
                    craftingInput.setStack(gridSlot, single);
                }
            }
            screen.onContentChanged(craftingInput);

            var resultSlot = screen.getSlot(PlayerScreenHandler.CRAFTING_RESULT_ID);
            var resultStack = resultSlot.getStack();
            if (resultStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(resultStack, plan.expectedResult())
                    || resultStack.getCount() != plan.expectedResult().getCount()) {
                abortCraft2x2();
                return;
            }

            screen.onSlotClick(PlayerScreenHandler.CRAFTING_RESULT_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);

            for (int gridSlot = 0; gridSlot < 4; gridSlot++) {
                var rem = craftingInput.getStack(gridSlot);
                if (!rem.isEmpty()) {
                    craftingInput.setStack(gridSlot, ItemStack.EMPTY);
                    inv.offerOrDrop(rem);
                }
            }
            screen.onContentChanged(craftingInput);
        });
    }

    private boolean craft3x3Internal(ActionLease lease, CraftPlan plan) {
        var world = player.getServerWorld();
        if (plan.workstationPos() == null || !world.isChunkLoaded(plan.workstationPos())
                || !world.getBlockState(plan.workstationPos()).isOf(Blocks.CRAFTING_TABLE)
                || player.getEyePos().squaredDistanceTo(plan.workstationPos().getX() + 0.5,
                        plan.workstationPos().getY() + 0.5, plan.workstationPos().getZ() + 0.5) > 20.25) {
            return false;
        }
        return execute(lease, Intent.CRAFT, plan.workstationPos(), () -> {
            var inv = player.getInventory();
            if (player.currentScreenHandler != player.playerScreenHandler
                    || !player.playerScreenHandler.getCursorStack().isEmpty()
                    || !player.playerScreenHandler.getCraftingInput().isEmpty()) {
                return;
            }
            var state = world.getBlockState(plan.workstationPos());
            var factory = state.createScreenHandlerFactory(world, plan.workstationPos());
            if (factory == null) return;
            player.openHandledScreen(factory);
            if (!(player.currentScreenHandler instanceof CraftingScreenHandler screen)) {
                player.closeHandledScreen();
                return;
            }
            if (!screen.canUse(player)) {
                player.closeHandledScreen();
                return;
            }

            var input = (RecipeInputInventory) screen.getSlot(1).inventory;
            for (int gridSlot = 0; gridSlot < 9; gridSlot++) {
                int sourceSlot = plan.sourceSlotForGrid(gridSlot);
                if (sourceSlot >= 0) {
                    var sourceStack = inv.getStack(sourceSlot);
                    if (sourceStack.isEmpty()) {
                        abortCraft3x3(screen);
                        return;
                    }
                    var single = sourceStack.split(1);
                    input.setStack(gridSlot, single);
                }
            }
            screen.onContentChanged(input);

            var resultSlot = screen.getSlot(CraftingScreenHandler.RESULT_ID);
            var resultStack = resultSlot.getStack();
            if (resultStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(resultStack, plan.expectedResult())
                    || resultStack.getCount() != plan.expectedResult().getCount()) {
                abortCraft3x3(screen);
                return;
            }

            screen.onSlotClick(CraftingScreenHandler.RESULT_ID, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);

            for (int gridSlot = 0; gridSlot < 9; gridSlot++) {
                var rem = input.getStack(gridSlot);
                if (!rem.isEmpty()) {
                    input.setStack(gridSlot, ItemStack.EMPTY);
                    inv.offerOrDrop(rem);
                }
            }
            player.closeHandledScreen();
        });
    }

    private void abortCraft2x2() {
        var screen = player.playerScreenHandler;
        var craftingInput = screen.getCraftingInput();
        var inv = player.getInventory();
        for (int i = 0; i < 4; i++) {
            var stack = craftingInput.getStack(i);
            if (!stack.isEmpty()) {
                craftingInput.setStack(i, ItemStack.EMPTY);
                inv.offerOrDrop(stack);
            }
        }
        screen.onContentChanged(craftingInput);
    }

    private void abortCraft3x3(CraftingScreenHandler screen) {
        var input = (RecipeInputInventory) screen.getSlot(1).inventory;
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            var stack = input.getStack(i);
            if (!stack.isEmpty()) {
                input.setStack(i, ItemStack.EMPTY);
                inv.offerOrDrop(stack);
            }
        }
        player.closeHandledScreen();
    }

    Optional<BlockPos> findNearbyBlockMatching(double maxReach, Predicate<BlockState> matcher) {
        requireThread();
        if (!connected()) {
            return Optional.empty();
        }
        var world = player.getServerWorld();
        var eyePos = player.getEyePos();
        int r = (int) Math.ceil(maxReach);
        int ry = Math.min(r, 3);
        BlockPos center = BlockPos.ofFloored(eyePos);
        BlockPos best = null;
        double bestDistSq = maxReach * maxReach;

        long lastSectionKey = Long.MIN_VALUE;
        boolean lastSectionMayContain = false;

        for (int dy = -ry; dy <= ry; dy++) {
            int y = center.getY() + dy;
            if (y < world.getBottomY() || y >= world.getTopY()) {
                continue;
            }
            int sectionCoordY = y >> 4;
            for (int dx = -r; dx <= r; dx++) {
                int x = center.getX() + dx;
                int chunkX = x >> 4;
                for (int dz = -r; dz <= r; dz++) {
                    int z = center.getZ() + dz;
                    double distSq = eyePos.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
                    if (distSq > bestDistSq) {
                        continue;
                    }

                    int chunkZ = z >> 4;
                    long sectionKey = packSectionKey(chunkX, sectionCoordY, chunkZ);
                    if (sectionKey != lastSectionKey) {
                        lastSectionKey = sectionKey;
                        lastSectionMayContain = sectionMayContain(world, chunkX, chunkZ, sectionCoordY, matcher);
                    }
                    if (!lastSectionMayContain) {
                        continue;
                    }

                    if (!blockScanBudget.tryConsumeScan()) {
                        return Optional.ofNullable(best);
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    if (matcher.test(world.getBlockState(pos))) {
                        bestDistSq = distSq;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static long packSectionKey(int chunkX, int sectionCoordY, int chunkZ) {
        return (((long) chunkX & 0x3FFFFFL) << 42)
                | (((long) sectionCoordY & 0xFFFFFL) << 20)
                | ((long) chunkZ & 0xFFFFFL);
    }

    private static boolean sectionMayContain(
            ServerWorld world,
            int chunkX,
            int chunkZ,
            int sectionCoordY,
            Predicate<BlockState> matcher) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        if (sectionCoordY < world.getBottomSectionCoord() || sectionCoordY >= world.getTopSectionCoord()) {
            return false;
        }
        ChunkSection section = chunk.getSection(chunk.sectionCoordToIndex(sectionCoordY));
        return !section.isEmpty() && section.hasAny(matcher);
    }

    Optional<BlockPos> findNearbyBlock(Block block, double maxReach) {
        return findNearbyBlockMatching(maxReach, state -> state.isOf(block));
    }

    Optional<BlockPos> findNearbyBed(double maxReach) {
        return findNearbyBlockMatching(maxReach, state -> state.isIn(BlockTags.BEDS));
    }

    Optional<BlockPos> findNearbyFarmland(double maxReach) {
        return findNearbyBlockMatching(maxReach, state -> state.isOf(Blocks.FARMLAND));
    }

    boolean canReceiveStack(ItemStack stack) {
        if (stack.isEmpty()) return true;
        var inv = player.getInventory();
        int needed = stack.getCount();
        for (int s = 0; s < 36; s++) {
            var existing = inv.getStack(s);
            if (existing.isEmpty()) {
                return true;
            } else if (ItemStack.areItemsAndComponentsEqual(existing, stack)) {
                int space = existing.getMaxCount() - existing.getCount();
                needed -= space;
                if (needed <= 0) return true;
            }
        }
        return false;
    }

    record SmeltPlan(BlockPos furnacePos, int fuelSourceSlot, int fuelCount,
                     int inputSourceSlot, int inputCount, boolean extractOutput, ItemStack expectedOutput) { }

    static boolean isSafeAutonomousFuel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!AbstractFurnaceBlockEntity.canUseAsFuel(stack)) return false;
        Item item = stack.getItem();
        if (item instanceof MiningToolItem
                || item instanceof SwordItem
                || item instanceof AxeItem
                || item instanceof net.minecraft.item.BowItem
                || item instanceof net.minecraft.item.CrossbowItem
                || item instanceof net.minecraft.item.TridentItem
                || item instanceof net.minecraft.item.ShieldItem
                || item == Items.CRAFTING_TABLE
                || item == Items.CHEST
                || item == Items.TRAPPED_CHEST
                || item == Items.BARREL
                || item == Items.LECTERN
                || item == Items.FISHING_ROD
                || item == Items.CARROT_ON_A_STICK
                || item == Items.WARPED_FUNGUS_ON_A_STICK
                || stack.isIn(ItemTags.BEDS)
                || stack.isIn(ItemTags.BOATS)
                || stack.isIn(ItemTags.SIGNS)
                || stack.isIn(ItemTags.BANNERS)) {
            return false;
        }
        return true;
    }

    static int fuelPriorityScore(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.COAL || item == Items.CHARCOAL) return 100;
        if (item == Items.COAL_BLOCK) return 90;
        if (item == Items.BLAZE_ROD) return 80;
        if (item == Items.STICK || item == Items.BAMBOO) return 60;
        if (stack.isIn(ItemTags.PLANKS)) return 50;
        if (stack.isIn(ItemTags.LOGS_THAT_BURN)) return 20;
        return 30;
    }

    private static int approximateFuelBurnTicks(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) return 1600;
        if (item == Items.COAL_BLOCK) return 16000;
        if (item == Items.BLAZE_ROD) return 2400;
        if (item == Items.STICK || item == Items.BAMBOO) return 100;
        if (item == Items.LAVA_BUCKET) return 20000;
        return 300;
    }

    Optional<SmeltPlan> planSmelt(BlockPos targetPos, String action, Integer fuelSlotArg, Integer inputSlotArg, Integer countArg) {
        requireThread();
        var world = player.getServerWorld();
        BlockPos furnacePos;
        if (targetPos != null) {
            if (!world.isChunkLoaded(targetPos) || !world.getBlockState(targetPos).isOf(Blocks.FURNACE)
                    || player.getEyePos().squaredDistanceTo(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5) > 20.25) {
                return Optional.empty();
            }
            furnacePos = targetPos;
        } else {
            var found = findNearbyBlock(Blocks.FURNACE, 4.5);
            if (found.isEmpty()) return Optional.empty();
            furnacePos = found.get();
        }

        var entity = world.getBlockEntity(furnacePos);
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) return Optional.empty();

        var currentInput = furnace.getStack(0);
        var currentFuel = furnace.getStack(1);
        var currentOutput = furnace.getStack(2);

        boolean extractMode = "extract".equalsIgnoreCase(action);
        boolean loadMode = "load".equalsIgnoreCase(action) || "insert".equalsIgnoreCase(action);
        boolean defaultMode = !extractMode && !loadMode;

        boolean willExtract = false;
        ItemStack expectedOut = ItemStack.EMPTY;
        if ((extractMode || defaultMode) && !currentOutput.isEmpty()) {
            if (canReceiveStack(currentOutput)) {
                willExtract = true;
                expectedOut = currentOutput.copy();
            }
        }

        if (extractMode) {
            if (!willExtract) return Optional.empty();
            return Optional.of(new SmeltPlan(furnacePos, -1, 0, -1, 0, true, expectedOut));
        }

        int chosenFuelSlot = -1;
        int fuelToInsert = 0;
        int chosenInputSlot = -1;
        int inputToInsert = 0;

        if (loadMode || defaultMode) {
            int fuelSpace = currentFuel.isEmpty() ? 64 : (currentFuel.getMaxCount() - currentFuel.getCount());
            if (fuelSpace > 0) {
                if (fuelSlotArg != null && fuelSlotArg >= 0 && fuelSlotArg < 36) {
                    var stack = player.getInventory().getStack(fuelSlotArg);
                    if (!stack.isEmpty() && AbstractFurnaceBlockEntity.canUseAsFuel(stack)
                            && (currentFuel.isEmpty() || ItemStack.areItemsAndComponentsEqual(currentFuel, stack))) {
                        chosenFuelSlot = fuelSlotArg;
                        int maxAvail = countArg != null && countArg > 0 ? Math.min(countArg, stack.getCount()) : stack.getCount();
                        fuelToInsert = Math.min(maxAvail, fuelSpace);
                    }
                } else {
                    int bestFuelScore = Integer.MIN_VALUE;
                    for (int s = 0; s < 36; s++) {
                        if (inputSlotArg != null && s == inputSlotArg) continue;
                        var stack = player.getInventory().getStack(s);
                        if (!stack.isEmpty() && isSafeAutonomousFuel(stack)
                                && (currentFuel.isEmpty() || ItemStack.areItemsAndComponentsEqual(currentFuel, stack))) {
                            int score = fuelPriorityScore(stack);
                            if (score > bestFuelScore) {
                                bestFuelScore = score;
                                chosenFuelSlot = s;
                            }
                        }
                    }
                    if (chosenFuelSlot >= 0) {
                        var stack = player.getInventory().getStack(chosenFuelSlot);
                        int maxAvail = countArg != null && countArg > 0 ? Math.min(countArg, stack.getCount()) : stack.getCount();
                        fuelToInsert = Math.min(maxAvail, fuelSpace);
                    }
                }
            }

            int inputSpace = currentInput.isEmpty() ? 64 : (currentInput.getMaxCount() - currentInput.getCount());
            if (inputSpace > 0) {
                if (inputSlotArg != null && inputSlotArg >= 0 && inputSlotArg < 36) {
                    var stack = player.getInventory().getStack(inputSlotArg);
                    if (!stack.isEmpty() && (currentInput.isEmpty() || ItemStack.areItemsAndComponentsEqual(currentInput, stack))) {
                        if (world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, new SingleStackRecipeInput(stack), world).isPresent()) {
                            chosenInputSlot = inputSlotArg;
                            int maxAvail = countArg != null && countArg > 0 ? Math.min(countArg, stack.getCount()) : stack.getCount();
                            inputToInsert = Math.min(maxAvail, inputSpace);
                        }
                    }
                } else {
                    for (int s = 0; s < 36; s++) {
                        if (s == chosenFuelSlot) continue;
                        var stack = player.getInventory().getStack(s);
                        if (!stack.isEmpty() && (currentInput.isEmpty() || ItemStack.areItemsAndComponentsEqual(currentInput, stack))) {
                            if (world.getRecipeManager().getFirstMatch(RecipeType.SMELTING, new SingleStackRecipeInput(stack), world).isPresent()) {
                                chosenInputSlot = s;
                                int maxAvail = countArg != null && countArg > 0 ? Math.min(countArg, stack.getCount()) : stack.getCount();
                                inputToInsert = Math.min(maxAvail, inputSpace);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Ore matched to fuel: with too little fuel for the whole stack, load only what the fuel at hand (in the
        // furnace and in the inventory) can smelt, so the rest stays with the player rather than in a cold furnace.
        if (chosenInputSlot >= 0 && inputToInsert > 0 && countArg == null) {
            int fuelTicks = currentFuel.isEmpty() ? 0 : currentFuel.getCount() * approximateFuelBurnTicks(currentFuel.getItem());
            if (chosenFuelSlot >= 0) {
                var playerFuel = player.getInventory().getStack(chosenFuelSlot);
                fuelTicks += Math.min(playerFuel.getCount(), Math.max(0, fuelToInsert)) * approximateFuelBurnTicks(playerFuel.getItem());
            }
            // Fuel still burning in a lit furnace is not counted: better one more trip than ore left in a cold furnace.
            int smeltable = fuelTicks / 200 - currentInput.getCount();
            inputToInsert = Math.max(0, Math.min(inputToInsert, smeltable));
            if (inputToInsert == 0) chosenInputSlot = -1;
        }

        if (chosenFuelSlot >= 0 && fuelToInsert > 0) {
            var playerFuel = player.getInventory().getStack(chosenFuelSlot);
            int burnTicksPerUnit = approximateFuelBurnTicks(playerFuel.getItem());
            int totalInputItems = currentInput.getCount() + inputToInsert;
            int totalTicksNeeded = Math.max(200, totalInputItems * 200);
            int existingFuelTicks = currentFuel.getCount() * approximateFuelBurnTicks(currentFuel.isEmpty() ? playerFuel.getItem() : currentFuel.getItem());
            int neededTicks = Math.max(0, totalTicksNeeded - existingFuelTicks);
            int neededFuelUnits = neededTicks == 0
                    ? (currentFuel.isEmpty() ? 1 : 0)
                    : (int) Math.ceil((double) neededTicks / Math.max(1, burnTicksPerUnit));
            int maxAvail = countArg != null && countArg > 0
                    ? Math.min(countArg, playerFuel.getCount())
                    : playerFuel.getCount();
            int fuelSpace = currentFuel.isEmpty() ? playerFuel.getMaxCount() : (currentFuel.getMaxCount() - currentFuel.getCount());
            int desiredUnits = countArg != null ? maxAvail : Math.max(1, neededFuelUnits);
            fuelToInsert = Math.min(desiredUnits, Math.min(maxAvail, Math.max(0, fuelSpace)));
        }

        if (!willExtract && fuelToInsert <= 0 && inputToInsert <= 0) {
            return Optional.empty();
        }

        return Optional.of(new SmeltPlan(furnacePos, chosenFuelSlot, fuelToInsert, chosenInputSlot, inputToInsert, willExtract, expectedOut));
    }

    boolean smelt(ActionLease lease, SmeltPlan plan) {
        requireThread();
        if (plan == null) return false;
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(plan.furnacePos())
                || !world.getBlockState(plan.furnacePos()).isOf(Blocks.FURNACE)
                || player.getEyePos().squaredDistanceTo(plan.furnacePos().getX() + 0.5,
                        plan.furnacePos().getY() + 0.5, plan.furnacePos().getZ() + 0.5) > 20.25) {
            return false;
        }
        return execute(lease, Intent.SMELT, plan.furnacePos(), () -> {
            var entity = world.getBlockEntity(plan.furnacePos());
            if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) return;

            if (plan.extractOutput()) {
                ItemStack output = furnace.getStack(2);
                if (!output.isEmpty() && canReceiveStack(output)) {
                    ItemStack taken = furnace.removeStack(2);
                    player.getInventory().offerOrDrop(taken);
                    furnace.dropExperienceForRecipesUsed(player);
                }
            }

            if (plan.inputCount() > 0 && plan.inputSourceSlot() >= 0 && plan.inputSourceSlot() < 36) {
                ItemStack source = player.getInventory().getStack(plan.inputSourceSlot());
                if (!source.isEmpty()) {
                    int toMove = Math.min(plan.inputCount(), source.getCount());
                    ItemStack split = source.split(toMove);
                    ItemStack current = furnace.getStack(0);
                    if (current.isEmpty()) {
                        furnace.setStack(0, split);
                    } else if (ItemStack.areItemsAndComponentsEqual(current, split)) {
                        current.increment(split.getCount());
                        furnace.setStack(0, current);
                    } else {
                        player.getInventory().offerOrDrop(split);
                    }
                }
            }

            if (plan.fuelCount() > 0 && plan.fuelSourceSlot() >= 0 && plan.fuelSourceSlot() < 36) {
                ItemStack source = player.getInventory().getStack(plan.fuelSourceSlot());
                if (!source.isEmpty() && AbstractFurnaceBlockEntity.canUseAsFuel(source)) {
                    int toMove = Math.min(plan.fuelCount(), source.getCount());
                    ItemStack split = source.split(toMove);
                    ItemStack current = furnace.getStack(1);
                    if (current.isEmpty()) {
                        furnace.setStack(1, split);
                    } else if (ItemStack.areItemsAndComponentsEqual(current, split)) {
                        current.increment(split.getCount());
                        furnace.setStack(1, current);
                    } else {
                        player.getInventory().offerOrDrop(split);
                    }
                }
            }

            furnace.markDirty();
        });
    }

    boolean placeBlock(ActionLease lease, int hotbarSlot, BlockPos target, Direction side) {
        return placeBlock(lease, hotbarSlot, target, side, null);
    }

    /**
     * A look for a placement (E-08b): the way the player faces and the point on the clicked face, which together give a
     * placed block its state (a stair's facing and half, a slab's half). Null keeps the face's middle and the current look.
     */
    record PlaceLook(float yaw, float pitch, Vec3d hit) { }

    boolean placeBlock(ActionLease lease, int hotbarSlot, BlockPos target, Direction side, PlaceLook aim) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var stack = player.getInventory().getStack(hotbarSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) return false;

        Direction clickedSide = side != null ? side : Direction.UP;
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) return false;

        final BlockPos placedPos;
        final BlockPos clickedPos;
        if (world.getBlockState(target).isReplaceable()) {
            placedPos = target;
            clickedPos = target.offset(clickedSide.getOpposite());
        } else {
            placedPos = target.offset(clickedSide);
            clickedPos = target;
        }

        if (!world.isChunkLoaded(placedPos) || !world.isChunkLoaded(clickedPos) || !world.isInBuildLimit(placedPos)) {
            return false;
        }
        if (!world.getBlockState(placedPos).isReplaceable()) return false;
        if (player.getEyePos().squaredDistanceTo(placedPos.getX() + 0.5, placedPos.getY() + 0.5, placedPos.getZ() + 0.5) > 25.0) {
            return false;
        }

        return execute(lease, Intent.PLACE, placedPos, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            var hitVec = aim != null ? aim.hit() : new Vec3d(clickedPos.getX() + 0.5 + clickedSide.getOffsetX() * 0.5,
                    clickedPos.getY() + 0.5 + clickedSide.getOffsetY() * 0.5,
                    clickedPos.getZ() + 0.5 + clickedSide.getOffsetZ() * 0.5);
            if (aim != null) look(aim.yaw(), aim.pitch());
            var hitResult = new BlockHitResult(hitVec, clickedSide, clickedPos, false);
            player.setSneaking(true);
            try {
                player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hitResult);
            } finally {
                player.setSneaking(false);
            }
        });
    }

    boolean tillBlock(ActionLease lease, int hotbarSlot, BlockPos target) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var stack = player.getInventory().getStack(hotbarSlot);
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.item.HoeItem)) return false;

        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) return false;
        var state = world.getBlockState(target);
        if (!state.isOf(Blocks.DIRT) && !state.isOf(Blocks.GRASS_BLOCK) && !state.isOf(Blocks.DIRT_PATH) && !state.isOf(Blocks.COARSE_DIRT)) {
            return false;
        }
        if (!world.getBlockState(target.up()).isAir() && !world.getBlockState(target.up()).isReplaceable()) {
            return false;
        }
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
            return false;
        }

        return execute(lease, Intent.TILL, target, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            var hitVec = new Vec3d(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
            var hitResult = new BlockHitResult(hitVec, Direction.UP, target, false);
            // Vanilla hoe use converts the block and wears the tool; a refused use changes nothing.
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hitResult);
        });
    }

    boolean plantCrop(ActionLease lease, int hotbarSlot, BlockPos target) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var stack = player.getInventory().getStack(hotbarSlot);
        if (stack.isEmpty()) return false;
        var item = stack.getItem();
        boolean isSeed = item == Items.WHEAT_SEEDS || item == Items.CARROT || item == Items.POTATO
                || item == Items.BEETROOT_SEEDS || item == Items.PUMPKIN_SEEDS || item == Items.MELON_SEEDS;
        if (!isSeed) return false;

        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) return false;
        var state = world.getBlockState(target);
        if (!state.isOf(Blocks.FARMLAND)) return false;
        BlockPos cropPos = target.up();
        if (!world.isChunkLoaded(cropPos) || (!world.getBlockState(cropPos).isAir() && !world.getBlockState(cropPos).isReplaceable())) {
            return false;
        }
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
            return false;
        }

        return execute(lease, Intent.PLANT, cropPos, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            var hitVec = new Vec3d(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
            var hitResult = new BlockHitResult(hitVec, Direction.UP, target, false);
            // Vanilla seed placement consumes exactly the seed it plants; a refused use changes nothing.
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hitResult);
        });
    }

    /**
     * Breaks one mature crop through the ordinary survival break path, so the block's own loot table decides
     * the drops (as item entities on the ground). Collection and any replanting are separate, observed steps.
     */
    boolean harvestCrop(ActionLease lease, int hotbarSlot, BlockPos target) {
        requireThread();
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) return false;
        var state = world.getBlockState(target);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMature(state)) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
            return false;
        }
        if (hotbarSlot >= 0 && hotbarSlot <= 8 && !selectHotbar(lease, hotbarSlot)) return false;
        faceBlock(target);
        return mine(lease, target);
    }

    /**
     * Sets the bed in {@code hotbarSlot} down on top of {@code floor} with its head toward {@code facing}, as a player
     * looking that way places one (E-39g): vanilla puts the foot on the clicked top and the head beyond it.
     */
    boolean placeBed(ActionLease lease, int hotbarSlot, BlockPos floor, Direction facing) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || facing.getAxis().isVertical()) return false;
        var stack = player.getInventory().getStack(hotbarSlot);
        if (!(stack.getItem() instanceof net.minecraft.item.BedItem)) return false;
        var world = player.getServerWorld();
        BlockPos foot = floor.up(), head = foot.offset(facing);
        if (!world.isChunkLoaded(head) || !world.getBlockState(foot).isReplaceable() || !world.getBlockState(head).isReplaceable()) return false;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(foot)) > 25.0) return false;
        return execute(lease, Intent.PLACE, foot, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            float yaw = facing.asRotation();
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            var hit = new BlockHitResult(new Vec3d(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5), Direction.UP, floor, false);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);
        });
    }

    /**
     * Uses the bed at {@code target} as a player right-clicks it (E-39g): vanilla sets the respawn point there, by day
     * as by night, and lays the player down only at night, when it gets straight up again.
     */
    boolean useBed(ActionLease lease, BlockPos target) {
        requireThread();
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target) || !world.getBlockState(target).isIn(BlockTags.BEDS)) return false;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > 25.0) return false;
        return execute(lease, Intent.SLEEP, target, () -> {
            var hit = new BlockHitResult(Vec3d.ofCenter(target).add(0, 0.06, 0), Direction.UP, target, false);
            player.setSneaking(false);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (player.isSleeping()) player.wakeUp(false, true);
        });
    }

    boolean sleepInBed(ActionLease lease, BlockPos target) {
        requireThread();
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) return false;
        var state = world.getBlockState(target);
        if (!state.isIn(BlockTags.BEDS)) return false;
        if (state.get(BedBlock.OCCUPIED)) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
            return false;
        }

        return execute(lease, Intent.SLEEP, target, () -> {
            try {
                player.trySleep(target);
            } catch (Exception ignored) { }
            player.setSpawnPoint(world.getRegistryKey(), target, player.getYaw(), false, true);
        });
    }

    boolean ignitePortal(ActionLease lease, int hotbarSlot, BlockPos target) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var stack = player.getInventory().getStack(hotbarSlot);
        if (stack.isEmpty()) return false;
        var item = stack.getItem();
        boolean isFlintAndSteel = item == Items.FLINT_AND_STEEL;
        boolean isFireCharge = item == Items.FIRE_CHARGE;
        if (!isFlintAndSteel && !isFireCharge) return false;

        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) return false;

        BlockPos interiorPos = target;
        if (world.getBlockState(target).isOf(Blocks.OBSIDIAN)) {
            interiorPos = target.up();
        }
        if (!world.isChunkLoaded(interiorPos)) return false;
        if (!world.getBlockState(interiorPos).isAir() && !world.getBlockState(interiorPos).isOf(Blocks.FIRE)) {
            return false;
        }

        if (player.getEyePos().squaredDistanceTo(interiorPos.getX() + 0.5, interiorPos.getY() + 0.5, interiorPos.getZ() + 0.5) > 25.0) {
            return false;
        }

        final BlockPos portalPos = interiorPos;
        final BlockPos frameBase = interiorPos.down();
        if (!world.getBlockState(frameBase).isSideSolidFullSquare(world, frameBase, Direction.UP)) return false;
        return execute(lease, Intent.IGNITE_PORTAL, portalPos, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            // Use the owned igniter on the frame's top face exactly as a player would. Vanilla places fire,
            // which lights a valid portal, and applies flint-and-steel wear or fire-charge consumption.
            var hit = new BlockHitResult(Vec3d.ofCenter(frameBase).add(0, 0.5, 0), Direction.UP, frameBase, false);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND, hit);
        });
    }

    boolean mine(ActionLease lease, BlockPos target) {
        requireThread();
        Objects.requireNonNull(target);
        if (!canMine(target)) return false;
        return execute(lease, Intent.MINE, target, () -> {
            abortMining();
            player.clearActiveItem();
            miningAttempt = Math.incrementExact(miningAttempt);
            miningTarget = target.toImmutable();
            miningState = player.getWorld().getBlockState(target);
            miningProgress = miningState.calcBlockBreakingDelta(player, player.getWorld(), target);
            player.interactionManager.processBlockBreakingAction(target, Action.START_DESTROY_BLOCK,
                    Direction.UP, player.getWorld().getTopY(), 0);
            if (player.getWorld().getBlockState(target) != miningState) abortMining();
        });
    }

    /**
     * Looks at {@code aim} and uses the item in {@code hotbarSlot} as a player would (vanilla raycasts along the
     * look direction). Used for buckets, whose fill or pour target is wherever that ray lands.
     */
    boolean useItemToward(ActionLease lease, int hotbarSlot, Vec3d aim, BlockPos target) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        return execute(lease, Intent.BUCKET, target, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            Vec3d delta = aim.subtract(player.getEyePos());
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            player.setYaw((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            player.setPitch((float) Math.toDegrees(-Math.atan2(delta.y, horizontal)));
            player.interactionManager.interactItem(player, player.getServerWorld(), player.getMainHandStack(), Hand.MAIN_HAND);
        });
    }

    /**
     * Throws the item in {@code hotbarSlot} the way a player does (an eye of ender, here): looks a little above the
     * horizon along {@code yaw} and uses it. Vanilla decides what it does; an eye with no stronghold to fly to is
     * kept, as for any player.
     */
    boolean throwHeld(ActionLease lease, int hotbarSlot, float yaw) {
        return throwHeld(lease, hotbarSlot, yaw, -15f);
    }

    /** As {@link #throwHeld(ActionLease, int, float)}, at {@code pitch}: a pearl thrown on the arc chosen for it (E-39f). */
    boolean throwHeld(ActionLease lease, int hotbarSlot, float yaw, float pitch) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90) return false;
        return execute(lease, Intent.THROW, null, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            player.setPitch(pitch);
            player.interactionManager.interactItem(player, player.getServerWorld(), player.getMainHandStack(), Hand.MAIN_HAND);
            player.clearActiveItem();
        });
    }

    /**
     * Puts the eye of ender in {@code hotbarSlot} into the End portal frame block at {@code frame}, as a player clicks
     * its top face within reach. Vanilla sets the eye, spends the item and opens the portal when the ring is full.
     */
    boolean fillFrame(ActionLease lease, int hotbarSlot, BlockPos frame) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;
        var world = player.getServerWorld();
        var state = world.getBlockState(frame);
        if (!state.isOf(net.minecraft.block.Blocks.END_PORTAL_FRAME) || state.get(net.minecraft.block.EndPortalFrameBlock.EYE)) return false;
        Vec3d top = new Vec3d(frame.getX() + 0.5, frame.getY() + 0.8125, frame.getZ() + 0.5);
        if (player.getEyePos().squaredDistanceTo(top) > 4.5 * 4.5) return false;
        return execute(lease, Intent.FRAME, frame, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            face(top);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, frame, false));
        });
    }

    /**
     * Feeds the animal the food in {@code hotbarSlot}, as a player's use click lands on it (M-01a): vanilla takes one of
     * the food and sets a grown animal ready to breed in love. Only within the reach the server allows a player's click.
     */
    boolean feed(ActionLease lease, int hotbarSlot, net.minecraft.entity.passive.AnimalEntity animal) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || animal == null || !animal.isAlive() || animal.getWorld() != player.getWorld()) return false;
        if (!player.canInteractWithEntity(animal, 0)) return false;
        Vec3d at = animal.getBoundingBox().getCenter();
        return execute(lease, Intent.FEED, null, () -> {
            if (player.getInventory().selectedSlot != hotbarSlot) {
                player.clearActiveItem();
                player.getInventory().selectedSlot = hotbarSlot;
            }
            face(at);
            player.interact(animal, Hand.MAIN_HAND);
            player.swingHand(Hand.MAIN_HAND);
        });
    }

    /**
     * Takes up to {@code count} of {@code item} out of the container at {@code chest} into the inventory, stack by stack,
     * as a player shift-clicks them out of a chest it has open (E-08c). Only within reach; what does not fit stays.
     *
     * @return how many moved
     */
    int takeFromContainer(ActionLease lease, BlockPos chest, Item item, int count) {
        requireThread();
        var world = player.getServerWorld();
        if (count <= 0 || !world.isChunkLoaded(chest) || !(world.getBlockEntity(chest) instanceof Inventory container)) return 0;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(chest)) > 4.5 * 4.5) return 0;
        int[] moved = {0};
        boolean done = execute(lease, Intent.TAKE, chest, () -> {
            for (int slot = 0; slot < container.size() && moved[0] < count; slot++) {
                ItemStack stack = container.getStack(slot);
                if (!stack.isOf(item)) continue;
                ItemStack part = stack.copyWithCount(Math.min(count - moved[0], stack.getCount()));
                int offered = part.getCount();
                player.getInventory().insertStack(part);
                int took = offered - part.getCount();
                if (took <= 0) break;
                stack.decrement(took);
                container.setStack(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                moved[0] += took;
            }
            container.markDirty();
        });
        return done ? moved[0] : 0;
    }

    /** What a container held as it was opened, and as it was shut, slot by slot: copies. */
    record Looted(List<ItemStack> opened, List<ItemStack> shut) { }

    /**
     * Opens the container at {@code chest}, or the chest minecart {@code cart}, with a use click, as a player does, which
     * fills it from its loot table if nobody has opened it before (E-42); shift-clicks out of it the slots {@code choose}
     * picks from what it sees in it, in that order; and shuts it. Only within a client's reach, and only with nothing else
     * open; null if it did not open (a block on a chest's lid, say).
     */
    Looted loot(ActionLease lease, BlockPos chest, UUID cart, java.util.function.Function<List<ItemStack>, List<Integer>> choose) {
        return useContainer(lease, chest, cart, choose, contents -> List.of());
    }

    /**
     * Opens the container at {@code chest}, or the chest minecart {@code cart}, with a use click, as {@link #loot} does;
     * shift-clicks out of it the container slots {@code out} picks, then into it the inventory slots (0 to 35)
     * {@code in} picks, as a player stores things in its own chest (E-43); and shuts it.
     */
    Looted useContainer(ActionLease lease, BlockPos chest, UUID cart, java.util.function.Function<List<ItemStack>, List<Integer>> out,
                        java.util.function.Function<List<ItemStack>, List<Integer>> in) {
        requireThread();
        var world = player.getServerWorld();
        if (player.currentScreenHandler != player.playerScreenHandler || !player.playerScreenHandler.getCursorStack().isEmpty()) return null;
        net.minecraft.entity.Entity vehicle = cart == null ? null : world.getEntity(cart);
        if (cart != null) {
            if (!(vehicle instanceof net.minecraft.entity.vehicle.StorageMinecartEntity) || !vehicle.isAlive()
                    || !player.canInteractWithEntity(vehicle, 0)) return null;
        } else if (!world.isChunkLoaded(chest) || !(world.getBlockEntity(chest) instanceof net.minecraft.inventory.Inventory)
                || player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(chest)) > 4.5 * 4.5) {
            return null;
        }
        Looted[] looted = {null};
        BlockPos at = vehicle != null ? vehicle.getBlockPos() : chest;
        boolean done = execute(lease, Intent.TAKE, at, () -> {
            if (vehicle != null) {
                face(vehicle.getBoundingBox().getCenter());
                player.interact(vehicle, Hand.MAIN_HAND);
            } else {
                Vec3d top = Vec3d.ofCenter(chest).add(0, 0.4, 0);
                face(top);
                player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND,
                        new BlockHitResult(top, Direction.UP, chest, false));
            }
            player.swingHand(Hand.MAIN_HAND);
            var handler = player.currentScreenHandler;
            if (handler == player.playerScreenHandler) return;
            // A container's own slots come first in its screen, the player's inventory after them.
            int size = handler.slots.size() - 36;
            var opened = new java.util.ArrayList<ItemStack>(size);
            for (int i = 0; i < size; i++) opened.add(handler.getSlot(i).getStack().copy());
            for (int index : out.apply(List.copyOf(opened))) {
                if (index >= 0 && index < size) handler.onSlotClick(index, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
            }
            // The inventory follows the container's slots in its screen: the three rows above, then the hotbar.
            var held = new java.util.ArrayList<ItemStack>(36);
            for (int slot = 0; slot < 36; slot++) held.add(player.getInventory().getStack(slot).copy());
            for (int slot : in.apply(List.copyOf(held))) {
                if (slot < 0 || slot >= 36) continue;
                int index = slot < 9 ? size + 27 + slot : size + slot - 9;
                if (index < handler.slots.size()) handler.onSlotClick(index, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE, player);
            }
            var shut = new java.util.ArrayList<ItemStack>(size);
            for (int i = 0; i < size; i++) shut.add(handler.getSlot(i).getStack().copy());
            player.closeHandledScreen();
            looted[0] = new Looted(List.copyOf(opened), List.copyOf(shut));
        });
        return done ? looted[0] : null;
    }

    /** A villager's offers as its trading screen showed them on opening, and how many times each was made, by its index, in order. */
    record Traded(List<net.minecraft.village.TradeOffer> offers, java.util.Map<Integer, Integer> made) { }

    /**
     * Trades with the villager {@code villager} as a player does (E-45b): opens its trading screen with a use click; for
     * each trade {@code choose} picks from the offers it sees (its index and how many times), selects it, as a client's
     * click on the offer does, which fills its slots from the inventory, and takes what it yields from the result slot
     * into the inventory, one trade at a time, stopping when the offer yields nothing more (out of stock, or not paid
     * for) or there is no room; and shuts it, which gives back what is left in its slots. Only a grown villager within a
     * client's reach, with nothing else open; null if its screen did not open (asleep, trading with someone, no offers).
     */
    Traded trade(ActionLease lease, UUID villager, java.util.function.Function<List<net.minecraft.village.TradeOffer>, List<int[]>> choose) {
        requireThread();
        var world = player.getServerWorld();
        if (player.currentScreenHandler != player.playerScreenHandler || !player.playerScreenHandler.getCursorStack().isEmpty()) return null;
        if (!(world.getEntity(villager) instanceof net.minecraft.entity.passive.VillagerEntity trader) || !trader.isAlive() || trader.isBaby()
                || !player.canInteractWithEntity(trader, 0)) return null;
        Traded[] traded = {null};
        boolean done = execute(lease, Intent.TRADE, trader.getBlockPos(), () -> {
            face(trader.getEyePos());
            player.interact(trader, Hand.MAIN_HAND);
            player.swingHand(Hand.MAIN_HAND);
            if (!(player.currentScreenHandler instanceof net.minecraft.screen.MerchantScreenHandler screen)) return;
            var offers = new java.util.ArrayList<net.minecraft.village.TradeOffer>();
            for (var offer : screen.getRecipes()) offers.add(offer.copy());
            var made = new java.util.LinkedHashMap<Integer, Integer>();
            for (int[] step : choose.apply(List.copyOf(offers))) {
                int index = step[0];
                if (index < 0 || index >= screen.getRecipes().size()) continue;
                for (int time = 0; time < step[1]; time++) {
                    screen.setRecipeIndex(index);
                    screen.switchTo(index);
                    ItemStack yields = screen.getSlot(2).getStack();
                    if (yields.isEmpty()) break;
                    int into = roomFor(screen, yields);
                    if (into < 0) break;
                    screen.onSlotClick(2, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, player);
                    if (screen.getCursorStack().isEmpty()) break;
                    screen.onSlotClick(into, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, player);
                    made.merge(index, 1, Integer::sum);
                    if (!screen.getCursorStack().isEmpty()) break;
                }
            }
            player.closeHandledScreen();
            traded[0] = new Traded(List.copyOf(offers), java.util.Collections.unmodifiableMap(made)); // In the order made.
        });
        return done ? traded[0] : null;
    }

    /** A slot of the trading screen's inventory (3 to 38) that takes all of {@code stack}: the same item with room, or empty. */
    private static int roomFor(net.minecraft.screen.MerchantScreenHandler screen, ItemStack stack) {
        int empty = -1;
        for (int i = 3; i < 39; i++) {
            ItemStack there = screen.getSlot(i).getStack();
            if (there.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (ItemStack.areItemsAndComponentsEqual(there, stack) && there.getCount() + stack.getCount() <= there.getMaxCount()) {
                return i;
            }
        }
        return empty;
    }

    /** An anvil's rename: the piece before and after, the levels before and after, and what the anvil asked. */
    record Renamed(ItemStack before, ItemStack after, int levelsBefore, int levelsAfter, int cost) { }

    /**
     * Names the piece in inventory slot {@code slot} (0 to 35) {@code name} at the anvil at {@code anvil}, as a player
     * does (E-16d): opens it with a use click, clicks the piece into its first slot, types the name, takes the named
     * piece from the result and puts it back where it was, which costs the levels the anvil asks; and shuts it. Shut
     * with nothing taken, the anvil gives the piece back. Null when the anvil would not open or the piece is gone.
     */
    Renamed rename(ActionLease lease, BlockPos anvil, int slot, String name) {
        requireThread();
        var world = player.getServerWorld();
        if (player.currentScreenHandler != player.playerScreenHandler || !player.playerScreenHandler.getCursorStack().isEmpty()) return null;
        if (slot < 0 || slot >= 36 || !world.isChunkLoaded(anvil) || !world.getBlockState(anvil).isIn(net.minecraft.registry.tag.BlockTags.ANVIL)
                || player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(anvil)) > 4.5 * 4.5) return null;
        ItemStack before = player.getInventory().getStack(slot).copy();
        if (before.isEmpty()) return null;
        int levels = player.experienceLevel;
        Renamed[] renamed = {null};
        boolean done = execute(lease, Intent.RENAME, anvil, () -> {
            Vec3d top = Vec3d.ofCenter(anvil).add(0, 0.4, 0);
            face(top);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, anvil, false));
            player.swingHand(Hand.MAIN_HAND);
            if (!(player.currentScreenHandler instanceof net.minecraft.screen.AnvilScreenHandler handler)) {
                if (player.currentScreenHandler != player.playerScreenHandler) player.closeHandledScreen();
                return;
            }
            // The anvil's three slots come first (the piece, what goes with it, the result), then the inventory: its
            // three rows, then the hotbar.
            int index = slot < 9 ? 3 + 27 + slot : 3 + slot - 9;
            var click = net.minecraft.screen.slot.SlotActionType.PICKUP;
            handler.onSlotClick(index, 0, click, player);
            handler.onSlotClick(0, 0, click, player);
            handler.setNewItemName(name);
            int cost = handler.getLevelCost();
            if (handler.getSlot(2).hasStack() && cost > 0 && cost <= player.experienceLevel) {
                handler.onSlotClick(2, 0, click, player);
                handler.onSlotClick(index, 0, click, player);
            }
            player.closeHandledScreen();
            renamed[0] = new Renamed(before, player.getInventory().getStack(slot).copy(), levels, player.experienceLevel, cost);
        });
        return done ? renamed[0] : null;
    }

    /**
     * Opens or shuts the wooden door at {@code door} (either half) with a use click on its near side, as a player does on
     * its way through (M-01b).
     */
    boolean useDoor(ActionLease lease, BlockPos door) {
        requireThread();
        var world = player.getServerWorld();
        var state = world.getBlockState(door);
        if (!(state.getBlock() instanceof net.minecraft.block.DoorBlock block) || !block.getBlockSetType().canOpenByHand() || player.isSneaking()) return false;
        Vec3d centre = Vec3d.ofCenter(door);
        if (player.getEyePos().squaredDistanceTo(centre) > 4.5 * 4.5) return false;
        Vec3d eye = player.getEyePos();
        Direction side = Direction.getFacing(eye.x - centre.x, 0, eye.z - centre.z);
        Vec3d hit = centre.add(side.getOffsetX() * 0.4, 0, side.getOffsetZ() * 0.4);
        return execute(lease, Intent.DOOR, door, () -> {
            face(hit);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND,
                    new BlockHitResult(hit, side, door, false));
            player.swingHand(Hand.MAIN_HAND);
        });
    }

    /**
     * Opens or shuts the fence gate at {@code gate} with a use click on its near side, as a player does (M-01a). Nothing
     * else is used: whatever the hand holds, a gate's own use comes first, and the player is never sneaking.
     */
    boolean useGate(ActionLease lease, BlockPos gate) {
        requireThread();
        var world = player.getServerWorld();
        var state = world.getBlockState(gate);
        if (!(state.getBlock() instanceof net.minecraft.block.FenceGateBlock) || player.isSneaking()) return false;
        Vec3d centre = Vec3d.ofCenter(gate);
        if (player.getEyePos().squaredDistanceTo(centre) > 4.5 * 4.5) return false;
        Vec3d eye = player.getEyePos();
        Direction side = Direction.getFacing(eye.x - centre.x, 0, eye.z - centre.z);
        Vec3d hit = centre.add(side.getOffsetX() * 0.5, 0, side.getOffsetZ() * 0.5);
        return execute(lease, Intent.GATE, gate, () -> {
            face(hit);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND,
                    new BlockHitResult(hit, side, gate, false));
            player.swingHand(Hand.MAIN_HAND);
        });
    }

    /**
     * Draws the bow in {@code hotbarSlot} along {@code yaw} and {@code pitch}, as a player holds the use key (E-39b):
     * vanilla draws only with an arrow carried, and the draw strengthens for a second.
     */
    boolean drawBow(ActionLease lease, int hotbarSlot, float yaw, float pitch) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || !player.getInventory().getStack(hotbarSlot).isOf(net.minecraft.item.Items.BOW)) return false;
        return execute(lease, Intent.SHOOT, null, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            look(yaw, pitch);
            player.interactionManager.interactItem(player, player.getServerWorld(), player.getMainHandStack(), Hand.MAIN_HAND);
        });
    }

    /**
     * Turns the head to {@code yaw} and {@code pitch} where it stands, as a player moves its view while it waits (E-33a).
     * The caller eases the turn; this only sets it, under the task's lease.
     */
    boolean glance(ActionLease lease, float yaw, float pitch) {
        requireThread();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Invalid look");
        return execute(lease, Intent.LOOK, null, () -> {
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            player.setPitch(Math.clamp(pitch, -90f, 90f));
        });
    }

    /** Keeps the drawn bow on a target that moves: the aim follows it. */
    boolean aimBow(ActionLease lease, float yaw, float pitch) {
        requireThread();
        return execute(lease, Intent.SHOOT, null, () -> look(yaw, pitch));
    }

    /** Looses the drawn arrow along the aim, as a player lets go of the use key. */
    boolean looseBow(ActionLease lease) {
        requireThread();
        if (!player.isUsingItem() || !player.getActiveItem().isOf(net.minecraft.item.Items.BOW)) return false;
        return execute(lease, Intent.SHOOT, null, player::stopUsingItem);
    }

    private void look(float yaw, float pitch) {
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(pitch);
    }

    /** The boat the body sits in, if it is in one. */
    Optional<BoatEntity> boat() {
        requireThread();
        return player.getVehicle() instanceof BoatEntity boat ? Optional.of(boat) : Optional.empty();
    }

    /**
     * Puts the boat in {@code hotbarSlot} on the water at {@code water}, as a player does: looking at the water's surface
     * and using the item, which places it where the look meets the water. Vanilla spends the item.
     */
    boolean launchBoat(ActionLease lease, int hotbarSlot, BlockPos water) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || player.hasVehicle()) return false;
        Vec3d surface = new Vec3d(water.getX() + 0.5, water.getY() + 0.9, water.getZ() + 0.5);
        if (player.getEyePos().distanceTo(surface) > 4.0) return false;
        return execute(lease, Intent.BOAT, water, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            face(surface);
            player.interactionManager.interactItem(player, player.getServerWorld(), player.getMainHandStack(), Hand.MAIN_HAND);
        });
    }

    /** Into {@code boat}, as a player clicks on one within reach. */
    boolean board(ActionLease lease, BoatEntity boat) {
        requireThread();
        // Reach as the server allows a player's click on an entity: to its box, within interaction range.
        if (boat == null || !boat.isAlive() || player.hasVehicle() || !player.canInteractWithEntity(boat, 0)) return false;
        boolean[] boarded = {false};
        boolean done = execute(lease, Intent.BOAT, boat.getBlockPos(), () -> {
            face(boat.getPos());
            boarded[0] = player.interact(boat, Hand.MAIN_HAND).isAccepted() && player.getVehicle() == boat;
        });
        return done && boarded[0];
    }

    /** The paddles: forward and either way, held until changed or halted. Looks where the boat heads. */
    boolean row(ActionLease lease, boolean ahead, boolean left, boolean right) {
        requireThread();
        if (!(player.getVehicle() instanceof BoatEntity boat)) return false;
        return execute(lease, Intent.MOVE, null, () -> {
            rowForward = ahead;
            rowLeft = left;
            rowRight = right;
            boat.setInputs(left, right, ahead, false);
            player.setYaw(boat.getYaw());
            player.setHeadYaw(boat.getYaw());
            player.setPitch(10f);
        });
    }

    /**
     * Out of the boat, as a player sneaks to get out, looking along {@code yaw}: vanilla sets a rider down on the side
     * it faces when there is dry ground there, beside the boat on its next tick.
     */
    boolean leaveBoat(ActionLease lease, float yaw) {
        requireThread();
        if (!(player.getVehicle() instanceof BoatEntity boat) || !Float.isFinite(yaw)) return false;
        return execute(lease, Intent.BOAT, boat.getBlockPos(), () -> {
            rowForward = rowLeft = rowRight = false;
            boat.setInputs(false, false, false, false);
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            player.setSneaking(true);
        });
    }

    /**
     * Puts the minecart in {@code hotbarSlot} on the rail at {@code rail}, as a player does: looking at the rail and
     * using the item on it (E-45e). Vanilla spends the item and sets the cart on the rail.
     */
    boolean placeCart(ActionLease lease, int hotbarSlot, BlockPos rail) {
        requireThread();
        if (hotbarSlot < 0 || hotbarSlot > 8 || player.hasVehicle()) return false;
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(rail) || !world.getBlockState(rail).isIn(net.minecraft.registry.tag.BlockTags.RAILS)) return false;
        if (!(player.getInventory().getStack(hotbarSlot).getItem() instanceof net.minecraft.item.MinecartItem)) return false;
        Vec3d top = new Vec3d(rail.getX() + 0.5, rail.getY() + 0.1, rail.getZ() + 0.5);
        if (player.getEyePos().distanceTo(top) > 4.5) return false;
        return execute(lease, Intent.CART, rail, () -> {
            player.getInventory().selectedSlot = hotbarSlot;
            face(top);
            player.interactionManager.interactBlock(player, world, player.getMainHandStack(), Hand.MAIN_HAND,
                    new BlockHitResult(top, Direction.UP, rail, false));
        });
    }

    /** One hit on a minecart within reach, as a player breaks one to take it back; a rider in it is set down (E-45e). */
    boolean strikeCart(ActionLease lease, net.minecraft.entity.vehicle.AbstractMinecartEntity cart) {
        requireThread();
        if (cart == null || !cart.isAlive() || player.hasVehicle() || !player.canInteractWithEntity(cart, 0)) return false;
        return execute(lease, Intent.CART, cart.getBlockPos(), () -> {
            face(cart.getPos().add(0, 0.3, 0));
            player.attack(cart);
            player.swingHand(Hand.MAIN_HAND);
        });
    }

    /** One hit on an empty boat within reach, as a player breaks one to take it back. */
    boolean strikeBoat(ActionLease lease, BoatEntity boat) {
        requireThread();
        if (boat == null || !boat.isAlive() || boat.hasPassengers() || player.hasVehicle() || !player.canInteractWithEntity(boat, 0)) return false;
        return execute(lease, Intent.ATTACK, boat.getBlockPos(), () -> {
            face(boat.getPos().add(0, 0.3, 0));
            player.attack(boat);
            player.swingHand(Hand.MAIN_HAND);
        });
    }

    private void face(Vec3d at) {
        Vec3d eye = player.getEyePos();
        double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setPitch(pitch);
    }

    boolean miningEligible(BlockPos target) { requireThread(); return player.isAlive() && canMine(target); }

    /** Whether survival mining of {@code target} is still in progress (not aborted or finished). */
    boolean miningActive(BlockPos target) { requireThread(); return target.equals(miningTarget); }

    boolean applyChestMove(ActionLease lease, BlockPos chestPos, ChestOrganizationPolicy.ChestSlotMove move) {
        requireThread();
        if (move == null || chestPos == null) return false;
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(chestPos)) return false;
        BlockEntity entity = world.getBlockEntity(chestPos);
        if (!(entity instanceof Inventory container)) return false;
        int from = move.fromSlot();
        int to = move.toSlot();
        if (from < 0 || from >= container.size() || to < 0 || to >= container.size() || from == to) return false;
        return execute(lease, Intent.CHEST_ORGANIZE, chestPos, () -> {
            var src = container.getStack(from);
            var dst = container.getStack(to);
            if (move.kind() == ChestOrganizationPolicy.ChestSlotMove.Kind.MERGE_PARTIAL_STACK) {
                if (src.isEmpty() || dst.isEmpty() || !ItemStack.areItemsAndComponentsEqual(src, dst)) return;
                int space = dst.getMaxCount() - dst.getCount();
                int transfer = Math.min(space, src.getCount());
                if (transfer <= 0) return;
                dst.increment(transfer);
                src.decrement(transfer);
                container.setStack(to, dst);
                container.setStack(from, src.isEmpty() ? ItemStack.EMPTY : src);
            } else {
                container.setStack(from, dst);
                container.setStack(to, src);
            }
            container.markDirty();
            entity.markDirty();
        });
    }

    boolean tossTowardPlayer(ActionLease lease, int inventorySlot, int count, ServerPlayerEntity recipient) {
        requireThread();
        if (recipient == null || inventorySlot < 0 || inventorySlot >= 36 || count <= 0) return false;
        if (player.squaredDistanceTo(recipient) > 16.0) return false;
        var stack = player.getInventory().getStack(inventorySlot);
        if (stack.isEmpty() || stack.getCount() < count) return false;
        return execute(lease, Intent.TOSS, null, () -> {
            ItemStack live = player.getInventory().getStack(inventorySlot);
            if (live.isEmpty() || live.getCount() < count) return;
            ItemStack gift = live.split(count);
            Vec3d toward = recipient.getEyePos().subtract(player.getEyePos()).normalize();
            player.dropItem(gift, false, false);
            var dropped = player.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                    player.getBoundingBox().expand(2.0), e -> e.getOwner() == player).stream().findFirst();
            dropped.ifPresent(item -> item.setVelocity(toward.x * 0.35, 0.2, toward.z * 0.35));
        });
    }

    /** Drops owned items at the player's feet the way a person presses Q; the items stay in the world. */
    boolean discard(ActionLease lease, int inventorySlot, int count) {
        requireThread();
        if (inventorySlot < 0 || inventorySlot >= 36 || count <= 0) return false;
        var stack = player.getInventory().getStack(inventorySlot);
        if (stack.isEmpty() || stack.getCount() < count) return false;
        return execute(lease, Intent.TOSS, null, () -> {
            ItemStack live = player.getInventory().getStack(inventorySlot);
            if (live.isEmpty() || live.getCount() < count) return;
            // Throw it behind, away from the way the player is heading, so it is not walked straight back over.
            player.setYaw(player.getYaw() + 180);
            player.setPitch(0);
            player.dropItem(live.split(count), false, false);
        });
    }

    private boolean execute(ActionLease lease, Intent intent, BlockPos target, Runnable action) {
        requireThread();
        return current(lease) && gate.permits(lease, intent, target)
                && arbiter.executeIfCurrent(lease, action);
    }

    private boolean current(ActionLease lease) {
        var mode = player.interactionManager.getGameMode();
        return lease != null && Objects.equals(controls, lease) && arbiter != null
                && arbiter.isCurrent(lease) && player.isAlive() && !player.isRemoved()
                && (mode == GameMode.SURVIVAL || mode == GameMode.CREATIVE && creativeAllowed);
    }

    /**
     * Whether the body may act in creative: only while the runtime has it build in a build battle (M-04a), the one mode
     * where blocks are free. Otherwise controls work in survival alone.
     */
    void allowCreative(boolean allowed) { requireThread(); creativeAllowed = allowed; }

    private boolean canMine(BlockPos target) {
        ServerWorld world = player.getServerWorld();
        if (!world.isChunkLoaded(target) || !world.getWorldBorder().contains(target)
                || !player.canInteractWithBlockAt(target, 0) || !world.canPlayerModifyAt(player, target)
                || world.getBlockState(target).isAir()
                || world.getBlockState(target).getHardness(world, target) < 0) return false;
        return aimPoint(target) != null;
    }

    /**
     * Where the eyes can see {@code target}: its centre, or else the middle of a face turned toward the player, as
     * a player aims at whatever part of a block shows. A block seen past a corner, its centre hidden behind a
     * neighbour, is still in view (live sky-island run 1 wrote off a whole outcrop it stood diagonally beside).
     */
    private Vec3d aimPoint(BlockPos target) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(target);
        if (sees(world, eye, center, target)) return center;
        for (Direction face : Direction.values()) {
            Vec3d point = center.add(face.getOffsetX() * 0.45, face.getOffsetY() * 0.45, face.getOffsetZ() * 0.45);
            if (point.subtract(eye).dotProduct(Vec3d.of(face.getVector())) >= 0) continue; // Faces away from the eyes.
            if (sees(world, eye, point, target)) return point;
        }
        return null;
    }

    private boolean sees(ServerWorld world, Vec3d eye, Vec3d point, BlockPos target) {
        BlockHitResult hit = world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    void faceBlock(BlockPos target) {
        requireThread();
        Vec3d eye = player.getEyePos();
        Vec3d aim = player.getServerWorld().isChunkLoaded(target) ? aimPoint(target) : null;
        Vec3d center = aim != null ? aim : Vec3d.ofCenter(target);
        Vec3d delta = center.subtract(eye);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horiz));
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    /** Runs immediately before native interactionManager.update and player physics/item use. */
    private void beforeTick() {
        requireThread();
        if (connected && connection != null && !connection.open) disconnect();
        if (arbiter != null) arbiter.tick();
        ticksRun++;
        tickHook.run();
        if (!current(controls)
                || ((forward != 0 || sideways != 0 || jump || sprint || rowForward || rowLeft || rowRight) && !gate.permits(controls, Intent.MOVE, null))
                || (player.isUsingItem() && !gate.permits(controls, Intent.EAT, null) && !gate.permits(controls, Intent.BLOCK, null)
                    && !gate.permits(controls, Intent.SHOOT, null))
                || (miningTarget != null && (!gate.permits(controls, Intent.MINE, miningTarget)
                    || !canMine(miningTarget) || player.getWorld().getBlockState(miningTarget) != miningState))) {
            if (controls != null && arbiter != null) {
                if (current(controls)) KindredMod.LOGGER.debug("{} controls revoked: move={} use={} mineGate={} canMine={} sameState={}",
                        identity.displayName(),
                        (forward != 0 || sideways != 0 || jump || sprint) && !gate.permits(controls, Intent.MOVE, null),
                        player.isUsingItem(), miningTarget != null && gate.permits(controls, Intent.MINE, miningTarget),
                        miningTarget != null && canMine(miningTarget),
                        miningTarget != null && player.getWorld().getBlockState(miningTarget) == miningState);
                arbiter.cancel(controls);
            }
            haltControls();
            controls = null;
        }
        // As a client does: an item in use (a raised shield, food being eaten) slows walking to a fifth, and no sprint.
        float pace = player.isUsingItem() ? 0.2f : 1.0f;
        player.applyInput(forward * pace, sideways * pace);
        player.setJumping(jump);
        player.setSprinting(sprint && !player.isUsingItem() && player.getHungerManager().getFoodLevel() > 6);
        if (player.getVehicle() instanceof BoatEntity boat) boat.setInputs(rowLeft, rowRight, rowForward, false);
    }

    private void afterTick() {
        if (miningTarget == null) return;
        BlockPos target = miningTarget;
        // Vanilla client-equivalent progress; the server interaction manager verifies the break/tool/drop.
        if (player.getWorld().getBlockState(target) != miningState) { abortMining(); return; }
        miningProgress += miningState.calcBlockBreakingDelta(player, player.getWorld(), target);
        if (miningProgress >= 1 && canMine(target)) {
            execute(controls, Intent.MINE, target, () -> player.interactionManager.processBlockBreakingAction(
                    target, Action.STOP_DESTROY_BLOCK, Direction.UP, player.getWorld().getTopY(), 0));
            abortMining();
        }
    }

    private void haltControls() {
        forward = sideways = 0;
        jump = sprint = false;
        rowForward = rowLeft = rowRight = false;
        if (player.getVehicle() instanceof BoatEntity boat) boat.setInputs(false, false, false, false);
        player.applyInput(0, 0);
        player.setJumping(false);
        player.setSprinting(false);
        player.clearActiveItem();
        abortMining();
    }

    private void abortMining() {
        if (miningTarget == null) return;
        BlockPos old = miningTarget;
        miningTarget = null;
        miningState = null;
        miningProgress = 0;
        player.interactionManager.processBlockBreakingAction(old, Action.ABORT_DESTROY_BLOCK,
                Direction.UP, player.getWorld().getTopY(), 0);
    }

    private void requireThread() {
        if (!player.server.isOnThread()) throw new IllegalStateException("Native body access requires the server thread");
    }

    private final class NativePlayer extends ServerPlayerEntity implements PreparedNativePlayer {
        private Optional<NbtCompound> preparedState;
        NativePlayerBody body() { return NativePlayerBody.this; }
        private NativePlayer(ServerWorld world, GameProfile profile) {
            super(world.getServer(), world, profile, new SyncedClientOptions("en_us", 2,
                    net.minecraft.network.message.ChatVisibility.HIDDEN, false, 127,
                    net.minecraft.util.Arm.RIGHT, false, false));
            networkHandler = new SinkNetworkHandler(this);
        }

        @Override public void tick() {
            if (this != player) return; // A dead entity replaced by respawn no longer drives the body.
            beforeTick();
            if (isRemoved()) return;
            super.tick();
            // Human physics/item/hunger ticks normally come from the network handler. There is no client.
            super.playerTick();
            // Movement packets keep a human's chunk tickets and tracking centred on them; follow the body here,
            // or the loaded area stays where it joined and the body freezes once it walks out of it.
            if (!isRemoved() && connected && server.getPlayerManager().getPlayer(getUuid()) == this) {
                getServerWorld().getChunkManager().updatePosition(this);
            }
            afterTick();
        }

        /**
         * Moved outside its own tick (a teleport by command, a fixture repositioning it, a push from another entity's
         * tick), the body's chunk tickets follow it at once. A world ticks an entity only where a chunk is simulated,
         * and a body moved into a chunk its tickets do not cover was never ticked again to move them: it froze where it
         * stood (M-05's rare idle start). A human's client re-centres them with its next movement packet.
         */
        @Override public void setPosition(double x, double y, double z) {
            super.setPosition(x, y, z);
            if (this == player && !isRemoved() && connected && getWatchedSection() != null
                    && !getWatchedSection().equals(net.minecraft.util.math.ChunkSectionPos.from(this))
                    && server.getPlayerManager().getPlayer(getUuid()) == this && getServerWorld().getPlayers().contains(this)) {
                getServerWorld().getChunkManager().updatePosition(this);
            }
        }

        @Override protected int getPermissionLevel() { return 0; }

        /**
         * A hit from another player is weighed by what it took, as the health and absorption it cost, after armour,
         * enchantments and a shield: vanilla's own reckoning, read rather than redone. A killing blow is no hit to answer.
         */
        @Override public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
            float before = getHealth() + getAbsorptionAmount();
            boolean took = super.damage(source, amount);
            if (took && this == player && !isDead() && source.getAttacker() instanceof ServerPlayerEntity by && by != this) {
                hitHook.hit(by, Math.max(0, before - getHealth() - getAbsorptionAmount()));
            }
            return took;
        }

        @Override public void onDeath(net.minecraft.entity.damage.DamageSource damageSource) {
            if (this == player) {
                haltControls();
                deathHook.run();
            }
            super.onDeath(damageSource);
        }

        @Override public ServerPlayerEntity kindred$respawned(ServerWorld world) {
            requireThread();
            if (!respawning || this != player) throw new IllegalStateException("A native body respawns only through its own body");
            NativePlayer next = new NativePlayer(world, getGameProfile());
            next.preparedState = Optional.empty();
            return next;
        }

        private void recordBreak(BlockPos position, BlockState previousState) {
            recordMiningBreak(position, previousState);
        }

        private boolean allowBreak(BlockPos position, BlockState state) {
            requireThread();
            return current(controls) && position.equals(miningTarget) && state == miningState
                    && gate.permits(controls, Intent.MINE, position) && canMine(position);
        }

        @Override protected void consumeItem() {
            // A long tick may cross the prepaid deadline after beforeTick checked it.
            if (current(controls) && gate.permits(controls, Intent.EAT, null)) {
                boolean food = getActiveItem().contains(DataComponentTypes.FOOD);
                super.consumeItem();
                if (food) foodUses++;
            } else clearActiveItem();
        }

        /** Cross-dimension portal usage qualified under E-15 when authorized. */
        @Override public boolean canUsePortals(boolean allowVehicles) { return portalTravelAuthorized; }

        /** Physics is advanced only by tick(), never a second time through a network handler. */
        @Override public void playerTick() { }

        @Override public Optional<NbtCompound> kindred$loadPreparedState() {
            requireThread();
            if (preparedState == null) throw new IllegalStateException("Native save was not prepared before connection");
            preparedLoadCalled = true;
            preparedState.ifPresent(state -> readNbt(state.copy()));
            return preparedState.map(NbtCompound::copy);
        }

        @Override public void writeCustomDataToNbt(NbtCompound nbt) {
            super.writeCustomDataToNbt(nbt);
            nbt.putUuid("KindredNativeIdentity", identity.id());
            nbt.putUuid("KindredWorldIdentity", identity.worldId());
            nbt.putBoolean("KindredNativeStopped", arbiter != null && arbiter.snapshot().stopLatched());
            if (saveReceipt != null) nbt.putUuid("KindredNativeSaveReceipt", saveReceipt);
        }

        @Override public Text getPlayerListName() { return Text.literal(identity.displayName()); }

        private void applyInput(float forward, float sideways) {
            this.forwardSpeed = forward;
            this.sidewaysSpeed = sideways;
        }

        @Override protected void fall(double heightDifference, boolean onGround, BlockState state, BlockPos pos) {
            // Human clients report this through movement packets; this body's collision physics supplies it.
            handleFall(0, heightDifference, 0, onGround);
        }
    }

    /** No socket, packet queue, external account or client-side action simulation. Tracking is F-25b. */
    private static final class SinkNetworkHandler extends ServerPlayNetworkHandler {
        private SinkNetworkHandler(ServerPlayerEntity player) {
            super(player.server, new ClientConnection(NetworkSide.SERVERBOUND), player,
                    ConnectedClientData.createDefault(player.getGameProfile(), false));
        }
        @Override public void send(Packet<?> packet, PacketCallbacks callbacks) { }
    }

    /** Never registered with ServerNetworkIo: world ticking owns the body and no packets accumulate. */
    private static final class NativeConnection extends ClientConnection {
        private boolean open = true;
        private PacketListener listener;
        private NativeConnection() { super(NetworkSide.SERVERBOUND); }
        @Override public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T listener) { this.listener = listener; }
        @Override public void transitionOutbound(NetworkState<?> state) { }
        @Override public PacketListener getPacketListener() { return listener; }
        @Override public void send(Packet<?> packet, PacketCallbacks callbacks, boolean flush) {
            if (callbacks != null) callbacks.onSuccess();
        }
        @Override public void tick() { }
        @Override public void flush() { }
        @Override public void tryDisableAutoRead() { }
        @Override public void handleDisconnection() { } // Body/world tick owns native removal.
        @Override public boolean isOpen() { return open; }
        @Override public boolean isLocal() { return true; }
        @Override public SocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getAddressAsString(boolean logIps) { return "Kindred native player"; }
        @Override public void disconnect(DisconnectionInfo info) { open = false; }
    }
}
