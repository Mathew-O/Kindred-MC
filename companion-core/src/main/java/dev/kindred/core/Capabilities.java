package dev.kindred.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: what the game says things are (F-38). Core asks "is this a log?" or "is this a chest?" here
 * instead of comparing names, so a log a mod adds is a log as soon as the game tags it as one.
 *
 * Technical: An immutable snapshot of item, block and entity facts as plain strings: for each
 * {@link VanillaIds.Group}, the ids that belong to it. The server adapter builds one on the server thread from the
 * server's tags at start and after a datapack reload, and {@link #install installs} it; until then, and in pure
 * tests and the simulator, the vanilla members written in {@link VanillaIds} stand in. A group marked exact takes
 * the server's tag as it is (a tagged modded log is a log); a group with no exact tag keeps its vanilla members,
 * since nothing in the game says more. Core never sees a Minecraft object here, only ids.
 */
public final class Capabilities {

    private static final Capabilities VANILLA = new Capabilities(Map.of());
    private static volatile Capabilities current = VANILLA;

    /** A tool by what it is and how good: tier 1 wood, 2 stone, 3 iron, 4 diamond, 5 netherite; 0 gold, which lasts too little to count. */
    public record Tool(String kind, int tier) {
        public static final String PICKAXE = "pickaxe", AXE = "axe", SHOVEL = "shovel", HOE = "hoe", SWORD = "sword";
    }

    /**
     * A wood's family, by full id: what its log makes and what its planks make. {@code name} is how builds and saves
     * name it: a vanilla wood's bare name ("oak"), another's planks id without "_planks". A part the server has no
     * recipe for is null.
     */
    public record Wood(String name, String log, String planks, String stairs, String slab, String fence,
                       String fenceGate, String door, String trapdoor) {
        public Wood {
            java.util.Objects.requireNonNull(name, "name");
            java.util.Objects.requireNonNull(log, "log");
            java.util.Objects.requireNonNull(planks, "planks");
        }

        /** Whether every part a build may ask for is known. */
        public boolean complete() {
            return stairs != null && slab != null && fence != null && fenceGate != null && door != null && trapdoor != null;
        }

        /** Whether {@code id} is one of this wood's parts. */
        public boolean has(String id) {
            return id != null && (id.equals(log) || id.equals(planks) || id.equals(stairs) || id.equals(slab)
                    || id.equals(fence) || id.equals(fenceGate) || id.equals(door) || id.equals(trapdoor));
        }
    }

    /**
     * A way the server makes an item: {@code makes} of {@code output} from {@code inputs} (an ingredient's first
     * matching id, with how many of it), as the adapter read the server's crafting and smelting recipes.
     */
    public record Recipe(String output, int makes, Map<String, Integer> inputs) {
        public Recipe {
            java.util.Objects.requireNonNull(output, "output");
            inputs = Map.copyOf(inputs);
            if (makes < 1 || inputs.isEmpty()) throw new IllegalArgumentException("A recipe makes something from something");
        }
    }

    private final Map<String, Set<String>> members;
    private final Map<String, Tool> tools;
    private final Map<String, Wood> woods;
    private final Map<String, java.util.List<Recipe>> recipes;

    private Capabilities(Map<String, Set<String>> tagMembers) {
        this(tagMembers, Map.of(), java.util.List.of(), java.util.List.of());
    }

    private Capabilities(Map<String, Set<String>> tagMembers, Map<String, Tool> serverTools, java.util.List<Wood> serverWoods,
                         java.util.List<Recipe> serverRecipes) {
        Map<String, Set<String>> groups = new HashMap<>();
        for (VanillaIds.Group group : VanillaIds.groups()) {
            Set<String> fromServer = group.exact() ? tagMembers.get(group.tag()) : null;
            groups.put(group.tag(), fromServer == null ? group.ids() : Set.copyOf(fromServer));
        }
        this.members = Map.copyOf(groups);
        Map<String, Tool> allTools = new HashMap<>(serverTools);
        allTools.putAll(VanillaIds.tools());
        this.tools = Map.copyOf(allTools);
        // Vanilla's woods as they were always named and built; the server's others after, never replacing one.
        Map<String, Wood> allWoods = new java.util.LinkedHashMap<>();
        for (Wood wood : VanillaIds.woods()) allWoods.put(wood.name(), wood);
        for (Wood wood : serverWoods) {
            if (VanillaIds.unbuiltWood(wood.planks())) continue;
            allWoods.putIfAbsent(wood.name(), wood);
        }
        this.woods = java.util.Collections.unmodifiableMap(allWoods);
        Map<String, java.util.List<Recipe>> byOutput = new HashMap<>();
        for (Recipe recipe : serverRecipes) byOutput.computeIfAbsent(recipe.output(), k -> new java.util.ArrayList<>()).add(recipe);
        Map<String, java.util.List<Recipe>> frozen = new HashMap<>();
        byOutput.forEach((k, v) -> frozen.put(k, java.util.List.copyOf(v)));
        this.recipes = Map.copyOf(frozen);
    }

