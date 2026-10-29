package dev.kindred.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: once a player can enchant, it names its best weapons and tools at an anvil, as players do:
 * only diamond, netherite or enchanted pieces, the best of each kind, and none already named. The name suits
 * the piece and the player's temper, and one an optional AI adviser suggests must pass the same rules.
 *
 * Technical: Named kit (E-16d), as the owner set it out (2026-10-25): once a player has reached the
 * enchanting stage, it names its best weapons and tools as players do, at an anvil, a level each. Here: which
 * pieces are worth a name (diamond or netherite, or enchanted; the best of each kind, and none already
 * named); what a name costs; the player's own name for a piece, a cool one drawn from what the piece is (its
 * material and its enchantments) in the manner of its temper ({@link NameForge.Flavor}); the model's question
 * and the rules its answer must pass, {@link NameForge}'s (2 to 32 characters of letters, spaces, apostrophes
 * and hyphens, a capital first, none it has used); and what it says of it. Pure: the adapter says what it
 * holds.
 */
public final class KitNames {
    private KitNames() { }

    /** The weapons and tools it names, weapons first. */
    public enum Kind {
        SWORD("sword", List.of("fang", "bane", "edge", "brand", "song", "reaver"), List.of("Fang", "Edge", "Blade", "Bane"),
                List.of("Sir Pokes-a-Lot", "Mister Pointy", "The Tickler", "Snickersnee", "Stabitha", "Pointy McPointface")),
        AXE("axe", List.of("cleaver", "splitter", "reaver", "maw", "hewer", "bite"), List.of("Cleaver", "Edge", "Hewer", "Axe"),
                List.of("Mister Chops", "Lumber Jack", "Splinters", "Axel", "Chopsy", "Timber")),
        BOW("bow", List.of("string", "whisper", "reach", "song", "flight", "call"), List.of("Song", "String", "Reach", "Bow"),
                List.of("Twang", "Mister Twangs", "Pointy Post", "Twangatha", "Arrow Dynamic")),
        CROSSBOW("crossbow", List.of("bolt", "strike", "shot", "lance", "snap"), List.of("Bolt", "Strike", "Sting"),
                List.of("Clunk", "Thwip", "Bolt Bucket", "Click-Clack")),
        TRIDENT("trident", List.of("caller", "spire", "fork", "lance", "prong"), List.of("Spear", "Fork", "Caller", "Prong"),
                List.of("Forky", "Fishstick", "Mister Pokey", "Fork Knight")),
        MACE("mace", List.of("fall", "maul", "hammer", "crash", "drop"), List.of("Hammer", "Maul", "Fall"),
                List.of("Bonk", "Bonkers", "Thud", "Mister Bonk")),
        PICKAXE("pickaxe", List.of("delver", "breaker", "bore", "crusher", "seeker", "heart"), List.of("Breaker", "Delver", "Pick", "Seeker"),
                List.of("Rock Botherer", "Pebble Popper", "Old Clanky", "Diggy", "Stonecold", "Ore Inspector")),
        SHOVEL("shovel", List.of("digger", "spade", "burrow", "shifter", "delver"), List.of("Spade", "Digger", "Delver"),
                List.of("Dirt Daddy", "Scoops", "Shovelina", "Mudslinger", "Scoopsy"));

        /** What a player calls it: "sword". */
        public final String noun;
        private final List<String> endings, titles, jokes;

        Kind(String noun, List<String> endings, List<String> titles, List<String> jokes) {
            this.noun = noun;
            this.endings = endings;
            this.titles = titles;
            this.jokes = jokes;
        }

        /** The kind of an item by its id ("minecraft:diamond_pickaxe"), or none for what it does not name. */
        public static Optional<Kind> of(String item) {
            String path = item.substring(item.indexOf(':') + 1);
            for (Kind kind : values()) if (path.equals(kind.noun) || path.endsWith("_" + kind.noun)) return Optional.of(kind);
            return Optional.empty();
        }
    }

    /**
     * A weapon or tool it holds: where, its item id, its enchantments by id with their levels, its name if it has one,
     * and the anvil's work it has had already, in levels.
     */
    public record Piece(int slot, String item, Map<String, Integer> enchantments, Optional<String> name, int priorWork) {
        public Piece {
            Objects.requireNonNull(item);
            if (Kind.of(item).isEmpty()) throw new IllegalArgumentException("Not a weapon or tool it names: " + item);
            enchantments = Map.copyOf(enchantments);
            Objects.requireNonNull(name);
            if (priorWork < 0) throw new IllegalArgumentException("Prior work is never negative");
        }

