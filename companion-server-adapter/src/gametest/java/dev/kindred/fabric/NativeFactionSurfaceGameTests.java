package dev.kindred.fabric;

import dev.kindred.core.Personality;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: whether it is at the surface, for its faction walks' wait (F-35i), is what it sees where it stands, the
 * sky's light at its feet, not the world's height map (F-35i2). Its walks all waiting after a run of refusals begun below,
 * the wait ends once it is where the sky's light reaches it, as at a cave's mouth under its rock; begun there, stepping
 * into the open sky is no coming up.
 *
 * Technical: a box of bedrock round it, 30 above the ground on nothing, a faction border beside it it can find no way to,
 * so every faction walk is refused; Red its land, Blue's beside it east. Staged daytime. Asserted: its faction walks wait
 * after the refusals; then, with its box opened as each case says, within {@value #WITHIN} ticks the wait ended or (the
 * second case) still held, with nothing else changed, where it stands.
 */
public final class NativeFactionSurfaceGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    /** Ticks it is watched after its box is opened. */
    private static final int WITHIN = 40;

    /**
     * Begun in the dark, its box's east wall taken out: the sky's light at its feet from the open side, rock still over
     * its head, as at a tunnel's mouth. It is out, and the wait ends (the height map, its roof over it, said it was not).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionSurfaceMouth", tickLimit = 7000)
    public void walksWaitingFromTheDarkTheWaitEndsWhereTheSkysLightReachesItsFeet(TestContext context) {
        run(context, false, true, false);
    }

    /**
     * Begun with its box's east side open (the sky's light at its feet under its roof), the roof taken off: it was at the
     * surface all along, and the wait holds (the height map read it as coming up, and ended the wait).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionSurfaceOverhang", tickLimit = 7000)
    public void walksWaitingBegunUnderAnOverhangTheWaitHoldsWhenItsRoofGoes(TestContext context) {
        run(context, true, false, true);
    }

    /** The control: begun in the open (no roof, an open side), nothing changes and the wait holds. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionSurfaceOpen", tickLimit = 7000)
    public void walksWaitingBegunInTheOpenTheWaitHolds(TestContext context) {
        run(context, true, false, false, true);
    }

    /**
     * Begun under an overhang (its east side open, rock over it), it walks by its own choice {@value #CLEAR} blocks south
     * along a ledge into the open sky, less than the run's {@value FactionsPlayer#STREAK_MOVED}: the refusals it waited out
     * were of places seen from where it stood, and the wait ends (F-35i2a). The height map ended it by reading the walk's end
     * as coming up; read by the sky's light, it was at the surface all along and the wait held.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionSurfaceWalkedClear", tickLimit = 7000)
    public void walksWaitingUnderAnOverhangTheWaitEndsWhenItsOwnWalkCarriesItClear(TestContext context) {
        walkedClear(context, false);
    }

    /**
     * As above, its walk the same {@value #CLEAR} blocks, but along a tunnel roofed and walled to its end, where no light of
     * the sky reaches its feet: the places it waited out would be asked from below the surface, refused again at once, and
     * the wait holds (F-35i2a; on Factions seeds 109 and 20261030 such walks were its goal's into the ground).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionSurfaceWalkedDark", tickLimit = 7000)
    public void walksWaitingUnderAnOverhangTheWaitHoldsWhenItsOwnWalkEndsInTheDark(TestContext context) {
        walkedClear(context, true);
    }

    /** Its walks waiting under the overhang, its own walk {@value #CLEAR} blocks south, into the open or the dark. */
    private static void walkedClear(TestContext context, boolean dark) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 6900);
        var server = context.getWorld().getServer();
        var world = context.getWorld();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        BlockPos[] feet = new BlockPos[1];
        long[] done = {-1};
        String[] wrong = {null};
        boolean[] ended = {false};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> feet[0] = staged(context, f, ben, true, false))
                .createAndAdd(() -> context.assertTrue(f.runtime.factionPausedForTest(f.id), "Its faction walks waiting after a run of refusals: "
                        + f.runtime.factionBlockedForTest(f.id)[0] + " refused"))
                .createAndAdd(() -> {
                    // A ledge out of its open side and south, its end CLEAR blocks from where it stood: in the open sky, or
                    // in a tunnel walled and roofed from the box's side to past its end.
                    FixtureGround.keep(context, feet[0].add(-1, -1, 0), feet[0].add(3, 3, CLEAR + 3));
                    for (int dz = 2; dz <= CLEAR + 1; dz++) world.setBlockState(feet[0].add(1, -1, dz), Blocks.BEDROCK.getDefaultState());
                    if (dark) for (int dz = 2; dz <= CLEAR + 2; dz++) for (int dy = -1; dy <= 2; dy++) for (int dx = 0; dx <= 2; dx++) {
                        boolean inside = dx == 1 && (dy == 0 || dy == 1) && dz <= CLEAR + 1;
                        if (!inside) world.setBlockState(feet[0].add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                    }
                    context.assertTrue(f.runtime.ownWalkForTest(f.id, feet[0].add(1, 0, CLEAR), 0.5) == 1, "Its walk south begun");
                })
                .createAndAdd(() -> {
                    var at = f.player().getBlockPos();
                    context.assertTrue(at.equals(feet[0].add(1, 0, CLEAR)) && f.player().isOnGround() && !f.runtime.nativeStatusLine(f.id).contains("runner=RUNNING"),
                            "Its walk done: at " + at.toShortString() + ", " + f.runtime.nativeStatusLine(f.id));
                    int light = world.getLightLevel(net.minecraft.world.LightType.SKY, at);
                    context.assertTrue(dark ? light == 0 : light > 0, "The sky's light at its walk's end: " + light);
                    done[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    long since = server.getTicks() - done[0];
                    boolean paused = f.runtime.factionPausedForTest(f.id);
                    long off = Math.round(Math.sqrt(feet[0].getSquaredDistance(f.player().getBlockPos())));
                    if (dark) {
                        if (!paused && since <= WITHIN && wrong[0] == null) wrong[0] = "ended " + since + " ticks after its walk, " + off + " blocks from where it waited";
                        context.assertTrue(wrong[0] == null, "Its wait held: " + wrong[0]);
                        context.assertTrue(since >= WITHIN, "Watched " + WITHIN + " ticks");
                    } else {
                        if (!paused && since <= WITHIN) ended[0] = true;
                        if (!ended[0] && since > WITHIN && wrong[0] == null) wrong[0] = "still waiting " + since + " ticks after its walk, " + off + " blocks from where it waited";
                        context.assertTrue(wrong[0] == null, "Its wait ended within " + WITHIN + " ticks of its walk: " + wrong[0]);
                        context.assertTrue(ended[0], "The wait ended");
                    }
                })
                .completeIfSuccessful();
    }

    /** How far its own walk carries it in {@link #walksWaitingUnderAnOverhangTheWaitEndsWhenItsOwnWalkCarriesItClear}. */
    private static final int CLEAR = 21;

    private static void run(TestContext context, boolean openSide, boolean thenOpenSide, boolean thenOffRoof) {
        run(context, openSide, thenOpenSide, thenOffRoof, false);
    }

    /**
     * Boxed with its east side open or not ({@code openSide}), and its roof on unless {@code noRoof}; once its walks wait,
     * its east side opened ({@code thenOpenSide}) or its roof taken off ({@code thenOffRoof}); the wait is to end only when
     * the sky's light first reaches its feet then.
     */
    private static void run(TestContext context, boolean openSide, boolean thenOpenSide, boolean thenOffRoof, boolean noRoof) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 6900);
        var server = context.getWorld().getServer();
        var world = context.getWorld();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        BlockPos[] feet = new BlockPos[1];
        long[] opened = {-1};
        // What it showed in the watched window, latched: a step that fails is tried again every tick, so a wait that ended
        // late (its own time out) or ended and was built again by new refusals must not pass later.
        String[] wrong = {null};
        boolean[] ended = {false};
        boolean ends = thenOpenSide && !openSide;
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> feet[0] = staged(context, f, ben, openSide, noRoof))
                .createAndAdd(() -> context.assertTrue(f.runtime.factionPausedForTest(f.id), "Its faction walks waiting after a run of refusals: "
                        + f.runtime.factionBlockedForTest(f.id)[0] + " refused, sky light at its feet "
                        + world.getLightLevel(net.minecraft.world.LightType.SKY, f.player().getBlockPos())))
                .createAndAdd(() -> {
                    if (thenOpenSide) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) world.setBlockState(feet[0].add(1, dy, dz), Blocks.AIR.getDefaultState());
                    if (thenOffRoof) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) world.setBlockState(feet[0].add(dx, 2, dz), Blocks.AIR.getDefaultState());
                    opened[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getBlockPos().equals(feet[0]), "Where it stood: " + player.getBlockPos().toShortString());
                    int light = world.getLightLevel(net.minecraft.world.LightType.SKY, player.getBlockPos());
                    boolean paused = f.runtime.factionPausedForTest(f.id);
                    long since = server.getTicks() - opened[0];
                    if (ends) {
                        if (!paused && since <= WITHIN) ended[0] = true;
                        if (!ended[0] && since > WITHIN && wrong[0] == null) wrong[0] = "still waiting " + since + " ticks on, the sky's light at its feet " + light;
                        context.assertTrue(wrong[0] == null, "Its wait ended within " + WITHIN + " ticks: " + wrong[0]);
                        context.assertTrue(ended[0], "The wait ended");
                    } else {
                        if (!paused && since <= WITHIN && wrong[0] == null) wrong[0] = "ended " + since + " ticks on, the sky's light at its feet " + light;
                        context.assertTrue(wrong[0] == null, "Its wait held: " + wrong[0]);
                        context.assertTrue(since >= WITHIN, "Watched " + WITHIN + " ticks");
                    }
                })
                .completeIfSuccessful();
    }

    /** The box, the player in it, and the two factions; where it stands. */
    private static BlockPos staged(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] ben, boolean openSide, boolean noRoof) {
        var server = context.getWorld().getServer();
        var world = context.getWorld();
        var player = f.player();
        BlockPos o = player.getBlockPos().up(30).toImmutable();
        FixtureGround.keep(context, o.add(-3, -2, -3), o.add(3, 4, 3));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
            boolean inside = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
            boolean side = openSide && dx == 1 && (dy == 0 || dy == 1);
            boolean roof = noRoof && dy == 2;
            world.setBlockState(o.add(dx, dy, dz), inside || side || roof ? Blocks.AIR.getDefaultState() : Blocks.BEDROCK.getDefaultState());
        }
        world.setTimeOfDay(1000);
        player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
        player.setVelocity(Vec3d.ZERO);
        player.getInventory().clear();
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        var here = Territory.Chunk.at(Territory.OVERWORLD, o.getX(), o.getZ());
        context.assertTrue(f.runtime.socialSetting(f.source, f.id, null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
        context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
        f.runtime.factionsWorldForTest(WIDE);
        ben[0] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
        f.runtime.factionsTickForTest();
        f.runtime.factions().clockForTest(-50 * 3_600_000L);
        context.assertTrue(f.runtime.faction(f.source, player.getUuid(), "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
        context.assertTrue(f.runtime.faction(f.source, ben[0].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
        f.runtime.factions().clockForTest(0);
        var ledgers = f.runtime.projected();
        String red = ledgers.factions().named("Red").orElseThrow().id().toString();
        String blue = ledgers.factions().named("Blue").orElseThrow().id().toString();
        // Red's land a column of five chunks, Blue's the five beside it east: places on the border to go to, each out of
        // reach from the box, as from a tunnel every place its faction play chooses is (F-35i's back-off fixture).
        for (int dz = -2; dz <= 2; dz++) {
            f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), Territory.OVERWORLD, ClaimLedger.CLAIM, player.getUuid(), red, here.offset(0, dz).key(), Map.of()));
            f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), Territory.OVERWORLD, ClaimLedger.CLAIM, null, blue, here.offset(1, dz).key(), Map.of()));
        }
        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), Territory.OVERWORLD, ClaimLedger.CLAIM, null, blue, here.offset(2, 0).key(), Map.of()));
        f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
        return o;
    }
}
