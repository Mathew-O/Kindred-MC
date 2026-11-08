package dev.kindred.core.factions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In plain terms: the numbers Factions mode plays by (members a faction, power, claims, coins, shields, fees, the
 * border), each with the rulebook's default, changeable by an operator in {@code config/kindred/factions.properties}.
 *
 * Technical: The owner's rulebook defaults (docs/proposals/factions-smp-spec.md, sections 3 to 5 and 11), with the
 * owner's fixed decision of an 8,000-block border for the first run. {@link #parse} reads the properties file's entries,
 * refusing out-of-range values with their key; {@link #properties} writes the file's seed. The locked rules (power from
 * PvP deaths and online hours, overclaim-only raids, protected territory) are not settings.
 */
public record FactionSettings(int maxMembers, int startPower, int maxPower, int deathLoss, int regenSeconds, int claimCap,
                              int pairCooldownSeconds, int newAccountSeconds, int spawnExclusion, int claimCost, int startCoins,
                              int newFactionShieldHours, int raidShieldMinutes, int border, int auctionSalePercent,
                              int auctionListingFee, int auctionListings, int auctionHours, int safeRadius) {
    /** The rulebook's defaults, the border at the owner's 8,000. */
    public static final FactionSettings DEFAULTS = new FactionSettings(10, 10, 10, 2, 3600, 80, 20 * 60, 3600, 500, 100, 500,
            48, 30, 8000, 3, 25, 5, 48, 64);

    public FactionSettings {
        check(maxMembers, 1, 100, "maxMembers");
        check(maxPower, 1, 1000, "maxPower");
        check(startPower, 0, maxPower, "startPower");
        check(deathLoss, 0, maxPower, "deathLoss");
        check(regenSeconds, 60, 7 * 24 * 3600, "regenSeconds");
        check(claimCap, 1, 10_000, "claimCap");
        check(pairCooldownSeconds, 0, 7 * 24 * 3600, "pairCooldownSeconds");
        check(newAccountSeconds, 0, 30 * 24 * 3600, "newAccountSeconds");
        check(spawnExclusion, 0, 100_000, "spawnExclusion");
        check(claimCost, 0, 1_000_000, "claimCost");
        check(startCoins, 0, 1_000_000_000, "startCoins");
        check(newFactionShieldHours, 0, 24 * 30, "newFactionShieldHours");
        check(raidShieldMinutes, 0, 24 * 60, "raidShieldMinutes");
        check(border, 64, 60_000_000, "border");
        check(auctionSalePercent, 0, 100, "auctionSalePercent");
        check(auctionListingFee, 0, 1_000_000, "auctionListingFee");
        check(auctionListings, 0, 1000, "auctionListings");
        check(auctionHours, 1, 24 * 30, "auctionHours");
        check(safeRadius, 16, 1024, "safeRadius");
    }

    /** The file's entries, in its order, with the defaults. */
    public Map<String, String> properties() {
        var out = new LinkedHashMap<String, String>();
        out.put("maxMembers", String.valueOf(maxMembers));
        out.put("startPower", String.valueOf(startPower));
        out.put("maxPower", String.valueOf(maxPower));
        out.put("deathLoss", String.valueOf(deathLoss));
        out.put("regenSeconds", String.valueOf(regenSeconds));
        out.put("claimCap", String.valueOf(claimCap));
        out.put("pairCooldownSeconds", String.valueOf(pairCooldownSeconds));
        out.put("newAccountSeconds", String.valueOf(newAccountSeconds));
        out.put("spawnExclusion", String.valueOf(spawnExclusion));
        out.put("claimCost", String.valueOf(claimCost));
        out.put("startCoins", String.valueOf(startCoins));
        out.put("newFactionShieldHours", String.valueOf(newFactionShieldHours));
        out.put("raidShieldMinutes", String.valueOf(raidShieldMinutes));
        out.put("border", String.valueOf(border));
        out.put("auctionSalePercent", String.valueOf(auctionSalePercent));
        out.put("auctionListingFee", String.valueOf(auctionListingFee));
        out.put("auctionListings", String.valueOf(auctionListings));
        out.put("auctionHours", String.valueOf(auctionHours));
        out.put("safeRadius", String.valueOf(safeRadius));
        return out;
    }

    /** Settings from a properties file's entries: each key it names replaces its default; an unknown key or a bad value is refused. */
    public static FactionSettings parse(Map<String, String> entries) {
        var values = new LinkedHashMap<>(DEFAULTS.properties());
        for (var entry : entries.entrySet()) {
            String key = entry.getKey().trim();
            if (!values.containsKey(key)) throw new IllegalArgumentException("Unknown factions setting " + key);
            try {
                Integer.parseInt(entry.getValue().trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("Factions setting " + key + " is not a whole number: " + entry.getValue());
            }
            values.put(key, entry.getValue().trim());
        }
        int[] v = values.values().stream().mapToInt(Integer::parseInt).toArray();
        return new FactionSettings(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18]);
    }

    private static void check(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException("Factions setting " + name + " must be " + min + " to " + max + ", not " + value);
    }
}