        /** The kind of weapon or tool it is. */
        public Kind kind() {
            return Kind.of(item).orElseThrow();
        }

        /** What it is made of ("diamond"), or nothing for a bow, a crossbow, a trident or a mace. */
        public String material() {
            String path = item.substring(item.indexOf(':') + 1);
            return path.equals(kind().noun) ? "" : path.substring(0, path.length() - kind().noun.length() - 1);
        }
    }

    /** What a name costs at an anvil, in levels: one, and the work the piece has had (vanilla's rename). */
    public static int cost(Piece piece) {
        return 1 + piece.priorWork();
    }

    /**
     * What it names, in order: of each kind it holds, the best (by material, then by its enchantments), when that is
     * worth a name (diamond or netherite, or enchanted) and not named already; weapons first. A best piece already named
     * stays as it is, and so does every lesser one of its kind.
     */
    public static List<Piece> toName(List<Piece> held) {
        var best = new EnumMap<Kind, Piece>(Kind.class);
        for (Piece piece : held) {
            var was = best.get(piece.kind());
            if (was == null || rank(piece) > rank(was)) best.put(piece.kind(), piece);
        }
        var out = new ArrayList<Piece>();
        for (Piece piece : best.values()) {
            if (piece.name().isEmpty() && (tier(piece.material()) >= 4 || !piece.enchantments().isEmpty())) out.add(piece);
        }
        return out;
    }

    /** Those of {@code toName} its levels pay for, in order, a name's cost each. */
    public static List<Piece> affordable(List<Piece> toName, int levels) {
        var out = new ArrayList<Piece>();
        int left = levels;
        for (Piece piece : toName) {
            if (cost(piece) > left) break;
            left -= cost(piece);
            out.add(piece);
        }
        return out;
    }

    private static int rank(Piece piece) {
        int levels = 0;
        for (int level : piece.enchantments().values()) levels += level;
        return tier(piece.material()) * 100 + levels;
    }

    private static int tier(String material) {
        return switch (material) {
            case "netherite" -> 5;
            case "diamond" -> 4;
            case "", "iron" -> 3;
            case "golden", "stone" -> 2;
            default -> 1;
        };
    }

    /** A piece's sense, by its enchantments first and then its material: what its own names are made of. */
    private enum Theme {
        FIRE(List.of("Ember", "Cinder", "Blaze", "Pyre", "Scorch", "Flame"), List.of("Embers", "Forge", "Flame", "Pyre")),
        GRAVE(List.of("Grave", "Wraith", "Bone", "Dusk", "Barrow", "Hallow"), List.of("Grave", "Barrow", "Dead", "Dusk")),
        VENOM(List.of("Venom", "Web", "Sting", "Mite"), List.of("Webs", "Deep", "Spiders")),
        SILK(List.of("Velvet", "Silk", "Whisper", "Feather", "Hush"), List.of("Hush", "Quiet", "Veil")),
        LUCK(List.of("Gold", "Fortune", "Gilt", "Coin", "Hoard", "Luck"), List.of("Hoard", "Vault", "Deep", "Lode")),
        THUNDER(List.of("Thunder", "Storm", "Gale", "Quake", "Tempest", "Squall"), List.of("Storm", "Thunder", "Sky", "Squall")),
        TIDE(List.of("Tide", "Wave", "Surge", "Brine", "Coral"), List.of("Tides", "Deep", "Sea", "Brine")),
        STAR(List.of("Star", "Moon", "Comet", "Sky", "Void"), List.of("Stars", "Moon", "Void", "Sky")),
        HAIL(List.of("Hail", "Swarm", "Volley", "Flurry"), List.of("Hail", "Storm", "Volley")),
        KEEN(List.of("Razor", "Keen", "Thorn", "Night", "Raven", "Grim"), List.of("Night", "Storm", "Hunt", "Wolf")),
        SWIFT(List.of("Swift", "Quick", "Haste", "Wind", "Dart"), List.of("Wind", "Gale", "Hunt")),
        EVER(List.of("Ever", "Stone", "Root", "Oath", "True"), List.of("Ages", "Mountain", "Roots", "Oath")),
        ASH(List.of("Ash", "Dread", "Soot", "Char", "Doom"), List.of("Nether", "Ashes", "Wastes", "Basalt")),
        GLEAM(List.of("Gleam", "Frost", "Shard", "Glint", "Prism"), List.of("Deep", "Frost", "Caverns", "Stars")),
        STEEL(List.of("Iron", "Steel", "Flint", "Grit", "Anvil"), List.of("Hills", "Deep", "Forge", "Mountain"));

