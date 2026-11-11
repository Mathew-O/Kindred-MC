package dev.kindred.fabric;

import dev.kindred.core.Capabilities;
import dev.kindred.core.VanillaIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * F-38: the capability snapshot the adapter builds from a vanilla server says exactly what {@link VanillaIds}
 * says, so core's tag-based reasoning behaves on vanilla as its id lists did. Each exact group's server tag holds
 * the group's vanilla members and no others; each vanilla tool's material gives the tier VanillaIds gives it.
 */
public final class NativeCapabilitiesGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void theServersTagsAndToolsMatchVanillaIdsOnAVanillaServer(TestContext context) {
        Capabilities facts = KindredCapabilities.refresh(context.getWorld().getServer());
        List<String> problems = new ArrayList<>();
        for (VanillaIds.Group group : VanillaIds.groups()) {
            if (!group.exact()) continue;
            Set<String> server = new TreeSet<>(facts.members(group));
            Set<String> vanilla = new TreeSet<>(group.ids());
            if (!server.equals(vanilla)) {
                Set<String> extra = new TreeSet<>(server);
                extra.removeAll(vanilla);
                Set<String> missing = new TreeSet<>(vanilla);
                missing.removeAll(server);
                problems.add(group.tag() + ": server adds " + extra + ", lacks " + missing);
            }
        }
        VanillaIds.tools().forEach((id, tool) -> {
            var item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(id));
            if (!(item instanceof net.minecraft.item.ToolItem toolItem)) {
                problems.add(id + ": not a tool item on this server");
            } else if (KindredCapabilities.tier(toolItem.getMaterial()) != tool.tier()) {
                problems.add(id + ": material tier " + KindredCapabilities.tier(toolItem.getMaterial())
                        + ", VanillaIds " + tool.tier());
            }
        });
        context.assertTrue(problems.isEmpty(), "Vanilla capabilities differ: " + String.join("; ", problems));
        context.complete();
    }

    /**
     * The tags that took the place of core's id endings pick out, among this server's items, exactly what each
     * ending did: so a placement, a house's count or a chest's category is decided on vanilla as before.
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void eachTagCoreUsesForAnIdEndingHoldsExactlyWhatTheEndingMatched(TestContext context) {
        Capabilities facts = KindredCapabilities.refresh(context.getWorld().getServer());
        java.util.Map<String, java.util.function.Predicate<String>> endings = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<VanillaIds.Group>> groups = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<String, List<VanillaIds.Group>> ending = (suffix, g) -> {
            endings.put(suffix, id -> id.endsWith(suffix));
            groups.put(suffix, g);
        };
        endings.put("_door, not _trapdoor", id -> id.endsWith("_door") && !id.endsWith("_trapdoor"));
        groups.put("_door, not _trapdoor", List.of(VanillaIds.DOORS));
        ending.accept("_trapdoor", List.of(VanillaIds.TRAPDOORS));
        ending.accept("_bed", List.of(VanillaIds.BEDS));
        ending.accept("_stairs", List.of(VanillaIds.STAIRS));
        ending.accept("_slab", List.of(VanillaIds.SLABS));
        ending.accept("_fence", List.of(VanillaIds.FENCES));
        ending.accept("_fence_gate", List.of(VanillaIds.FENCE_GATES));
        ending.accept("_pressure_plate", List.of(VanillaIds.PRESSURE_PLATES));
        ending.accept("_concrete_powder", List.of(VanillaIds.CONCRETE_POWDERS));
        ending.accept("_glazed_terracotta", List.of(VanillaIds.GLAZED_TERRACOTTAS));
        ending.accept("_seeds", List.of(VanillaIds.SEEDS));
        ending.accept("_leaves", List.of(VanillaIds.LEAVES));
        ending.accept("_planks", List.of(VanillaIds.PLANKS));
        ending.accept("_sword", List.of(VanillaIds.SWORDS));
        ending.accept("_pickaxe", List.of(VanillaIds.PICKAXES));
        ending.accept("_axe", List.of(VanillaIds.AXES));
        ending.accept("_shovel", List.of(VanillaIds.SHOVELS));
        ending.accept("_hoe", List.of(VanillaIds.HOES));
        ending.accept("_helmet", List.of(VanillaIds.HEAD_ARMOR));
        ending.accept("_chestplate", List.of(VanillaIds.CHEST_ARMOR));
        ending.accept("_leggings", List.of(VanillaIds.LEG_ARMOR));
        ending.accept("_boots", List.of(VanillaIds.FOOT_ARMOR));
        ending.accept("_sign", List.of(VanillaIds.SIGNS, VanillaIds.HANGING_SIGNS));
        // Ores: the tag adds ancient debris, which the valuables list already held.
        endings.put("_ore, or valuable", id -> id.endsWith("_ore") || VanillaIds.CHEST_VALUABLES.ids().contains(id));
        groups.put("_ore, or valuable", List.of(VanillaIds.ORES, VanillaIds.CHEST_VALUABLES));
        // Carpets: moss carpet is not a wool carpet; Placement names it beside the tag.
        endings.put("_carpet", id -> id.endsWith("_carpet"));
        groups.put("_carpet", List.of(VanillaIds.WOOL_CARPETS));
        // Timber: the mushroom stem ended in _stem but is not a log; the timber list names it.
        endings.put("_log, _wood, _stem, _hyphae, or timber", id -> id.endsWith("_log") || id.endsWith("_wood")
                || id.endsWith("_stem") || id.endsWith("_hyphae") || VanillaIds.CHEST_TIMBER.ids().contains(id));
        groups.put("_log, _wood, _stem, _hyphae, or timber", List.of(VanillaIds.LOGS, VanillaIds.CHEST_TIMBER));
        List<String> problems = new ArrayList<>();
        endings.forEach((name, test) -> {
            Set<String> byEnding = new TreeSet<>(), byTag = new TreeSet<>();
            for (var item : net.minecraft.registry.Registries.ITEM) {
                String id = net.minecraft.registry.Registries.ITEM.getId(item).toString();
                if (test.test(id)) byEnding.add(id);
                for (VanillaIds.Group g : groups.get(name)) if (facts.is(g, id)) byTag.add(id);
            }
            if (name.equals("_carpet")) byTag.add(VanillaIds.MOSS_CARPET);
            if (!byEnding.equals(byTag)) {
                Set<String> extra = new TreeSet<>(byTag);
                extra.removeAll(byEnding);
                Set<String> missing = new TreeSet<>(byEnding);
                missing.removeAll(byTag);
                problems.add(name + ": tag adds " + extra + ", lacks " + missing);
            }
        });
        context.assertTrue(problems.isEmpty(), "Tags differ from the endings they replace: " + String.join("; ", problems));
        context.complete();
    }
}
