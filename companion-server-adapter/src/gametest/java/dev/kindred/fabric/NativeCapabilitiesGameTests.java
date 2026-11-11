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
        Capabilities facts = KindredCapabilities.refresh();
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
}
