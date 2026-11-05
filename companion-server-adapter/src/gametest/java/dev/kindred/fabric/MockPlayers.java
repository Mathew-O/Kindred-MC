package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;

import java.util.UUID;

/**
 * In plain terms: a pretend person for a GameTest, standing in the test world as a creative-mode player who is
 * logged in to the server, so a fixture can have someone talk to an AI player, watch it or stand near it.
 *
 * Technical: The same player vanilla's {@code TestContext.createMockCreativeServerPlayerInWorld} makes (deprecated
 * for removal), built from the non-deprecated parts: a {@link ServerPlayerEntity} that is creative and never a
 * spectator, connected through a {@link ClientConnection} on an embedded Netty channel with the default client data.
 */
final class MockPlayers {
    private MockPlayers() { }

    /** A creative player named "test-mock-player", logged in to the test world's server. */
    static ServerPlayerEntity creative(TestContext context) {
        var world = context.getWorld();
        var server = world.getServer();
        var data = ConnectedClientData.createDefault(new GameProfile(UUID.randomUUID(), "test-mock-player"), false);
        var player = new ServerPlayerEntity(server, world, data.gameProfile(), data.syncedOptions()) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return true; }
        };
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerManager().onPlayerConnect(connection, player, data);
        return player;
    }

    /**
     * A person in survival named {@code name}, logged in to the test world's server at {@code pos} (relative to the
     * test): a stand-in for a human at an ordinary client, with no runtime entry, so to Kindred it is a human player.
     * It takes damage as any survival player does. The test removes it when done.
     */
    static ServerPlayerEntity survival(TestContext context, String name, net.minecraft.util.math.BlockPos pos) {
        var world = context.getWorld();
        var server = world.getServer();
        var data = ConnectedClientData.createDefault(new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayerEntity(server, world, data.gameProfile(), data.syncedOptions());
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerManager().onPlayerConnect(connection, player, data);
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        var at = context.getAbsolutePos(pos);
        player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return player;
    }
}
