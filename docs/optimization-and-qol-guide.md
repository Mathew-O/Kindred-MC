# Kindred MC — Optimization, Minecraft 1.21.1 Mechanics & Quality-of-Life Implementation Guide
[Player Contract](../minecraft_player_spec.md) · [Architecture](player-architecture.md) · [Feature Tracker](feature-spec.md) · [Validation](validation.md)

Version 1.0 · 18 October 2026

This engineering guide translates the post-`E-11`/`E-12`/`E-13`/`E-07c` technical audit and [Minecraft Wiki (Java Edition 1.21.1)](https://minecraft.wiki/) mechanics review into **self-contained, step-by-step implementation blueprints**. Each blueprint is written so an implementing agent can execute the change safely without guessing class names, method signatures, thread boundaries, or conservation invariants.

---

## 0. Mandatory Rules for Implementing Agents (Read Before Touching Code)

Every optimization or quality-of-life slice in this guide **must** obey the workspace boundaries in `AGENTS.md`:

1. **Claim Work First**: Before editing code, claim the relevant open feature ID (or child slice) and intended files in the `Active work` table of [`docs/feature-spec.md`](feature-spec.md). Put a `<!-- LIVE EDIT: ... -->` marker at the top of any contract/docs file while editing it and remove the marker before handoff.
2. **Core vs. Adapter Separation**:
   - `companion-core` (`dev.kindred.core.*`) is **pure Java 21**. Never import `net.minecraft.*` into `companion-core`. Pass only immutable primitives, strings, UUIDs, and records across the boundary.
   - `companion-server-adapter` (`dev.kindred.fabric.*`) runs on the Fabric server thread. Never block the server thread on SQLite futures (`CompletableFuture.join()` / `.get()`).
3. **Single Body Controller & Lease Fencing**:
   - All physical actions must flow through `PlayerRuntime` $\rightarrow$ `SkillRunner` $\rightarrow$ `ActionArbiter` lease $\rightarrow$ `ActionAdmission` (`PREPARED` $\rightarrow$ `DISPATCHED` $\rightarrow$ single `take()` $\rightarrow$ `VERIFIED` / `UNCERTAIN`).
   - Never add direct inventory mutations (`insertStack`, `removeStack`) outside an admitted action, and never synthesize items (`PLY-04`).
4. **Verification Commands**: Run and pass all four commands before claiming completion:
   ```powershell
   .\gradlew.bat build
   .\gradlew.bat :companion-testkit:run
   .\gradlew.bat :companion-testkit:runPersonalityDemo
   .\gradlew.bat :companion-server-adapter:runGametest
   ```

---

## 1. Server-Tick Performance & Spatial Scanning Blueprints

### OPT-01 (P0): Budgeted & Palette-Filtered Proximity Block Scans
- **Related Tracker IDs**: `F-12`, `F-24`, `F-25c`
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java` (lines `683–757`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java` (lines `1637–1735`, `1970–2045`)

#### Problem Statement
`PlayerRuntime` enforces `MAX_BLOCK_CHECKS_PER_TICK = 128` inside `budgetedResourceScan` (`line 1255`), but `NativePlayerBody.findNearbyBlock`, `findNearbyBed`, and `findNearbyFarmland` scan an unbudgeted $11 \times 11 \times 11 = 1,331$-block cube (`r = (int) Math.ceil(4.5) = 5`, `dx/dy/dz` from `-5..5`).
When workstations, beds, or farmland are absent nearby, `PlayerRuntime.observeProgression` calls `findNearbyBlock(CRAFTING_TABLE, 4.5)`, `findNearbyBlock(FURNACE, 4.5)`, `findNearbyFarmland(4.5)` (twice!), and `findNearbyBed(4.5)`, totaling **$6,655$ unbudgeted `world.getBlockState` calls** in a single routine slice—plus two $7 \times 3 \times 7$ loops in `evaluateScheduledRoutineCandidates` (where `isWaterNearby` runs a $9 \times 2 \times 9 = 162$-block inner loop per dirt block).

#### Step-by-Step Implementation Guide

**Step 1: Add ChunkSection palette prefiltering and vertical clamping in `NativePlayerBody.java`**
In `NativePlayerBody.java`, players interact with blocks within vertical reach `[-2, +2]` from eye/foot level, and `ChunkSection.hasAny(...)` can reject an entire $16 \times 16 \times 16$ section in $O(\text{palette})$ time if the target block does not exist in that section's palette.

Replace `findNearbyBlock`, `findNearbyBed`, and `findNearbyFarmland` (`NativePlayerBody.java:683–757`) with a shared helper that:
1. Clamps `dy` to `Math.min(r, 3)` (`[-3, +3]` around `BlockPos.ofFloored(eyePos)` reduces $11 \times 11 \times 11 = 1,331$ to $11 \times 7 \times 11 = 847$ max coordinates).
2. Checks `pos.getSquaredDistance(eyePos) <= maxDistSq` **before** calling `world.getBlockState(pos)` (a sphere of radius $4.5$ inside $11 \times 7 \times 11$ visits only $\approx 310$ positions instead of $1,331$).
3. Checks the `ChunkSection` palette via `chunk.getSection(chunk.sectionCoordToIndex(pos.getY() >> 4)).hasAny(Predicate<BlockState>)` once per overlapped section so sections without the target block skip all block lookups entirely (`0` block checks!).

```java
    Optional<BlockPos> findNearbyBlockMatching(double maxReach, java.util.function.Predicate<BlockState> matcher) {
        if (!connected()) return Optional.empty();
        var world = player.getServerWorld();
        var eyePos = player.getEyePos();
        int r = (int) Math.ceil(maxReach);
        int ry = Math.min(r, 3);
        BlockPos center = BlockPos.ofFloored(eyePos);
        BlockPos best = null;
        double bestDistSq = maxReach * maxReach;

        // Cache palette check per (chunkX, sectionY, chunkZ)
        long lastSectionKey = Long.MIN_VALUE;
        boolean lastSectionMayContain = false;

        for (int dy = -ry; dy <= ry; dy++) {
            int y = center.getY() + dy;
            if (y < world.getBottomY() || y >= world.getTopY()) continue;
            int sectionY = y >> 4;
            for (int dx = -r; dx <= r; dx++) {
                int x = center.getX() + dx;
                int chunkX = x >> 4;
                for (int dz = -r; dz <= r; dz++) {
                    int z = center.getZ() + dz;
                    double distSq = eyePos.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
                    if (distSq > bestDistSq) continue;

                    int chunkZ = z >> 4;
                    long sectionKey = (((long) chunkX & 0x3FFFFFL) << 42)
                            | (((long) sectionY & 0xFFFFFL) << 20)
                            | ((long) chunkZ & 0xFFFFFL);
                    if (sectionKey != lastSectionKey) {
                        lastSectionKey = sectionKey;
                        var chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                        if (chunk == null) {
                            lastSectionMayContain = false;
                        } else {
                            var section = chunk.getSection(chunk.sectionCoordToIndex(sectionY));
                            lastSectionMayContain = !section.isEmpty() && section.hasAny(matcher);
                        }
                    }
                    if (!lastSectionMayContain) continue;

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

    Optional<BlockPos> findNearbyBlock(Block targetBlock, double maxReach) {
        return findNearbyBlockMatching(maxReach, state -> state.isOf(targetBlock));
    }

    Optional<BlockPos> findNearbyBed(double maxReach) {
        return findNearbyBlockMatching(maxReach, state -> state.getBlock() instanceof net.minecraft.block.BedBlock);
    }

    Optional<BlockPos> findNearbyFarmland(double maxReach) {
        return findNearbyBlockMatching(maxReach, state -> state.isOf(Blocks.FARMLAND));
    }
```

**Step 2: Deduplicate `findNearbyFarmland(4.5)` in `PlayerRuntime.observeProgression` (`lines 1709–1726`)**
In `PlayerRuntime.observeProgression`:
```java
        var farmPosOpt = entry.body.findNearbyFarmland(4.5);
        boolean farmlandNearby = farmPosOpt.isPresent();
        boolean bedNearby = entry.body.findNearbyBed(4.5).isPresent();
        boolean spawnAnchored = player.getSpawnPointPosition() != null;

        boolean cropsPlantedNearby = false;
        boolean matureCropsNearby = false;
        if (farmPosOpt.isPresent()) {
            var cropState = world.getBlockState(farmPosOpt.get().up());
            if (cropState.getBlock() instanceof net.minecraft.block.CropBlock cropBlock) {
                cropsPlantedNearby = true;
                if (cropBlock.isMature(cropState)) {
                    matureCropsNearby = true;
                }
            }
        }
```

**Step 3: Gate `evaluateScheduledRoutineCandidates` tilling/harvesting loops with `canScanBlock()` (`PlayerRuntime.java:1975–2040`)**
Before calling `world.getBlockState(checkPos)` in the tilling and crop-harvesting loops inside `evaluateScheduledRoutineCandidates` (and inside `isWaterNearby`), check `if (!canScanBlock()) break;` and call `recordBlockScan();`.

#### Do-Not-Break Invariants
- Never call `world.getChunk(chunkX, chunkZ)` (which synchronously loads/generates chunks). Always use `world.getChunkManager().getWorldChunk(chunkX, chunkZ)` which returns `null` if unloaded.
- Keep `maxReach = 4.5` so workstation/bed/farmland reach validation in `NativeCraftAction`, `NativeSmeltAction`, `NativeSleepAction`, and `NativePlantAction` (`squaredDistanceTo <= 25.0` or `20.25`) always agrees with discovery.

#### Verification
- Run `.\gradlew.bat :companion-server-adapter:runGametest` and verify `NativeCraftGameTests`, `NativeSmeltGameTests`, `NativeFarmGameTests`, and `NativeProgressionGameTests` all pass.

---

### OPT-02 (P0): Wire Tactical Threat into `AttentionStateMachine` & Cache Weapon Profiles by Inventory Fingerprint
- **Related Tracker IDs**: `F-12a`, `E-07b`, `E-07c`
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java` (lines `1115–1160`, `2310–2355`, `4120–4165`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativeTacticalObserver.java` (lines `40–115`)

#### Problem Statement
1. `PlayerRuntime.createSignalFrame` (`line 1152`) hardcodes `ThreatKnowledge.CLEAR` and `attackSequence = null`. As a result, `AttentionStateMachine` never transitions to `AttentionState.ALERT` or `AttentionState.COMBAT` when hostile mobs are nearby.
2. `computeInventoryFingerprint` (`line 1115`) hashes only `Registries.ITEM.getRawId(stack.getItem())` and `stack.getCount()`, ignoring durability (`stack.getDamage()`) and enchantments/components (`stack.getComponents()`).
3. `NativeTacticalObserver.observe` (`line 65–111`) rebuilds `List<WeaponProfile>` (scanning 36 slots and parsing enchantments/attributes) on every single server tick even when the player's inventory has not changed.

#### Step-by-Step Implementation Guide

**Step 1: Include damage and components in `PlayerRuntime.computeInventoryFingerprint` (`lines 1115–1141`)**
Update `computeInventoryFingerprint` so tool wear, breakage, and enchantments update the fingerprint:
```java
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                hash = 31 * hash + i;
                hash = 31 * hash + Registries.ITEM.getRawId(stack.getItem());
                hash = 31 * hash + stack.getCount();
                hash = 31 * hash + stack.getDamage();
                hash = 31 * hash + stack.getComponents().hashCode();
            }
        }
```

**Step 2: Cache `List<WeaponProfile>` on `NativeEntry` (`PlayerRuntime.java:4130`)**
Add two fields to `NativeEntry`:
```java
        private long cachedWeaponFingerprint = Long.MIN_VALUE;
        private int cachedWeaponHeldSlot = -1;
        private List<dev.kindred.core.tactics.WeaponProfile> cachedWeapons = List.of();
```
Pass `cachedWeapons` into an overloaded `NativeTacticalObserver.observe(...)` when `currentInvFingerprint == entry.cachedWeaponFingerprint && player.getInventory().selectedSlot == entry.cachedWeaponHeldSlot`, avoiding 36-slot attribute/enchantment extraction on unchanged ticks.

**Step 3: Connect `entry.lastTacticalObservation` to `createSignalFrame` (`PlayerRuntime.java:1143–1158`)**
In `createSignalFrame(NativeEntry entry, long tick)`:
```java
        ThreatKnowledge threat = ThreatKnowledge.CLEAR;
        long threatTick = tick;
        if (entry.lastTacticalObservation != null
                && !entry.lastTacticalObservation.isStale(tick, entry.bodyGeneration)) {
            threat = entry.lastTacticalObservation.observedMobs().isEmpty()
                    ? ThreatKnowledge.CLEAR
                    : ThreatKnowledge.PRESENT;
            threatTick = entry.lastTacticalObservation.tick();
        }
```
And reorder `beforeNativeTick` (`lines 2313–2335`) so `entry.lastTacticalObservation` is updated **before** `createSignalFrame(entry, tick)` is passed to `entry.attention.observe(frame)`.

#### Do-Not-Break Invariants
- When `entry.attention` enters `AttentionState.COMBAT` or `AttentionState.ALERT`, `AttentionSchedulePolicy` intentionally defers `ROUTINE_PLANNING` and `RESOURCE_DISCOVERY` tokens while keeping `TACTICAL_EVAL` and `MATERIAL_LOGISTICS` active. Wait to throttle `ROUTINE` entity scanning until all existing `NativeAttentionGameTests` and `NativeCombatGameTests` pass with `ThreatKnowledge.PRESENT` wired in.

#### Verification
- Run `.\gradlew.bat test` and `.\gradlew.bat :companion-server-adapter:runGametest` (specifically checking `NativeAttentionGameTests` and `NativeCombatGameTests`).

---

## 2. Minecraft 1.21.1 Wiki Mechanics & Fidelity Blueprints

### OPT-03 (P0): Safe Furnace Fuel Ranking & Burn-Time Budgeting
- **Related Tracker IDs**: `E-04d`, `F-05c`
- **Minecraft Wiki Reference**: [Smelting#Fuel](https://minecraft.wiki/w/Smelting#Fuel)
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java` (lines `759–855`)

#### Problem Statement
Per the [Minecraft Wiki — Smelting#Fuel](https://minecraft.wiki/w/Smelting#Fuel), `AbstractFurnaceBlockEntity.canUseAsFuel(stack)` returns `true` for:
- Wooden tools (`wooden_pickaxe`, `wooden_axe`, `wooden_hoe`, `wooden_sword`, `wooden_shovel`) — smelts $1$ item ($200\text{ ticks}$)
- Workstations & gear (`crafting_table`, `chest`, `barrel`, `bow`, `crossbow`, `fishing_rod`, `ladder`) — smelts $1\text{–}1.5$ items ($200\text{–}300\text{ ticks}$)
- Wood planks & logs — smelts $1.5$ items ($300\text{ ticks}$)
- Coal & Charcoal — smelts $8$ items ($1,600\text{ ticks}$)

Currently, `NativePlayerBody.planSmelt` (`lines 805–829`) selects the **first** slot `0..35` where `canUseAsFuel(s)` is `true` and inserts `Math.min(maxAvail, fuelSpace)` (up to 64 items). If a player holds a wooden pickaxe or crafting table in hotbar slot `0..8` and coal in slot `9`, `planSmelt` burns the player's own tool or crafting table! Furthermore, if smelting 1 raw iron with 64 planks, it dumps all 64 planks into the furnace fuel slot, starving the player of wood for sticks and tools.

#### Step-by-Step Implementation Guide

**Step 1: Add a safe fuel filter and priority comparator in `NativePlayerBody.java`**
Add helper methods in `NativePlayerBody`:
```java
    static boolean isSafeAutonomousFuel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!AbstractFurnaceBlockEntity.canUseAsFuel(stack)) return false;
        Item item = stack.getItem();
        // Never burn tools, weapons, bows, workstations, beds, or storage containers as fuel
        if (item instanceof net.minecraft.item.ToolItem
                || item instanceof net.minecraft.item.RangedWeaponItem
                || item instanceof net.minecraft.item.ShieldItem
                || item == Items.CRAFTING_TABLE
                || item == Items.CHEST
                || item == Items.TRAPPED_CHEST
                || item == Items.BARREL
                || item == Items.LECTERN
                || item == Items.FISHING_ROD
                || item == Items.CARROT_ON_A_STICK
                || item == Items.WARPED_FUNGUS_ON_A_STICK
                || stack.isIn(net.minecraft.registry.tag.ItemTags.BEDS)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.BOATS)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.SIGNS)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.BANNERS)) {
            return false;
        }
        // Avoid burning raw logs directly when planks or coal/charcoal can be used
        return true;
    }

    static int fuelPriorityScore(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.COAL || item == Items.CHARCOAL) return 100;
        if (item == Items.COAL_BLOCK) return 90;
        if (item == Items.BLAZE_ROD) return 80;
        if (item == Items.STICK || item == Items.BAMBOO) return 60;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) return 50;
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS_THAT_BURN)) return 20;
        return 30;
    }
```

**Step 2: Cap `fuelToInsert` to match the required smelting work (`NativePlayerBody.java:812–829`)**
When `fuelSlotArg == null` (autonomous fuel selection), pick the highest-scoring slot where `isSafeAutonomousFuel(s)` is true. Then calculate how many items need smelting (`totalItemsToSmelt = furnaceInput.getCount() + inputToInsert`) and bound `fuelToInsert`:
```java
        if (resolvedFuelSlot != -1) {
            ItemStack playerFuel = inv.getStack(resolvedFuelSlot);
            int maxAvail = countArg != null ? Math.min(countArg, playerFuel.getCount()) : playerFuel.getCount();
            int fuelSpace = furnaceFuel.isEmpty() ? playerFuel.getMaxCount() : furnaceFuel.getMaxCount() - furnaceFuel.getCount();
            int burnTicksPerUnit = AbstractFurnaceBlockEntity.createFuelTimeMap().getOrDefault(playerFuel.getItem(), 200);
            int totalInputItems = furnaceInput.getCount() + inputToInsert;
            int totalTicksNeeded = Math.max(200, totalInputItems * 200);
            int existingFuelTicks = furnaceFuel.getCount() * burnTicksPerUnit;
            int neededTicks = Math.max(0, totalTicksNeeded - existingFuelTicks);
            int neededFuelUnits = neededTicks == 0
                    ? (furnaceFuel.isEmpty() ? 1 : 0)
                    : (int) Math.ceil((double) neededTicks / burnTicksPerUnit);
            int desiredUnits = countArg != null ? maxAvail : Math.max(1, neededFuelUnits);
            fuelToInsert = Math.min(desiredUnits, Math.min(maxAvail, Math.max(0, fuelSpace)));
        }
```

#### Verification
- Add a unit/GameTest in `NativeSmeltGameTests` verifying that an AI player holding a `wooden_pickaxe` in slot 0, `crafting_table` in slot 1, and `coal` in slot 9 selects `coal` (slot 9) and inserts only the required coal count rather than burning the pickaxe or table.

---

### OPT-04 (P0): Poison/Hunger Food Blacklist, Saturation-First Healing & Non-Weapon Hotbar Swap
- **Related Tracker IDs**: `E-07a`, `F-04a`
- **Minecraft Wiki Reference**: [Hunger#Effects](https://minecraft.wiki/w/Hunger#Effects) and [Hunger#Saturation_boost](https://minecraft.wiki/w/Hunger#Saturation_boost)
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java` (lines `1460–1503`, `4212–4217`)

