package dev.kindred.fabric;

import dev.kindred.core.route.DigTerrain;
import dev.kindred.core.route.Terrain;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * In plain terms: the live world as the core's dig decisions see it: each block's plain facts, read from the block as
 * the campaign used to read them itself.
 *
 * Technical: The server's side of {@link DigTerrain} (F-35e). The walk's facts are a fresh {@link NativeNavigator}'s,
 * read through its cache of the blocks, as the campaign's climb asked a navigator of its own; the dig's own facts are
 * read from the block's state each time, as the campaign read them: {@code empty} is the collision shape's emptiness,
 * {@code falls} a {@link FallingBlock}, {@code fallsThrough} {@link FallingBlock#canFallThrough}, and {@code falling}
 * the falling block entities in the same box. One lives for one decision on the server thread.
 */
final class WorldDig implements DigTerrain {
    private final ServerWorld world;
    private final Terrain walk;

    WorldDig(ServerWorld world) {
        this.world = world;
        this.walk = new NativeNavigator(world).terrain();
    }

    @Override public boolean loaded(int x, int y, int z) { return walk.loaded(x, y, z); }
    @Override public Fluid fluid(int x, int y, int z) { return walk.fluid(x, y, z); }
    @Override public boolean harms(int x, int y, int z) { return walk.harms(x, y, z); }
    @Override public boolean passThrough(int x, int y, int z) { return walk.passThrough(x, y, z); }
    @Override public boolean supports(int x, int y, int z) { return walk.supports(x, y, z); }
    @Override public boolean bears(int x, int y, int z) { return walk.bears(x, y, z); }
    @Override public boolean scorches(int x, int y, int z) { return walk.scorches(x, y, z); }
    @Override public boolean bottomHalf(int x, int y, int z) { return walk.bottomHalf(x, y, z); }
    @Override public boolean portal(int x, int y, int z) { return walk.portal(x, y, z); }
    @Override public boolean replaceable(int x, int y, int z) { return walk.replaceable(x, y, z); }
    @Override public float hardness(int x, int y, int z) { return walk.hardness(x, y, z); }
    @Override public boolean placeAgainst(int x, int y, int z) { return walk.placeAgainst(x, y, z); }

    @Override
    public boolean empty(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    @Override
    public boolean falls(int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof FallingBlock;
    }

    @Override
    public boolean fallsThrough(int x, int y, int z) {
        return FallingBlock.canFallThrough(world.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public boolean falling(int x0, int y0, int z0, int x1, int y1, int z1) {
        return !world.getEntitiesByClass(FallingBlockEntity.class, new Box(x0, y0, z0, x1, y1, z1), e -> true).isEmpty();
    }
}