        final List<String> roots, places;

        Theme(List<String> roots, List<String> places) {
            this.roots = roots;
            this.places = places;
        }
    }

    /** Enchantments by what they say of a piece, the most telling first. */
    private static final List<Map.Entry<String, Theme>> TELLING = List.of(
            Map.entry("fire_aspect", Theme.FIRE), Map.entry("flame", Theme.FIRE), Map.entry("smite", Theme.GRAVE),
            Map.entry("bane_of_arthropods", Theme.VENOM), Map.entry("silk_touch", Theme.SILK), Map.entry("fortune", Theme.LUCK),
            Map.entry("looting", Theme.LUCK), Map.entry("luck_of_the_sea", Theme.LUCK), Map.entry("channeling", Theme.THUNDER),
            Map.entry("riptide", Theme.TIDE), Map.entry("loyalty", Theme.TIDE), Map.entry("impaling", Theme.TIDE),
            Map.entry("infinity", Theme.STAR), Map.entry("multishot", Theme.HAIL), Map.entry("wind_burst", Theme.THUNDER),
            Map.entry("density", Theme.THUNDER), Map.entry("breach", Theme.THUNDER), Map.entry("knockback", Theme.THUNDER),
            Map.entry("punch", Theme.THUNDER), Map.entry("sweeping_edge", Theme.THUNDER), Map.entry("sharpness", Theme.KEEN),
            Map.entry("power", Theme.KEEN), Map.entry("piercing", Theme.KEEN), Map.entry("quick_charge", Theme.SWIFT),
            Map.entry("efficiency", Theme.SWIFT), Map.entry("unbreaking", Theme.EVER), Map.entry("mending", Theme.EVER));

    private static List<Theme> themes(Piece piece) {
        var out = new LinkedHashSet<Theme>();
        for (var telling : TELLING) {
            if (piece.enchantments().containsKey("minecraft:" + telling.getKey())) out.add(telling.getValue());
        }
        out.add(switch (piece.material()) {
            case "netherite" -> Theme.ASH;
            case "diamond" -> Theme.GLEAM;
            default -> Theme.STEEL;
        });
        return List.copyOf(out);
    }

    /**
     * Its own name for {@code piece}: the first of its candidates it has not used, or none when all of them are taken.
     * The same player, piece and names taken always give the same name.
     */
    public static Optional<NameForge.Name> own(Piece piece, UUID player, Personality personality, Set<String> taken) {
        var flavor = NameForge.Flavor.of(personality);
        for (int attempt = 0; attempt < NameForge.MAX_ATTEMPTS; attempt++) {
            String text = candidate(piece, player, flavor, attempt);
            if (text.length() > NameForge.MAX_LENGTH || used(taken, text)) continue;
            return Optional.of(new NameForge.Name(text, NameForge.Subject.TOOL, NameForge.Source.FORGE, attempt));
        }
        return Optional.empty();
    }

    /**
     * One candidate: from the piece's most telling sense first, then the next; as one word of a root and an ending
     * ("Emberfang"), in a curious player's manner as a title ("Fang of the Embers"), and a playful player's half the
     * time a joke ("Sir Pokes-a-Lot").
     */
    public static String candidate(Piece piece, UUID player, NameForge.Flavor flavor, int attempt) {
        if (attempt < 0 || attempt >= NameForge.MAX_ATTEMPTS) throw new IllegalArgumentException("Attempt must be 0.." + (NameForge.MAX_ATTEMPTS - 1));
        var themes = themes(piece);
        Theme theme = themes.get(attempt % themes.size());
        Kind kind = piece.kind();
        long seed = mix(player.getMostSignificantBits() * 31 + player.getLeastSignificantBits());
        seed = mix(seed ^ kind.ordinal());
        // The piece itself, so that two swords of one player are not named alike.
        seed = mix(seed ^ (piece.item() + new java.util.TreeSet<>(piece.enchantments().keySet())).hashCode());
        seed = mix(seed ^ (attempt * 0x9E3779B97F4A7C15L));
        if (flavor == NameForge.Flavor.PLAYFUL && (mix(seed + 7) & 1) == 0) return pick(kind.jokes, seed, 1);
        if (flavor == NameForge.Flavor.DESCRIPTIVE && (attempt & 1) == 0) {
            return pick(kind.titles, seed, 2) + " of the " + pick(theme.places, seed, 3);
        }
        String root = pick(theme.roots, seed, 4);
        String ending = pick(kind.endings, seed, 5);
        if (root.toLowerCase(Locale.ROOT).endsWith(ending)) ending = kind.endings.get((kind.endings.indexOf(ending) + 1) % kind.endings.size());
        return root + ending;
    }

