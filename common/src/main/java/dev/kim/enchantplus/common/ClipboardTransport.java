package dev.kim.enchantplus.common;

import net.minecraft.server.level.ServerPlayer;

/** Platform-specific bridge for sending clipboard data only to modded clients. */
public final class ClipboardTransport {
    @FunctionalInterface
    public interface Sender {
        boolean send(ServerPlayer player, String value);
    }

    private static volatile Sender sender = (player, value) -> false;

    private ClipboardTransport() {
    }

    public static void setSender(Sender newSender) {
        sender = newSender == null ? (player, value) -> false : newSender;
    }

    public static boolean sendIfSupported(ServerPlayer player, String value) {
        return sender.send(player, value);
    }
}
