package dev.kindred.core.guide;

import dev.kindred.core.team.ShareOut;
import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Slot;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: what an item is worth to a player right now, counted in the minutes of play it would take
 * to get it otherwise and in what the player would do with it. A player picks up what beats its own gear or
 * what its run is short of, stores what it will need later, and leaves what is not worth the space.
 *
 * Technical: What an item is worth to a player as it stands (E-42), reckoned as players do: what it would
 * cost the player to get it otherwise, in minutes of play, and what it would do with it. The owner's
 * direction (2026-10-25): a player never walks past valuables. It takes up what is better than its own kit,
 * carries what its run is short of, and takes and stores what it has no use for yet but will have (gold
 * before the Nether, an enchanted book, a saddle); what is worth less than the slot it would take is left
 * where it lies.
 *
 * <ul>
 *   <li>Worth falls off past what a player could ever use: the thirtieth diamond is worth a tenth of the first.</li>
 *   <li>Blocks of a material are worth what they are made of (a gold block, nine ingots); raw ore what it smelts to.</li>
 *   <li>Kit is weighed against the player's own in that slot ({@link ShareOut#gain}); enchantments add to it.</li>
 *   <li>A container not yet opened is worth what one of its kind usually holds, since nothing in it can be seen.</li>
 * </ul>
 * Pure: the adapter says what the stack is, and what the player holds, wears and means to have for its run.
 */
public final class Worth {
    private Worth() { }

    /** What a player does with something it comes across. */
    public enum Keep {
        /** Better than what it wears or wields in that slot: taken and put on, or taken up in hand. */
        UPGRADE,
        /** What its run is short of as it stands: carried, never given up for room. */
        CARRY,
        /** Of use later, or worth having though not needed now: taken, and stored at home when room is short. */
        STORE,
        /** Not worth the slot it would take: left. */
        LEAVE
    }

    /** Any piece of gold armour, as a run wants one for the piglins (E-07e). */
    public static final String GOLD_ARMOUR = "gold armour";
    /** Anything a player eats, counted a piece each, as the run's food is (the planner's food for the run). */
    public static final String FOOD = "food";
    /** Blocks to build up, bridge and wall with (the guide's blocks). */
    public static final String BLOCKS = "blocks";

    /** Less than this many minutes' worth is not worth a slot. */
    static final double STORE_MIN = 0.25;
    /** Past what a player could use, each more is worth this share of one. */
    static final double SURPLUS = 0.1;
    /** Blocks out of its way it goes for anything worth taking, and as many again for each minute it is worth. */
    static final int DETOUR_BASE = 6, DETOUR_PER_MINUTE = 12, DETOUR_MAX = 64;

    /** A stack as a player sees it: the item, how many, and the levels of the enchantments on it. */
    public record Stack(String item, int count, int enchantments) {
        public Stack {
            Objects.requireNonNull(item);
            if (count <= 0) throw new IllegalArgumentException("A stack holds something");
            if (enchantments < 0) throw new IllegalArgumentException("Enchantment levels are not negative");
        }

        public Stack(String item, int count) { this(item, count, 0); }
    }

    /** A piece of kit worn or in hand: what it is made of and the levels of its enchantments. */
    public record Piece(Material material, int enchantments) {
        public static final Piece NONE = new Piece(Material.NONE, 0);

        public Piece {
            Objects.requireNonNull(material);
        }
    }

    /**
     * What the player has as it stands: what it holds, item by item (its kit too); the best kit it wears or holds for
     * each slot; and what its run means it to have, by item or by {@link #FOOD}, {@link #BLOCKS} or
     * {@link #GOLD_ARMOUR}, from which what it is short of follows.
     */
    public record Holding(Map<String, Integer> held, Map<Slot, Piece> kit, Map<String, Integer> wanted) {
        public Holding {
            held = Map.copyOf(held);
            kit = Map.copyOf(kit);
            wanted = Map.copyOf(wanted);
        }

        public static Holding of(Map<String, Integer> held, Map<Slot, Piece> kit) { return new Holding(held, kit, Map.of()); }

        Piece kit(Slot slot) { return kit.getOrDefault(slot, Piece.NONE); }

