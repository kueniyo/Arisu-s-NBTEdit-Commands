package dev.kim.enchantplus.common;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Clientbound clipboard data. Only clients with this mod register this payload. */
public record ClipboardPayload(String value) implements CustomPacketPayload {
    public static final int MAX_LENGTH = 262_144;
    public static final Type<ClipboardPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("enchantplus", "copy_clipboard"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClipboardPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_LENGTH),
                    ClipboardPayload::value,
                    ClipboardPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
