package dev.kindred.fabric;

import dev.kindred.core.InventorySummary;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.ai.control.JumpControl;
import net.minecraft.entity.ai.control.LookControl;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Legacy mob-body backend retained for saves and fixtures. F-25b replaces it with qualified native player mechanics. */
public final class CompanionEntity extends PathAwareEntity implements PolymerEntity {
    static final int INVENTORY_SIZE = 27;
    private UUID companionId;
    private UUID ownerId;
    private boolean ownerStopped;
    private long inventoryRevision = 0;
    /** Native slots. This list is the item authority; metadata never copies it. */
    private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);

    public CompanionEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.moveControl = new CompanionMoveControl(this);
        this.jumpControl = new CompanionJumpControl(this);
        this.lookControl = new CompanionLookControl(this);
        setPersistent();
        setCanPickUpLoot(false);
        this.inventory.addListener(inv -> inventoryRevision++);
    }

    public UUID companionId() { return companionId; }
    public UUID ownerId() { return ownerId; }
    public boolean ownerStopped() { return ownerStopped; }
    public void ownerStopped(boolean stopped) { ownerStopped = stopped; }
    public SimpleInventory inventory() { return inventory; }
    public long inventoryRevision() { return inventoryRevision; }

    public InventorySummary summary() {
        int totalItems = 0;
        int occupied = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) {
                occupied++;
                totalItems += stack.getCount();
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        return new InventorySummary(inventoryRevision, INVENTORY_SIZE, occupied, totalItems, counts);
    }

    /** Equips a tool or weapon from native inventory into the specified slot. Reflects real inventory. */
    public boolean equipFromInventory(EquipmentSlot slot, Item item) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(item)) {
                equipStack(slot, stack.copy());
                return true;
            }
        }
        return false;
    }

    /** Clears an equipment slot (e.g. sheathing tool back into inventory). */
    public void clearEquipment(EquipmentSlot slot) {
        equipStack(slot, ItemStack.EMPTY);
    }

    /** Pure presentation mapping: vanilla clients never need Kindred's entity registry. */
    @Override
    public EntityType<?> getPolymerEntityType(ServerPlayerEntity player) {
        return EntityType.VILLAGER;
    }

    /** Keep the same compatible presentation even when a viewer happens to have Polymer. */
    @Override
    public boolean canSynchronizeToPolymerClient(ServerPlayerEntity player) {
        return false;
    }

    /** Cancel requested motion as well as the navigation path; gravity/inertia are not reversed. */
    void haltControls() {
        getNavigation().stop();
        setSprinting(false); // A habit is presentation on a lease; it ends with the lease.
        ((CompanionMoveControl) moveControl).halt();
        ((CompanionJumpControl) jumpControl).halt();
        ((CompanionLookControl) lookControl).halt();
        clearActiveItem();
    }

    public void bind(UUID companion, UUID owner) {
        if (companionId != null && (!companionId.equals(companion) || !ownerId.equals(owner))) {
            throw new IllegalStateException("A companion body cannot change ownership");
        }
        companionId = companion;
        ownerId = owner;
    }

    @Override
    public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        boolean result = super.damage(source, amount);
        // Route player attacks to the runtime for hit-back / duel logic on the server thread.
        // super.damage() already runs on the server thread; this call is synchronous and safe.
        if (result && source.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity attacker) {
            PlayerRuntime rt = KindredMod.runtime();
            if (rt != null) rt.playerAttackedCompanion(attacker, this);
        }
        return result;
    }

    @Override
    public void onDeath(net.minecraft.entity.damage.DamageSource damageSource) {
        super.onDeath(damageSource);
        PlayerRuntime rt = KindredMod.runtime();
        if (rt != null) rt.entityDied(this);
    }

    @Override
    public void tick() {
        if (!getWorld().isClient) {
            PlayerRuntime runtime = KindredMod.runtime();
            if (runtime == null) haltControls();
            else runtime.beforeEntityTick(this);
        }
        super.tick();
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (companionId != null) nbt.putUuid("KindredCompanion", companionId);
        if (ownerId != null) nbt.putUuid("KindredOwner", ownerId);
        nbt.putBoolean("KindredOwnerStopped", ownerStopped);
        nbt.putLong("KindredInventoryRevision", inventoryRevision);
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        for (int slot = 0; slot < inventory.size(); slot++) stacks.set(slot, inventory.getStack(slot).copy());
        Inventories.writeNbt(nbt, stacks, getRegistryManager());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        companionId = nbt.containsUuid("KindredCompanion") ? nbt.getUuid("KindredCompanion") : null;
        ownerId = nbt.containsUuid("KindredOwner") ? nbt.getUuid("KindredOwner") : null;
        ownerStopped = nbt.getBoolean("KindredOwnerStopped");
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(inventory.size(), ItemStack.EMPTY);
        Inventories.readNbt(nbt, stacks, getRegistryManager());
        for (int slot = 0; slot < inventory.size(); slot++) inventory.setStack(slot, stacks.get(slot));
        inventoryRevision = nbt.getLong("KindredInventoryRevision");
        haltControls();
        setPersistent();
        setCanPickUpLoot(false);
    }

    private static final class CompanionLookControl extends LookControl {
        private CompanionLookControl(CompanionEntity entity) { super(entity); }
        private void halt() { lookAtTimer = 0; }
    }

    /** Whether a jump has been requested for the next control tick; used by native fixtures. */
    boolean jumpRequested() { return ((CompanionJumpControl) jumpControl).pending(); }

    private static final class CompanionJumpControl extends JumpControl {
        private CompanionJumpControl(CompanionEntity entity) { super(entity); }
        private void halt() { active = false; }
        private boolean pending() { return active; }
    }

    private static final class CompanionMoveControl extends MoveControl {
        private CompanionMoveControl(CompanionEntity entity) { super(entity); }
        private void halt() {
            state = State.WAIT;
            forwardMovement = 0;
            sidewaysMovement = 0;
            speed = 0;
            entity.setForwardSpeed(0);
            entity.setSidewaysSpeed(0);
            entity.setMovementSpeed(0);
            entity.setJumping(false);
        }
    }
}