#### Problem Statement
1. `PlayerRuntime.isEligibleFood` (`line 4212`) only excludes `Items.CHORUS_FRUIT`. Per the Minecraft Wiki, `ROTTEN_FLESH` ($80\%$ Hunger I), `RAW_CHICKEN` ($30\%$ Hunger I), `SPIDER_EYE` (Poison I), `POISONOUS_POTATO` ($60\%$ Poison I), `PUFFERFISH` (Poison II, Hunger III, Nausea), and `SUSPICIOUS_STEW` carry `DataComponentTypes.FOOD` and will be eaten autonomously!
2. In Java Edition 1.21.1, rapid health regeneration ($1\text{ HP}$ per $0.5\text{s} / 10\text{ ticks}$) requires `foodLevel == 20` and consumes $1.5\text{ saturation}$ per $1\text{ HP}$. Currently, `evaluateEssentialSurvivalCandidates` (`line 1470`) grabs the first hotbar slot `0..8` containing any food rather than picking the food with the highest saturation/nutrition.
3. When food must be swapped from main inventory (`9..35`) into a full hotbar (`0..8`), `line 1479` defaults `int targetH = 0;`, overwriting slot 0 (which usually holds the player's primary sword or pickaxe).

#### Step-by-Step Implementation Guide

**Step 1: Exclude harmful foods in `PlayerRuntime.isEligibleFood` (`lines 4212–4217`)**
```java
    static boolean isEligibleFood(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isOf(Items.CHORUS_FRUIT)
                || stack.isOf(Items.ROTTEN_FLESH)
                || stack.isOf(Items.SPIDER_EYE)
                || stack.isOf(Items.POISONOUS_POTATO)
                || stack.isOf(Items.PUFFERFISH)
                || stack.isOf(Items.RAW_CHICKEN)
                || stack.isOf(Items.SUSPICIOUS_STEW)) {
            return false;
        }
        var food = stack.get(DataComponentTypes.FOOD);
        if (food == null) return false;
        for (var effectEntry : food.effects()) {
            var type = effectEntry.effect().getEffectType();
            if (type == net.minecraft.entity.effect.StatusEffects.POISON
                    || type == net.minecraft.entity.effect.StatusEffects.HUNGER
                    || type == net.minecraft.entity.effect.StatusEffects.WITHER
                    || type == net.minecraft.entity.effect.StatusEffects.NAUSEA
                    || type == net.minecraft.entity.effect.StatusEffects.BLINDNESS) {
                return false;
            }
        }
        return true;
    }
```

**Step 2: Score foods by saturation + nutrition and pick a safe hotbar swap slot (`PlayerRuntime.java:1470–1502`)**
Add a helper `foodQualityScore(ItemStack stack, boolean injured)` that returns `food.saturation() * (injured ? 3.0f : 1.5f) + food.nutrition()`.
When choosing `hotbarFoodSlot` (`0..8`) or `invFoodSlot` (`9..35`), pick the slot with the **highest** `foodQualityScore`.
When `hotbarFoodSlot == -1` and all hotbar slots `0..8` are occupied, select a non-weapon, non-tool hotbar slot scanning downward from slot `8` to `0`:
```java
                    int targetH = -1;
                    for (int h = 0; h <= 8; h++) {
                        if (player.getInventory().getStack(h).isEmpty()) {
                            targetH = h;
                            break;
                        }
                    }
                    if (targetH == -1) {
                        for (int h = 8; h >= 0; h--) {
                            Item item = player.getInventory().getStack(h).getItem();
                            if (!(item instanceof net.minecraft.item.ToolItem)
                                    && !(item instanceof net.minecraft.item.SwordItem)
                                    && !(item instanceof net.minecraft.item.RangedWeaponItem)) {
                                targetH = h;
                                break;
                            }
                        }
                    }
                    if (targetH == -1) targetH = 8;
```

#### Verification
- Add unit tests in `PlayerRuntime` / GameTests in `NativeSurvivalGameTests` confirming:
  1. `isEligibleFood` rejects `ROTTEN_FLESH`, `SPIDER_EYE`, `POISONOUS_POTATO`, and `PUFFERFISH`.
  2. When injured with both `APPLE` and `COOKED_BEEF` in the hotbar, the player selects `COOKED_BEEF` (higher saturation).
  3. Swapping food from slot 9 into a full hotbar preserves weapons/pickaxes in slot 0 and swaps into slot 8.

---

### OPT-05 (P1): Shield 5-Tick Activation Delay, Threat Yaw Orientation & Self-Armor Mitigation
- **Related Tracker IDs**: `E-07b`, `E-07c`, `E-07`
- **Minecraft Wiki Reference**: [Shield#Defense](https://minecraft.wiki/w/Shield#Defense) and [Damage#Armor](https://minecraft.wiki/w/Damage#Armor)
- **Target Files**:
  - `companion-core/src/main/java/dev/kindred/core/tactics/TacticalObservation.java`
  - `companion-core/src/main/java/dev/kindred/core/tactics/StandardCombatPredictor.java` (lines `166–170`, `250–295`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativeTacticalObserver.java` (lines `170–225`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java` (lines `393–407`)

#### Problem Statement
1. **Shield Warmup (5 Ticks)**: Per the [Minecraft Wiki — Shield](https://minecraft.wiki/w/Shield#Defense), after raising a shield (`setCurrentHand`), there is a **5-tick ($0.25\text{s}$) warmup delay** before `player.isBlocking()` becomes active. `StandardCombatPredictor` currently sets `STAY_AND_GUARD` execution ticks to `1` (`line 167`).
2. **Shield $180^\circ$ Directional Arc**: Shields only block attacks originating within a $180^\circ$ horizontal front cone. `NativePlayerBody.block` (`line 393`) raises the shield without rotating `player.setYaw(...)` toward the nearest hostile mob or skeleton projectile.
3. **Self-Armor Mitigation**: `StandardCombatPredictor.calculateMitigatedDamage` (`line 88`) computes armor and toughness reduction for **targets**, but `estimateSelfDamage` (`lines 252–288`) applies unmitigated mob damage (`3.0` zombie, `4.0` skeleton, `25.0` creeper) directly against the AI player's health, ignoring the AI player's equipped armor!

#### Step-by-Step Implementation Guide

**Step 1: Add `companionArmor` and `companionArmorToughness` to `TacticalObservation.java`**
Add `float companionArmor` and `float companionArmorToughness` to `TacticalObservation` (keep the existing 15-arg and 16-arg constructors as backwards-compatible overloads delegating with `0.0f, 0.0f` so zero existing unit tests break!).
In `NativeTacticalObserver.java`, pass `player.getArmor()` and `(float) player.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS)`.

**Step 2: Apply `calculateMitigatedDamage` to incoming mob damage in `StandardCombatPredictor.java` (`lines 250–295`)**
In `estimateSelfDamage`:
```java
        // Apply player armor & toughness mitigation to non-creeper/creeper physical damage
        double minMitigated = calculateMitigatedDamage(minDmg, obs.companionArmor(), obs.companionArmorToughness());
        double maxMitigated = calculateMitigatedDamage(maxDmg, obs.companionArmor(), obs.companionArmorToughness());
        return new DamageInterval(minMitigated, maxMitigated);
```
And update `ActionStep.STAY_AND_GUARD` setup time (`line 167`) to account for the 5-tick shield activation window:
```java
        case STAY_AND_GUARD -> {
            minTicks += 5;
            maxTicks += 5;
        }
```

**Step 3: Orient yaw toward the nearest threat in `NativePlayerBody.block` (`lines 393–407`)**
When `NativePlayerBody.block(ActionLease lease)` is called (and during `NativePlayerSkills.Block.tick`), look up the nearest `HostileEntity` within $16$ blocks and face it before/while holding the shield:
```java
        var nearestHostile = player.getServerWorld().getEntitiesByClass(
                net.minecraft.entity.mob.HostileEntity.class,
                player.getBoundingBox().expand(16.0),
                e -> e.isAlive() && !e.isRemoved())
                .stream()
                .min(java.util.Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
        if (nearestHostile != null) {
            double dx = nearestHostile.getX() - player.getX();
            double dz = nearestHostile.getZ() - player.getZ();
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            player.setYaw(yaw);
            player.setHeadYaw(yaw);
            player.setBodyYaw(yaw);
        }
```

#### Verification
- Add unit tests in `StandardCombatPredictorTest` verifying that an AI player wearing full iron armor (`armor = 15.0f, toughness = 0.0f`) receives mitigated damage (`1.2` instead of `3.0` from a zombie) and remains in `SurvivalClass.SURVIVABLE` at `4.0 HP`.
- Run `.\gradlew.bat :companion-server-adapter:runGametest` to confirm `NativeCombatGameTests` pass.

---

### OPT-06 (P1): Jump-Fall Critical Strike Execution in `NativeAttackAction`
- **Related Tracker IDs**: `E-07b`, `E-07c`, `E-07`
- **Minecraft Wiki Reference**: [Damage#Critical_hit](https://minecraft.wiki/w/Damage#Critical_hit) and [Damage#Attack_cooldown](https://minecraft.wiki/w/Damage#Attack_cooldown)
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativeAttackAction.java` (lines `100–160`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java` (lines `2551–2582`)

#### Problem Statement
`TacticalPriorityPolicy` and `StandardCombatPredictor` generate and score `ActionStep.CRITICAL_STRIKE` ($1.5\times$ base weapon damage, $9\text{ ticks}$ setup). However, `PlayerRuntime.dispatchTacticalDecision` (`line 2576`) dispatches `native_attack` without distinguishing `CRITICAL_STRIKE` from `NORMAL_STRIKE`, and `NativeAttackAction` (`line 112`) swings immediately while standing on the ground.
Per the [Minecraft Wiki — Critical hit](https://minecraft.wiki/w/Damage#Critical_hit), a melee attack is a critical hit if and only if:
1. `player.getAttackCooldownProgress(0.5f) > 0.848f` (Kindred uses `>= 0.9f` for full charge safety)
2. `player.fallDistance > 0.0f && !player.isOnGround()`
3. `!player.isClimbing() && !player.isTouchingWater() && !player.hasVehicle()`
4. `!player.hasStatusEffect(StatusEffects.BLINDNESS)`
5. `!player.isSprinting()` (sprinting overrides critical hit with sprint-knockback!)

#### Step-by-Step Implementation Guide

**Step 1: Pass `critical=true` when `ActionStep.CRITICAL_STRIKE` is dispatched (`PlayerRuntime.java:2575`)**
In `PlayerRuntime.dispatchTacticalDecision`, check if `seq.steps().contains(ActionStep.CRITICAL_STRIKE)`:
```java
        boolean wantCrit = seq.steps().contains(dev.kindred.core.tactics.ActionStep.CRITICAL_STRIKE);
        dispatchNativeTask(entry, "native_attack", Map.of(
                "target", targetId.toString(),
                "slot", String.valueOf(targetSlot),
                "critical", String.valueOf(wantCrit)
        ), "Tactical defensive strike on hostile threat");
```

**Step 2: Support optional jump-fall timing in `NativeAttackAction.java` (`lines 110–145`)**
In `NativeAttackAction`:
- Parse `boolean wantCritical = Boolean.parseBoolean(args.getOrDefault("critical", "false"));`
- If `wantCritical` is `true` and `player.isOnGround() && !player.isTouchingWater() && !player.isClimbing()`:
  1. Ensure `player.setSprinting(false)`.
  2. Trigger `player.jump()` once (`jumpedForCrit = true`) and wait (up to 12 ticks) until `player.fallDistance > 0.0f && !player.isOnGround() && player.getAttackCooldownProgress(0.5f) >= 0.9f` before taking the `ActionAdmission` and calling `body.attack(lease.get(), slot, targetId)`.
  3. If the target moves close to max reach (`distSq > 7.5`) or 12 ticks elapse (e.g., low ceiling prevented jump), fall back immediately to a normal standing strike so the attack never stalls.

#### Verification
- Add a GameTest in `NativeCombatGameTests` verifying that `native_attack` with `critical=true` jumps, waits for `fallDistance > 0`, and deals $1.5\times$ critical damage compared to `critical=false`.

---

### OPT-07 (P1): Minecraft 1.21.1 Deepslate Ore Families & `cobbled_deepslate` Equivalence
- **Related Tracker IDs**: `E-03`, `E-04c`, `E-12`, `E-16`
- **Minecraft Wiki Reference**: [Ore#Distribution](https://minecraft.wiki/w/Ore#Distribution) and [Cobbled Deepslate#Crafting ingredient](https://minecraft.wiki/w/Cobbled_Deepslate#Crafting_ingredient)
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java` (lines `1295–1325`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativeGatherAction.java` (lines `175–188`, `228–240`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java` (lines `485–535`)
  - `companion-core/src/main/java/dev/kindred/core/progression/MilestoneGraph.java` (lines `190–245`)

#### Problem Statement
Per the [Minecraft Wiki — Ore#Distribution](https://minecraft.wiki/w/Ore#Distribution), in Minecraft 1.18–1.21.1:
- Below $Y = 8$, stone transitions to `minecraft:deepslate`, and ores generate as `deepslate_coal_ore`, `deepslate_iron_ore`, `deepslate_copper_ore`, `deepslate_gold_ore`, `deepslate_redstone_ore`, `deepslate_lapis_ore`, `deepslate_emerald_ore`, and `deepslate_diamond_ore` (Diamond peaks at $Y = -59$, where almost 100% of diamond ore is `deepslate_diamond_ore`).
- Mining `minecraft:deepslate` drops `minecraft:cobbled_deepslate`, which substitutes for `minecraft:cobblestone` in all stone tool and furnace recipes via the `minecraft:stone_tool_materials` and `minecraft:stone_crafting_materials` item tags.

Currently:
1. `PlayerRuntime.budgetedResourceScan` (`line 1303`) checks `Registries.BLOCK.getId(state.getBlock()).toString().equals(patch.resourceId())`. An `iron_ore` patch ignores `deepslate_iron_ore`, and a `stone` patch ignores `deepslate` or `cobblestone`.
2. `MilestoneGraph` (`line 193`) checks only `obs.has("minecraft:cobblestone", 3)` and `NativePlayerBody.resolveRecipeSpec` (`line 506–515`) requires `Items.COBBLESTONE`.

#### Step-by-Step Implementation Guide

**Step 1: Match ore/stone families in `PlayerRuntime.budgetedResourceScan` and `NativeGatherAction`**
Create a helper `matchesResourceFamily(BlockState state, String patchResourceId)`:
```java
    static boolean matchesResourceFamily(BlockState state, String resourceId) {
        String actualId = Registries.BLOCK.getId(state.getBlock()).toString();
        if (actualId.equals(resourceId)) return true;
        return switch (resourceId) {
            case "minecraft:stone", "minecraft:cobblestone" ->
                    state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE)
                    || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.COBBLED_DEEPSLATE);
            case "minecraft:iron_ore", "minecraft:deepslate_iron_ore" ->
                    state.isOf(Blocks.IRON_ORE) || state.isOf(Blocks.DEEPSLATE_IRON_ORE);
            case "minecraft:coal_ore", "minecraft:deepslate_coal_ore" ->
                    state.isOf(Blocks.COAL_ORE) || state.isOf(Blocks.DEEPSLATE_COAL_ORE);
            case "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore" ->
                    state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE);
            case "minecraft:gold_ore", "minecraft:deepslate_gold_ore" ->
                    state.isOf(Blocks.GOLD_ORE) || state.isOf(Blocks.DEEPSLATE_GOLD_ORE);
            case "minecraft:copper_ore", "minecraft:deepslate_copper_ore" ->
                    state.isOf(Blocks.COPPER_ORE) || state.isOf(Blocks.DEEPSLATE_COPPER_ORE);
            default -> false;
        };
    }
```

**Step 2: Support `cobbled_deepslate` in `MilestoneGraph` and `NativePlayerBody.resolveRecipeSpec`**
In `ProgressionObservation.java`, add:
```java
    public int stoneMaterialCount() {
        return count("minecraft:cobblestone") + count("minecraft:cobbled_deepslate") + count("minecraft:blackstone");
    }
```
Use `obs.stoneMaterialCount() >= 3` (or `>= 8` for furnace) in `MilestoneGraph.java` (`lines 193, 206`), and in `NativePlayerBody.resolveRecipeSpec` (`lines 506–515`), choose `Items.COBBLED_DEEPSLATE` when the player has enough `COBBLED_DEEPSLATE` and fewer `COBBLESTONE`.

#### Verification
- Add unit tests in `MilestoneGraphTest` for `minecraft:cobbled_deepslate` satisfying `MINE_COBBLESTONE` and `CRAFT_FURNACE` prerequisites.

---

### OPT-08 (P1): Authentic Server Loot Tables & Seed Conservation in `NativePlayerBody.harvestCrop`
- **Related Tracker IDs**: `E-13`, `E-02`, `PLY-04`
- **Minecraft Wiki Reference**: [Tutorials/Crop_farming](https://minecraft.wiki/w/Tutorials/Crop_farming)
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java` (lines `1055–1095`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativeHarvestAction.java` (lines `140–230`)

#### Problem Statement
In `NativePlayerBody.harvestCrop` (`lines 1068–1092`), breaking a mature crop currently calls `world.breakBlock(target, false, player)` (suppressing vanilla loot table drops) and manually constructs `new ItemStack(Items.WHEAT, 1)` + `new ItemStack(Items.WHEAT_SEEDS, 1)` (or `new ItemStack(Items.CARROT, 2)`), while resetting the crop block to `cropBlock.withAge(0)` without consuming a seed from drops or inventory.
Per `PLY-04` (*"Earn all progress: no free gear, copied usable items..."*) and the Minecraft Wiki, mature crops drop items via `Block.getDroppedStacks(state, world, target, null, player, player.getMainHandStack())`, and replanting must consume exactly $1$ seed/crop item from those generated drops (or from player inventory).

#### Step-by-Step Implementation Guide

**Step 1: Evaluate authentic server loot tables in `NativePlayerBody.harvestCrop` (`lines 1068–1092`)**
Replace the hardcoded `new ItemStack(...)` synthesis in `NativePlayerBody.harvestCrop` with `Block.getDroppedStacks`:
```java
        List<ItemStack> drops = Block.getDroppedStacks(
                state, world, target, world.getBlockEntity(target), player, player.getMainHandStack());
        // Guarantee at least standard mature yield if loot context RNG rolled 0 seeds
        Item replantSeedItem = state.isOf(Blocks.CARROTS) ? Items.CARROT
                : state.isOf(Blocks.POTATOES) ? Items.POTATO
                : state.isOf(Blocks.BEETROOTS) ? Items.BEETROOT_SEEDS
                : Items.WHEAT_SEEDS;

        world.breakBlock(target, false, player);

        boolean replanted = false;
        if (replant) {
            // 1. Try consuming 1 seed from the loot drops first
            for (ItemStack drop : drops) {
                if (drop.isOf(replantSeedItem) && drop.getCount() > 0) {
                    drop.decrement(1);
                    replanted = true;
                    break;
                }
            }
            // 2. Otherwise try consuming 1 seed from player inventory
            if (!replanted) {
                for (int i = 0; i < player.getInventory().size(); i++) {
                    ItemStack invStack = player.getInventory().getStack(i);
                    if (invStack.isOf(replantSeedItem) && invStack.getCount() > 0) {
                        invStack.decrement(1);
                        replanted = true;
                        break;
                    }
                }
            }
            if (replanted) {
                world.setBlockState(target, cropBlock.withAge(0), Block.NOTIFY_ALL);
            }
        }

        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                player.getInventory().insertStack(drop.copy());
            }
        }
        player.getInventory().markDirty();
        return true;
```

#### Do-Not-Break Invariants
- Ensure `NativeHarvestAction.verifyConservation` (`lines 207–228`) continues to verify that no non-crop/non-seed slots were altered and that total crop/seed count increased monotonically (`afterCropCount >= beforeCropCount` and `afterSeedCount >= beforeSeedCount - (replanted ? 1 : 0)`).

#### Verification
- Run `.\gradlew.bat :companion-server-adapter:runGametest` and verify all 4 tests in `NativeFarmGameTests` (`cropSowingAndReplantingConservation`, `sharedFarmHomeProjectCoordination`, etc.) pass.

---

## 3. Team Coordination (`E-11`) & SQLite Persistence Blueprints

### OPT-09 (P1): Persist `TeamCoordinator` Task Transitions to SQLite & Reuse Warm `PaidActivitySession` Permits
- **Related Tracker IDs**: `E-11`, `F-11b`
- **Target Files**:
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java` (lines `935–975`, `2405–2425`, `2920–2970`)

#### Problem Statement
1. **Unpersisted Runtime Task Transitions**: `SqliteCompanionStore` (`lines 995–1040`) already implements `saveTask(SharedTask task, long expectedRevision)` and `saveReservation(StockReservation reservation)`. Furthermore, `PlayerRuntime.start()` (`line 332`) loads `store.listProjects()` on startup, and `SqliteCompanionStore.recover()` (`line 1451`) recovers interrupted `CLAIMED`/`IN_PROGRESS` tasks on crash. However, when `PlayerRuntime` calls `teamCoordinator.claimTask(...)`, `startTask(...)`, `completeTask(...)`, or `revokeTask(...)` at runtime (`lines 2407, 2419, 2935, 2948, 2965`), it never calls `store.saveTask(...)`!
2. **Per-Action SQLite Budget Churn**: `PlayerRuntime.dispatchNativeTask` (`line 939`) opens a brand-new `PaidActivitySession` (querying `store.limit(id)` and `store.openBudget(id, period)` and writing a new SQLite reservation) for every single atomic task, and `observeRunner` (`line 2921`) immediately stops and closes that session the moment the task finishes. During rapid sequences (e.g., swapping slots, crafting planks, crafting sticks, placing a table), this causes 8–12 SQLite transactions per second.

#### Step-by-Step Implementation Guide

**Step 1: Add a helper `persistProjectTasks(ProjectId projectId)` in `PlayerRuntime.java`**
Whenever `teamCoordinator.claimTask`, `startTask`, `completeTask`, `releasePlayerTasks`, or `reconcileExpiredLeases` mutates a project, persist the updated tasks asynchronously via `store.saveTask`:
```java
    private void persistTaskAsync(ProjectId projectId, TaskId taskId, long previousRevision) {
        if (closing || store == null) return;
        teamCoordinator.getProject(projectId).ifPresent(proj -> {
            SharedTask updated = proj.tasks().get(taskId);
            if (updated != null) {
                store.saveTask(updated, previousRevision).exceptionally(err -> {
                    LOGGER.debug("Async shared task persistence skipped or conflicted for {}: {}", taskId, err.getMessage());
                    return null;
                });
            }
        });
    }
```
Call `persistTaskAsync` immediately after `claimTask`, `startTask`, `completeTask`, and `revokeTask` in `PlayerRuntime.java` (`lines 2407, 2419, 2935, 2948, 2965`).

**Step 2: Reuse an active `PaidActivitySession` within its valid permit window (`PlayerRuntime.java:935–965`)**
In `dispatchNativeTask`:
- Before closing `entry.activity` and querying `store.limit(entry.id)` + `store.openBudget(...)`, check if `entry.activity != null && entry.activity.currentPermit(clock.millis(), nanoClock.getAsLong()).isPresent()` and the remaining permit window is $\ge 1,500\text{ ms}$ (for instant/short actions like `native_swap`, `native_craft`, `native_place`, `native_equip`).
- Only close `entry.activity` immediately when:
  1. `/aiplayer stop` or `stopLatched` is triggered,
  2. The player disconnects or dies,
  3. The permit has $< 1,500\text{ ms}$ remaining or has expired, or
  4. The server is shutting down.

#### Do-Not-Break Invariants
- Never bypass `PaidActivitySession` on `stop`: `stopNativeEntry` and `close()` must still synchronously call `entry.activity.stop()` and `closeNativeActivity(entry)` so `F-04a` / `F-11b` cancellation settlement tests pass with zero changes.

#### Verification
- Run `NativeTeamGameTests` and `PaidActivitySessionTest` to verify durable task states in SQLite and accurate budget settlement.

---

## 4. Roadmap Engineering Guide Toward `E-40` (Ender Dragon)

### OPT-10 (P2): Qualifying `E-14`..`E-17`, `E-39`, and `E-40` Under Minecraft 1.21.1 Rules
- **Related Tracker IDs**: `F-10`, `E-14`, `E-15`, `E-16`, `E-39`, `E-40`
- **Minecraft Wiki Reference**: [Tutorials/Defeating the ender dragon](https://minecraft.wiki/w/Tutorials/Defeating_the_ender_dragon) and [Ender Dragon](https://minecraft.wiki/w/Ender_Dragon)
- **Target Files**:
  - `companion-core/src/main/java/dev/kindred/core/SurvivalPolicy.java` (lines `13–18`)
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java` (line `1254`)
  - `companion-core/src/main/java/dev/kindred/core/progression/MilestoneGraph.java`

#### Step-by-Step Architectural Blueprint for Upcoming Milestones

1. **Unblock Qualified Boss Encounters (`SurvivalPolicy.java:13–18` & `PROG-03`)**:
   - Currently, `SurvivalPolicy.FORBIDDEN_BOSSES` hardcodes `"minecraft:ender_dragon"`, `"minecraft:wither"`, and `"minecraft:warden"`.
   - Per contract rule `PROG-03` (*"There is no permanent product-wide boss ban. Dragon expeditions require qualified skills/readiness and server policy"*), keep `"minecraft:wither"` and `"minecraft:warden"` forbidden by default, and gate `"minecraft:ender_dragon"` behind a new `boolean dragonExpeditionAuthorized` flag on `EditPolicy.ServerPolicy` + `MilestoneGraph` readiness check (`LOCATE_STRONGHOLD` met + iron/diamond armor + bow/arrows + beds/water bucket).
   - Preserve the existing `SurvivalPolicyTest.companionNeverDefendsAgainstWitherOrBoss` unit test by defaulting `dragonExpeditionAuthorized = false` until explicitly enabled for `E-39`/`E-40`.

2. **Enable Portal Dimension Handoff (`NativePlayerBody.java:1254` for `E-15` / `E-39`)**:
   - `NativePlayerBody.BodyPlayer.canUsePortals(boolean allowVehicles)` (`line 1254`) currently returns `false`.
   - For `E-15` (Nether travel) and `E-39` (End portal entry), gate `canUsePortals` on an active `ActionLease` holding a `NativePlayerBody.Intent.PORTAL_TRAVEL` permission so accidental wandering into a portal is still blocked, while deliberate `native_portal` traversal transitions the `ServerPlayerEntity` across dimensions and updates `entry.perceptionSource = new ChunkSectionSource(player.getServerWorld())` and `companion.withDimension(...)` in SQLite.

3. **Two-Throw Eye of Ender Triangulation (`E-39`)**:
   - Per the [Minecraft Wiki — Eye of Ender#Locating strongholds](https://minecraft.wiki/w/Eye_of_Ender#Locating_strongholds), a thrown `EyeOfEnderEntity` flies $12$ blocks horizontally toward the nearest Stronghold's spiral staircase at chunk coordinates `(4, ~, 4)` and has a $20\%$ chance to shatter on each throw.
   - Instead of throwing an Eye every 20 blocks (which consumes 15–20 Eyes), throw **one Eye** at $(x_1, z_1)$ and record its horizontal travel vector $(\Delta x_1, \Delta z_1)$, walk $150\text{–}200$ blocks perpendicular to that ray to $(x_2, z_2)$, throw a **second Eye** to record $(\Delta x_2, \Delta z_2)$, and solve the 2D line intersection in `companion-core` to estimate the Stronghold coordinates using only **2–3 Eyes of Ender**:
     ```java
     /**
      * Pure 2D ray intersection for two Eye of Ender throws (E-39).
      * Returns empty if rays are nearly parallel (|det| < 1e-4) or intersection is behind either throw.
      */
     public static Optional<WorkSetup.BlockPoint> triangulateStronghold(
             double x1, double z1, double dx1, double dz1,
             double x2, double z2, double dx2, double dz2) {
         double len1 = Math.hypot(dx1, dz1);
         double len2 = Math.hypot(dx2, dz2);
         if (len1 < 1e-5 || len2 < 1e-5) return Optional.empty();
         double ux1 = dx1 / len1, uz1 = dz1 / len1;
         double ux2 = dx2 / len2, uz2 = dz2 / len2;

         // Solve: (x1, z1) + t1 * (ux1, uz1) = (x2, z2) + t2 * (ux2, uz2)
         double det = ux1 * (-uz2) - uz1 * (-ux2);
         if (Math.abs(det) < 0.05) return Optional.empty(); // Parallel or < ~3 deg angle difference

         double rhsX = x2 - x1;
         double rhsZ = z2 - z1;
         double t1 = (rhsX * (-uz2) - rhsZ * (-ux2)) / det;
         double t2 = (ux1 * rhsZ - uz1 * rhsX) / det;
         if (t1 <= 0 || t2 <= 0 || t1 > 10_000 || t2 > 10_000) return Optional.empty();

         int estX = (int) Math.round(x1 + t1 * ux1);
         int estZ = (int) Math.round(z1 + t1 * uz1);
         // Snap to Stronghold staircase chunk offset (chunkX * 16 + 4, chunkZ * 16 + 4)
         int strongholdX = ((estX >> 4) << 4) + 4;
         int strongholdZ = ((estZ >> 4) << 4) + 4;
         return Optional.of(new WorkSetup.BlockPoint(strongholdX, 0, strongholdZ));
     }
     ```

4. **Multi-Part Hitbox & Perch Phase Mechanics for the Ender Dragon (`E-40`)**:
   - Per the [Minecraft Wiki — Ender Dragon](https://minecraft.wiki/w/Ender_Dragon#Behavior):
     - **End Crystals**: The 10 End Crystals on the obsidian pillars heal the Dragon $1\text{ HP}$ ($0.5\text{ hearts}$) per Half-Second ($10\text{ ticks}$). Destroying a crystal while the Dragon is tethered to it deals $10\text{ HP}$ ($5\text{ hearts}$) damage to the Dragon. Two crystals are enclosed in iron bars and require pillaring/mining to break.
     - **Multi-Part Hitboxes (`EnderDragonPart`)**: Calling `player.attack(dragonEntity)` on the root `EnderDragonEntity` or on wing/tail/body parts reduces damage to $\frac{1}{4} \times \text{damage} + \min(1, \text{damage})$! Only attacking `dragon.head` (`EnderDragonPart` with `name.equals("head")`) deals $100\%$ melee damage.
     - **Perched Arrow Immunity**: While perched on the central bedrock portal (`PhaseType.SITTING_SCANNING`, `SITTING_ATTACKING`, `SITTING_FLAMING`), the Dragon is **completely immune to arrows** (arrows deflect and catch fire).
   - In `NativePlayerBody.attack` (`lines 365–391`), resolve `EnderDragonEntity` to its `head` part when within melee reach before calling `player.attack(...)`:
     ```java
     Entity attackTarget = target;
     if (target instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon) {
         for (net.minecraft.entity.boss.dragon.EnderDragonPart part : dragon.getBodyParts()) {
             if ("head".equals(part.name) && player.getEyePos().squaredDistanceTo(part.getBoundingBox().getCenter()) <= 9.0) {
                 attackTarget = part;
                 break;
             }
         }
     }
     player.attack(attackTarget);
     ```

---

## 5. Quality-of-Life (QoL) Improvements for Operators & AI Players

### QOL-01: `/aiplayer diagnostics` Command for Live Scheduler, Perception & Progression Inspection
- **Target Files**: `PlayerCommands.java`, `PlayerRuntime.java`
- **Step-by-Step Implementation Guide**:
  1. In `PlayerRuntime.java`, add a read-only method `Optional<String> formatNativeDiagnostics(UUID controllerId, String name)` that locates the `NativeEntry` on the server thread and formats:
     - `AttentionState` (`entry.attention.state()`), `bodyGeneration`, and `attentionScheduler.diagnostics()` (`totalSlicesCompleted`, `overruns`, `staleTokenRejections`).
     - Active & recovery frontiers from `MilestoneGraph`:
       ```java
       var obs = observeProgression(entry, server.getTicks());
       var state = MilestoneGraph.evaluate(obs, entry.achievedMilestones);
       String active = state.activeFrontier().toString();
       String recovery = state.recoveryFrontier().toString();
       ```
     - Current `TeamCoordinator` assignment for `entry.id` (`task.id()`, `task.title()`, `task.state()`, `task.stockReservation()`).
  2. In `PlayerCommands.java`, register `.then(CommandManager.literal("diagnostics").then(CommandManager.argument("name", StringArgumentType.word())...))` calling `runtime.formatNativeDiagnostics(...)`.

### QOL-02: Proactive Low-Durability Tool Replacement in `MilestoneGraph` & `ProgressionPolicy`
- **Target Files**: `ProgressionObservation.java`, `ProgressionPolicy.java`, `PlayerRuntime.java` (`observeProgression`)
- **Step-by-Step Implementation Guide**:
  1. Currently, an AI player only enters `LOST_RECOVERY_REQUIRED` after its pickaxe or sword completely breaks (`count == 0`). If a stone or iron pickaxe reaches $\le 5$ remaining durability (`stack.getMaxDamage() - stack.getDamage() <= 5`) while deep underground, the player risks breaking its only pickaxe mid-vein.
  2. In `PlayerRuntime.observeProgression` (`lines 1637–1680`), when scanning slots `0..35`, compute `int maxPickaxeRemainingDurability` across all owned pickaxes (`stack.getMaxDamage() - stack.getDamage()`).
  3. In `ProgressionPolicy.evaluate`, if `maxPickaxeRemainingDurability > 0 && maxPickaxeRemainingDurability <= 5` and `obs.hasNearbyCraftingTable()` and the player holds sufficient sticks (`>= 2`) and stone/iron (`>= 3`), emit a high-priority `ProgressionRecommendation` (`priority = 78.0`) to craft a replacement pickaxe before starting a new `native_gather` or `native_mine` task.

### QOL-03: Smart Hotbar Role Organization (`MATERIAL_LOGISTICS` Slice)
- **Target Files**: `PlayerRuntime.java` (`evaluateMaterialLogisticsCandidates`, lines `2045–2105`)
- **Step-by-Step Implementation Guide**:
  Standardize hotbar slot roles during calm `MATERIAL_LOGISTICS` slices (`AttentionState.ROUTINE` or `RECOVERY`) so combat, mining, and eating rarely require an extra pre-action `native_swap`:
  - **Slot 0**: Best melee weapon (`SwordItem` > `AxeItem`)
  - **Slot 1**: Best pickaxe (`PickaxeItem`)
  - **Slot 2**: Best axe / hoe (`AxeItem` / `HoeItem`)
  - **Slot 7**: Placeable blocks (`COBBLESTONE`, `COBBLED_DEEPSLATE`, `DIRT`, `OAK_PLANKS`)
  - **Slot 8**: Highest-saturation safe food (`isEligibleFood(stack)`)
  - **Off-Hand (Slot 40)**: Shield (`Items.SHIELD` via `native_equip`)
  ```java
  // Inside evaluateMaterialLogisticsCandidates(NativeEntry entry, List<TaskDirector.Candidate> candidates):
  // If slot 0 does not hold a SwordItem, and a SwordItem exists in slots 9..35:
  int swordSlot = findBestSlotInMainInventory(player, s -> s.getItem() instanceof net.minecraft.item.SwordItem);
  if (swordSlot != -1 && !(player.getInventory().getStack(0).getItem() instanceof net.minecraft.item.SwordItem)) {
      candidates.add(new TaskDirector.Candidate(
              TaskDirector.Kind.INVENTORY_ORGANIZE, "native_swap", 62.0, true, false,
              Map.of("source", String.valueOf(swordSlot), "target", "0"),
              "Organize primary weapon into hotbar slot 0"));
  }
  ```

### QOL-04: Stuck-Movement & Oscillation Backoff in Autonomous 3D Traversal
- **Target Files**: `PlayerRuntime.java` (`NativeEntry` and `evaluateScheduledRoutineCandidates`)
- **Step-by-Step Implementation Guide**:
  When an AI player uses `native_traverse` (1–3 block local step under `F-10b`) to approach a resource patch or workstation, an obstacle of height $\ge 2$ blocks or a corner can cause `NativePlayerBody.stepToward` to return `false` (`no safe 3D step`), and the next `ROUTINE_PLANNING` slice immediately re-proposes the exact same `native_traverse` target.
  1. Add three fields to `NativeEntry` (`PlayerRuntime.java:4130`):
     ```java
     private BlockPos lastFailedTraverseTarget = null;
     private int consecutiveTraverseFailures = 0;
     private long traverseTargetCooldownUntilTick = -1;
     ```
  2. In `observeRunner` (`PlayerRuntime.java:2945`), when a task with skill `"native_traverse"` finishes with `Status.BLOCKED`:
     ```java
     if ("native_traverse".equals(activeSkill)) {
         BlockPos failedPos = parseTargetPos(activeArgs);
         if (failedPos != null && failedPos.equals(entry.lastFailedTraverseTarget)) {
             entry.consecutiveTraverseFailures++;
             if (entry.consecutiveTraverseFailures >= 2) {
                 entry.traverseTargetCooldownUntilTick = server.getTicks() + 200;
             }
         } else {
             entry.lastFailedTraverseTarget = failedPos;
             entry.consecutiveTraverseFailures = 1;
         }
     }
     ```
  3. In `evaluateScheduledRoutineCandidates`, skip proposing `native_traverse` toward `entry.lastFailedTraverseTarget` while `server.getTicks() < entry.traverseTargetCooldownUntilTick`, allowing the AI player to pick an alternate reachable block in the patch or rest cleanly without rapid-fire blocked task spam.

---

## 6. Non-Cheat Bot $\leftrightarrow$ Player & Bot $\leftrightarrow$ Bot Interaction, Global Discovery Atlas & Combat Hot-Swapping

Everything in this section strictly obeys `PLY-04` (zero cheats, zero omniscience, 100% item conservation) and `SOC-02` (memories and shared coordinates require physical observation or verified communication provenance).

---

### INT-01: Provenance-Backed Global Discovery Atlas (`SharedWaypointLedger` + Physical Lectern/Sign/Map Artifacts)
- **Related Tracker IDs**: `E-14`, `E-21`, `E-22`, `E-23`, `F-18`, `F-20`, `F-21`
- **Minecraft Wiki Reference**: [Lectern](https://minecraft.wiki/w/Lectern), [Written Book](https://minecraft.wiki/w/Written_Book), [Map#Marking_points](https://minecraft.wiki/w/Map#Marking_points), [Lodestone](https://minecraft.wiki/w/Lodestone)
- **Target Files**:
  - `companion-core/src/main/java/dev/kindred/core/expedition/Waypoint.java`
  - `companion-core/src/main/java/dev/kindred/core/expedition/WaypointKind.java`
  - `companion-core/src/main/java/dev/kindred/core/expedition/DiscoveryAtlas.java` *(new pure-core registry)*
  - `companion-persistence/src/main/java/dev/kindred/persistence/SqliteCompanionStore.java`
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java`

#### Architectural Design (Non-Cheat "Global Storage" of Key Finds)
How do three AI players and human teammates share key finds (exposed iron/diamond veins, villages, lava pools, monster spawners, Nether fortresses, Stronghold portals, or a fallen teammate's death drop coordinates) **without** telepathic omniscience?
We use a **Two-Tier Provenance Model**:
1. **Local Personal Fieldbook (`privateWaypoints`)**: When Player A physically observes an exposed vein or structure via `BlockPerception` (`F-24`) or `NativeTacticalObserver`, it records a `DiscoveredLandmark` in its personal memory (`UNSHARED`). Player B and Player C **cannot** see or pathfind to this coordinate yet!
2. **Global Team Discovery Atlas (`sharedAtlas` + Physical Base Artifacts)**: A personal discovery is promoted to the **Global Team Atlas** only through one of three authentic in-world actions:
   - **A. Proximity / Radio Callout (`SocialBrain` + `F-18`)**: Player A is within earshot/line-of-sight of a teammate or human (or uses permitted team chat) and announces: *"Spotted exposed diamond_ore (4 blocks) at [-42, -54, 118] in overworld."* Listeners within range (or the recipient) record the `DiscoveredLandmark` with `provenance = "heard_from:" + speakerId + "@tick:" + tick`.
   - **B. Physical Base Logbook (`Blocks.LECTERN` / Signboard Sync)**: When any AI player returns to the Home Base (`WaypointKind.HOME_BED` / `WORKSTATION`) and interacts with the base **Expedition Lectern** (`Blocks.LECTERN` holding a `WRITABLE_BOOK`) or **Base Notice Sign**, it writes its new `UNSHARED` discoveries into the book/atlas and reads all entries written by teammates and humans!
   - **C. Physical Cartography & Lodestone Tokens (`E-23`)**:
     - Using a `FILLED_MAP` on a placed named `BannerBlock` marks the coordinate on the vanilla map item (`DataComponentTypes.MAP_DECORATIONS`), which can be placed in a base `ItemFrameEntity` or gifted (`E-22`).
     - Using a `COMPASS` on a `Blocks.LODESTONE` binds `DataComponentTypes.LODESTONE_TRACKER` to Home Base or a Nether Portal so any player holding the compass has a physical directional anchor.

#### Step-by-Step Implementation Guide

**Step 1: Expand `WaypointKind.java` and create `DiscoveredLandmark.java` in `companion-core`**
Add high-value discovery categories to `WaypointKind.java`:
```java
public enum WaypointKind {
    HOME_BED,
    SHELTER,
    RESOURCE_PATCH,
    WORKSTATION,
    HAZARD,
    LANDMARK,
    BREADCRUMB,
    ORE_VEIN,          // Exposed coal, iron, gold, diamond, emerald, ancient_debris
    LAVA_POOL,         // Surface or cave lava source for obsidian / Nether portal casting
    VILLAGE,           // Beds, bell, or villagers observed
    NETHER_FORTRESS,   // Nether bricks / Blaze spawner observed
    STRONGHOLD_PORTAL, // End portal frame observed
    DEATH_RECOVERY     // Exact coordinates where a player died with items
}
```
Create `companion-core/src/main/java/dev/kindred/core/expedition/DiscoveredLandmark.java`:
```java
package dev.kindred.core.expedition;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A provenance-backed spatial discovery. Other agents may only use this landmark
 * if their UUID is in knownByPlayers or sharedAtBaseLectern is true after they visit base.
 */
public record DiscoveredLandmark(
        UUID landmarkId,
        Waypoint waypoint,
        String resourceOrStructureId,
        int estimatedYield,
        UUID discoveredBy,
        String provenance,
        Set<UUID> knownByPlayers,
        boolean syncedToBaseAtlas,
        boolean depleted
) {
    public DiscoveredLandmark {
        Objects.requireNonNull(landmarkId, "landmarkId");
        Objects.requireNonNull(waypoint, "waypoint");
        Objects.requireNonNull(resourceOrStructureId, "resourceOrStructureId");
        Objects.requireNonNull(discoveredBy, "discoveredBy");
        Objects.requireNonNull(provenance, "provenance");
        knownByPlayers = Set.copyOf(Objects.requireNonNull(knownByPlayers, "knownByPlayers"));
    }

    public boolean isKnownTo(UUID playerId) {
        return discoveredBy.equals(playerId) || knownByPlayers.contains(playerId);
    }
}
```

**Step 2: Add Automatic Nether $\leftrightarrow$ Overworld `8:1` Coordinate Calculator**
Per the [Minecraft Wiki — Nether portal#Coordinate conversion](https://minecraft.wiki/w/Nether_portal#Coordinate_conversion), horizontal coordinates between the Overworld and the Nether scale by exactly $8:1$ ($\lfloor X_{\text{overworld}} / 8 \rfloor = X_{\text{nether}}$, $\lfloor Z_{\text{overworld}} / 8 \rfloor = Z_{\text{nether}}$), while $Y$ is preserved.
Add a pure helper on `Waypoint.java`:
```java
    public Optional<Waypoint> linkedPortalCoordinate() {
        if ("minecraft:overworld".equals(dimension)) {
            return Optional.of(new Waypoint(name + "_nether_link", Math.floorDiv(x, 8), y, Math.floorDiv(z, 8),
                    "minecraft:the_nether", WaypointKind.LANDMARK, recordedTick));
        } else if ("minecraft:the_nether".equals(dimension)) {
            return Optional.of(new Waypoint(name + "_overworld_link", x * 8, y, z * 8,
                    "minecraft:overworld", WaypointKind.LANDMARK, recordedTick));
        }
        return Optional.empty();
    }
```

**Step 3: Add Chat Coordinate Parsing & Callouts (`SocialBrain` + `PlayerCommands`)**
- Allow human players to share coordinates with bots via `/aiplayer waypoint share <name> <x> <y> <z> <kind>` (or natural chat `[x, y, z]`), which records a `DiscoveredLandmark` with `provenance = "shared_by_human:" + senderUuid`.
- When an AI player observes a high-value target (`diamond_ore`, `iron_ore`, `spawner`, `lava`, or `deaths`) or gets stuck/injured (`health <= 6.0f`), queue a `SocialBrain` callout including exact `[x, y, z]` coordinates and dimension so both human players and nearby bots receive it.

---

### INT-02: Combat Hot-Swapping & Team Micro-Tactics ("Quickdraw, Clutch & Peel")
- **Related Tracker IDs**: `E-07`, `E-07c`, `F-07`, `E-16`, `E-39`
- **Minecraft Wiki Reference**: [Shield#Defense](https://minecraft.wiki/w/Shield#Defense), [Totem of Undying](https://minecraft.wiki/w/Totem_of_Undying), [Water Bucket](https://minecraft.wiki/w/Water_Bucket), [Damage#Knockback](https://minecraft.wiki/w/Damage#Knockback)
- **Target Files**:
  - `companion-core/src/main/java/dev/kindred/core/tactics/ActionStep.java`
  - `companion-core/src/main/java/dev/kindred/core/tactics/TacticalPriorityPolicy.java`
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/PlayerRuntime.java`
  - `companion-server-adapter/src/main/java/dev/kindred/fabric/NativePlayerBody.java`

#### 4 Non-Cheat Combat Hot-Swaps Every Skilled Player Uses

1. **Dynamic Off-Hand Hot-Swap (`SHIELD` $\leftrightarrow$ `TOTEM_OF_UNDYING`)**:
   - Per the [Minecraft Wiki — Totem of Undying](https://minecraft.wiki/w/Totem_of_Undying), a Totem only triggers if held in the **main hand or off-hand (`slot 40`)** at the moment of fatal damage, restoring $1\text{ HP}$ and granting Regeneration II ($45\text{s}$), Fire Resistance I ($40\text{s}$), and Absorption II ($5\text{s}$).
   - **Rule**: In `evaluateEssentialSurvivalCandidates` (`PlayerRuntime.java:1410`), if `player.getHealth() <= 6.0f` (or `SurvivalClass == EMERGENCY_ONLY` against a Creeper/Boss) and the player owns `Items.TOTEM_OF_UNDYING` in slots `0..35` while off-hand (`slot 40`) holds a Shield or is empty, emit a `Priority.URGENT` (`99.0`) `native_equip` / off-hand swap to put the Totem in `slot 40` immediately! Once health recovers above $12.0\text{ HP}$, swap `Items.SHIELD` back into `slot 40`.

2. **Axe Shield-Breaker $\rightarrow$ Sword DPS Hot-Swap**:
   - Per the [Minecraft Wiki — Shield](https://minecraft.wiki/w/Shield#Defense), striking a shield-blocking target with any `AxeItem` disables the target's shield for $100\text{ ticks}$ ($5\text{ seconds}$), whereas a `SwordItem` has higher sustained DPS ($1.6\text{ attack speed} = 12.5\text{ ticks}$ vs. Axe $0.8\text{–}1.0\text{ attack speed} = 20\text{–}25\text{ ticks}$).
   - **Rule**: Keep `SwordItem` in `slot 0` and `AxeItem` in `slot 2`. When a target is blocking (or when opening with a single heavy hit before retreating), select `slot 2` (Axe), land the opening strike, and switch to `slot 0` (Sword) for follow-up attacks.

3. **Ranged-to-Melee Quickdraw (`Bow/Crossbow` at $>5\text{m}$ $\rightarrow$ `Sword` at $\le 4.5\text{m}$)**:
   - When a hostile mob (especially a Skeleton, Blaze, or approaching Creeper) is at distance $5.0 < d \le 16.0\text{ blocks}$, select the hotbar slot holding `Items.BOW` or a loaded `Items.CROSSBOW` to fire from safety.
   - The moment a melee mob closes to $d \le 4.5\text{ blocks}$, hot-swap to `slot 0` (`SwordItem`) **12 ticks before** the mob reaches $3.0\text{ blocks}$ so the weapon switch cooldown ($0.2 + 0.8c^2$ per Wiki) reaches $100\%$ charge right as the mob enters melee range!

4. **Water Bucket Clutch & Lava/Fire/Enderman Extinguish (`native_water_clutch`)**:
   - Per the [Minecraft Wiki — Water Bucket](https://minecraft.wiki/w/Water_Bucket), placing water from a `WATER_BUCKET` at the player's feet:
     - Instantly extinguishes `player.isOnFire()` (saving $1\text{ HP/sec}$ burn damage after lava/Blaze hits),
     - Converts adjacent flowing lava / lava source blocks into `COBBLESTONE` / `OBSIDIAN`,
     - Resets `player.fallDistance = 0.0f`, and
     - Deals $1\text{ HP}$ water damage to nearby `EndermanEntity` instances, forcing them to teleport away and drop aggro!
   - **Implementation**: Add a checkpointed `NativeBucketAction` (`native_bucket`) that:
     1. Verifies an owned `Items.WATER_BUCKET` in hotbar `0..8` under `EditPolicy.Kind.PLACE` and `ActionAdmission`.
     2. Places water at `player.getBlockPos()` via `player.interactionManager.interactBlock(...)` (consuming `WATER_BUCKET` $\rightarrow$ `BUCKET` in slot).
     3. On the next tick (once `!player.isOnFire()` and `player.isOnGround()`), uses the empty `BUCKET` on the water source block to scoop it back into `Items.WATER_BUCKET`, verifying 100% item conservation (`WATER_BUCKET` count before == after, block state restored to air)!

5. **Bot $\leftrightarrow$ Bot & Bot $\leftrightarrow$ Human "Agro-Peel" (Body-Blocking for an Injured Ally)**:
   - In `NativeTacticalObserver.observe`, also scan friendly teammates and human partners within $12\text{ blocks}$:
     - If an ally has `ally.getHealth() <= 6.0f` and a hostile mob within $6\text{ blocks}$ is targeting that ally (`mob.getTarget() == ally`), and this AI player is healthy (`health >= 14.0f` with armor/shield):
     - Generate a `TacticalObjective.RESOLVE_IMMEDIATE_SERIOUS_HARM` candidate (`PROTECT_ALLY`) where the healthy AI player sprints to intercept the mob and performs a sprint-knockback strike (`player.setSprinting(true)` $\rightarrow$ `native_attack`) to pull aggro (`mob.setTarget(protector)`) and knock the attacker away from the injured teammate!

---

## 7. Architectural Ingenuity & Creative Building Engine (`E-08`, `E-29`, `E-30`, `E-31`)

### Why Most Bots Build "Stale Boxes" — And How Kindred Builds Masterpieces
In [`docs/architectural-design.md`](architectural-design.md), Kindred's building contract requires:
- Declarative plans generated from functional construction rules and persistent preferences (`E-29`),
- Structural fingerprints that **reject duplicate or near-copy houses** where mere rotation, recoloring, or adding a window does not count as novelty (`E-30`),
- Full foundation, access, headroom, site permission, and material conservation validation before placing a single block (`E-08`, `E-31`), and
- **Zero generated executable code** (`AI-03`).

To produce builds with **genuine architectural ingenuity and a sprinkle of creativity**, implement the **5-Stage Procedural Architectural Grammar (`dev.kindred.core.architecture.*`)** below.

---

### Stage 1: Terrain-Adaptive Site Survey (`SiteSurvey.java`) — *Never Bulldoze the Landscape*
Instead of flattening terrain into a sterile square pad, sample theloaded area's heightmap $H(x, z)$, water mask $W(x, z)$, and cliff normals before choosing a foundation style:

| Terrain Topology | Detected Condition | Generated Architectural Foundation |
| --- | --- | --- |
| **Flat Meadow / Clearing** | $\max H - \min H \le 1$ | **Raised Stone-Brick Plinth**: $1$-block elevated stone/deepslate foundation with recessed entry stairs and perimeter planter boxes. |
| **Hillside Slope** | $2 \le \max H - \min H \le 5$ | **Split-Level Terraced Villa**: Lower level cut into the hill as a stone-vaulted smeltery/cellar; upper timber-framed living hall stepped up via an internal half-landing staircase. |
| **Shoreline / River / Ravine** | $\ge 25\%$ footprint over water or drop | **Stilted Timber Pier & Cantilever Deck**: Vertical `STRIPPED_SPRUCE_LOG` / `COBBLESTONE_WALL` pilings extending down to solid ground, braced by upside-down stair corbels (`BlockHalf.TOP`) and a wrap-around fishing dock. |
| **Cliff Face / Mountain Wall** | Steep vertical wall ($\Delta Y \ge 6$ on one edge) | **Cliffside Sanctuary & Cantilevered Overlook**: Hearth and vault anchored into the rock face with an arched timber-and-glass gallery projecting out over the valley. |

---

### Stage 2: Multi-Volume Compositional Shape Grammar (`MassingGrammar.java`) — *True Structural Novelty (`E-30`)*
A building is never a single cuboid. It is a **Connected Volume Graph** $G = (V, E)$ of $2\text{ to }4$ functional wings joined at $90^\circ$ or octagonal offsets:

1. **Primary Hall (`MAIN_HALL`, e.g., $7 \times 9$ or $7 \times 11$, odd widths so roof peaks center cleanly!)**: Houses the central hearth, crafting workstation, and communal table.
2. **Secondary Wing (`WORKSHOP_ANNEX` or `SLEEPING_LOFT`, e.g., $5 \times 7$ attached in an `L-plan`, `T-plan`, or `Courtyard U-plan`)**: Attached at an offset so the roofline forms an **intersecting cross-gable** or **shed dormer** rather than a single flat ridge.
3. **Vertical Accent (`WATCHTOWER_TURRET`, `CHIMNEY_STACK`, or `SKYLIGHT_CUPOLA`, $3 \times 3$ or $5 \times 5$ octagon, $+3\text{ to }+6$ blocks taller)**: Breaks up the horizontal silhouette so the structure reads as a distinct landmark from afar.
4. **Transitional Outdoor Space (`COVERED_PORCH`, `ARCHED_LOGGIA`, or `TERRACED_GARDEN`)**: Connects the interior doorway to the surrounding path/farm with stairs, fence railings, and hanging lanterns.

#### Structural Fingerprint Formula (`E-30` Anti-Clone Guarantee)
Compute `StructuralFingerprint` from the canonical graph invariant:
$$\text{Fingerprint} = \text{Hash}\Big(\text{SortedVolumeGraph}(V, E),\; \text{FoundationType},\; \text{RoofProfileSet},\; \text{FloorElevationDeltas}\Big)$$
Because this fingerprint is invariant under $90^\circ/180^\circ/270^\circ$ rotation and palette swapping, `E-30`'s uniqueness check **forces** every new house in the world to have a genuinely different multi-wing massing, roof silhouette, or split-level elevation!

---

### Stage 3: The "3-Layer Depth" Wall & Roof Detailing Rule (`DetailCompiler.java`)
Stale bot builds fail because their walls are $1$ block thick and flat. Human builders create depth using **three concentric shells** and directional block states (`StairBlock`, `SlabBlock`, `TrapdoorBlock`, `WallBlock`, `LanternBlock`):

```
Top-Down Corner Cross-Section (3-Layer Depth):
  [P] = Protruding Corner Pillar (Stripped Log / Deepslate Tile) + Stair Corbel
  [T] = Exterior Trim (Trapdoor Shutter / Cobblestone Wall Buttress / Planter)
  [W] = Recessed Wall Infill (Stone Brick -> Planks Gradient, 1 block inside frame)

      [P] [T] [T] [T] [P]
      [T] [W] [W] [W] [T]
      [T] [W]         [W]
```

1. **Outer Structural Frame (`Layer 0` — Protruding by $1$ block)**:
   - Corner pillars of `STRIPPED_OAK_LOG`, `STRIPPED_SPRUCE_LOG`, or `POLISHED_DEEPSLATE` placed $1$ block *outside* the recessed wall plane.
   - Horizontal cross-timbers (`Axis.X` / `Axis.Z` logs) marking floor divisions, supported at the corners by **upside-down stairs** (`Properties.BLOCK_HALF = BlockHalf.TOP`, `Properties.HORIZONTAL_FACING` pointing into the pillar) acting as structural brackets/corbels.
2. **Recessed Wall Infill with Vertical Weathering Gradient (`Layer 1`)**:
   - Instead of uniform cobblestone, apply a **3-Band Vertical Palette**:
     - **Socle / Base ($y = 0..1$)**: Heavy textured stone (`COBBLESTONE`, `MOSSY_COBBLESTONE`, `ANDESITE`, or `COBBLED_DEEPSLATE` mixed via deterministic coordinate hash `(x * 734287 + z * 912931) % 5`).
     - **Mid Wall ($y = 2..3$)**: Refined masonry or warm timber (`STONE_BRICKS`, `OAK_PLANKS`, `SPRUCE_PLANKS`).
     - **Upper Gable ($y \ge 4$)**: Lighter contrast infill (`BIRCH_PLANKS`, `CALCITE`, `WHITE_TERRACOTTA`, or timber lattice).
3. **Articulated Roof Profiles & Working Chimneys (`Layer 2`)**:
   - **Never stop a roof flush with the wall!** Extend all roof eaves and gable rakes **1 block beyond the outer frame**, with upside-down stairs underneath the overhang for a thick, finished fascia profile.
   - Support 4 distinct roof grammars:
     1. **Steep Nordic A-Frame** (alternating full block + stair per $Y$ step for a dramatic $2:1$ pitch).
     2. **Gambrel Barn Roof** (steep $2:1$ lower slope transitioning to a shallow $1:2$ slab upper slope).
     3. **Pagoda / Curved Swayback Eaves** (slab flare at the eaves rising to a central ridge fin with fence-post finials).
     4. **Crenellated Battlement & Parapet Walk** (alternating stone-brick merlons and slab crenels with corner watch-lanterns).
   - **Working Chimney**: Above every placed `Blocks.FURNACE` or `Blocks.SMOKER`, route a $1 \times 1$ `COBBLESTONE_WALL` / `BRICK_WALL` flue through the roof topped with a `Blocks.CAMPFIRE` surrounded by 4 closed `SPRUCE_TRAPDOOR` panels—producing authentic rising smoke particles visible across the valley!

---

### Stage 4: Personality-Driven Architectural Taste & "Creative Flourishes" (`ArchitecturalTaste.java`)
Bind the AI player's existing 9 `Personality` traits (`FND-07`) directly to architectural style and signature creative motifs (`E-29`):

```java
package dev.kindred.core.architecture;

import dev.kindred.core.Personality;

/**
 * Pure mapping from an AI player's 9 personality weights to architectural style,
 * massing complexity, roof grammar, and creative signature motifs (E-29).
 */
public record ArchitecturalTaste(
        Archetype archetype,
        RoofStyle preferredRoof,
        SignatureMotif primaryMotif,
        SignatureMotif secondaryMotif,
        double asymmetryBias,
        double verticalityBias
) {
    public enum Archetype {
        TIMBER_HOMESTEAD,      // High farming / sociability: wrap-around porch, warm oak/spruce, crop aqueduct
        FORTIFIED_BASTION,     // High caution / combativeness: deepslate/stone-brick keep, battlements, iron-bar embrasures
        SCHOLAR_OBSERVATORY,   // High curiosity / initiative: asymmetric tower, glass skylight dome, cantilevered deck
        DWARVEN_FOUNDRY_HALL   // High mining / helpfulness: arched stone vaults, twin smoking chimneys, sunken forge
    }

    public enum RoofStyle {
        STEEP_NORDIC_GABLE,
        GAMBREL_BARN,
        CURVED_PAGODA_EAVES,
        CRENELLATED_PARAPET
    }

    public enum SignatureMotif {
        ROSE_WINDOW_GABLE,         // Circular/diamond stained or clear glass focal window in the main gable
        COVERED_BRIDGE_WALKWAY,    // Arched timber bridge linking two upper-story rooms or crossing terrain
        SUNKEN_HEARTH_CONVERSATION_PIT, // Recessed floor around a campfire/furnace with stair seating
        LIVING_ROOF_TERRACE,       // Leaf bushes, flower boxes, and lantern pergola on a stepped roof deck
        WATERWHEEL_OR_AQUEDUCT,    // Decorative timber waterwheel or stone channel feeding the farm irrigation
        ARCADE_COLONNADE_PORCH     // Repeating stair-arch columns framing a shaded front veranda
    }

    public static ArchitecturalTaste fromPersonality(Personality p, long playerSeed) {
        int homesteadScore = p.farming() * 2 + p.sociability() + p.helpfulness();
        int bastionScore = p.caution() * 2 + p.combativeness() * 2;
        int observatoryScore = p.curiosity() * 2 + p.initiative() * 2;
        int foundryScore = p.mining() * 3 + p.helpfulness();

        Archetype archetype = Archetype.TIMBER_HOMESTEAD;
        int max = homesteadScore;
        if (bastionScore > max) { max = bastionScore; archetype = Archetype.FORTIFIED_BASTION; }
        if (observatoryScore > max) { max = observatoryScore; archetype = Archetype.SCHOLAR_OBSERVATORY; }
        if (foundryScore > max) { archetype = Archetype.DWARVEN_FOUNDRY_HALL; }

        RoofStyle roof = switch (archetype) {
            case TIMBER_HOMESTEAD -> RoofStyle.GAMBREL_BARN;
            case FORTIFIED_BASTION -> RoofStyle.CRENELLATED_PARAPET;
            case SCHOLAR_OBSERVATORY -> RoofStyle.CURVED_PAGODA_EAVES;
            case DWARVEN_FOUNDRY_HALL -> RoofStyle.STEEP_NORDIC_GABLE;
        };

        SignatureMotif[] motifs = SignatureMotif.values();
        int m1 = Math.floorMod((int) (playerSeed ^ p.curiosity() * 31L), motifs.length);
        int m2 = Math.floorMod(m1 + 1 + (p.humor() / 25), motifs.length);
        if (m2 == m1) m2 = (m1 + 1) % motifs.length;

        double asymmetry = Math.min(1.0, (p.curiosity() + p.humor()) / 160.0);
        double verticality = Math.min(1.0, (p.initiative() + p.caution()) / 150.0);

        return new ArchitecturalTaste(archetype, roof, motifs[m1], motifs[m2], asymmetry, verticality);
    }
}
```

---

### Stage 5: Gravity-Safe Placement DAG & Multi-Agent Construction (`BlueprintCompiler.java`)
To ensure an AI player (or a 3-player team under `E-11`) can physically construct these complex buildings using `native_place` and `native_traverse` without getting trapped inside walls or failing placement checks because a floating block has no neighbor:

1. **Support-First Topological Sort (`PlacementNode`)**:
   - Every planned block `(x, y, z, blockId, properties)` declares its **Attachment Face** (`Direction.DOWN` for floors/pillars, `Direction.NORTH/SOUTH/EAST/WEST` for wall-mounted trapdoors/torches/stairs, `Direction.UP` for hanging lanterns).
   - Sort the blueprint into strict construction phases:
     - **Phase 1 (`FOUNDATION_AND_FLOOR`, $y = 0$)**: Lay plinth, pilings, and floor slabs from the outside perimeter inward so the builder always stands on completed floor.
     - **Phase 2 (`PILLARS_AND_WALL_COURSES`, $y = 1..H$)**: Build ring-by-ring upward ($y = 1$, then $y = 2$, then $y = 3$), always leaving the doorway (`2` blocks tall) unobstructed as an exit path.
     - **Phase 3 (`ROOF_AND_EAVES`)**: Place central ridge/gable supports first, then step outward along the roof stairs. If roof height exceeds reach ($> 4.5\text{ blocks}$), include explicit **temporary dirt/scaffolding steps** in the plan that are mined and reclaimed (`100%` conserved) during Phase 5!
     - **Phase 4 (`FENESTRATION_AND_EXTERIOR_TRIM`)**: Attach trapdoor shutters, upside-down stair corbels, fence railings, and chimney campfire once all backing blocks exist.
     - **Phase 5 (`INTERIOR_WORKSTATIONS_AND_LIGHTING`)**: Place crafting table, furnace, bed, chests, and lanterns (`light level >= 8` everywhere inside to guarantee zero hostile mob spawns inside the home!).

2. **Cooperative 3-Player Team Partitioning (`E-11` Integration)**:
   - A `SharedProject` for building a Guildhall or Base Shelter divides the blueprint into **independent task slices**:
     - **Task 1 (`gather_and_mill_timber`)**: Assignee A gathers logs and crafts stripped logs, planks, stairs, slabs, and trapdoors.
     - **Task 2 (`quarry_and_masonry`)**: Assignee B mines stone/deepslate and crafts stone bricks, cobblestone walls, and furnaces.
     - **Task 3 (`construct_foundation_and_frame`)**: Dependent on Tasks 1 & 2; builds Phases 1 & 2.
     - **Task 4 (`construct_roof_and_details`)**: Dependent on Task 3; completes Phases 3, 4 & 5.

---

## 8. Task Execution Engine: Optimal Batch-Pipelining vs. Bare-Minimum Speedrun Mode (`EXEC-01` & `EXEC-02`)

---

## `EXEC-01` (P0/P1): How Bots Actually Execute Tasks — Lookahead BOM Batching, 5-Phase Construction Pipelining & Async Smelt Multiplexing

### Why Naive Task Execution Fails in Survival Minecraft
If a bot says *"Okay, I am building a house"* or *"Okay, I am gearing up for Iron tier"*, there are two classic failure modes:

1. **The "1-Item Ping-Pong" Bug (Currently in `MilestoneGraph` / `ProgressionPolicy`)**:
   - In `MilestoneGraph.java:35`, `GATHER_WOOD` is marked `ACHIEVED` as soon as `hasAnyWoodLog(obs)` is true (`1` log = `4` planks).
   - In `MilestoneGraph.java:78`, `MINE_COBBLESTONE` is marked `ACHIEVED` as soon as `obs.has("minecraft:cobblestone", 1)` is true (`1` cobblestone).
   - **Result**: The bot chops 1 log, crafts 4 planks, crafts 4 sticks, and then stalls on `CRAFT_CRAFTING_TABLE` (which needs 4 more planks). Or it mines 1 cobblestone, walks back to the Crafting Table, and realizes `CRAFT_STONE_PICKAXE` needs 3 cobblestone and `CRAFT_FURNACE` needs 8 cobblestone!
2. **The "Gather 100% Upfront" Trap (For Large Builds & Multi-Part Projects)**:
   - Suppose a multi-wing house (`ARCH-01`) requires `340 blocks` (`120 stone bricks`, `80 stripped logs`, `96 planks/stairs`, `24 glass panes`, `20 lanterns/workstations`).
   - If a bot tries to gather all `340 blocks` before placing a single block:
     - It overflows its 36-slot inventory after 2–3 resource types.
     - If night falls while it is at $75\%$ material collection, it has **zero walls and zero roof** despite holding 250 blocks in its pockets.
     - In a 3-player team (`E-11`), the builder sits completely idle waiting for the quarry bot and smelter bot to finish 100% of their quotas.

---

### The Optimal Solution: 3-Part Task Execution Pipeline (`TaskExecutionPlanner`)

#### 1. Lookahead Horizon Bill-of-Materials (BOM) Batching (`HorizonBomCalculator.java`)
Instead of evaluating milestones 1 item at a time, `ProgressionPolicy` should compute the **Horizon Material Deficit** across all currently unlocked milestones plus their immediate 1-hop children, and gather that **entire batch while already standing at the resource patch**:

| Progression Horizon | Current Naive Quota | **Optimal Survival Batch (`OPTIMAL_SURVIVAL`)** | **Speedrun Bare-Minimum Batch (`SPEEDRUN_ANY_PERCENT`)** | Why |
| --- | --- | --- | --- | --- |
| **Initial Wood Sweep** (`GATHER_WOOD`) | `1 log` (4 planks) | **`5 logs` (20 planks)** | **`3 logs` (12 planks)** | `3 logs` = Table (4p) + 4 Sticks (2p) + Wood Pickaxe (3p) + 3 spare planks for Shield/Fuel. `5 logs` adds Chest/Charcoal buffer. |
| **Initial Stone Sweep** (`MINE_COBBLESTONE`) | `1 cobblestone` | **`19 cobblestone`** | **`11 cobblestone`** | `11 cobble` = Stone Pickaxe (3) + Furnace (8) (or Stone Axe 3). `19 cobble` = Pickaxe (3) + Axe (3) + Sword (2) + Hoe (2) + Furnace (8) + 1 spare. |
| **Iron Smelting Batch** (`SMELT_RESOURCES`) | `1 iron_ingot` | **`9–24 iron_ingot` / bot** | **`10 iron_ingot` (team total)** | Optimal equips Iron Pickaxe (3) + Sword (2) + Shield (1) + Bucket (3) + Armor (15). Speedrun needs only Bucket (3) + Flint & Steel (1) + 1 Iron Pick (3) + 3 Shields (3). |

#### 2. 5-Phase Pipelined House Construction ("Gather-While-Building", Never 100% Upfront or 1-By-1)
When building a structure (`ARCH-01`), `BlueprintCompiler` partitions the `BlueprintPlan` into **5 Self-Supporting Structural Phases**, each with its own bounded `PhaseBom` (typically `32–64 blocks` per phase):

```
[Phase 1: Foundation & Bed Anchor]  (32–48 Stone/Cobble + 1 Bed + 4 Torches)
        │
        ▼
[Phase 2: Structural Pillars & Door] (24–36 Logs + 1 Door + Workstations)
        │   ◄── DUSK SHORT-CIRCUIT: If timeOfDay >= 11,000, cap a 3x3 roof over Bed & sleep!
        ▼
[Phase 3: Recessed Wall Courses]     (64–96 Planks/Stone Bricks — built while Sand smelts into Glass!)
        │
        ▼
[Phase 4: Articulated Roof & Glass]  (64–96 Stairs/Slabs + 16 Glass Panes from Furnace)
        │
        ▼
[Phase 5: Interior Trim & Chimney]   (Campfire, Lanterns, Shutters, Chests, Lectern Atlas)
```

- **How a Single Bot Executes a House Build**:
  1. **Batch 1 (Foundation + Frame)**: Gathers only the materials for **Phase 1 + Phase 2** (~1.5 stacks total), drops raw sand into the furnace to smelt into glass in the background, and **immediately builds the foundation, pillars, door, workstations, and bed**.
  2. **Batch 2 (Walls + Roof)**: With the base perimeter and respawn bed already physically anchored in the world, the bot gathers the timber/stone for **Phase 3 + Phase 4**, extracts the finished glass from the furnace, and encloses the walls and roof.
- **How the 3-Bot Team Executes a House Build in Parallel (`E-11` Producer-Consumer Pipeline)**:
  - **Bot A (Quarry Producer)**: Mines stone/deepslate and deposits batches of `32` into the base chest (`StockLedger.addStock`).
  - **Bot B (Timber & Smelter Producer)**: Chops wood, crafts stairs/slabs, and loads sand/ore into the furnace.
  - **Bot C (Architect Consumer)**: Does **not** wait for Bot A and Bot B to gather all 340 blocks! The moment `StockLedger` has the 48 blocks for `PHASE_1`, Bot C claims `build_phase_1`, reserves those 48 blocks, and lays the foundation **while Bots A & B are concurrently gathering Phases 2–4**.

#### 3. Asynchronous Furnace & Crop Time-Multiplexing (Zero Idle Waiting)
In Java Edition 1.21.1, smelting 1 item takes `200 ticks` ($10.0\text{s}$); smelting 8 iron takes `1,600 ticks` ($80.0\text{s}$).
- **Never block on a lit furnace**: In `PlayerRuntime.Entry`, add `long furnaceExpectedReadyTick = -1L;`.
- When `native_smelt(action=load)` succeeds with $N$ items, set `entry.furnaceExpectedReadyTick = server.getTicks() + (Math.min(N, 8) * 200L)`.
- While `server.getTicks() < entry.furnaceExpectedReadyTick` and ` !obs.hasSmeltOutputWaiting()`, the bot is free to chop wood, mine cobblestone, till farmland, or place Phase 1–2 house blocks within base radius.
- Once `server.getTicks() >= entry.furnaceExpectedReadyTick` (or `obs.hasSmeltOutputWaiting()`), `native_smelt(action=extract)` immediately rises to `needScore = 66.0` to collect the ingots.

---

## `EXEC-02` (P1): Dual Playstyle Profiles — `OPTIMAL_SURVIVAL` vs. `SPEEDRUN_ANY_PERCENT`

### Goal
Support two distinct, non-cheat (`PLY-04`) operational doctrines in `companion-core`:
1. **`OPTIMAL_SURVIVAL` (Default Playthrough Mode)**: Methodical, resilient, architecturally creative (`ARCH-01`), builds sustainable farms (`E-13`), full Iron/Diamond armor (`E-36`), Lectern discovery atlas (`INT-01`), and low-risk Dragon prep.
2. **`SPEEDRUN_ANY_PERCENT` (Glitchless Co-op Speedrun Mode)**: Bare-minimum material quotas, zero permanent base building, nomadic workstation pickup, village hay-bale/fire-aspect food, water-bucket lava-cast Nether portal (0 diamonds required), Piglin gold bartering for Ender Pearls, 2-eye Stronghold triangulation, and 1-cycle/bed-cycle Ender Dragon kill.

---

### Side-by-Side Doctrine Matrix (`OPTIMAL_SURVIVAL` vs. `SPEEDRUN_ANY_PERCENT`)

| Gameplay Domain | `OPTIMAL_SURVIVAL` (Standard Playthrough) | `SPEEDRUN_ANY_PERCENT` (Bare-Minimum Fast Clear) | Authentic 1.21.1 Wiki Mechanic Used |
| --- | --- | --- | --- |
| **1. Wood & Stone Opener** | Gather `5 logs` $\rightarrow$ Wood Pick $\rightarrow$ Mine `19 cobble` $\rightarrow$ Stone Pick, Axe, Sword, Hoe, Furnace. | Gather **`3 logs`** $\rightarrow$ Wood Pick $\rightarrow$ Mine **`3 cobble`** $\rightarrow$ Craft Stone Pick immediately $\rightarrow$ Mine **`8 cobble`** (Stone Axe + Furnace). | Mining 8 cobble with a Stone Pickaxe saves $3.2\text{s}$ over mining with a Wooden Pickaxe. |
| **2. Workstation Mobility** | Leave Crafting Tables and Furnaces permanently placed inside the home base. | **Nomadic Pocket Base (`native_reclaim_workstation`)**: Mine Crafting Table (with Axe) and Furnace (with Pickaxe) back into inventory before leaving any area. | Crafting Tables and Furnaces drop $100\%$ of themselves when mined with the proper tool. |
| **3. Iron Quota (3 Bots)** | `24–30 Iron Ingots` per bot (Full Iron Armor = 24, Sword = 2, Pick = 3, Shield = 1). | **`10 Iron Ingots` across the entire 3-bot team**: `1 Water Bucket (3)` + `1 Flint & Steel (1)` + `1 Iron Pickaxe (3)` + `3 Shields (3)`. | A Stone Axe deals `9.0` damage (`7.2` DPS)—higher per-hit damage than an Iron/Diamond Sword (`6.0`/`7.0`) for zero iron cost! |
| **4. Food & Hunger** | Craft Hoe, till irrigated farmland, grow wheat/carrots (`E-13`), smelt meat in furnace. | **Skip `ESTABLISH_FARM`**. Harvest **Village `Hay Bales`** (`1 Hay -> 3 Bread`) or ignite cows/pigs/sheep with `Flint & Steel` / Lava before killing for **instant cooked meat**. | Mobs dying while on fire drop `cooked_beef`/`cooked_porkchop` directly, saving $200\text{ ticks}$ ($10\text{s}$) of furnace time per food item. |
| **5. Shelter & Beds** | Build a multi-wing `ARCH-01` house; place 3 beds to anchor spawn and sleep through night. | **Skip `ARCH-01` house builds**. Collect **`6–8 Beds`** (from shearing sheep or village houses) and store them in inventory as **End/Nether explosives**. | Beds explode with power `5.0` (stronger than TNT's `4.0`) when interacted with in the Nether or End. |
| **6. Nether Portal (`E-37`)** | Mine to $Y = -59$, find 3 Diamonds, craft Diamond Pickaxe, mine 10 Obsidian ($94\text{s}$ mining). | **Water-Bucket Lava Casting (`native_cast_portal`)**: Use `1 Water Bucket` + surface/cave `Lava Pool` to cast the 10-block Obsidian frame in-place in $\sim 15\text{s}$ (**0 Diamonds needed**). | Flowing water hitting stationary lava source blocks turns them directly into `Blocks.OBSIDIAN` in place. |
| **7. Nether Split (`E-37`)** | Sequential Fortress clearing + Enderman hunting. | **Parallel 3-Bot Split**: Bots A & B rush Fortress for **`7 Blaze Rods`**; Bot C wears 1 Gold Armor piece and **barters Gold Ingots with Piglins** for **`12–16 Ender Pearls`**. | Wiki (`Bartering`): Piglins barter `2–4 Ender Pearls` at weight `10/459` per Gold Ingot every $6\text{ seconds}$. |
| **8. Stronghold (`E-39`)** | Throw Eyes of Ender periodically along the overland path. | **2-Throw Parallax Triangulation**: Throw 1 Eye, record yaw $\theta_1$, pick up dropped Eye, strafe $25\text{m}$ perpendicular, throw 2nd time ($\theta_2$), compute intersection $(x_s, z_s)$. | Eyes have an $80\%$ survival rate per throw; 2-ray triangulation finds the Stronghold chunk within $\pm 16\text{ blocks}$ using only 1–2 Eyes. |
| **9. Ender Dragon (`E-40`)** | Destroy all 10 End Crystals with Bows; melee Dragon head during perch with Swords. | **Bed-Cycle Perch Detonation**: 1 bot shoots uncaged Crystals; when Dragon perches on the fountain, place Obsidian/Stone shield block + detonate **5–7 Beds** against the head hitbox. | 5–7 timed Bed explosions timed to the Dragon's head descent deal $200+\text{ HP}$, completing the fight in a single perch! |

---

### Step-by-Step Implementation for `EXEC-01` & `EXEC-02`

#### Step 1: Create `PlaystyleProfile.java` and `HorizonBomCalculator.java` in `companion-core`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-core\src\main\java\dev\kindred\core\progression\PlaystyleProfile.java`:

```java
package dev.kindred.core.progression;

/**
 * Defines whether the AI player team executes a full, sustainable survival playthrough
 * (with architectural building, farming, and full armor tiers) or a bare-minimum
 * glitchless speedrun toward the Ender Dragon.
 */
public enum PlaystyleProfile {
    OPTIMAL_SURVIVAL(
            5,   // initialWoodLogsQuota
            19,  // initialCobblestoneQuota
            24,  // ironIngotQuotaPerPlayer
            true,  // buildPermanentArchitecture
            true,  // establishIrrigatedFarms
            false, // reclaimWorkstationsAfterUse
            false, // allowBucketCastPortalWithoutDiamonds
            12     // targetEyesOfEnder
    ),
    SPEEDRUN_ANY_PERCENT(
            3,   // initialWoodLogsQuota (12 planks: Table=4, 4 Sticks=2, WoodPick=3, Shield/Spare=3)
            11,  // initialCobblestoneQuota (StonePick=3 right away, then Furnace=8 or StoneAxe=3)
            4,   // ironIngotQuotaPerPlayer (Team total ~10: Bucket=3, Flint&Steel=1, IronPick=3, Shields=3)
            false, // buildPermanentArchitecture (nomadic pocket base)
            false, // establishIrrigatedFarms (use hay bales / fire-killed livestock)
            true,  // reclaimWorkstationsAfterUse (mine Crafting Table & Furnace when moving on)
            true,  // allowBucketCastPortalWithoutDiamonds (cast portal at lava pool with water bucket)
            14     // targetEyesOfEnder (12 for portal + 2 for 2-throw triangulation)
    );

    private final int initialWoodLogsQuota;
    private final int initialCobblestoneQuota;
    private final int ironIngotQuotaPerPlayer;
    private final boolean buildPermanentArchitecture;
    private final boolean establishIrrigatedFarms;
    private final boolean reclaimWorkstationsAfterUse;
    private final boolean allowBucketCastPortalWithoutDiamonds;
    private final int targetEyesOfEnder;

    PlaystyleProfile(
            int initialWoodLogsQuota,
            int initialCobblestoneQuota,
            int ironIngotQuotaPerPlayer,
            boolean buildPermanentArchitecture,
            boolean establishIrrigatedFarms,
            boolean reclaimWorkstationsAfterUse,
            boolean allowBucketCastPortalWithoutDiamonds,
            int targetEyesOfEnder) {
        this.initialWoodLogsQuota = initialWoodLogsQuota;
        this.initialCobblestoneQuota = initialCobblestoneQuota;
        this.ironIngotQuotaPerPlayer = ironIngotQuotaPerPlayer;
        this.buildPermanentArchitecture = buildPermanentArchitecture;
        this.establishIrrigatedFarms = establishIrrigatedFarms;
        this.reclaimWorkstationsAfterUse = reclaimWorkstationsAfterUse;
        this.allowBucketCastPortalWithoutDiamonds = allowBucketCastPortalWithoutDiamonds;
        this.targetEyesOfEnder = targetEyesOfEnder;
    }

    public int initialWoodLogsQuota() { return initialWoodLogsQuota; }
    public int initialCobblestoneQuota() { return initialCobblestoneQuota; }
    public int ironIngotQuotaPerPlayer() { return ironIngotQuotaPerPlayer; }
    public boolean buildPermanentArchitecture() { return buildPermanentArchitecture; }
    public boolean establishIrrigatedFarms() { return establishIrrigatedFarms; }
    public boolean reclaimWorkstationsAfterUse() { return reclaimWorkstationsAfterUse; }
    public boolean allowBucketCastPortalWithoutDiamonds() { return allowBucketCastPortalWithoutDiamonds; }
    public int targetEyesOfEnder() { return targetEyesOfEnder; }
}
```

#### Step 2: Upgrade `ProgressionPolicy.java` to Use Batch Quotas & Fast-Upgrade Short-Circuits
In `ProgressionPolicy.recommendForMilestone`:
1. **Batch Wood Gathering (Don't stop at 1 log)**:
   - Even after `GATHER_WOOD` is achieved with 1 log, if the player has not yet crafted a `Crafting Table` + `Wooden Pickaxe` and `logsCount * 4 + planksCount < profile.initialWoodLogsQuota() * 4` while standing in an assigned log patch, emit a **Lookahead Batch Gather** candidate (`needScore = 60.6`, higher than crafting sticks prematurely) so the bot finishes chopping its `3` (Speedrun) or `5` (Optimal) logs before walking to craft!
2. **Speedrun 3-Cobble Fast Upgrade**:
   - In `MINE_COBBLESTONE`, as soon as `cobbleCount >= 3` and the player is still using a `wooden_pickaxe` with a `Crafting Table` within `4.5m`, immediately boost `CRAFT_STONE_PICKAXE` to `needScore = 63.0` so the bot upgrades from Wooden Pickaxe to Stone Pickaxe after just **3 blocks**, then mines the remaining `8–16` cobblestone at Stone Pickaxe speed!
3. **Nomadic Workstation Reclaim (`reclaimWorkstationsAfterUse`)**:
   - In `SPEEDRUN_ANY_PERCENT`, once all currently craftable recipes at a `Crafting Table` are complete and no teammate is within `4.5m` using it, emit `native_mine` targeting `entry.cachedCraftingTablePos` using the bot's Axe so the Crafting Table is carried in inventory to the next stop instead of crafting a new one at every biome.

---

## 9. Canonical Trio Roster (`Kindred`, `Sol`, `Vesp`), Sovereign Peer Independence & In-Game Settings GUI (`ROSTER-01` & `GUI-01`)

---

## `ROSTER-01` (P1): The Canonical Trio (`Kindred`, `Sol`, `Vesp`), Zero Inter-Bot Dependency & Toggable Collaboration

### 1. Canonical Roster & Distinct Default Personalities (`CanonicalRoster.java`)
Kindred MC's three autonomous AI players have canonical names, deterministic UUID seeds per world, and complementary (but **never restrictive**) default `Personality` profiles:

| Slot | Canonical Name | Etymology / Persona | Default 9-Trait `Personality` Profile (`0..100`) | Architectural Archetype (`ARCH-01`) |
| --- | --- | --- | --- | --- |
| **1 (Main)** | **`Kindred`** | *Heart & Bond* — Balanced All-Rounder, Homestead Architect & Team Anchor | `initiative=75, caution=60, curiosity=65, sociability=80, humor=60, combativeness=65, helpfulness=85, farming=80, mining=70` | `TIMBER_HOMESTEAD` (Gambrel Barn Roof, Wrap-Around Porch, Sunken Hearth) |
| **2** | **`Sol`** | *The Sun* — Bold Vanguard, Surface Explorer, Blaze/Dragon Striker | `initiative=90, caution=40, curiosity=85, sociability=65, humor=70, combativeness=90, helpfulness=70, farming=45, mining=60` | `FORTIFIED_BASTION` (Crenellated Parapet, Stone-Brick Keep, Watchtower) |
| **3** | **`Vesp`** | *The Evening Star* — Deep Caver, Redstone/Smelting Artisan & Stronghold Scholar | `initiative=80, caution=75, curiosity=95, sociability=55, humor=50, combativeness=55, helpfulness=75, farming=50, mining=95` | `SCHOLAR_OBSERVATORY` / `DWARVEN_FOUNDRY_HALL` (Steep Nordic Gable, Rose Window, Twin Chimneys) |

> **Critical Invariant (`SOC-01` & `PLY-01`)**: Personality weights only apply a bounded $[-5.0, +5.0]$ preference bias (`TaskPreferences.MAX_PERSONALITY_BIAS`). They **never** lock skills or create class dependencies: **`Kindred`**, **`Sol`**, and **`Vesp`** all possess 100% of the survival, mining, crafting, smelting, farming, building, and combat capabilities!

**Skins (`CanonicalRosterSkins`)**: When a native body's saved display name is exactly **`Kindred`**, **`Sol`**, or **`Vesp`**, spawn/connect applies the matching NameMC texture hash to its `GameProfile` (`textures.minecraft.net`). PNGs live under `assets/kindred/textures/entity/roster/` for legacy mob-body client rendering.

---

### 2. Zero Inter-Bot Dependency ("Sovereign Peer Architecture")
None of **`Kindred`**, **`Sol`**, or **`Vesp`** is a "sub-bot" or dependent on another bot being online, alive, or nearby:
1. **Independent Survival Loop**:
   - Every bot owns its own `PlayerIdentity`, `ServerPlayerEntity` native body (`F-25b`), `PlayerDecisionLoop`, `MilestoneGraph` state, `ActionArbiter`, and `SkillRunner`.
   - Whether **only `Kindred`** is spawned, **only `Sol`**, or all three (`Kindred`, `Sol`, `Vesp`), any single bot can complete the entire `PLY-03`/`E-40` progression from empty inventory to the Ender Dragon solo.
2. **No Leader Single-Point-of-Failure**:
   - `Kindred` is the flagship bot by name, not a master controller for `Sol` or `Vesp`. If `Kindred` is paused, stopped, or disconnected, `Sol` and `Vesp` continue operating at 100% capability without waiting for `Kindred`.

---

### 3. Toggable Collaboration (`CollaborationMode`: `COOP_TEAM` vs. `SOLO_INDEPENDENT`)
Collaboration is a **first-class runtime setting** (`CollaborationMode`), togglable both **globally** and **per-bot** at any safe decision checkpoint:

```java
package dev.kindred.core.team;

/**
 * Controls whether AI players coordinate via shared projects, stock reservations,
 * and shared discovery atlases, or operate as completely independent solo players.
 */
public enum CollaborationMode {
    /**
     * Bots share TeamCoordinator projects (E-11), reserve shared chest stock (StockLedger),
     * synchronize discoveries at the Base Lectern (INT-01), and perform PROTECT_ALLY peels (INT-02).
     * Even in COOP_TEAM, if teammates are offline/paused, any bot can claim and finish any task solo.
     */
    COOP_TEAM,

    /**
     * Every bot ignores TeamCoordinator shared tasks and shared chest reservations,
     * evaluates only its own personal MilestoneGraph and personal inventory/workstations,
     * and plays a 100% self-contained solo run.
     * Ideal for solo play, parallel homesteads, or 3-way Kindred vs. Sol vs. Vesp speedrun races!
     */
    SOLO_INDEPENDENT
}
```

#### How `PlayerRuntime.evaluateScheduledRoutineCandidates` Respects `collaborationMode`:
In `PlayerRuntime.java:1842`:
- Wrap Section `0. Team project task candidates (TEAM-01, TEAM-02)` in `if (isCollaborationEnabled(entry))`:
  - When `COOP_TEAM` is active: the bot checks `teamCoordinator.getPlayerCurrentTask(entry.data.id())` and `teamCoordinator.findEligibleTasks(entry.data.id())`, shares waypoints/discoveries, and peels for allies.
  - When toggled to `SOLO_INDEPENDENT`: `teamCoordinator.handlePlayerStop(entry.data.id())` immediately releases any held shared task or stock reservation so teammates are never blocked, and the bot relies exclusively on its own `MilestoneGraph` (`ProgressionPolicy.recommend`) and personal inventory!

---

## `GUI-01` (P1): In-Game Settings & Roster Control Deck GUI (`KindredControlScreenHandler`)

### Why a Server-Side Virtual Container GUI (`SimpleNamedScreenHandlerFactory`) Is Ideal First
Per contract `SYS-01`:
> *"Retain manifest-pinned Java 21/Fabric/Minecraft, server authority and ordinary-client support. Client enhancements and inference are optional. Reuse native mechanics; do not build a new client engine."*

By implementing the Settings & Roster GUI as a **server-side 9x6 (`GENERIC_9X6`, 54-slot) Virtual Control Deck (`KindredControlScreenHandler`)**, **every ordinary vanilla Minecraft 1.21.1 client** gets a rich, interactive, color-coded visual menu with zero client-side mod installation required!
- Opened anytime via `/aiplayer gui` (or `/kindred gui`), or by sneak-interacting with a named **Command Clock / Lectern** in-world.
- Every button is a richly formatted virtual `ItemStack` using 1.21.1 `DataComponentTypes.CUSTOM_NAME`, `DataComponentTypes.LORE`, and `DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE` (glint = `ON / ACTIVE`).
- **100% `PLY-04` Item Conservation**: `onSlotClick` intercepts all clicks server-side, plays `SoundEvents.UI_BUTTON_CLICK`, updates `RuntimeSettings`, and refreshes the menu slots without ever moving a physical item into or out of the player's inventory.

---

### Visual Layout of the 9x6 `Kindred Control Deck` GUI

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                       KINDRED MC — AI PLAYER CONTROL DECK (9x6)                        │
├───────┬────────┬────────┬────────┬────────┬────────┬────────┬────────┬─────────────────┤
│ [0]   │ [1]    │ [2] ★  │ [3]    │ [4] ☀  │ [5]    │ [6] ☾  │ [7]    │ [8] ⚡          │
│ Glass │ Glass  │KINDRED │ Glass  │  SOL   │ Glass  │ VESP │ Glass  │ SPAWN / RESUME  │
│ Border│ Border │Status &│ Border │Status &│ Border │Status &│ Border │ ALL 3 PLAYERS   │
│       │        │ Select │        │ Select │        │ Select │        │ (1-Click Trio)  │
├───────┴────────┴────────┴────────┴────────┴────────┴────────┴────────┴─────────────────┤
│                         ROW 2: GLOBAL GAMEPLAY DOCTRINE TOGGLES                        │
├───────┬────────┬────────┬────────┬────────┬────────┬────────┬────────┬─────────────────┤
│ [18] 🐉│ [19] ⏱ │ [20] 🌾│ [21] 🤝│ [22]   │ [23] ⛏ │ [24]   │ [25] 🛡│ [26] 🏛         │
│ BOSS & │SPEEDRUN│FOREVER │COLLAB  │        │ AUTON. │        │ COMBAT │ ARCHITECTURE    │
│PLAYTHRU│  MODE  │ WORLD  │  MODE  │        │ EDITS  │        │HOTSWAP │ STYLE PRESET    │
│ON / OFF│ON / OFF│ KAIZEN │COOP/SOL│        │POLICY  │        │ON / OFF│ (E-08 / E-29)   │
├───────┴────────┴────────┴────────┴────────┴────────┴────────┴────────┴─────────────────┤
│                         ROW 3: DISCOVERY, SOCIAL & ACTIVITY CAPS                       │
├───────┬────────┬────────┬────────┬────────┬────────┬────────┬────────┬─────────────────┤
│ [27]  │ [28] 📖│ [29]   │ [30] 💬│ [31]   │ [32] 🌙│ [33]   │ [34] ⏳│ [35] 🔍         │
│       │ GLOBAL │        │ SOCIAL │        │OFFLINE │        │ DAILY  │ PRINT LIVE      │
│       │ ATLAS  │        │ SPEECH │        │AUTONOMY│        │ACTIVITY│ DIAGNOSTICS     │
│       │ SYNC   │        │  MODE  │        │ON / OFF│        │  CAP   │ TO CHAT         │
├───────┴────────┴────────┴────────┴────────┴────────┴────────┴────────┴─────────────────┤
│              ROW 4 & 5: SELECTED BOT INSPECTOR (Currently Selected: KINDRED)           │
├────────┬────────┬────────┬────────┬────────┬────────┬────────┬────────┬────────────────┤
│ [36]   │ [37]   │ [38]   │ [39]   │ [40]   │ [41]   │ [42]   │ [43]   │ [44]           │
│Init: 75│Caut: 60│Curi: 65│Soci: 80│Humo: 60│Comb: 65│Help: 85│Farm: 80│Mine: 70        │
│(L+/R-) │(L+/R-) │(L+/R-) │(L+/R-) │(L+/R-) │(L+/R-) │(L+/R-) │(L+/R-) │(L+/R-)         │
├────────┼────────┼────────┼────────┼────────┼────────┼────────┼────────┼────────────────┤
│ [45] ⏸ │ [46] 🏠│ [47] 📦│ [48] 🧭│ [49] 🤝│ [50] ⏱ │ [51] 🎒│ [52] 🔄│ [53] ❌         │
│ PAUSE /│SET HOME│SET BASE│ ASSIGN │BOT COL.│BOT SPD.│ VIEW   │ RESET  │ CLOSE          │
│ RESUME │ ANCHOR │ CHEST  │ PATCH  │OVERRIDE│OVERRIDE│ INVENT.│DEFAULT │ MENU           │
│ KINDRED│ HERE   │ TARGET │ NEARBY │(SOLO?) │(SPEED?)│ SUMMARY│ TRAITS │                │
└────────┴────────┴────────┴────────┴────────┴────────┴────────┴────────┴────────────────┘
```

---

### Step-by-Step Implementation for `ROSTER-01` & `GUI-01`

#### Step 1: Create `CanonicalRoster.java` and `RuntimeSettings.java` in `companion-core`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-core\src\main\java\dev\kindred\core\CanonicalRoster.java`:

```java
package dev.kindred.core;

import dev.kindred.core.progression.PlaystyleProfile;
import dev.kindred.core.team.CollaborationMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Defines the three canonical autonomous AI players (Kindred, Sol, Vesp)
 * and runtime-toggable gameplay settings.
 * None of the three players depends on another; each is a sovereign peer.
 */
public final class CanonicalRoster {

    public static final String KINDRED_NAME = "Kindred";
    public static final String SOL_NAME = "Sol";
    public static final String VESP_NAME = "Vesp";

    public record RosterPreset(
            String name,
            String title,
            Personality defaultPersonality
    ) {
        public UUID deterministicId(UUID worldId) {
            Objects.requireNonNull(worldId, "worldId");
            return UUID.nameUUIDFromBytes(("kindred-canonical-player:" + worldId + ":" + name)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final RosterPreset KINDRED = new RosterPreset(
            KINDRED_NAME,
            "Heart & Homestead Anchor",
            new Personality(75, 60, 65, 80, 60, 65, 85, 80, 70, 0L)
    );

    public static final RosterPreset SOL = new RosterPreset(
            SOL_NAME,
            "Solar Vanguard & Expedition Pioneer",
            new Personality(90, 40, 85, 65, 70, 90, 70, 45, 60, 0L)
    );

    public static final RosterPreset VESP = new RosterPreset(
            VESP_NAME,
            "Evening Star Artisan & Deep Scholar",
            new Personality(80, 75, 95, 55, 50, 55, 75, 50, 95, 0L)
    );

    public static final List<RosterPreset> TRIO = List.of(KINDRED, SOL, VESP);

    /**
     * Global and per-player toggable settings controlled via /aiplayer gui or commands.
     */
    public record RuntimeSettings(
            PlaystyleProfile playstyleProfile,
            CollaborationMode collaborationMode,
            boolean bossProgressionEnabled,
            boolean foreverWorldKaizenEnabled,
            boolean combatHotSwapEnabled,
            boolean lecternAtlasSyncEnabled,
            String architecturalPresetOverride // "AUTO_PERSONALITY" or Archetype name
    ) {
        public static RuntimeSettings defaults() {
            return new RuntimeSettings(
                    PlaystyleProfile.OPTIMAL_SURVIVAL,
                    CollaborationMode.COOP_TEAM,
                    true,  // bossProgressionEnabled: when false, shifts to pure Forever World Kaizen
                    true,  // foreverWorldKaizenEnabled: continuous ranch, farm, rail, netherite & build upgrades
                    true,
                    true,
                    "AUTO_PERSONALITY"
            );
        }

        public RuntimeSettings toggleBossProgression() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    !bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleForeverWorldKaizen() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    !foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleSpeedrun() {
            return new RuntimeSettings(
                    playstyleProfile == PlaystyleProfile.OPTIMAL_SURVIVAL
                            ? PlaystyleProfile.SPEEDRUN_ANY_PERCENT
                            : PlaystyleProfile.OPTIMAL_SURVIVAL,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleCollaboration() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode == CollaborationMode.COOP_TEAM
                            ? CollaborationMode.SOLO_INDEPENDENT
                            : CollaborationMode.COOP_TEAM,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleCombatHotSwap() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    !combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    architecturalPresetOverride
            );
        }
    }

    private CanonicalRoster() { }
}
```

#### Step 2: Create `KindredControlScreenHandler.java` in `companion-server-adapter`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-server-adapter\src\main\java\dev\kindred\fabric\KindredControlScreenHandler.java`:
- Extend `GenericContainerScreenHandler` (`ScreenHandlerType.GENERIC_9X6`, `rows = 6`) backed by a purely virtual `SimpleInventory(54)`.
- Override `onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player)`:
  - **Always return early without calling `super.onSlotClick`** so zero items can ever be picked up, cloned, or moved (`PLY-04` invariant).
  - If `slotIndex` is one of the interactive control slots:
    - **Slots `2`, `4`, `6` (`Kindred`, `Sol`, `Vesp`)**:
      - `button == 0` (Left-Click): Select this bot for the Row 4–5 Inspector (or if not yet spawned in the world and `player.hasPermissionLevel(2)`, spawn/connect that canonical bot!).
      - `button == 1` (Right-Click): Toggle `stop` / `resume` on that specific bot.
    - **Slot `8` (`Spawn / Wake All Three: Kindred, Sol, Vesp`)**:
      - Ensures all three canonical players (`Kindred`, `Sol`, `Vesp`) are created, connected as native `ServerPlayerEntity` bodies (`F-25b`), and resumed.
    - **Slot `18` (`Boss & Playthrough Mode Toggle`)**:
      - Calls `runtime.toggleBossProgression(actor)` (`bossProgressionEnabled = true/false`). When **OFF**, `Kindred`, `Sol`, and `Vesp` suppress `E-39`/`E-40` boss rushes and devote 100% of their project bandwidth to **Forever World Kaizen (`WORLD-01`)**.
    - **Slot `19` (`Speedrun Mode Toggle`)**:
      - Calls `runtime.toggleSpeedrunMode(actor)` (`OPTIMAL_SURVIVAL` $\leftrightarrow$ `SPEEDRUN_ANY_PERCENT`).
    - **Slot `20` (`Forever World Kaizen Toggle`)**:
      - Toggles `foreverWorldKaizenEnabled` (continuous Ranch estate expansion, Mob/XP farms, Netherite & Level-30 Enchanting grind, Tunnels & Minecart Rail networks, and playful builds).
    - **Slot `21` (`Collaboration Mode Toggle`)**:
      - Calls `runtime.toggleCollaborationMode(actor)` (`COOP_TEAM` $\leftrightarrow$ `SOLO_INDEPENDENT`). When switching to `SOLO_INDEPENDENT`, calls `teamCoordinator.handlePlayerStop` for each bot to cleanly release all shared task/stock reservations.
    - **Slot `23` (`Autonomous Edits Policy Toggle`)**:
      - Cycles `EditPolicy.ServerPolicy` (`DISABLED` $\to$ `HOME_SCOPE` $\to$ `APPROVED_AUTONOMY`).
    - **Slot `25` (`Combat Hot-Swap Toggle`)**:
      - Toggles `combatHotSwapEnabled` (Totem/Shield swap, Axe shield-break, Water bucket clutch, Ally peel).
    - **Slots `36..44` (9 Personality Trait Sliders for Selected Bot)**:
      - Left-Click (`button == 0`): `+10` to trait (clamped `0..100`).
      - Right-Click (`button == 1`): `-10` to trait (clamped `0..100`).
      - Persists updated `SocialProfile` via `store.saveProfile`.
    - **Slot `49` & `50` (Per-Bot Collaboration & Speedrun Override)**:
      - Allows e.g. **`Kindred`** and **`Sol`** to collaborate in `COOP_TEAM` while **`Vesp`** runs `SOLO_INDEPENDENT` (or lets one bot run `SPEEDRUN_ANY_PERCENT` while another builds a house in `OPTIMAL_SURVIVAL`!).
  - Call `refreshSlots()` and `sendContentUpdates()` after any click.

#### Step 3: Wire `/aiplayer gui` and `/aiplayer roster` in `PlayerCommands.java`
In `PlayerCommands.register`:
1. Add `/aiplayer gui` (and `/companion gui`):
   ```java
   root.then(CommandManager.literal("gui").executes(context -> {
       PlayerRuntime runtime = KindredMod.runtime();
       if (runtime == null) return unavailable(context.getSource());
       return runtime.openControlGui(context.getSource());
   }));
   ```
2. Add `/aiplayer roster spawn` to spawn **`Kindred`**, **`Sol`**, and **`Vesp`** with their canonical `Personality` presets in one command, or `/aiplayer roster spawn <Kindred|Sol|Vesp>` to spawn any one of them individually.

---

## 10. Boss-Fight / Playthrough Toggle & The "2+ Year Forever World" Civilization Engine (`WORLD-01`)

---

## `WORLD-01` (P1/P2): Toggable Boss Progression vs. Endless "2+ Year Minecraft World" Kaizen (`WorldKaizenDirector`)

### The Vision: What Happens When Boss Fights Are Toggled OFF (or After the Dragon Falls)?
In a real 2+ year Minecraft survival world, beating the Ender Dragon is just one optional chapter—or something players deliberately postpone while building a sprawling, interconnected civilization.

When **`bossProgressionEnabled == false`** in `/aiplayer gui` (or once `E-40` is completed and the team returns home), **`Kindred`**, **`Sol`**, and **`Vesp`** shift their top-level planner into the **Infinite World Kaizen Loop (`WorldKaizenDirector`)**—where every completed project unlocks the next tier of estate expansion, technical automation, rail infrastructure, Netherite gear perfection, and playful creative architecture.

---

### The 6 Pillars of a "2+ Year Forever World" (`WorldKaizenDirector.java`)

```
                    ┌──────────────────────────────────────────────┐
                    │   INFINITE WORLD KAIZEN LOOP (WORLD-01)      │
                    │  (Active when Boss Playthrough = OFF or Won) │
                    └──────────────────────┬───────────────────────┘
                                           │
       ┌───────────────────┬───────────────┼───────────────┬───────────────────┐
       ▼                   ▼               ▼               ▼                   ▼
┌─────────────┐   ┌─────────────────┐ ┌──────────┐ ┌──────────────┐   ┌────────────────┐
│ 1. HOME     │   │ 2. TECHNICAL &  │ │ 3. RAIL  │ │ 4. NETHERITE │   │ 5. PLAYFUL &   │
│ RANCH       │   │ MOB FARMS       │ │ & TUNNEL │ │ & LEVEL-30   │   │ CREATIVE       │
│ ESTATE      │   │                 │ │ NETWORK  │ │ ENCHANTING   │   │ LANDMARKS      │
├─────────────┤   ├─────────────────┤ ├──────────┤ ├──────────────┤   ├────────────────┤
│• Timber Barn│   │• 22m Drop-Chute │ │• 3x3     │ │• 15-Bookshelf│   │• Treehouse     │
│• Horse/Cow  │   │  Mob Grinder    │ │  Shored  │ │  Enchant Hall│   │  Lookout       │
│  Pastures   │   │• Dungeon Spawner│ │  Tunnels │ │• Y=15 Ancient│   │• Windmill Over │
│• Item-Frame │   │  XP Farm        │ │• Powered │ │  Debris Mine │   │  Wheat Fields  │
│  Storage    │   │• Hopper Super-  │ │  Minecart│ │• Template    │   │• Fishing Pier &│
│  Warehouse  │   │  Smelter Array  │ │  Lines + │ │  Duplication │   │  Lighthouse    │
│• Lit Cobble │   │• Auto Sugarcane │ │  Chest   │ │• Mending /   │   │• Gazebo, Statue│
│  & Slab     │   │  & Bamboo/Kelp  │ │  Carts   │ │  Prot IV     │   │  & Campfire    │
│  Roadways   │   │  Farms          │ │• Nether  │ │  Anvil Loop  │   │  Amphitheater  │
│             │   │                 │ │  Ice Hub │ │              │   │                │
└─────────────┘   └─────────────────┘ └──────────┘ └──────────────┘   └────────────────┘
```

---

#### Pillar 1: The Home Ranch Estate & Automated Storage Barn
Instead of stopping at a single starter shelter (`E-13`), the base evolves into a **multi-building Home Ranch Compound**:
1. **Fenced Pastures, Barn & Stables (`E-06` + `E-36/37`)**:
   - Lure and breed Cows (leather for bookshelves + steak), Sheep (wool for beds/banners), Chickens (feathers for arrows), and tame Horses/Wolves (`native_tame` / `native_breed`) inside oak/spruce fence paddocks with carpet-topped fence-posts (so bots can hop in/out while animals stay inside) and hay-bale feeding troughs.
2. **Categorized Item-Frame Warehouse**:
   - Upgrade from a single chest to a **Double-Chest Storage Barn** where each chest column is tagged with an `ItemFrame` (`Blocks.ITEM_FRAME`) displaying the category icon (`Stone & Masonry`, `Timber & Planks`, `Ores & Ingots`, `Food & Crops`, `Redstone & Rails`, `Tools & Enchanted Gear`).
3. **Spawn-Proof Campus Roads & Lantern Grid**:
   - Connect every building on the ranch with 3-wide textured pathways (`dirt_path` + `coarse_dirt` + `stone_brick_stairs` curbs) lined with `Lantern` posts every `10 blocks` so the entire ranch compound maintains `blockLight >= 8` (zero Creeper craters inside the estate!).

#### Pillar 2: Technical Farms, Mob Grinders & Super-Smelters
To fuel large builds and high-tier enchanting without endless manual grinding, the bots construct **authentic 1.21.1 technical farms**:
1. **Classic 22-Block Drop-Chute Mob Tower (or Spawner Grinder)**:
   - If the `DiscoveryAtlas` (`INT-01`) has recorded a Zombie/Skeleton `MOB_SPAWNER`, convert it into a **9x9x6 water-flushed XP & Loot Grinder** (`water_bucket` corners pushing mobs into a 22-block drop chute onto a `Hopper` $\rightarrow$ `Double Chest` with a half-slab swing window so bots can farm XP safely without taking damage).
   - If no spawner is nearby, build an elevated dark-room flushing canal tower (`blockLight == 0` inside, trapdoors along the canal edges so mobs pathfind into the water stream, roof carpeted/lit on top so mobs only spawn inside the chamber).
2. **Hopper-Fed Super-Smelter Array (`4–8 Furnaces`)**:
   - Construct a row of Furnaces with top input `Hoppers`, back fuel `Hoppers`, and bottom output `Hoppers` draining into a collection chest—multiplying glass, stone-brick, charcoal, and ore smelting throughput by $4\times\text{–}8\times$.
3. **High-Yield Crop, Sugarcane, Bamboo & Kelp Farms**:
   - Plant rows of `Sugar Cane` along water channels (for Paper $\rightarrow$ Bookshelves and Firework Rockets) and `Bamboo` (for `Scaffolding` and infinite furnace fuel planks/sticks).

#### Pillar 3: Shored Tunnels, Bridges & Powered Minecart Rail Networks
Long-distance travel between the Home Ranch, deep deepslate mines ($Y = -59$), discovered villages, and Nether portals is upgraded into **permanent transport infrastructure**:
1. **3x3 Timber-Shored Mine Shafts & Staircases**:
   - Whenever a quarry or branch mine extends more than `24 blocks`, bots install `45°` staircased shafts (`stone_brick_stairs` with `3-block` headroom so players never bump their heads) and timber support arches (`stripped_oak_log` posts + crossbeam + `WallTorch` every 6 blocks).
2. **Powered Minecart Rail Lines (`Blocks.RAIL` + `Blocks.POWERED_RAIL` + `Blocks.REDSTONE_TORCH`)**:
   - Using iron and gold/redstone from deep mining, bots lay continuous rail tracks between the **Home Ranch Warehouse** and the **Deep Mine / Outpost Waypoints**:
     - Place `1 Powered Rail` + adjacent `Redstone Torch` every **`32 blocks`** on flat ground (or every **`4 blocks`** on `45°` inclines, matching the [Minecraft Wiki Powered Rail physics](https://minecraft.wiki/w/Powered_Rail#Momentum) where 1 powered rail maintains $8\text{ m/s}$ max speed across 32 flat rails!).
     - Dispatch **`Chest Minecarts` (`EntityType.CHEST_MINECART`)** from the deep mine up to the surface warehouse, and let bots/humans ride **`Minecarts`** between hubs.
3. **Nether Hub & Ice Boat Highways (`8:1` Fast Travel)**:
   - Enclose the Nether Portal inside a ghast-proof **Cobblestone/Deepslate Nether Hub** and dig spawn-proofed $3\times 3$ tunnels at $Y = 115$ (just below the Nether bedrock ceiling) connecting linked Overworld portals (`INT-01`).

#### Pillar 4: Endgame Gear Grinding — Level-30 Enchanting & `Y = 15` Netherite
Even with Boss Fights OFF, the bots relentlessly optimize their gear to true endgame perfection:
1. **15-Bookshelf Enchanting Library & Grindstone Rerolling**:
   - Craft `45 Leather + 135 Paper` $\rightarrow$ `45 Books` $\rightarrow$ **`15 Bookshelves`** arranged in the canonical 1-block-air-gap ring around an `Enchanting Table` (`Blocks.ENCHANTING_TABLE`) to unlock **Level-30 Enchantments** (`Efficiency IV/V`, `Unbreaking III`, `Fortune III`, `Protection IV`, `Sharpness IV`, `Power IV`).
   - Use a `Grindstone` (`Blocks.GRINDSTONE`) to disenchant unwanted low-tier rolls and an `Anvil` (`Blocks.ANVIL`) to combine books.
2. **`Y = 15` Ancient Debris Mining & Smithing Template Duplication (Java 1.21.1)**:
   - Per the [Minecraft Wiki (Ancient Debris)](https://minecraft.wiki/w/Ancient_Debris), Ancient Debris generation peaks at **$Y = 15$** in the Nether ($1\text{–}3$ veins per chunk, blast-resistant to bed/TNT mining!).
   - Once a bot obtains 1 `Netherite Upgrade Smithing Template` from a Bastion Remnant, it **never wastes the last template**: it always duplicates the template first using **`7 Diamonds + 1 Netherrack + 1 Template -> 2 Templates`** before upgrading Diamond Armor/Tools at a `Smithing Table` (`Blocks.SMITHING_TABLE`)!

#### Pillar 5: Playful & Creative Structures ("Architectural Joy", `E-27..E-31`)
When core survival and infrastructure quotas are healthy, the bots spend discretionary project cycles building **playful, scenic structures** around the Home Ranch so the world feels lived-in and full of personality:
- **`Kindred`'s Signature Builds**:
  - **Rustic Windmill** overlooking the wheat fields (`white_wool` / `oak_fence` sails + rotating grain silo).
  - **Campfire Amphitheater & Gazebo** with stair benches, flower-pot tables, and hanging lanterns where the trio gathers at dusk.
  - **Covered Timber Bridge** spanning rivers or ravines near the ranch.
- **`Sol`'s Signature Builds**:
  - **Canopy Treehouse & Archery Range** with `Target` blocks (`Blocks.TARGET` + hay bale + redstone lamps).
  - **Coastal Lighthouse / Beacon Tower** with a spiral staircase and high-altitude lookout deck.
- **`Vesp`'s Signature Builds**:
  - **Glass-Domed Botanical Conservatory & Aquarium** housing tropical fish/axolotls and rare biome flora brought back from expeditions.
  - **Clocktower & Redstone Bell Shrine** that rings a `Bell` (`Blocks.BELL`) at dawn and dusk via a `DaylightDetectorBlock`.

#### Pillar 6: Continuous World Kaizen ("Improvement After Improvement")
`WorldKaizenDirector` evaluates the ranch and infrastructure every in-game morning (`tick % 24000 == 1000`) and selects the highest-leverage upgrade from an **8-Stage Civilization Ladder**:

```java
package dev.kindred.core.progression;

/**
 * Endless world-development ladder ("2+ Year Forever World") executed when
 * bossProgressionEnabled is false (or after the Ender Dragon is defeated).
 */
public enum WorldKaizenStage {
    STAGE_1_STARTER_HOMESTEAD(
            "Establish Home Ranch House, Irrigated Crop Plot & 3 Respawn Beds"
    ),
    STAGE_2_RANCH_PASTURES_AND_WAREHOUSE(
            "Build Fenced Livestock Pastures, Barn & Item-Frame Categorized Storage Warehouse"
    ),
    STAGE_3_SHORED_MINE_AND_SUPER_SMELTER(
            "Excavate 3x3 Timber-Shored Staircase to Y=-59 & Build Hopper Super-Smelter"
    ),
    STAGE_4_LEVEL_30_ENCHANTING_LIBRARY(
            "Craft 15-Bookshelf Enchanting Hall, Anvil, Grindstone & Fortune/Efficiency Diamond Kit"
    ),
    STAGE_5_MOB_GRINDER_AND_AUTO_FARMS(
            "Construct 22m Drop-Chute Mob/Spawner XP Grinder, Sugarcane & Bamboo Farms"
    ),
    STAGE_6_MINECART_RAIL_AND_ROAD_NETWORK(
            "Lay Lantern-Lit Ranch Roads & Powered Minecart Rail Line (1 Booster / 32 Rails) to Mines"
    ),
    STAGE_7_NETHER_HUB_AND_NETHERITE_FORGE(
            "Build Ghast-Proof Nether Hub, Mine Y=15 Ancient Debris & Duplicate Smithing Templates"
    ),
    STAGE_8_PLAYFUL_LANDMARKS_AND_ESTATE_EXPANSION(
            "Construct Windmill, Treehouse, Glass Conservatory, Bridges & Continuous Estate Upgrades"
    );

    private final String summary;

    WorldKaizenStage(String summary) {
        this.summary = summary;
    }

    public String summary() {
        return summary;
    }
}
```

#### How the Boss Toggle Works in `ProgressionPolicy` & `SurvivalPolicy`
1. **When `bossProgressionEnabled == true` (Playthrough Mode ON)**:
   - `MilestoneGraph` advances toward `E-37` (Blaze Rods & Ender Pearls), `E-39` (Stronghold Triangulation & End Portal), and `E-40` (Ender Dragon Expedition).
2. **When `bossProgressionEnabled == false` (Forever World Mode)**:
   - `ProgressionPolicy` filters out `E-39` / `E-40` boss-rush milestones and delegates `PROJECT`-tier candidate generation to `WorldKaizenDirector.recommendNextUpgrade(...)`.
   - The Nether (`E-37`) is still visited—not to rush the End, but to gather **Quartz** (for `Observers`/`Comparators`), **Glowstone/Shroomlights** (for ranch lighting), **Soul Sand** (for bubble-column elevators!), and **`Y = 15` Ancient Debris** (for full Netherite gear).
   - You can toggle `bossProgressionEnabled` **ON at any time** in `/aiplayer gui`—meaning `Kindred`, `Sol`, and `Vesp` can spend 50 in-game days building a massive ranch, rail network, mob grinder, and full Enchanted Netherite armor, and then you click **🐉 Boss & Playthrough: ON** and watch them march into the End Portal as an unstoppable maxed-out trio!

---

## 11. Ambient Idling, Trinket Scavenging & Mutual Eye-Contact Lock (`IDLE-01`)

### Goal
1. **Default Upon Creation & Non-Locked Mode Switching**:
   - When **`Kindred`**, **`Sol`**, or **`Vesp`** is first created/spawned, its initial `PlaystyleProfile` starts in **`IDLE_OBSERVING`** until a player/operator selects an active play mode (`OPTIMAL_SURVIVAL`, `FOREVER_WORLD`, or `SPEEDRUN_ANY_PERCENT`) via `/aiplayer gui` or `/aiplayer mode`.
   - Modes are **never hard-locked**: a player can switch any bot (or the whole trio) between `IDLE_OBSERVING`, `OPTIMAL_SURVIVAL`, `FOREVER_WORLD`, and `SPEEDRUN_ANY_PERCENT` at any safe checkpoint without restarting or losing state.
   - `IDLE_OBSERVING` is also automatically used as the **in-game waiting sub-state** whenever a bot is waiting for a furnace batch, waiting for crops/daylight, or waiting on a human player.
2. **Adjustable Wander Range (`default = 40 blocks`, range `8..128`)**:
   - While in `IDLE_OBSERVING`, the bot stays within `idleRadiusBlocks` (default `40`) of its anchor point (home anchor, spawn point, or nearby human player).
3. **Passive Observation & Landmark Acknowledgement (Zero World Edits / Zero Progression Start)**:
   - In `IDLE_OBSERVING`, the bot **never mines blocks, places blocks, crafts progression tiers, or initiates boss/expedition quests**.
   - Instead, it strolls casually within the 40-block radius, looking around (`Mannerisms.Quirk.CURIOUSGLANCE`) and passively recording surrounding features into its `DiscoveryAtlas` (`INT-01`):
     - Cave entrances (descending dark air openings with `skyLight <= 4`), exposed ore veins, ravines, villages, ruined portals, and scenic ridges.
     - It can verbally acknowledge these discoveries in chat (`SocialBrain`) when a player is nearby (e.g., *"Spotted a deep cave entrance over at [128, 64, -32]—looks like it drops straight into deepslate."*), ready to use that knowledge the moment you switch it out of Idle Mode!
4. **Loose Ground Trinket & Stick Scavenging (`TRINKET_ITEMS`)**:
   - While idling, if the bot spots loose dropped items on the ground within its 40-block radius—such as fallen **`stick`**, **`sapling`**, **`feather`**, **`flint`**, **`wheat_seeds`**, **`flower`**, **`sweet_berries`**, **`apple`**, **`bone`**, or **`nautilus_shell`**—it walks over to pick them up naturally via vanilla `ItemEntity` collision without breaking any blocks.
5. **Mutual Eye-Contact Lock + Crouch ("Shat" / Squat Greeting) + Live Chat**:
   - When a human player within `16 blocks` looks directly at the bot (the human's look vector $\vec{L}_{\text{human}}$ is within an $\approx 8^\circ$ cone of the bot's eye hitbox, $\vec{L}_{\text{human}} \cdot \vec{V}_{\text{human}\to\text{bot}} \ge 0.985$, with clear line-of-sight):
     - **Stop & Lock**: The bot immediately stops walking (`stopInputs()`), turns its head/body to look directly back into the human player's eyes (`EntityAnchor.EYES`), and holds **Mutual Eye-Contact Lock** for as long as the player keeps looking at it.
     - **Friendly Crouch / Squat ("Shat") & Mirroring**: Upon locking eye contact (or whenever the human player crouches/uncrouches while looking at the bot), the bot performs a friendly Minecraft crouch-uncrouch greeting (`player.setSneaking(true)` for 5 ticks, then `false`, or mirrors the human's sneak state).
     - **Chat While Locked**: Being in `EYE_CONTACT_LOCKED` **never mutes `SocialBrain`**—the bot can initiate a casual greeting, report a cave/trinket it just noticed, or reply to `/aiplayer talk` while maintaining unbroken eye contact and crouch-greeting!

---

### Step-by-Step Implementation for Lesser Agents (`IDLE-01`)

#### Step 1: Create `IdleObservationController.java` in `companion-core`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-core\src\main\java\dev\kindred\core\idle\IdleObservationController.java`:

```java
package dev.kindred.core.idle;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure decision controller for IDLE_OBSERVING mode and in-game waiting states.
 * Enforces the adjustable wander radius (default 40 blocks), passive cave/structure
 * observation, loose ground trinket pickup, and Mutual Eye-Contact Lock with
 * friendly crouch ("shat") greeting and live chat eligibility.
 */
public final class IdleObservationController {

    public static final int DEFAULT_IDLE_RADIUS_BLOCKS = 40;
    public static final int MIN_IDLE_RADIUS_BLOCKS = 8;
    public static final int MAX_IDLE_RADIUS_BLOCKS = 128;

    /** Cosine of ~10 degrees: dot product threshold for human looking at bot's eyes. */
    public static final double EYE_CONTACT_DOT_THRESHOLD = 0.985;
    public static final double EYE_CONTACT_MAX_DISTANCE_SQ = 16.0 * 16.0;

    public static final Set<String> SCAVENGEABLE_TRINKETS = Set.of(
            "minecraft:stick",
            "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
            "minecraft:cherry_sapling", "minecraft:dark_oak_sapling", "minecraft:acacia_sapling",
            "minecraft:feather", "minecraft:flint", "minecraft:wheat_seeds",
            "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:beetroot_seeds",
            "minecraft:sweet_berries", "minecraft:apple", "minecraft:bone",
            "minecraft:string", "minecraft:nautilus_shell", "minecraft:dandelion",
            "minecraft:poppy", "minecraft:cornflower", "minecraft:lily_of_the_valley"
    );

    public enum IdleSubState {
        EYE_CONTACT_LOCKED,   // Stopped, looking back at human player's eyes, can crouch & chat
        PICKING_UP_TRINKET,   // Walking to a loose stick/trinket ItemEntity on the ground
        ACKNOWLEDGING_FEATURE,// Looking at a newly spotted cave mouth, ruin, or ore vein
        STROLLING_IN_RADIUS,  // Gentle wander within the 40-block anchor radius
        RETURNING_TO_RADIUS   // Walked past idleRadiusBlocks; stepping back inside boundary
    }

    public record IdleObservationInput(
            double botX, double botY, double botZ,
            double anchorX, double anchorY, double anchorZ,
            int configuredRadiusBlocks,
            Optional<HumanGazeObservation> gazingHuman,
            Optional<GroundTrinketObservation> nearbyTrinket,
            Optional<LandmarkSighting> newlySpottedLandmark,
            long currentTick
    ) {
        public IdleObservationInput {
            Objects.requireNonNull(gazingHuman, "gazingHuman");
            Objects.requireNonNull(nearbyTrinket, "nearbyTrinket");
            Objects.requireNonNull(newlySpottedLandmark, "newlySpottedLandmark");
        }
    }

    public record HumanGazeObservation(
            UUID humanId,
            String humanName,
            double eyeX, double eyeY, double eyeZ,
            double lookDotProduct,
            double distanceSq,
            boolean humanIsSneaking
    ) { }

    public record GroundTrinketObservation(
            String itemId,
            double x, double y, double z,
            double distanceSq
    ) { }

    public record LandmarkSighting(
            String kind, // "CAVE_ENTRANCE", "EXPOSED_ORE", "VILLAGE", "RUINED_PORTAL"
            int x, int y, int z,
            String description
    ) { }

    public record IdleActionPlan(
            IdleSubState subState,
            boolean haltMovement,
            Optional<double[]> lookAtEyeTarget,
            boolean shouldSneakPulse,
            Optional<double[]> walkTowardTarget,
            Optional<String> ambientChatRemark
    ) { }

    public static IdleActionPlan evaluate(IdleObservationInput input) {
        Objects.requireNonNull(input, "input");
        int radius = Math.clamp(
                input.configuredRadiusBlocks() > 0 ? input.configuredRadiusBlocks() : DEFAULT_IDLE_RADIUS_BLOCKS,
                MIN_IDLE_RADIUS_BLOCKS,
                MAX_IDLE_RADIUS_BLOCKS
        );

        // Priority 1: Mutual Eye-Contact Lock when a human player looks at the bot
        if (input.gazingHuman().isPresent()) {
            HumanGazeObservation gaze = input.gazingHuman().get();
            if (gaze.distanceSq() <= EYE_CONTACT_MAX_DISTANCE_SQ
                    && gaze.lookDotProduct() >= EYE_CONTACT_DOT_THRESHOLD) {
                // Mirror human sneak or perform a friendly 5-tick squat pulse every 40 ticks
                boolean sneakPulse = gaze.humanIsSneaking() || (input.currentTick() % 40L < 5L);
                Optional<String> remark = input.newlySpottedLandmark()
                        .map(lm -> "Hey " + gaze.humanName() + "! I noticed a " + lm.description()
                                + " over at [" + lm.x() + ", " + lm.y() + ", " + lm.z() + "].");
                return new IdleActionPlan(
                        IdleSubState.EYE_CONTACT_LOCKED,
                        true, // Stop walking immediately
                        Optional.of(new double[]{gaze.eyeX(), gaze.eyeY(), gaze.eyeZ()}),
                        sneakPulse,
                        Optional.empty(),
                        remark
                );
            }
        }

        // Priority 2: Enforce adjustable wander radius (default 40 blocks)
        double dxAnchor = input.anchorX() - input.botX();
        double dzAnchor = input.anchorZ() - input.botZ();
        double distSqFromAnchor = dxAnchor * dxAnchor + dzAnchor * dzAnchor;
        if (distSqFromAnchor > (double) (radius * radius)) {
            return new IdleActionPlan(
                    IdleSubState.RETURNING_TO_RADIUS,
                    false,
                    Optional.of(new double[]{input.anchorX(), input.botY() + 1.62, input.anchorZ()}),
                    false,
                    Optional.of(new double[]{input.anchorX(), input.anchorY(), input.anchorZ()}),
                    Optional.empty()
            );
        }

        // Priority 3: Pick up loose ground trinkets (sticks, saplings, feathers, flint, etc.) inside radius
        if (input.nearbyTrinket().isPresent()) {
            GroundTrinketObservation trinket = input.nearbyTrinket().get();
            if (SCAVENGEABLE_TRINKETS.contains(trinket.itemId())) {
                double tx = trinket.x() - input.anchorX();
                double tz = trinket.z() - input.anchorZ();
                if (tx * tx + tz * tz <= (double) (radius * radius)) {
                    return new IdleActionPlan(
                            IdleSubState.PICKING_UP_TRINKET,
                            false,
                            Optional.of(new double[]{trinket.x(), trinket.y() + 0.25, trinket.z()}),
                            false,
                            Optional.of(new double[]{trinket.x(), trinket.y(), trinket.z()}),
                            Optional.empty()
                    );
                }
            }
        }

        // Priority 4: Pause briefly to look at and acknowledge a newly spotted cave or structure
        if (input.newlySpottedLandmark().isPresent()) {
            LandmarkSighting lm = input.newlySpottedLandmark().get();
            return new IdleActionPlan(
                    IdleSubState.ACKNOWLEDGING_FEATURE,
                    true,
                    Optional.of(new double[]{lm.x() + 0.5, lm.y() + 0.5, lm.z() + 0.5}),
                    false,
                    Optional.empty(),
                    Optional.of("Noted " + lm.description() + " at [" + lm.x() + ", " + lm.y() + ", " + lm.z() + "].")
            );
        }

        // Priority 5: Gentle ambient stroll / observation within the 40-block radius
        return new IdleActionPlan(
                IdleSubState.STROLLING_IN_RADIUS,
                false,
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty()
        );
    }

    private IdleObservationController() { }
}
```

#### Step 2: Wire Eye-Contact Detection & Idle Execution in `PlayerRuntime.java`
In `PlayerRuntime.java`:
1. Add helper `detectGazingHuman(ServerPlayerEntity bot)`:
   ```java
   private Optional<IdleObservationController.HumanGazeObservation> detectGazingHuman(ServerPlayerEntity bot) {
       var world = bot.getServerWorld();
       Vec3d botEyes = bot.getEyePos();
       for (ServerPlayerEntity human : world.getPlayers()) {
           if (human == bot || entries.containsKey(human.getUuid()) || human.isSpectator() || !human.isAlive()) {
               continue;
           }
           double distSq = human.squaredDistanceTo(bot);
           if (distSq > IdleObservationController.EYE_CONTACT_MAX_DISTANCE_SQ || distSq < 0.01) continue;
           if (!human.canSee(bot)) continue;

           Vec3d humanEyes = human.getEyePos();
           Vec3d toBot = botEyes.subtract(humanEyes).normalize();
           Vec3d humanLook = human.getRotationVec(1.0F).normalize();
           double dot = humanLook.dotProduct(toBot);
           if (dot >= IdleObservationController.EYE_CONTACT_DOT_THRESHOLD) {
               return Optional.of(new IdleObservationController.HumanGazeObservation(
                       human.getUuid(),
                       human.getName().getString(),
                       humanEyes.x, humanEyes.y, humanEyes.z,
                       dot,
                       distSq,
                       human.isSneaking()
               ));
           }
       }
       return Optional.empty();
   }
   ```
2. When `entry.playstyleProfile == PlaystyleProfile.IDLE_OBSERVING` (or when the bot has no active progression task and is waiting):
   - Call `IdleObservationController.evaluate(...)`.
   - If `plan.haltMovement()` is `true`, call `body.stopInputs()`.
   - If `plan.lookAtEyeTarget()` is present, orient the bot's yaw and pitch smoothly toward `(eyeX, eyeY, eyeZ)` and set `bot.setSneaking(plan.shouldSneakPulse())`.
   - Keep `SocialBrain` active so the bot can chat freely while locked in eye contact!

---

## 12. Toggable Seed Locator & Fair-Play Speedrunning Community Tech (`SPEED-01`)

> **Seed locator retired 2026-10-20.** The structure locator below was deleted (commit `7f10521`, `SeedStructureLocator` and the `seedLocatorEnabled` setting removed): seed or structure lookups are hidden-resource omniscience, which the player contract rules out. AI players know only what they have seen. Item 1 is kept as history, not as a plan; the speedrun-technique ideas in item 2 remain an unimplemented proposal.

### Goal
1. **Toggable Seed-Based Structure Locator (`seedLocatorEnabled`)**:
   - Add a boolean setting `seedLocatorEnabled` (default `false`, togglable in `/aiplayer gui` Slot 22 or `/aiplayer settings seedlocator <on|off>`).
   - When `OFF`: 100% strict `PLY-04` / `QUAL-03` unassisted discovery (bots only know what they physically see or triangulate with Eyes of Ender).
   - When `ON`: Bots may query `ServerWorld.locateStructure(...)` / seed chunk math to locate the nearest Village, Ruined Portal, Shipwreck, Buried Treasure, Nether Fortress, Bastion Remnant, and Stronghold, recording `seedLocatorUsed = true` in the session log.
2. **Speedrunning Community Gimmicks & Exploits (`speedrunCommunityTechEnabled`)**:
   - In `SPEEDRUN_ANY_PERCENT`, enable the classic glitchless speedrunning community mechanics ("fair-play exploits") used by top RSG/FSG runners in Java Edition 1.21.1:

| Speedrun Tech / Gimmick | How Speedrunners Do It in 1.21.1 | Exact Bot Execution (`companion-core` + `companion-server-adapter`) |
| --- | --- | --- |
| **1. Mapless Chunk `(9, ~, 9)` Buried Treasure** | Buried Treasure chests in Java Edition **always** generate at chunk-local coordinate `(x = 9, z = 9)` (`BlockPos(chunkX * 16 + 9, y, chunkZ * 16 + 9)`) right under the sand/gravel layer. | When a Shipwreck map is read or a beach treasure chunk is identified, the bot walks to `(chunkX << 4) + 9, (chunkZ << 4) + 9` and digs straight down 3–6 blocks to loot Iron/Gold/TNT/Cooked Fish in `< 4 seconds`. |
| **2. Underwater Door Air-Pocket (`native_door_airpocket`)** | Placing a `Wooden Door` underwater in Java Edition displaces a $1\times 2$ column of water, creating a breathable air pocket at $Y = 12$ in oceans and magma ravines. | Crafted `oak_door` (6 planks $\to$ 3 doors!) is kept on hotbar during ocean/ravine dives. When `player.getAir() <= 90`, place door on seabed, step inside to restore `air = 300`, loot shipwreck/ravine portal, and reclaim door. |
| **3. Boat Fall-Damage Clutch (`native_boat_clutch`)** | Entering a `Boat` (`5 planks`) 1–2 ticks before hitting the ground resets `fallDistance` to `0.0f`, negating $100\%$ of fall damage from any height (even in the Nether where water evaporates!). | Keep 1 `Boat` in hotbar slot 7; during steep ravine drops or Nether Fortress jumps, place Boat at landing block and call `player.startRiding(boat)` before impact, then break Boat to reclaim. |
| **4. Bastion Piglin Boat-Trap & Gold-Drop Distraction** | Dropping 1 `Gold Ingot` makes nearby Piglins stop attacking for 6 seconds while inspecting the ingot; placing a `Boat` traps dangerous `Piglin Brutes` in place so they cannot pathfind. | Bot wears 1 Gold Armor piece, traps any approaching `PiglinBruteEntity` in a placed `Boat`, mines Bastion `Gold Blocks` (`1 block -> 9 ingots`), drops the stack at a trapped Piglin pit, and collects Pearls/Obsidian/String/Fire-Res Potions. |
| **5. Woolless String-to-Bed Crafting** | If no sheep spawn near spawn, runners barter `String` from Piglins (or kill spiders) and craft `4 String -> 1 White Wool`, then `3 Wool + 3 Planks -> 1 Bed`. | Expand `ProgressionPolicy` `CRAFT_BED` and `NativePlayerBody.planCraft` to automatically craft `white_wool` from `4x minecraft:string` whenever `woolCount < 3 && stringCount >= (3 - woolCount) * 4`. |
| **6. Chunk `(4, ~, 4)` Stronghold Staircase Dive** | Every Stronghold's generation origin (the spiral starter staircase) is centered at chunk-local `(x = 4, z = 4)`. | After 2-Eye triangulation (`E-39`) identifies the Stronghold chunk `(chunkX, chunkZ)`, the bot digs down at `(chunkX * 16 + 4, y, chunkZ * 16 + 4)` (standing on the border of `(4,4)` and `(4,5)` to prevent lava falls) straight into the staircase. |
| **7. One-Cycle Bed & Respawn Anchor Blast Shielding** | Explosion damage in Minecraft is calculated from the explosion center to the entity's **feet** (`player.getPos()`) via raycast. Placing 1 `Obsidian` / `Crafting Table` block between the bot's feet and the Bed/Respawn Anchor blocks $95\%$ of self-damage while the Dragon's head above takes full `50+` damage! | During `E-40` Dragon perch, the bot stands 1 block below the bedrock fountain rim, places 1 blast-shield block in front of its feet, and right-clicks Beds (or glowstone-charged `Respawn Anchors`) at the exact tick the Dragon's head hitbox enters the `2.5m` blast radius. |

---

### Step-by-Step Implementation for Lesser Agents (`SPEED-01`)

#### Step 1: Create `SpeedrunTechPolicy.java` in `companion-core`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-core\src\main\java\dev\kindred\core\progression\SpeedrunTechPolicy.java`:

```java
package dev.kindred.core.progression;

/**
 * Pure coordinate math and recipe helpers for fair-play speedrunning community tech (SPEED-01).
 */
public final class SpeedrunTechPolicy {

    /**
     * In Java Edition 1.21.1, Buried Treasure chests always generate at chunk-local (9, ~, 9).
     */
    public static int[] buriedTreasureBlockXZ(int chunkX, int chunkZ) {
        return new int[]{(chunkX << 4) + 9, (chunkZ << 4) + 9};
    }

    /**
     * In Java Edition 1.21.1, Stronghold starter staircases originate at chunk-local (4, ~, 4).
     */
    public static int[] strongholdStaircaseBlockXZ(int chunkX, int chunkZ) {
        return new int[]{(chunkX << 4) + 4, (chunkZ << 4) + 4};
    }

    /**
     * Checks whether the player can synthesize the required 3 wool for a Bed using String (4 String = 1 Wool).
     */
    public static int craftableWoolFromString(int currentWoolCount, int currentStringCount) {
        int neededWool = Math.max(0, 3 - currentWoolCount);
        int craftable = currentStringCount / 4;
        return Math.min(neededWool, craftable);
    }

    private SpeedrunTechPolicy() { }
}
```

#### Step 2: Add `SeedStructureLocator.java` in `companion-server-adapter`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-server-adapter\src\main\java\dev\kindred\fabric\SeedStructureLocator.java`:
- **Guard**: At the top of `locateNearest(...)`, check `if (!runtimeSettings.seedLocatorEnabled()) return Optional.empty();`.
- When enabled, use `ServerWorld.locateStructure(TagKey<Structure>, BlockPos, int radiusChunks, boolean skipExistingChunks)` on the server thread (bounded to once per 600 ticks per player, never per-tick!) and register the returned `BlockPos` into `DiscoveryAtlas` with provenance tag `"SEED_LOCATOR_TOOL"`.

---

## 13. Signature Combat Loadouts & Dynamic Optimal Weapon Switching (`WEAP-01`)

### Goal
Give **`Vesp`**, **`Sol`**, and **`Kindred`** distinct, iconic weapon identities once those weapons are discovered/crafted, while ensuring **none of them is locked to a single weapon**—each dynamically hot-swaps to whichever weapon is mathematically optimal for the exact range, weather, vertical height, and target count!

| AI Player | Primary Signature Weapon(s) | Secondary / Situation Weapons | Dynamic Optimal Hot-Swap Rules (Java Edition 1.21.1) |
| --- | --- | --- | --- |
| **`Vesp`** | **Trident** (`Items.TRIDENT`: `9.0` melee dmg, `1.1` speed, `8.0` thrown ranged dmg; `Loyalty`, `Impaling`, `Riptide`, `Channeling`) | **Bow** (`Power` / long-range dry targets) & **Sword** (`Sweeping Edge` / `Looting III`) | • **Use Trident (Melee or Throw)** when: target is in water/rain (`Impaling V` adds **`+12.5` bonus damage** to wet targets in 1.21.1!), or at mid-range (`4–18m`) when Trident has `Loyalty >= 1` (and not over End void).<br>• **Use `Riptide` Trident** in rain/water to launch across gaps or burst-strike.<br>• **Hot-Swap to Bow** while a thrown `Loyalty` Trident is in flight returning to hand, or at `> 20m` against dry airborne targets (Blazes/Dragon crystals).<br>• **Hot-Swap to Sword** when surrounded by `>= 3` melee mobs (`Sweeping Edge` crowd cleave) or landing the final blow on a mob whose drops scale with `Looting III` (Endermen/Blazes/Wither Skeletons). |
| **`Sol`** | **Mace** (`Items.MACE`: `6.0` base, `0.6` speed, **1.21 Smash Attack** when `fallDistance >= 1.5 blocks`) & **Charged Crossbow** (`Items.CROSSBOW` with `Piercing` / `Multishot`) | **Sword / Axe** (flat-ground sustained melee) & **Wind Charge** (`Items.WIND_CHARGE`) | • **Pre-Charge Crossbow Before Combat**: Outside melee range, `Sol` loads its Crossbow (`DataComponentTypes.CHARGED_PROJECTILES`) and keeps it ready in hotbar slot 1. On engagement or against a shielded enemy (`Piercing` ignores shields!), fires an instant `0-tick` snap-shot.<br>• **Mace Smash Combo (`fallDistance >= 1.5m`)**: Whenever `Sol` drops from high ground, jumps off a pillar/ledge, or throws a **`Wind Charge` at its feet** (launching `5–7 blocks` into the air!), `Sol` hot-swaps to the **Mace** in mid-air. Hitting the target triggers the 1.21 **Smash Attack** (`+4 HP/block` for first 3 blocks, `+2 HP/block` for next 5, `+1 HP/block` beyond, plus `Density` & `Breach` $-60\%$ armor) and **cancels 100% of `Sol`'s fall damage**!<br>• **Immediate Post-Smash Hot-Swap to Sword**: Because the Mace has a slow `0.6` attack speed (`34 ticks` recharge) on flat ground, the moment `Sol` lands (`onGround && fallDistance < 1.5f`), `Sol` hot-swaps to its **Sword** (`1.6` speed) for rapid follow-up DPS! |
| **`Kindred`** | **Sword + Axe + Bow** (Classic Master-at-Arms Loadout) | **Shield / Totem** (Off-hand Slot 40) & **Water Bucket** (Slot 6) | • **Bow** at `5–24m` range, pre-switching to **Sword** `12 ticks` before the mob closes to `3.0m`.<br>• **Axe** (`9.0–10.0` single hit) to shatter raised shields (`100-tick` disable) or open with a jump-crit.<br>• **Sword** (`9.6–12.8` sustained DPS) for continuous melee combos, `Sweeping Edge` cleave, and `PROTECT_ALLY` sprint-knockback peels. |

---

### Step-by-Step Implementation for Lesser Agents (`WEAP-01`)

#### Step 1: Extend `WeaponProfile.java` in `companion-core`
Update `c:\Users\matmu\Downloads\Kindred MC\companion-core\src\main\java\dev\kindred\core\tactics\WeaponProfile.java` to recognize 1.21.1 `Trident`, `Mace`, `Bow`, and `Crossbow` (including whether a Crossbow is currently loaded via `DataComponentTypes.CHARGED_PROJECTILES`):

```java
public enum WeaponCategory {
    SWORD,
    AXE,
    TRIDENT,
    MACE,
    BOW,
    CROSSBOW,
    OTHER_TOOL
}
```
And add `SUPPORTED_ENCHANTMENTS` entries for 1.21.1 Trident, Mace, Bow, and Crossbow enchantments:
```java
public static final java.util.Set<String> SUPPORTED_ENCHANTMENTS = java.util.Set.of(
        "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
        "sweeping", "sweeping_edge", "unbreaking", "mending", "looting",
        // Trident (Vesp)
        "impaling", "loyalty", "riptide", "channeling",
        // Mace (Sol - 1.21)
        "density", "breach", "wind_burst",
        // Bow & Crossbow (Kindred & Sol)
        "power", "punch", "flame", "infinity", "quick_charge", "multishot", "piercing"
);
```

#### Step 2: Create `SignatureWeaponPolicy.java` in `companion-core`
Create `c:\Users\matmu\Downloads\Kindred MC\companion-core\src\main\java\dev\kindred\core\tactics\SignatureWeaponPolicy.java`:

```java
package dev.kindred.core.tactics;

import dev.kindred.core.CanonicalRoster;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evaluates owned weapons and selects the optimal weapon for the current combat tick,
 * combining mathematical DPS/utility optimization with each canonical AI player's
 * signature combat style:
 * - Vesp: Trident primary (melee/Loyalty throw/Riptide/Impaling), Bow & Sword secondary.
 * - Sol: 1.21 Mace (aerial Smash Attack) & Charged Crossbow primary, Sword secondary on flat ground.
 * - Kindred: Classic Sword + Axe (shield-break) + Bow master-at-arms.
 */
public final class SignatureWeaponPolicy {

    public record CombatContext(
            String playerName,
            double distanceToTarget,
            double playerFallDistance,
            boolean playerTouchingWaterOrRain,
            boolean targetTouchingWaterOrRain,
            boolean targetBlockingWithShield,
            int nearbyHostileCount,
            boolean overVoidOrLava
    ) { }

    /**
     * Computes Java Edition 1.21.1 Mace Smash Attack bonus damage for a given fall distance.
     * Wiki (Mace#Smash_attack):
     * - Requires fallDistance >= 1.5 blocks.
     * - First 3 blocks fallen: +4 damage per block.
     * - Next 5 blocks fallen (blocks 3..8): +2 damage per block.
     * - Any blocks beyond 8: +1 damage per block.
     * - Density enchantment adds +0.5 damage per block fallen per level.
     */
    public static double calculateMaceSmashDamage(double baseDamage, double fallDistance, int densityLevel) {
        if (fallDistance < 1.5) {
            return baseDamage;
        }
        double firstTier = Math.min(3.0, fallDistance) * 4.0;
        double secondTier = Math.max(0.0, Math.min(5.0, fallDistance - 3.0)) * 2.0;
        double thirdTier = Math.max(0.0, fallDistance - 8.0) * 1.0;
        double densityBonus = fallDistance * (0.5 * Math.max(0, densityLevel));
        return baseDamage + firstTier + secondTier + thirdTier + densityBonus;
    }

    public static Optional<WeaponProfile> selectOptimalWeapon(
            List<WeaponProfile> ownedWeapons,
            CombatContext ctx
    ) {
        Objects.requireNonNull(ownedWeapons, "ownedWeapons");
        Objects.requireNonNull(ctx, "ctx");
        if (ownedWeapons.isEmpty()) return Optional.empty();

        boolean isVesp = CanonicalRoster.VESP_NAME.equalsIgnoreCase(ctx.playerName());
        boolean isSol = CanonicalRoster.SOL_NAME.equalsIgnoreCase(ctx.playerName());

        WeaponProfile best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (WeaponProfile wp : ownedWeapons) {
            if (wp.durability() <= 2) continue; // Preserve 1-durability weapons from breaking
            double score = scoreWeapon(wp, ctx, isVesp, isSol);
            if (score > bestScore) {
                bestScore = score;
                best = wp;
            }
        }
        return Optional.ofNullable(best);
    }

    private static double scoreWeapon(WeaponProfile wp, CombatContext ctx, boolean isVesp, boolean isSol) {
        String id = wp.itemId();
        boolean isTrident = id.equals("minecraft:trident");
        boolean isMace = id.equals("minecraft:mace");
        boolean isCrossbow = id.equals("minecraft:crossbow");
        boolean isBow = id.equals("minecraft:bow");
        boolean isSword = wp.isSword();
        boolean isAxe = wp.isAxe();

        double dps = wp.baseDamage() * wp.attackSpeed();
        double score = dps;

        // 1. Shield-break priority: Axes or Piercing Crossbows against blocking targets
        if (ctx.targetBlockingWithShield()) {
            if (isAxe && ctx.distanceToTarget() <= 3.5) {
                score += 45.0; // Instant 100-tick shield disable
            } else if (isCrossbow && wp.enchantmentLevel("piercing") > 0) {
                score += 40.0; // Piercing bolts bypass shields completely!
            }
        }

        // 2. SOL's Signature Loadout: 1.21 Mace (Aerial Smash) + Crossbow + Sword fallback
        if (isMace) {
            if (ctx.playerFallDistance() >= 1.5 && ctx.distanceToTarget() <= 4.0) {
                double smashDmg = calculateMaceSmashDamage(
                        wp.baseDamage(), ctx.playerFallDistance(), wp.enchantmentLevel("density"));
                score = smashDmg * 3.0 + (isSol ? 35.0 : 15.0); // Massive priority for aerial Mace Smash!
            } else {
                // On flat ground (fallDistance < 1.5), Mace's 0.6 speed (3.6 DPS) is inferior to a Sword (9.6 DPS)
                score = dps - 6.0;
            }
        }

        if (isCrossbow) {
            if (ctx.distanceToTarget() > 4.0 && ctx.distanceToTarget() <= 24.0) {
                score = 22.0 + (isSol ? 14.0 : 4.0) + wp.enchantmentLevel("quick_charge") * 3.0;
            } else {
                score = 5.0;
            }
        }

        // 3. VESP's Signature Loadout: Trident (Melee/Loyalty/Impaling/Riptide) + Bow/Sword fallback
        if (isTrident) {
            int impaling = wp.enchantmentLevel("impaling");
            int loyalty = wp.enchantmentLevel("loyalty");
            int riptide = wp.enchantmentLevel("riptide");
            double wetBonus = ctx.targetTouchingWaterOrRain() ? impaling * 2.5 : 0.0;

            if (ctx.distanceToTarget() <= 3.5) {
                // Melee Trident (9.0 base damage + Impaling wet bonus)
                // If surrounded by >= 3 mobs on dry land, allow a Sweeping Edge Sword to edge it out
                double crowdPenalty = (ctx.nearbyHostileCount() >= 3 && !ctx.targetTouchingWaterOrRain()) ? -5.0 : 0.0;
                score = (wp.baseDamage() + wetBonus) * wp.attackSpeed() + (isVesp ? 12.0 : 3.0) + crowdPenalty;
            } else if (ctx.distanceToTarget() <= 20.0) {
                if (riptide > 0 && ctx.playerTouchingWaterOrRain()) {
                    score = 32.0 + (isVesp ? 15.0 : 5.0); // Riptide gap-closer strike!
                } else if (loyalty > 0 && !ctx.overVoidOrLava()) {
                    score = 24.0 + wetBonus + (isVesp ? 14.0 : 4.0); // Safe Loyalty ranged throw
                } else {
                    score = -100.0; // NEVER throw an un-loyal Trident or throw over the End void!
                }
            }
        }

        // 4. Bow (Kindred primary ranged; Vesp secondary ranged when Trident is un-loyal or out of range)
        if (isBow) {
            if (ctx.distanceToTarget() > 4.5 && ctx.distanceToTarget() <= 32.0) {
                score = 21.0 + wp.enchantmentLevel("power") * 2.5 + (isVesp ? 6.0 : 10.0);
            } else {
                score = 2.0; // Pre-switch to melee inside 4.5m!
            }
        }

        // 5. Sword (Kindred primary melee; Vesp crowd/Looting fallback; Sol flat-ground fallback)
        if (isSword) {
            if (ctx.distanceToTarget() <= 4.5) {
                double crowdBonus = ctx.nearbyHostileCount() >= 3 ? 8.0 + wp.enchantmentLevel("sweeping_edge") * 3.0 : 0.0;
                score = dps + crowdBonus + (!isVesp && !isSol ? 10.0 : 6.0);
            } else {
                score = 4.0;
            }
        }

        return score;
    }

    private SignatureWeaponPolicy() { }
}
```

---

## 13b. Autonomous Chest Organization & Bot $\leftrightarrow$ Player Quality-of-Life Engine (`CHEST-01` & `QOL-05`)

### Goal
1. **Autonomous Chest Organization & Multi-Chest Categorization (`CHEST-01` — `ChestOrganizationPolicy.java`)**:
   - **8 Canonical Warehouse Categories** (`StorageCategory`):
     1. `ORES_AND_VALUABLES` (*Ores, Ingots & Gems* — ItemFrame icon: `minecraft:diamond`, order `10`)
     2. `TOOLS_AND_COMBAT` (*Tools, Weapons & Armor* — ItemFrame icon: `minecraft:iron_pickaxe`, order `20`)
     3. `FOOD_AND_CROPS` (*Food, Crops & Seeds* — ItemFrame icon: `minecraft:golden_carrot`, order `30`)
     4. `WOOD_AND_TIMBER` (*Logs, Planks & Saplings* — ItemFrame icon: `minecraft:oak_log`, order `40`)
     5. `STONE_AND_MASONRY` (*Stone, Bricks & Terracotta* — ItemFrame icon: `minecraft:stone_bricks`, order `50`)
     6. `REDSTONE_AND_RAILS` (*Redstone, Rails & Mechanisms* — ItemFrame icon: `minecraft:redstone`, order `60`)
     7. `NETHER_AND_END` (*Nether, Potions & End Materials* — ItemFrame icon: `minecraft:blaze_rod`, order `70`)
     8. `MOB_DROPS_AND_MISC` (*Mob Drops, Utility & Trinkets* — ItemFrame icon: `minecraft:ender_pearl`, order `80`)
   - **2-Pass Chest Tidying (`planChestOrganization`)**:
     - **Pass 1 (Partial Stack Compaction)**: Merges fragmented partial stacks of identical items and components (`canMergeWith`) so a chest doesn't waste 6 slots on `4x cobblestone`, `12x cobblestone`, and `9x cobblestone`.
     - **Pass 2 (Canonical Category + Item + Count Sorting)**: Orders non-empty stacks toward the top-left of the single/double chest by `(StorageCategory.sortOrder -> itemId -> count DESC)`.
   - **Smart Surplus Backpack Deposit (`planSurplusDeposits`)**:
     - When a bot returns to base or its backpack (`slots 9..35`) exceeds $75\%$ capacity, it deposits surplus ores, cobblestone, wood, and mob drops into the matching category chest while **strictly preserving its personal survival kit** (`Tools/Weapons`, `<= 64 Torches`, `<= 32 Cooked Food`, `<= 64 Building Blocks`).
2. **Everyday Bot $\leftrightarrow$ Player Quality-of-Life Etiquette (`QOL-05` — `evaluatePlayerQolAssistance`)**:
   - **Polite Chest Yielding (`YIELD_CHEST_ACCESS`)**: If a human player is currently looking into / interacting with a chest (`humanHasOpenContainerAtTargetChest == true`), the bot pauses outside a `2.5m` radius and says *"Take your time with the chest—I'll sort my pack once you're done"* instead of crowding the player or fighting over slots.
   - **Emergency Food Share (`TOSS_EMERGENCY_FOOD`)**: If a nearby human teammate drops to `foodLevel <= 6` (can no longer sprint) or `health <= 8.0f` ($4\text{ hearts}$) and the bot has $\ge 8$ cooked food items, the bot tosses `4` cooked food items directly to the player.
   - **Dark-Cave Torch Support (`TOSS_TORCHES_IN_DARK`)**: When a human player is mining alongside a bot in darkness (`blockLight <= 3`) and the bot carries $\ge 16$ torches, the bot either places a torch or tosses `8` torches to the human.
   - **Synchronized Night Sleep (`SLEEP_SYNC_IN_BED`)**: The moment a human player gets into a bed at night (`world.isNight()`), nearby bots prioritize `native_sleep` at `needScore = 88.0` so the night skips smoothly without the human waiting on bots.

---

## 14. Master Step-by-Step Playbook for Lesser Agents (`AGENT-PLAYBOOK`)

> **Instructions for Implementing Agents**:
> Execute one slice ID at a time. Before touching any file, read **Section 0 (Mandatory Rules)**, claim the slice in `docs/feature-spec.md`, follow the exact file paths and steps below, and run the verification command at the end of the slice before moving to the next slice.

### Phase A: Tick Performance & Survival Correctness (`OPT-01` to `OPT-08`)
1. **`OPT-01` (Palette-Filtered & Spherical Proximity Scans)**:
   - **Files**: `NativePlayerBody.java`, `PlayerRuntime.java`.
   - **Action**: Replace cubic `-5..5` loops in `findNearbyBlock`, `findNearbyBed`, and `findNearbyFarmland` with spherical distance check (`x*x + y*y + z*z <= rSq`), `y in [-2, +2]`, and `ChunkSectionSource` palette check. Decrement `PlayerRuntime.canScanBlock()` on every check.
   - **Verify**: `.\gradlew.bat :companion-server-adapter:test :companion-server-adapter:runGametest`
2. **`OPT-02` (SignalFrame Threat Wiring & Weapon Cache)**:
   - **Files**: `PlayerRuntime.java`, `NativeTacticalObserver.java`.
   - **Action**: Pass `entry.lastTacticalObservation` into `createSignalFrame` (`ThreatKnowledge.CONFIRMED_DANGER` when hostiles are within 16m). Cache `List<WeaponProfile>` behind `entry.lastInventoryFingerprint` (updated to include `stack.getDamage()` and `stack.getComponents().hashCode()`).
   - **Verify**: `.\gradlew.bat :companion-core:test :companion-server-adapter:test`
3. **`OPT-03` & `OPT-04` (Safe Furnace Fuel & Saturation Food Selection)**:
   - **Files**: `NativePlayerBody.java` (`planSmelt`), `PlayerRuntime.java` (`isEligibleFood`).
   - **Action**: Blacklist wooden tools, weapons, bows, crafting tables, and chests from `planSmelt` fuel; cap fuel count to `ceil(inputCount / itemsPerFuel)`. Blacklist Rotten Flesh, Spider Eye, Poisonous Potato, Pufferfish, and Raw Chicken from `isEligibleFood`; rank foods by saturation when injured; swap food into hotbar `slot 8` instead of `slot 0`.
   - **Verify**: `.\gradlew.bat :companion-server-adapter:runGametest`
4. **`OPT-05`, `OPT-06`, `OPT-07`, `OPT-08` (1.21.1 Shield, Crits, Deepslate & Crop Loot)**:
   - **Files**: `StandardCombatPredictor.java`, `NativeAttackAction.java`, `MilestoneGraph.java`, `NativePlayerBody.java`.
   - **Action**: Add 5-tick shield warmup + threat yaw orientation + self-armor mitigation (`OPT-05`); add jump-fall (`fallDistance > 0 && !onGround`) critical strike execution (`OPT-06`); accept `deepslate_*_ore` and `cobbled_deepslate` in mining and tool/furnace crafting (`OPT-07`); use `Block.getDroppedStacks` and deduct 1 seed on replant in `harvestCrop` (`OPT-08`).
   - **Verify**: `.\gradlew.bat build :companion-server-adapter:runGametest`

---

### Phase B: Trio Roster (`Kindred`, `Sol`, `Vesp`), Idle Mode & In-Game GUI (`ROSTER-01`, `IDLE-01`, `GUI-01`)
5. **`ROSTER-01` (Canonical Trio & Sovereign Independence)**:
   - **Files to Create**: `companion-core/.../CanonicalRoster.java`, `companion-core/.../team/CollaborationMode.java`.
   - **Action**: Define `KINDRED`, `SOL`, and `VESP` presets with deterministic UUID seeds and 9-trait personalities. Gate `TeamCoordinator` task claims in `PlayerRuntime.evaluateScheduledRoutineCandidates` behind `collaborationMode == CollaborationMode.COOP_TEAM`; call `teamCoordinator.handlePlayerStop` when switching to `SOLO_INDEPENDENT`.
   - **Verify**: `.\gradlew.bat :companion-core:test`
6. **`IDLE-01` (Default Idle Mode, 40-Block Radius, Trinket Pickup & Eye-Contact Lock)**:
   - **Files to Create**: `companion-core/.../idle/IdleObservationController.java`.
   - **Files to Edit**: `PlaystyleProfile.java` (add `IDLE_OBSERVING` as initial state on creation), `PlayerRuntime.java` (`detectGazingHuman`, ground trinket pickup, crouch pulse, and non-blocking `SocialBrain` chat while in `EYE_CONTACT_LOCKED`).
   - **Verify**: Unit-test `IdleObservationController` (`lookDotProduct >= 0.985`, `radius = 40`, trinket filter) in `companion-core`.
7. **`GUI-01` (9x6 Vanilla-Compatible Control Deck GUI)**:
   - **Files to Create**: `companion-server-adapter/.../KindredControlScreenHandler.java`.
   - **Files to Edit**: `PlayerCommands.java` (add `/aiplayer gui` and `/aiplayer roster spawn`).
   - **Action**: Build the 54-slot virtual container menu with click-intercepted buttons for `Kindred`/`Sol`/`Vesp`, `Idle/Survival/Speedrun/Forever-World` mode selector, `Boss Fights ON/OFF`, `Collaboration CO-OP/SOLO`, `Seed Locator ON/OFF`, `Idle Radius (16/40/64/128)`, and the 9 Personality sliders.
   - **Verify**: Verify in GameTest that clicking any GUI slot mutates `RuntimeSettings` and transfers `0` items into player inventory (`PLY-04`).

---

### Phase C: Non-Cheat Interaction, Combat Hot-Swapping & Signature Weapons (`INT-01`, `INT-02`, `WEAP-01`)
8. **`INT-01` (Provenance Discovery Atlas, Base Lectern & 8:1 Portal Linker)**:
   - **Files**: `Waypoint.java`, `DiscoveryAtlas.java`, `NativeLecternSyncAction.java`.
   - **Action**: Enforce `knownByPlayers` provenance set; sync discoveries when reading/writing a `Blocks.LECTERN` `WRITABLE_BOOK` at base or via proximity `SocialBrain` callouts; add `Waypoint.linkedPortalCoordinate()`.
9. **`INT-02` & `WEAP-01` (Combat Hot-Swapping & Signature Weapons for `Kindred`, `Sol`, `Vesp`)**:
   - **Files**: `WeaponProfile.java`, `SignatureWeaponPolicy.java`, `TacticalPriorityPolicy.java`, `NativePlayerBody.java`, `PlayerRuntime.java`.
   - **Action**: Add off-hand `Shield` $\leftrightarrow$ `Totem of Undying` swap (`slot 40` at $\le 6.0\text{ HP}$), `Water Bucket` clutch (`native_bucket`), `PROTECT_ALLY` peel, and `SignatureWeaponPolicy` (`Vesp` = Trident + Bow/Sword; `Sol` = 1.21 Mace aerial smash + Charged Crossbow + Sword; `Kindred` = Sword + Axe + Bow).
   - **Verify**: `.\gradlew.bat :companion-core:test :companion-server-adapter:runGametest`

---

### Phase D: Task Execution Pipeline, Speedrun Tech, Architectural Engine & 2+ Year Forever World (`EXEC-01..02`, `SPEED-01`, `ARCH-01`, `WORLD-01`)
10. **`EXEC-01`, `EXEC-02` & `SPEED-01` (Lookahead BOM Batching, Async Smelting & Speedrun Tech)**:
    - **Files**: `PlaystyleProfile.java`, `SpeedrunTechPolicy.java`, `SeedStructureLocator.java`, `ProgressionPolicy.java`, `PlayerRuntime.java`.
    - **Action**: Gather full Horizon BOM batches (5 logs / 19 cobble in Survival; 3 logs / 3$\to$8 cobble in Speedrun); track `furnaceExpectedReadyTick`; add `SpeedrunTechPolicy` (`(9,9)` buried treasure dig, underwater door air pockets, boat fall clutch, string-to-wool beds, `(4,4)` stronghold dive, and blast-shielded bed/anchor detonation).
11. **`ARCH-01` (5-Stage Procedural Architectural Ingenuity Engine)**:
    - **Files**: `dev.kindred.core.architecture.SiteSurvey`, `MassingGrammar`, `DetailCompiler`, `ArchitecturalTaste`, `BlueprintCompiler`.
    - **Action**: Compile terrain-adaptive foundations (plinths/terraces/stilts), multi-wing L/T/Courtyard massing (`E-30` rotation-invariant fingerprint), 3-layer wall depth with vertical weathering gradients, overhanging Nordic/Gambrel/Pagoda roofs with live `Campfire` chimneys, and 5-phase pipelined construction.
12. **`WORLD-01` (Boss Toggle & 2+ Year Forever World Kaizen Engine)**:
    - **Files**: `WorldKaizenStage.java`, `WorldKaizenDirector.java`, `ProgressionPolicy.java`.
    - **Action**: When `bossProgressionEnabled == false` (or post-Dragon), schedule continuous civilization upgrades across the 8 `WorldKaizenStage` tiers: Home Ranch pastures & ItemFrame warehouse, 22-block Mob/XP grinders, 4–8 furnace Hopper Super-Smelters, $3\times 3$ shored tunnels & Powered Minecart rail networks (`1` powered rail per `32` flat / `4` incline blocks), 15-bookshelf Level-30 enchanting + $Y=15$ Netherite with mandatory Smithing Template duplication, and playful hobby landmarks.

---

## 15. Complete Implementation Priority Matrix

| ID | Priority | Slice / Area | Key Files | Expected Impact |
| --- | --- | --- | --- | --- |
| `OPT-01` | **P0** | Palette-filtered & spherical proximity block scans | `NativePlayerBody.java`, `PlayerRuntime.java` | Cuts routine block lookups by $>90\%$ ($6,964 \rightarrow \le 128$/slice) |
| `OPT-02` | **P0** | Wire `ThreatKnowledge` to `SignalFrame` & cache `WeaponProfile` | `PlayerRuntime.java`, `NativeTacticalObserver.java` | Enables real `ALERT`/`COMBAT` state transitions; cuts per-tick slot scans |
| `OPT-03` | **P0** | Safe furnace fuel ranking & burn-time cap | `NativePlayerBody.java` | Prevents burning wooden tools/tables; conserves wood/coal stocks |
| `OPT-04` | **P0** | Poison/hunger food blacklist, saturation ranking & slot 8 swap | `PlayerRuntime.java` | Eliminates self-poisoning; $8\times$ faster Java 1.21.1 saturation healing |
| `EXEC-01` | **P0** | Lookahead BOM batching, 5-phase build pipelining & async furnace timer | `ProgressionPolicy.java`, `TeamCoordinator.java`, `PlayerRuntime.java` | Eliminates 1-item ping-pong trips, 100%-upfront hoarding & furnace idle wait |
| `IDLE-01` | **P1** | Default Idle Mode (`40m` radius), trinket pickup & Eye-Contact Lock + Crouch/Chat | `IdleObservationController.java`, `PlayerRuntime.java` | Natural ambient presence on creation/wait with eye-contact lock & squat greeting |
| `CHEST-01` | **P1** | Autonomous Chest Organization, 8-Category Sorting & Surplus Deposit | `ChestOrganizationPolicy.java`, `NativeChestOrganizeAction.java` | Compacts partial stacks, sorts chests by category & preserves bot survival kit |
| `QOL-05` | **P1** | Bot$\leftrightarrow$Player QoL: Chest Yielding, Emergency Food Toss, Cave Torches & Sleep Sync | `ChestOrganizationPolicy.java`, `PlayerRuntime.java` | Polite chest access, shares food/torches with low-stat humans & syncs bed sleep |
| `ROSTER-01` | **P1** | Canonical Trio (`Kindred`, `Sol`, `Vesp`) & Independent/Co-op Toggle | `CanonicalRoster.java`, `CollaborationMode.java`, `PlayerRuntime.java` | Zero inter-bot dependency; toggleable `COOP_TEAM` vs `SOLO_INDEPENDENT` |
| `WEAP-01` | **P1** | Signature Weapons (`Vesp` Trident, `Sol` Mace/Crossbow, `Kindred` Sword/Axe/Bow) | `SignatureWeaponPolicy.java`, `WeaponProfile.java`, `PlayerRuntime.java` | Distinct weapon identities with dynamic mathematical hot-swapping |
| `GUI-01` | **P1** | In-Game 9x6 Control Deck GUI (`/aiplayer gui`) | `KindredControlScreenHandler.java`, `PlayerCommands.java` | Clickable vanilla-compatible GUI for Modes, Bosses, Collab, Seed Tool & Traits |
| `EXEC-02` | **P1** | `PlaystyleProfile`: `IDLE` / `OPTIMAL_SURVIVAL` / `SPEEDRUN_ANY_PERCENT` | `PlaystyleProfile.java`, `ProgressionPolicy.java`, `MilestoneGraph.java` | Non-locked mode switching between Idle, Optimal Survival, and Speedrun |
| `SPEED-01` | **P1** | Fair-Play Speedrun Community Tech (seed locator retired 2026-10-20) | `SpeedrunTechPolicy.java` | `(9,9)` treasure, underwater door air, boat clutch, Bastion tech & `(4,4)` stronghold |
| `WORLD-01` | **P1** | Boss Toggle & "2+ Year Forever World" Civilization Engine | `WorldKaizenDirector.java`, `WorldKaizenStage.java`, `ProgressionPolicy.java` | Home Ranch, Mob Farms, Powered Minecart Rails, Netherite & Playful Builds |
| `OPT-05..09` | **P1** | Shield 5t warmup, jump crits, deepslate ores, crop loot & SQLite task durability | `StandardCombatPredictor.java`, `NativeAttackAction.java`, `NativePlayerBody.java` | Authentic 1.21.1 survival mechanics & crash-safe Schema 8 persistence |
| `INT-01..02` | **P1** | Provenance Discovery Atlas, Lectern Sync, Totem/Bucket Clutch & Ally Peel | `DiscoveryAtlas.java`, `TacticalPriorityPolicy.java`, `NativePlayerBody.java` | Non-cheat Bot$\leftrightarrow$Player coordinate sharing & human-grade combat reflexes |
| `ARCH-01` | **P2** | 5-Stage Procedural Architectural Engine (`E-08`, `E-29..31`) | `dev.kindred.core.architecture.*`, `NativePlaceAction.java` | Terrain-adaptive, multi-wing, 3-layer textured builds with creative motifs |
| `OPT-10` | **P2** | Portal handoff, 2-Eye triangulation & Dragon head hitbox | `SurvivalPolicy.java`, `NativePlayerBody.java`, `NativeAttackAction.java` | Unblocks `E-15` (Nether), `E-39` (Stronghold), and `E-40` (Dragon) |





