package dev.kindred.fabric;

import dev.kindred.core.SkinFiles;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.UUID;

/**
 * Skin selector protocol for the optional client. The server sends the skin each AI player wears (the PNG itself,
 * since clients cannot read the server's folder) and, when a client opens the selector, the skins on offer.
 */
final class KindredSkinPayloads {
    private KindredSkinPayloads() { }

    private static final PacketCodec<io.netty.buffer.ByteBuf, byte[]> PNG = PacketCodecs.byteArray(SkinFiles.MAX_BYTES);

    /** The skin a native body wears, by its player UUID; an empty {@code png} means its own skin again. */
    record Binding(UUID body, String key, boolean slim, byte[] png) implements CustomPayload {
        static final Id<Binding> ID = new Id<>(Identifier.of("kindred", "skin_binding"));
        static final PacketCodec<RegistryByteBuf, Binding> CODEC = PacketCodec.tuple(
                Uuids.PACKET_CODEC, Binding::body,
                PacketCodecs.string(32), Binding::key,
                PacketCodecs.BOOL, Binding::slim,
                PNG, Binding::png,
                Binding::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client asks for the skins on offer. */
    record CatalogRequest() implements CustomPayload {
        static final Id<CatalogRequest> ID = new Id<>(Identifier.of("kindred", "skin_catalog_request"));
        static final PacketCodec<RegistryByteBuf, CatalogRequest> CODEC = PacketCodec.unit(new CatalogRequest());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * One skin on offer, {@code index} of {@code total}, one payload each so a full folder stays under the payload
     * size limit. A {@code total} of zero (with an empty key) says the folder has none.
     */
    record CatalogEntry(String key, boolean slim, byte[] png, int index, int total) implements CustomPayload {
        static final Id<CatalogEntry> ID = new Id<>(Identifier.of("kindred", "skin_catalog_entry"));
        static final PacketCodec<RegistryByteBuf, CatalogEntry> CODEC = PacketCodec.tuple(
                PacketCodecs.string(32), CatalogEntry::key,
                PacketCodecs.BOOL, CatalogEntry::slim,
                PNG, CatalogEntry::png,
                PacketCodecs.VAR_INT, CatalogEntry::index,
                PacketCodecs.VAR_INT, CatalogEntry::total,
                CatalogEntry::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(Binding.ID, Binding.CODEC);
        PayloadTypeRegistry.playC2S().register(CatalogRequest.ID, CatalogRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(CatalogEntry.ID, CatalogEntry.CODEC);
    }
}
