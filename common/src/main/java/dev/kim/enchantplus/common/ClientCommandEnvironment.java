package dev.kim.enchantplus.common;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Loader bridge for the shared client-side command fallback implementation. */
public interface ClientCommandEnvironment<S> {
    boolean fallbackEnabled();
    /** True only when a command was directly entered by the player, where the loader can detect it. */
    default boolean isDirectUserCommand(S source) { return true; }
    Player player(S source);
    Level level(S source);
    RegistryAccess registryAccess(S source);
    String language(S source);
    void sendVanillaCommand(S source, String command);
    void sendFeedback(S source, Component component);
    void copyToClipboard(S source, String value);
}
