package dev.kindred.core.factions;

import dev.kindred.core.record.WorldEvent;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * In plain terms: the rules of power: who may fight whom in a faction world, which deaths cost the victim two power
 * (a kill by an enemy faction's member outside a safe zone, not the same pair twice in twenty minutes, not by or of an
 * account new in the last hour), a faction's power as its members' sum, its claim capacity (the lesser of its power and
 * 80), and how many of its chunks are exposed when its claims exceed its power.
 *
 * Technical: Pure rules over the faction and power ledgers (M-06c), the same for people and AI players. A faction world
 * is a match its members joined (D7): a player in no faction is never struck and never loses power to a kill.
 */
public final class PowerRules {
    private PowerRules() {}

    /** Whether a death costs power, and why. */
    public record Qualification(boolean counts, String reason) {}

    /**
     * Whether {@code attacker} may strike {@code target}: both in factions, different ones, enemies, and neither standing
     * in a safe zone. Never a player in no faction.
     */
    public static boolean mayAttack(FactionBook book, UUID attacker, UUID target, Zone attackerZone, Zone targetZone) {
        if (attacker == null || target == null || attacker.equals(target)) return false;
        if (attackerZone == Zone.SAFE || targetZone == Zone.SAFE) return false;
        var a = book.of(attacker).orElse(null);
        var b = book.of(target).orElse(null);
        if (a == null || b == null || a.id().equals(b.id())) return false;
        return book.relation(a.id(), b.id()) == Faction.Relation.ENEMY;
    }

    /** Whether {@code victim}'s death, by {@code killer} (null for none) in {@code zone} at {@code nowMs}, costs it power. */
    public static Qualification qualifies(FactionBook factions, PowerBook powers, FactionSettings settings, UUID victim, UUID killer, Zone zone, long nowMs) {
        if (killer == null || killer.equals(victim)) return new Qualification(false, "not a PvP death");
        if (zone == Zone.SAFE) return new Qualification(false, "in a safe zone");
        var victimFaction = factions.of(victim).orElse(null);
        var killerFaction = factions.of(killer).orElse(null);
        if (victimFaction == null || killerFaction == null) return new Qualification(false, "not between two factions");
        if (victimFaction.id().equals(killerFaction.id())) return new Qualification(false, "by a faction-mate");
        var relation = factions.relation(victimFaction.id(), killerFaction.id());
        if (relation != Faction.Relation.ENEMY) return new Qualification(false, "by " + (relation == Faction.Relation.ALLY ? "an ally" : "a faction in truce"));
        long newer = settings.newAccountSeconds() * 1000L;
        Long victimSeen = powers.firstSeen().get(victim), killerSeen = powers.firstSeen().get(killer);
        if (victimSeen == null || nowMs - victimSeen < newer || killerSeen == null || nowMs - killerSeen < newer) {
            return new Qualification(false, "an account new in the last " + settings.newAccountSeconds() / 60 + " minutes");
        }
        Long last = powers.lastCounted().get(PowerBook.pair(killer, victim));
        if (last != null && nowMs - last < settings.pairCooldownSeconds() * 1000L) {
            return new Qualification(false, "the same killer within " + settings.pairCooldownSeconds() / 60 + " minutes");
        }
        return new Qualification(true, "a PvP death to an enemy faction");
    }

    /** The {@code power.loss} event a qualifying death writes: the victim's loss, its killer, and its power after it. */
    public static WorldEvent loss(PowerBook powers, UUID victim, UUID killer, FactionSettings settings, long tick, long realTimeMs, String world, String place) {
        var before = powers.of(victim, settings);
        var data = new LinkedHashMap<String, String>();
        data.put("amount", String.valueOf(settings.deathLoss()));
        data.put("killer", killer.toString());
        data.put("power", String.valueOf(Math.max(0, before.power() - settings.deathLoss())));
        data.put("progress", String.valueOf(before.progressSeconds()));
        return WorldEvent.recorded(tick, realTimeMs, world, PowerLedger.LOSS, victim, "", place, data);
    }

    /** {@code seconds} more online for {@code player}, as an event carrying its power and progress after them. */
    public static WorldEvent online(PowerBook powers, UUID player, int seconds, FactionSettings settings, long tick, long realTimeMs) {
        var after = regenerate(powers.of(player, settings), seconds, settings);
        var data = new LinkedHashMap<String, String>();
        data.put("seconds", String.valueOf(seconds));
        data.put("power", String.valueOf(after.power()));
        data.put("progress", String.valueOf(after.progressSeconds()));
        return WorldEvent.recorded(tick, realTimeMs, "", PowerLedger.ONLINE, player, "", "", data);
    }

    /** An operator's correction of {@code player}'s power, within 0 and the maximum. */
    public static WorldEvent set(UUID player, int power, UUID operator, FactionSettings settings, long tick, long realTimeMs) {
        var data = new LinkedHashMap<String, String>();
        data.put("power", String.valueOf(Math.max(0, Math.min(settings.maxPower(), power))));
        data.put("progress", "0");
        data.put("by", operator == null ? "console" : operator.toString());
        return WorldEvent.recorded(tick, realTimeMs, "", PowerLedger.SET, player, "", "", data);
    }

    /** {@code power} after {@code seconds} more online: a point each full hour below the maximum, the rest kept. */
    public static PowerBook.Power regenerate(PowerBook.Power power, int seconds, FactionSettings settings) {
        if (seconds <= 0) return power;
        if (power.power() >= settings.maxPower()) return new PowerBook.Power(power.power(), 0);
        long progress = (long) power.progressSeconds() + seconds;
        int gained = (int) Math.min(settings.maxPower() - power.power(), progress / settings.regenSeconds());
        int now = power.power() + gained;
        int left = now >= settings.maxPower() ? 0 : (int) (progress - (long) gained * settings.regenSeconds());
        return new PowerBook.Power(now, left);
    }

    /** That {@code player} was first seen on the server now. */
    public static WorldEvent seen(UUID player, long tick, long realTimeMs) {
        return WorldEvent.recorded(tick, realTimeMs, "", PowerLedger.SEEN, player, "", "", java.util.Map.of());
    }

    /** A faction's power: the sum of its members' power. */
    public static int factionPower(FactionBook factions, PowerBook powers, UUID faction, FactionSettings settings) {
        return factions.get(faction).map(f -> f.members().keySet().stream().mapToInt(p -> powers.of(p, settings).power()).sum()).orElse(0);
    }

    /** How many chunks a faction may hold: the lesser of its power and the cap. */
    public static int capacity(int factionPower, FactionSettings settings) { return Math.max(0, Math.min(factionPower, settings.claimCap())); }

    /** How many of its chunks are exposed to capture: as many as its claims exceed its power. */
    public static int exposed(int claims, int factionPower) { return Math.max(0, claims - factionPower); }
}