        /** What it has of a unit or group: blocks of a material counted as what they are made of. */
        double has(String unit) {
            double total = 0;
            if (unit.equals(GOLD_ARMOUR)) {
                for (Slot slot : Slot.values()) if (slot.armour() && kit(slot).material() == Material.GOLD) total++;
                return total;
            }
            for (var entry : held.entrySet()) {
                Info info = info(entry.getKey());
                if (info == null) continue;
                if (info.unit().equals(unit)) total += entry.getValue() * info.per();
                else if (unit.equals(info.group())) total += entry.getValue();
            }
            return total;
        }

        /** How many more of a unit or group its run means it to have. */
        double shortOf(String unit) {
            Integer want = wanted.get(unit);
            return want == null ? 0 : Math.max(0, want - has(unit));
        }

        /** What it would hold with this stack gone. */
        Holding without(Stack stack) {
            var next = new HashMap<>(held);
            next.computeIfPresent(stack.item(), (item, count) -> count - stack.count() > 0 ? count - stack.count() : null);
            return new Holding(next, kit, wanted);
        }
    }

    /** What a stack is worth to the player, in minutes of play, what it does with it, and why, in words it can say. */
    public record Judgement(Stack stack, Keep keep, double worth, String why) { }

    /**
     * What one of an item counts as ({@code per} of {@code unit}), worth {@code minutes} a unit up to {@code useful} of
     * them, and the group it also counts toward.
     */
    private record Info(String unit, double per, double minutes, int useful, String group) { }

    private static final Map<String, Info> ITEMS = new HashMap<>();

    private static void unit(String item, double minutes, int useful) { unit(item, minutes, useful, null); }

    private static void unit(String item, double minutes, int useful, String group) {
        ITEMS.put(id(item), new Info(id(item), 1, minutes, useful, group));
    }

    private static void as(String item, String unit, double per) {
        Info of = ITEMS.get(id(unit));
        ITEMS.put(id(item), new Info(of.unit(), per, of.minutes(), of.useful(), of.group()));
    }

    static {
        // Minutes a player would spend getting one otherwise, and how many it could use on a run to the dragon and a base.
        unit("diamond", 3.0, 29);
        unit("iron_ingot", 0.6, 40);
        unit("gold_ingot", 0.6, 64);
        unit("netherite_scrap", 6.0, 4);
        unit("emerald", 0.5, 32);
        unit("lapis_lazuli", 0.15, 64);
        unit("coal", 0.08, 64);
        unit("redstone", 0.03, 64);
        unit("obsidian", 0.6, 14);
        unit("crying_obsidian", 0.1, 8);
        unit("ender_pearl", 2.0, 16);
        unit("blaze_rod", 2.0, 8);
        unit("ender_eye", 4.0, 15);
        unit("flint_and_steel", 1.0, 1);
        unit("flint", 0.2, 4);
        unit("bucket", 1.8, 2);
        unit("water_bucket", 1.9, 2);
        unit("lava_bucket", 1.9, 1);
        unit("string", 0.15, 24);
        unit("white_wool", 0.3, 12);
        unit("white_bed", 1.2, 6);
        unit("arrow", 0.1, 128);
        unit("bow", 0.8, 1);
        unit("crossbow", 0.8, 1);
        unit("shield", 1.0, 1);
        unit("golden_apple", 6.0, 16, FOOD);
        unit("enchanted_golden_apple", 30.0, 8, FOOD);
        unit("golden_carrot", 0.8, 64, FOOD);
        unit("bread", 0.2, 64, FOOD);
        unit("cooked_beef", 0.25, 64, FOOD);
        unit("baked_potato", 0.15, 64, FOOD);
        unit("apple", 0.15, 64, FOOD);
        unit("carrot", 0.08, 64, FOOD);
        unit("beef", 0.15, 64, FOOD);
        unit("sweet_berries", 0.03, 64, FOOD);
        unit("rotten_flesh", 0.01, 16);
        unit("enchanted_book", 2.0, 16);
        unit("book", 0.1, 16);
        unit("experience_bottle", 0.3, 64);
        unit("saddle", 1.0, 1);
        unit("name_tag", 1.0, 2);
        unit("lead", 0.3, 2);
        unit("compass", 0.5, 1);
        unit("clock", 0.4, 1);
        unit("rail", 0.04, 64);
        unit("powered_rail", 0.12, 32);
        unit("torch", 0.03, 64);
        unit("gunpowder", 0.1, 16);
        unit("tnt", 0.6, 16);
        unit("bone", 0.05, 32);
        unit("heart_of_the_sea", 1.0, 1);
        unit("nautilus_shell", 0.2, 8);
        unit("trident", 5.0, 1);
        unit("totem_of_undying", 20.0, 4);
        unit("ghast_tear", 1.0, 2);
        unit("diamond_horse_armor", 1.0, 1);
        unit("golden_horse_armor", 0.5, 1);
        unit("iron_horse_armor", 0.4, 1);
        unit("cobblestone", 0.002, 128, BLOCKS);
        unit("cobweb", 0.01, 8);
        // What it is made of, or smelts to.
        for (String m : List.of("iron", "gold")) {
            as(m + "_block", m + "_ingot", 9);
            as("raw_" + m, m + "_ingot", 1);
            as("raw_" + m + "_block", m + "_ingot", 9);
            as(m + "_nugget", m + "_ingot", 1.0 / 9);
        }
        as("diamond_block", "diamond", 9);
        as("emerald_block", "emerald", 9);
        as("lapis_block", "lapis_lazuli", 9);
        as("coal_block", "coal", 9);
        as("charcoal", "coal", 1);
        as("redstone_block", "redstone", 9);
        as("ancient_debris", "netherite_scrap", 1);
        as("netherite_ingot", "netherite_scrap", 4);
        as("spectral_arrow", "arrow", 1.5);
        as("tipped_arrow", "arrow", 1.5);
        as("detector_rail", "powered_rail", 0.7);
        as("activator_rail", "powered_rail", 0.7);
        for (String meat : List.of("porkchop", "mutton", "chicken", "rabbit", "cod", "salmon")) {
            as("cooked_" + meat, "cooked_beef", 1);
            as(meat, "beef", 1);
        }
        as("potato", "carrot", 1);
        as("glow_berries", "sweet_berries", 1);
        as("beetroot", "carrot", 1);
        as("pumpkin_pie", "bread", 1.5);
        for (String block : List.of("cobbled_deepslate", "stone", "deepslate", "dirt", "netherrack", "blackstone", "andesite",
                "diorite", "granite", "tuff", "sandstone", "end_stone")) {
            as(block, "cobblestone", 1);
        }
        for (String colour : List.of("orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
                "purple", "blue", "brown", "green", "red", "black")) {
            as(colour + "_wool", "white_wool", 1);
            as(colour + "_bed", "white_bed", 1);
        }
    }