    private static boolean used(Set<String> taken, String name) {
        for (String each : taken) if (each.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static String pick(List<String> options, long seed, int lane) {
        return options.get((int) Math.floorMod(mix(seed + lane), options.size()));
    }

    /** The model's instructions, the same every time. */
    public static final String SYSTEM = """
            You name a weapon or tool for an autonomous Minecraft player, as players name their best kit at an anvil once \
            it is worth it. Give it one name in keeping with the player's temper and with what the item is: its material \
            and its enchantments. A name is 2 to 32 characters of letters, spaces, apostrophes and hyphens, and starts \
            with a capital letter. Never give one the player has used already, a real person's name, or a name from a \
            game, book or film. Answer with the name, and one short sentence why.""";

    /** The longest name it asks for. */
    public static final int MAX_NAME = NameForge.MAX_LENGTH;

    /**
     * The question, from what the player is and holds: "You are Wren, playful and fierce. Name your diamond sword, with
     * Sharpness III and Fire Aspect II. You have named Emberfang, your pickaxe."
     */
    public static String question(String player, Personality personality, Piece piece, Map<String, String> named) {
        var text = new StringBuilder("You are ").append(player).append(", ").append(temper(personality)).append(". ");
        text.append("Name your ").append(piece.material().isEmpty() ? "" : piece.material() + " ").append(piece.kind().noun);
        String enchanted = enchantments(piece);
        if (!enchanted.isEmpty()) text.append(", with ").append(enchanted);
        text.append(". ");
        if (named.isEmpty()) {
            text.append("You have named nothing yet.");
        } else {
            var parts = new ArrayList<String>();
            named.forEach((name, what) -> parts.add(name + ", your " + what));
            parts.sort(null);
            text.append("You have named ").append(String.join("; ", parts)).append('.');
        }
        return text.toString();
    }

    /** The model's name, if it passes the naming rules and it has not used it; none otherwise. */
    public static Optional<NameForge.Name> fromModel(String text, Set<String> taken) {
        try {
            return Optional.of(NameForge.external(text, NameForge.Subject.TOOL, NameForge.Source.MODEL, taken));
        } catch (IllegalArgumentException refused) {
            return Optional.empty();
        }
    }

    /** What it is, as a player says it: "diamond sword", "bow". */
    public static String noun(Piece piece) {
        return (piece.material().isEmpty() ? "" : piece.material() + " ") + piece.kind().noun;
    }

    /** What it says of a name it gave: "I named my diamond sword Emberfang." */
    public static String told(Piece piece, String name) {
        return "I named my " + noun(piece) + " " + name + ".";
    }

    /** Its temper in a word or two, from its strongest traits. */
    static String temper(Personality p) {
        var words = new ArrayList<String>();
        if (p.humor() >= 65) words.add("playful");
        if (p.curiosity() >= 65) words.add("curious");
        if (p.combativeness() >= 65) words.add("fierce");
        if (p.caution() >= 65) words.add("careful");
        if (p.sociability() >= 65) words.add("friendly");
        if (p.initiative() >= 65) words.add("bold");
        if (words.isEmpty()) return "even-tempered";
        if (words.size() > 2) words.subList(2, words.size()).clear();
        return String.join(" and ", words);
    }

    /** Its enchantments as the game shows them, by name: "Fire Aspect II and Sharpness III". */
    static String enchantments(Piece piece) {
        var parts = new ArrayList<String>();
        for (var entry : piece.enchantments().entrySet()) {
            String path = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            var words = new ArrayList<String>();
            for (String word : path.split("_")) {
                words.add(word.equals("of") || word.equals("the") ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
            }
            String name = String.join(" ", words);
            parts.add(SINGLE.contains(path) ? name : name + " " + roman(entry.getValue()));
        }
        parts.sort(null);
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /** The enchantments of one level only, shown without a number. */
    private static final Set<String> SINGLE = Set.of("silk_touch", "mending", "infinity", "flame", "channeling", "multishot");

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
