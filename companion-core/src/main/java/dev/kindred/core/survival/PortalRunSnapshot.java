package dev.kindred.core.survival;

/**
 * What one AI player currently owns and can observe that matters for earning a Nether portal. Counts are
 * physical items in its own inventory (and in a furnace it is using); nothing here is a world query.
 *
 * @param logs            any log or wood item that crafts planks
 * @param planks          any planks
 * @param sticks          sticks
 * @param cobble          cobblestone-like blocks usable for stone tools and furnaces
 * @param coal            coal or charcoal
 * @param rawIron         raw iron (including any in the furnace being used)
 * @param ironIngots      iron ingots (including smelted output waiting in that furnace)
 * @param diamonds        diamonds
 * @param flint           flint
 * @param gravel          gravel blocks carried (for re-rolling flint)
 * @param obsidian        obsidian blocks carried
 * @param emptyBuckets    empty buckets
 * @param waterBuckets    water buckets
 * @param flintAndSteel   flint and steel items
 * @param craftingTables  crafting table items carried
 * @param furnaces        furnace items carried
 * @param edibleFood      edible food items that are safe to eat
 * @param pickaxeTier     best pickaxe: 0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond, 5 netherite
 * @param swordTier       best sword on the same scale
 * @param chestArmorTier  best chest armor worn or carried on the same scale (0 none, 3 iron)
 * @param tableNearby     a crafting table is within reach
 * @param furnaceNearby   a furnace is within reach
 * @param furnaceSmelting the reachable furnace still has input to smelt
 * @param furnaceOutput   items waiting in the reachable furnace's output slot
 * @param foodLevel       hunger points 0..20
 * @param portalFramed    a complete obsidian frame is observed nearby
 * @param portalLit       a lit portal this player built is observed nearby
 */
public record PortalRunSnapshot(
        int logs, int planks, int sticks, int cobble, int coal, int rawIron, int ironIngots, int diamonds,
        int flint, int gravel, int obsidian, int emptyBuckets, int waterBuckets, int flintAndSteel,
        int craftingTables, int furnaces, int edibleFood,
        int pickaxeTier, int swordTier, int chestArmorTier,
        boolean tableNearby, boolean furnaceNearby, boolean furnaceSmelting, int furnaceOutput,
        int foodLevel, boolean portalFramed, boolean portalLit) {

    /** Plank-equivalents available from planks and uncrafted logs. */
    public int woodUnits() { return planks + logs * 4; }
}
