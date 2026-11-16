package dev.kim.enchantplus.common;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Optional server-to-client marker. Vanilla servers simply never send this payload. */
public record ServerModPresentPayload() implements CustomPacketPayload {
    public static final Type<ServerModPresentPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("enchantplus", "server_present"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerModPresentPayload> STREAM_CODEC =
            StreamCodec.unit(new ServerModPresentPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
