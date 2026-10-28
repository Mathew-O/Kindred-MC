package dev.kindred.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * In plain terms: the messages the optional client mod and the server exchange for the K panel: the client
 * asks for the panel's contents or presses a button; the server checks the player is allowed, just as it
 * would for the matching command.
 *
 * Technical: Optional client panel protocol. The client only asks for state and sends named actions; the
 * server checks the sender's authority for every action exactly as it does for the equivalent command.
 */
final class KindredPanelPayloads {
    private KindredPanelPayloads() { }

    /** Client asks for a fresh panel snapshot. */
    record Request() implements CustomPayload {
        static final Id<Request> ID = new Id<>(Identifier.of("kindred", "panel_request"));
        static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.unit(new Request());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client requests one named action on one AI player (or the server when {@code playerId} is empty). */
    record Action(String action, String playerId, String argument) implements CustomPayload {
        static final Id<Action> ID = new Id<>(Identifier.of("kindred", "panel_action"));
        static final PacketCodec<RegistryByteBuf, Action> CODEC = PacketCodec.tuple(
                PacketCodecs.string(64), Action::action,
                PacketCodecs.string(64), Action::playerId,
                PacketCodecs.string(256), Action::argument,
                Action::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server snapshot of what the requesting player may see and control, as compact JSON. */
    record State(String json, String message) implements CustomPayload {
        static final Id<State> ID = new Id<>(Identifier.of("kindred", "panel_state"));
        static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.tuple(
                PacketCodecs.string(262_144), State::json,
                PacketCodecs.string(512), State::message,
                State::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Tells Minecraft about these messages. */
    static void register() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playC2S().register(Action.ID, Action.CODEC);
        PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
    }
}
