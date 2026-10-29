package dev.kindred.core.tactics;

/**
 * In plain terms: how much an explosion, such as an end crystal or a bed blowing up, would hurt the player
 * where it stands, counting the blocks that shield it. The player uses it to stand clear or take cover before
 * setting one off.
 *
 * Technical: Blast exposure (E-39i), as vanilla 1.21.1 reckons an explosion's harm to an entity: rays from a
 * grid of points over the entity's box to the blast, the share of them no block's collision shape stops; and
 * from that share and the entity's distance, the damage the blast does before armour (what a crystal's blast
 * of power 6 or a bed's of 5 would do to a player where it stands). Used to keep clear of a crystal it is
 * about to set off, and to put a block between itself and a bed set off against the dragon. Pure: the adapter
 * says which rays a block stops.
 */
public final class BlastExposure {
    private BlastExposure() { }

    /** A crystal's blast. */
    public static final double CRYSTAL = 6.0;
    /** A bed's blast, set off in the Nether or the End. */
    public static final double BED = 5.0;

    /** Whether a block's collision shape stops the ray between two points. */
    public interface Rays {
        boolean blocked(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);
    }

    /** An entity's box. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        /** A player's box standing with its feet at {@code x}, {@code y}, {@code z}: 0.6 wide, 1.8 high. */
        public static Box player(double x, double y, double z) {
            return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        }
    }

    /** The share of the box the blast at {@code x}, {@code y}, {@code z} reaches, as vanilla's {@code getExposure} samples it. */
    public static double exposure(double x, double y, double z, Box box, Rays rays) {
        double d = 1.0 / ((box.maxX() - box.minX()) * 2.0 + 1.0);
        double e = 1.0 / ((box.maxY() - box.minY()) * 2.0 + 1.0);
        double f = 1.0 / ((box.maxZ() - box.minZ()) * 2.0 + 1.0);
        double g = (1.0 - Math.floor(1.0 / d) * d) / 2.0;
        double h = (1.0 - Math.floor(1.0 / f) * f) / 2.0;
        if (d < 0.0 || e < 0.0 || f < 0.0) return 0.0;
        int clear = 0, all = 0;
        for (double k = 0.0; k <= 1.0; k += d) {
            for (double l = 0.0; l <= 1.0; l += e) {
                for (double m = 0.0; m <= 1.0; m += f) {
                    double n = lerp(k, box.minX(), box.maxX()), o = lerp(l, box.minY(), box.maxY()), p = lerp(m, box.minZ(), box.maxZ());
                    if (!rays.blocked(n + g, o, p + h, x, y, z)) clear++;
                    all++;
                }
            }
        }
        return (float) clear / all;
    }

    private static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    /**
     * The damage a blast of {@code power} at {@code distance} from an entity's feet, with {@code exposure}, does before
     * armour, as vanilla's {@code calculateDamage}: none beyond twice its power.
     */
    public static double damage(double power, double distance, double exposure) {
        double reach = power * 2.0;
        double v = distance / reach;
        if (v > 1.0) return 0.0;
        double e = (1.0 - v) * exposure;
        return (float) ((e * e + e) / 2.0 * 7.0 * reach + 1.0);
    }

    /** The damage of a blast of {@code power} at {@code x}, {@code y}, {@code z} to a player standing at {@code fx}, {@code fy}, {@code fz}, before armour. */
    public static double toPlayer(double power, double x, double y, double z, double fx, double fy, double fz, Rays rays) {
        double distance = Math.sqrt((fx - x) * (fx - x) + (fy - y) * (fy - y) + (fz - z) * (fz - z));
        if (distance > power * 2.0) return 0.0;
        return damage(power, distance, exposure(x, y, z, Box.player(fx, fy, fz), rays));
    }

    /** Whether standing there is safe from the blast: what it would do after armour leaves at least {@code spare} health. */
    public static boolean safe(double damageBeforeArmour, double armour, double toughness, double health, double spare) {
        return health - CombatRuleset.afterArmour(damageBeforeArmour, armour, toughness) >= spare;
    }
}