    /** The vanilla facts, from {@link VanillaIds} alone. */
    public static Capabilities vanilla() { return VANILLA; }

    /**
     * Facts from a server's tags: {@code tagMembers} maps a tag (#minecraft:logs) to the ids in it, as the
     * adapter resolved them on the server thread. Groups with no tag in the map keep their vanilla members.
     */
    public static Capabilities fromTags(Map<String, ? extends Set<String>> tagMembers) {
        Objects.requireNonNull(tagMembers, "tagMembers");
        Map<String, Set<String>> copy = new HashMap<>();
        tagMembers.forEach((tag, ids) -> copy.put(tag, Set.copyOf(ids)));
        return new Capabilities(copy);
    }

    /**
     * Facts from a server's tags and its tools: {@code tools} holds what the adapter read from each tool item's
     * material. A vanilla tool keeps the tier written in {@link VanillaIds}; the server's adds the rest.
     */
    public static Capabilities fromServer(Map<String, ? extends Set<String>> tagMembers, Map<String, Tool> tools) {
        return fromServer(tagMembers, tools, java.util.List.of());
    }

    /**
     * As {@link #fromServer(Map, Map)}, with the woods the adapter read from the server's recipes: each planks the
     * server's #minecraft:planks holds, the log its recipe takes and what those planks make. Vanilla's woods keep
     * their written families; one VanillaIds keeps out of builds stays out.
     */
    public static Capabilities fromServer(Map<String, ? extends Set<String>> tagMembers, Map<String, Tool> tools,
                                          java.util.List<Wood> woods) {
        return fromServer(tagMembers, tools, woods, java.util.List.of());
    }

    /** As above, with the server's recipes for items vanilla's tables do not name (a mod's), for their worth. */
    public static Capabilities fromServer(Map<String, ? extends Set<String>> tagMembers, Map<String, Tool> tools,
                                          java.util.List<Wood> woods, java.util.List<Recipe> recipes) {
        Objects.requireNonNull(tagMembers, "tagMembers");
        Map<String, Set<String>> copy = new HashMap<>();
        tagMembers.forEach((tag, ids) -> copy.put(tag, Set.copyOf(ids)));
        return new Capabilities(copy, Map.copyOf(tools), java.util.List.copyOf(woods), java.util.List.copyOf(recipes));
    }

    /** The facts in force: the server's once the adapter installed them, else the vanilla ones. */
    public static Capabilities current() { return current; }

    /** Installed by the adapter on the server thread; an immutable snapshot, so readers on any thread see a whole one. */
    public static void install(Capabilities capabilities) {
        current = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** Back to the vanilla facts (a server stopping, a test cleaning up). */
    public static void reset() { current = VANILLA; }

    /** Whether {@code id} belongs to {@code group}; an id with no namespace is read as a vanilla one. */
    public boolean is(VanillaIds.Group group, String id) {
        if (id == null || id.isBlank()) return false;
        return members(group).contains(normalize(id));
    }

    /** Whether {@code id} belongs to any of {@code groups}. */
    public boolean isAny(String id, VanillaIds.Group... groups) {
        for (VanillaIds.Group group : groups) {
            if (is(group, id)) return true;
        }
        return false;
    }

    /** Every id in {@code group}. */
    public Set<String> members(VanillaIds.Group group) {
        Set<String> ids = members.get(group.tag());
        return ids == null ? group.ids() : ids;
    }

    /** The tool this item is, if it is one. */
    public java.util.Optional<Tool> tool(String id) {
        if (id == null || id.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(tools.get(normalize(id)));
    }

    /** Whether this item is a tool of this kind at this tier or better. */
    public boolean isTool(String id, String kind, int minimumTier) {
        return tool(id).filter(t -> t.kind().equals(kind) && t.tier() >= minimumTier).isPresent();
    }

    /** Every wood a build may be made of, vanilla's first, by name. */
    public java.util.Collection<Wood> woods() { return woods.values(); }

    /** The wood of this name ("oak", or a modded wood's planks id without "_planks"). */
    public java.util.Optional<Wood> wood(String name) {
        return name == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(woods.get(name));
    }

    /** The wood an item or block is a part of, if any. */
    public java.util.Optional<Wood> woodOfPart(String id) {
        if (id == null || id.isBlank()) return java.util.Optional.empty();
        String full = normalize(id);
        for (Wood wood : woods.values()) if (wood.has(full)) return java.util.Optional.of(wood);
        return java.util.Optional.empty();
    }

    /** The server's ways of making an item, as read; none for a vanilla item or one nothing makes. */
    public java.util.List<Recipe> recipesFor(String id) {
        if (id == null || id.isBlank()) return java.util.List.of();
        return recipes.getOrDefault(normalize(id), java.util.List.of());
    }

    /** An id in its full form: trimmed, lower case, the vanilla namespace added when it has none. */
    public static String normalize(String raw) {
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        return clean.indexOf(':') >= 0 ? clean : VanillaIds.NAMESPACE + clean;
    }
}
