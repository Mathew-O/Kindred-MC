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
}