    private static String id(String item) {
        return item.indexOf(':') >= 0 ? item : "minecraft:" + item;
    }

    private static Info info(String item) {
        return ITEMS.get(id(item));
    }

    /** A piece of kit by its id, "minecraft:iron_chestplate", or null for anything else. */
    record Kit(Slot slot, Material material) { }

    /** A piece of gear by its item id, or nothing for anything that is not gear. */
    static Kit kit(String item) {
        String path = id(item).substring("minecraft:".length());
        int split = path.lastIndexOf('_');
        if (split < 0) return null;
        Slot slot;
        try {
            slot = Slot.valueOf(path.substring(split + 1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notKit) {
            return null;
        }
        Material material = switch (path.substring(0, split)) {
            case "leather" -> Material.LEATHER;
            case "golden" -> Material.GOLD;
            case "chainmail" -> Material.CHAIN;
            case "iron" -> Material.IRON;
            case "diamond" -> Material.DIAMOND;
            case "netherite" -> Material.NETHERITE;
            default -> null;
        };
        if (material == null || material == Material.LEATHER && !slot.armour()) return null;
        return new Kit(slot, material);
    }

    /** The slot a piece of kit goes in, by its id ("minecraft:iron_boots"), or none for anything that is not kit. */
    public static Optional<Slot> slotOf(String item) {
        Kit kit = kit(item);
        return kit == null ? Optional.empty() : Optional.of(kit.slot());
    }

    /** A piece of kit by its id and the levels of its enchantments, or {@link Piece#NONE} for anything that is not kit. */
    public static Piece pieceOf(String item, int enchantments) {
        Kit kit = kit(item);
        return kit == null ? Piece.NONE : new Piece(kit.material(), enchantments);
    }

    /** What one piece of kit would cost to make, in minutes; chain mail as iron, though nobody can make it. */
    private static double made(Kit kit) {
        return switch (kit.material()) {
            case NONE -> 0;
            case LEATHER -> 0.2 * kit.slot().cost();
            case GOLD -> 0.3 * kit.slot().cost();
            case CHAIN, IRON -> 0.6 * kit.slot().cost();
            case DIAMOND -> 3.0 * kit.slot().cost();
            case NETHERITE -> 3.0 * kit.slot().cost() + 24;
        };
    }

    /** Minutes the enchantments on a piece or book save: enchanting it with levels and lapis. */
    private static double enchanted(int levels) {
        return 0.6 * levels;
    }

    /** What a player keeps on it wherever it is, to use as it goes: never put away. A bed of any colour too: it sleeps in it. */
    private static final java.util.Set<String> KEPT_ON = java.util.Set.of("bucket", "water_bucket", "lava_bucket", "flint_and_steel",
            "shield", "bow", "crossbow", "arrow", "spectral_arrow", "tipped_arrow", "torch", "compass", "clock", "ender_pearl",
            "totem_of_undying", "trident", "white_bed").stream().map(item -> "minecraft:" + item).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** What no run to the dragon uses, kept only for later: what a player on its run puts away when it passes its store. */
    private static final java.util.Set<String> FOR_LATER = java.util.Set.of("enchanted_book", "book", "saddle", "name_tag", "lead",
            "rail", "powered_rail", "diamond_horse_armor", "golden_horse_armor", "iron_horse_armor", "heart_of_the_sea", "nautilus_shell",
            "experience_bottle", "emerald").stream().map(item -> "minecraft:" + item).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * What of what it holds a player puts away in its storage (E-43), item by item and all of each: what it keeps for
     * later and has no use for as it goes (an enchanted book, a saddle, rails; and with no run on, the diamonds and gold
     * it has no want of now). On a run, only what no run uses: what it carries for the run, short of it or not, stays
     * with it. Never its kit, its food, blocks it builds and climbs with, nor what it keeps on it to use as it goes (a
     * bucket, its bow and arrows, torches, pearls); nothing not worth a slot either.
     */
    public static Map<String, Integer> stow(Map<String, Integer> held, Holding holding, boolean onRun) {
        var out = new java.util.TreeMap<String, Integer>();
        for (var entry : held.entrySet()) {
            String item = id(entry.getKey());
            Info unit = info(item);
            if (entry.getValue() <= 0 || kit(item) != null || KEPT_ON.contains(item) || unit != null && KEPT_ON.contains(unit.unit())) continue;
            if (onRun && (unit == null || !FOR_LATER.contains(unit.unit()))) continue;
            Info info = info(item);
            if (info == null || FOOD.equals(info.group()) || BLOCKS.equals(info.group())) continue;
            if (holding.wanted().containsKey(item) || holding.wanted().containsKey(info.unit())
                    || info.group() != null && holding.wanted().containsKey(info.group())) continue;
            // Judged as what it holds, not as one more on top of it.
            var stack = new Stack(item, entry.getValue());
            if (judge(stack, holding.without(stack)).keep() == Keep.STORE) out.put(item, entry.getValue());
        }
        return out;
    }

    /** What a stack is worth to the player as it stands, and what it does with it. */
    public static Judgement judge(Stack stack, Holding holding) {
        Objects.requireNonNull(stack);
        Objects.requireNonNull(holding);
        Kit kit = kit(stack.item());
        if (kit != null) return judgeKit(stack, kit, holding);
        Info info = info(stack.item());
        if (info == null) {
            // Nothing a player knows a use for on its run or at its base: a curiosity, not worth a slot.
            return new Judgement(stack, Keep.LEAVE, 0, "no use to it");
        }
        double units = stack.count() * info.per();
        double have = holding.has(info.unit());
        double fullUnits = Math.max(0, Math.min(units, info.useful() - have));
        double worth = info.minutes() * (fullUnits + SURPLUS * (units - fullUnits)) + enchanted(stack.enchantments());
        double unitShort = holding.shortOf(info.unit());
        double groupShort = info.group() == null ? 0 : holding.shortOf(info.group());
        if (unitShort > 0 || groupShort > 0) {
            String want = unitShort > 0 ? words(info.unit(), unitShort) : groupShort(info.group(), groupShort);
            return new Judgement(stack, Keep.CARRY, worth, "its run is short of " + want);
        }
        if (worth >= STORE_MIN) return new Judgement(stack, Keep.STORE, worth, "worth keeping for later");
        return new Judgement(stack, Keep.LEAVE, worth, "not worth a slot");
    }

    private static Judgement judgeKit(Stack stack, Kit kit, Holding holding) {
        Piece own = holding.kit(kit.slot());
        double gain = ShareOut.gain(kit.slot(), own.material(), kit.material());
        double worth = (made(kit) + enchanted(stack.enchantments())) * stack.count();
        boolean better = gain > 0 || gain == 0 && kit.material() == own.material() && stack.enchantments() > own.enchantments();
        if (better) {
            return new Judgement(stack, Keep.UPGRADE, Math.max(worth, 1.0), own.material() == Material.NONE
                    ? "it has no " + slotWord(kit.slot()) : "better than its " + material(own.material()) + " " + slotWord(kit.slot()));
        }
        if (kit.material() == Material.GOLD && kit.slot().armour() && holding.shortOf(GOLD_ARMOUR) > 0) {
            return new Judgement(stack, Keep.CARRY, worth, "a piece of gold to wear among piglins");
        }
        // No better than its own: of iron or better a spare to fall back on, worth half a new one; leather or gold, next
        // to nothing.
        worth *= kit.material().ordinal() >= Material.CHAIN.ordinal() ? 0.5 : 0.1;
        if (worth >= STORE_MIN) return new Judgement(stack, Keep.STORE, worth, "a spare, worth keeping");
        return new Judgement(stack, Keep.LEAVE, worth, "no better than its own");
    }

    /** What an unopened container of a kind usually holds, in minutes, as a player knows from having opened others. */
    public enum Container {
        MINESHAFT_CART(3), RUINED_PORTAL(4), DUNGEON(3), VILLAGE(1.5), DESERT_TEMPLE(6), JUNGLE_TEMPLE(3), SHIPWRECK(3),
        BURIED_TREASURE(10), STRONGHOLD(4), FORTRESS(3), BASTION(8), OTHER(2);

        private final double minutes;

        Container(double minutes) { this.minutes = minutes; }

        /** What one usually holds, in minutes of play. */
        public double expected() { return minutes; }
    }

    /** How many blocks out of its way a player goes for what it is worth: nothing for what it leaves. */
    public static int detour(double worth) {
        if (worth < STORE_MIN) return 0;
        return (int) Math.min(DETOUR_MAX, DETOUR_BASE + DETOUR_PER_MINUTE * worth);
    }

    /** How many blocks out of its way a player goes for a judged stack. */
    public static int detour(Judgement judgement) {
        return judgement.keep() == Keep.LEAVE ? 0 : detour(judgement.worth());
    }

    /**
     * With no room for {@code incoming}: which of the stacks it holds (not its worn kit) it gives up for it, the one
     * worth least to it that its run is not short of and that is worth less than what comes in; or none, and the new
     * stack is left. What its run is short of, and kit better than what it has, are never given up.
     */
    public static Optional<Integer> makeRoom(List<Stack> held, Judgement incoming, Holding holding) {
        if (incoming.keep() == Keep.LEAVE) return Optional.empty();
        int least = -1;
        double leastWorth = Double.MAX_VALUE;
        for (int i = 0; i < held.size(); i++) {
            Stack stack = held.get(i);
            Judgement own = judge(stack, holding.without(stack));
            if (own.keep() == Keep.CARRY || own.keep() == Keep.UPGRADE) continue;
            if (own.worth() < leastWorth) {
                least = i;
                leastWorth = own.worth();
            }
        }
        // Like for like is no gain: only what is worth plainly more than the slot it frees.
        if (least < 0 || leastWorth >= incoming.worth() * 0.9) return Optional.empty();
        return Optional.of(least);
    }

    private static String slotWord(Slot slot) {
        return slot.name().toLowerCase(Locale.ROOT);
    }

    private static String material(Material material) {
        return switch (material) {
            case GOLD -> "gold";
            case CHAIN -> "chain mail";
            default -> material.name().toLowerCase(Locale.ROOT);
        };
    }

    private static String words(String unit, double count) {
        int n = (int) Math.ceil(count);
        String name = unit.substring(unit.indexOf(':') + 1).replace('_', ' ');
        return n + " " + name + (n == 1 || name.endsWith("s") ? "" : "s");
    }

    private static String groupShort(String group, double count) {
        int n = (int) Math.ceil(count);
        return switch (group) {
            case FOOD -> n + " food";
            case BLOCKS -> n + " blocks";
            default -> group;
        };
    }

    /** A summary of the kit a player wears and holds, for {@link Holding}. */
    public static Map<Slot, Piece> kit(Map<Slot, Material> materials) {
        var kit = new EnumMap<Slot, Piece>(Slot.class);
        materials.forEach((slot, material) -> kit.put(slot, new Piece(material, 0)));
        return kit;
    }
}
