package dev.kindred.core.guide;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: a thing a player can find itself short of for what it plans to do later, such as a bow, arrows,
 * a water bucket, a bed, blocks or food. Every one is something the player knows how to gather or craft itself.
 *
 * Technical: A need as data (F-38): its name, the word the report uses, the most a queue may ask for, what satisfies
 * it (a tag, ids, or a component) and how it is got. The vanilla set is the resource {@code needs.txt}, read in its
 * order, which puts what is crafted before what it is crafted from ({@link NeedsAssessment} relies on it). The vanilla
 * needs are also constants here, as the enum had them, and {@link #name}, {@link #ordinal}, {@link #values} and
 * {@link #valueOf} keep the enum's meaning, so saves, the strategist's schema and the planner see the same names in
 * the same order. A need is one the local planner knows how to act on; nothing outside the resource makes one.
 *
 * @param name         its saved name ("ARROWS")
 * @param word         what the report and the log call it ("arrow")
 * @param pluralS      whether the report adds an s for more than one
 * @param cap          the most of it a queue may ask for
 * @param satisfiedBy  what satisfies it, as written in the resource: a tag, ids, or a component
 * @param how          how it is got: craft, hunt, gather, smelt, shear or fill
 * @param ordinal      its place in the resource's order
 */
public record Need(String name, String word, boolean pluralS, int cap, String satisfiedBy, String how, int ordinal) {

    private static final List<Need> VALUES = load();
    private static final Map<String, Need> BY_NAME = byName(VALUES);

    public static final Need BOW = valueOf("BOW");
    public static final Need ARROWS = valueOf("ARROWS");
    public static final Need SHIELD = valueOf("SHIELD");
    public static final Need WATER_BUCKET = valueOf("WATER_BUCKET");
    public static final Need BED = valueOf("BED");
    public static final Need STRING = valueOf("STRING");
    public static final Need FEATHERS = valueOf("FEATHERS");
    public static final Need WOOL = valueOf("WOOL");
    public static final Need FLINT = valueOf("FLINT");
    public static final Need STICKS = valueOf("STICKS");
    public static final Need BLOCKS = valueOf("BLOCKS");
    public static final Need FOOD = valueOf("FOOD");
    /** One piece of gold armour worn keeps piglins from attacking (E-07e); gold does not calm hoglins. */
    public static final Need GOLD_ARMOR = valueOf("GOLD_ARMOR");
    /** Gold ingots: nether gold ore mined with any pickaxe gives nuggets, nine to an ingot. */
    public static final Need GOLD = valueOf("GOLD");
    /** A manhunt hunter's weapon (M-02a): two iron ingots and a stick, from what the run's iron gives. */
    public static final Need IRON_SWORD = valueOf("IRON_SWORD");

    public Need {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(word, "word");
        if (cap < 1) throw new IllegalArgumentException("A need's cap is at least one: " + name);
    }

    /** What the report calls {@code count} of it ("8 feathers", "3 string"). */
    public String words(int count) {
        if (count == 1 || !pluralS) return word;
        return word + "s";
    }

    /** Every need, in the resource's order. */
    public static Need[] values() { return VALUES.toArray(new Need[0]); }

    /** The need of this saved name, exactly; an unknown name is an error, as the enum's was. */
    public static Need valueOf(String name) {
        Need need = BY_NAME.get(name);
        if (need == null) throw new IllegalArgumentException("No need named " + name);
        return need;
    }

    /** A need by its name in any case ("arrows", " BOW "), or nothing for one not in the set. */
    public static Optional<Need> parse(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_')));
    }

    /** By order, as the enum compared. */
    public static int compare(Need a, Need b) { return Integer.compare(a.ordinal, b.ordinal); }

    @Override
    public String toString() { return name; }

    private static List<Need> load() {
        try (InputStream in = Need.class.getResourceAsStream("needs.txt")) {
            if (in == null) throw new IllegalStateException("The vanilla needs resource is missing");
            List<Need> needs = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] f = t.split(" \\| ");
                if (f.length != 6) throw new IllegalStateException("A need is six fields: " + t);
                needs.add(new Need(f[0], f[1], f[2].equals("s"), Integer.parseInt(f[3]), f[4], f[5], needs.size()));
            }
            return List.copyOf(needs);
        } catch (IOException e) {
            throw new IllegalStateException("The vanilla needs resource could not be read", e);
        }
    }

    private static Map<String, Need> byName(List<Need> needs) {
        Map<String, Need> map = new LinkedHashMap<>();
        for (Need need : needs) if (map.put(need.name(), need) != null) throw new IllegalStateException("Two needs named " + need.name());
        return Map.copyOf(map);
    }
}
