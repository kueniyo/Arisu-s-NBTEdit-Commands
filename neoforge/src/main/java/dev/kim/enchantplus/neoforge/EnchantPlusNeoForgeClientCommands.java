package dev.kim.enchantplus.neoforge;

import dev.kim.enchantplus.common.ClientCommandEnvironment;
import dev.kim.enchantplus.common.ClientFallbackCommands;
import dev.kim.enchantplus.common.ClientFallbackState;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = EnchantPlusNeoForge.MOD_ID, value = Dist.CLIENT)
public final class EnchantPlusNeoForgeClientCommands {
    private EnchantPlusNeoForgeClientCommands() {
    }


    public static void refreshClientCommands() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            var connection = minecraft.getConnection();
            if (connection == null) return;

            var featureFlags = minecraft.level != null
                    ? minecraft.level.enabledFeatures()
                    : FeatureFlagSet.of();
            CommandBuildContext buildContext = CommandBuildContext.simple(connection.registryAccess(), featureFlags);
            connection.commands = ClientCommandHandler.mergeServerCommands(connection.commands, buildContext);
        });
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        ClientFallbackCommands.register(event.getDispatcher(), new ClientCommandEnvironment<CommandSourceStack>() {
            @Override public boolean fallbackEnabled() { return ClientFallbackState.isFallbackEnabled(); }
            @Override public Player player(CommandSourceStack source) { return Minecraft.getInstance().player; }
            @Override public Level level(CommandSourceStack source) { return Minecraft.getInstance().level; }
            @Override public RegistryAccess registryAccess(CommandSourceStack source) {
                Level level = Minecraft.getInstance().level;
                return level != null ? level.registryAccess() : source.registryAccess();
            }
            @Override public String language(CommandSourceStack source) {
                return Minecraft.getInstance().getLanguageManager().getSelected();
            }
            @Override public void sendVanillaCommand(CommandSourceStack source, String command) {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.sendCommand(command);
            }
            @Override public void sendFeedback(CommandSourceStack source, Component component) {
                if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(component);
            }
            @Override public void copyToClipboard(CommandSourceStack source, String value) {
                Minecraft.getInstance().keyboardHandler.setClipboard(value);
            }
        });
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientFallbackState.beginConnection();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFallbackState.disconnect();
    }
}
