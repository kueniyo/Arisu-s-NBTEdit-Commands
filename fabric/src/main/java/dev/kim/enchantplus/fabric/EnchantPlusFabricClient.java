package dev.kim.enchantplus.fabric;

import dev.kim.enchantplus.common.ClientCommandEnvironment;
import dev.kim.enchantplus.common.ClientFallbackCommands;
import dev.kim.enchantplus.common.ClientFallbackState;
import dev.kim.enchantplus.common.ClipboardPayload;
import dev.kim.enchantplus.common.ServerModPresentPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class EnchantPlusFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientFallbackState.setStateChangeListener(() -> Minecraft.getInstance().execute(() -> {
            try {
                ClientCommands.refreshCommandCompletions();
            } catch (IllegalStateException ignored) {
                // No command tree has been received yet, or the client is between connections.
            }
        }));

        ClientPlayNetworking.registerGlobalReceiver(ClipboardPayload.TYPE, (payload, context) ->
                context.client().execute(() -> Minecraft.getInstance().keyboardHandler.setClipboard(payload.value())));
        ClientPlayNetworking.registerGlobalReceiver(ServerModPresentPayload.TYPE, (payload, context) ->
                context.client().execute(ClientFallbackState::markServerPresent));

        ClientPlayConnectionEvents.INIT.register((handler, client) -> ClientFallbackState.beginConnection());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientFallbackState.disconnect());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                ClientFallbackCommands.register(dispatcher, new ClientCommandEnvironment<FabricClientCommandSource>() {
                    @Override public boolean fallbackEnabled() { return ClientFallbackState.isFallbackEnabled(); }
                    @Override public boolean isDirectUserCommand(FabricClientCommandSource source) { return source.attended(); }
                    @Override public net.minecraft.world.entity.player.Player player(FabricClientCommandSource source) { return source.getPlayer(); }
                    @Override public net.minecraft.world.level.Level level(FabricClientCommandSource source) { return source.getLevel(); }
                    @Override public net.minecraft.core.RegistryAccess registryAccess(FabricClientCommandSource source) {
                        return source.getLevel().registryAccess();
                    }
                    @Override public String language(FabricClientCommandSource source) {
                        return source.getClient().getLanguageManager().getSelected();
                    }
                    @Override public void sendVanillaCommand(FabricClientCommandSource source, String command) {
                        if (source.getClient().getConnection() != null) source.getClient().getConnection().sendCommand(command);
                    }
                    @Override public void sendFeedback(FabricClientCommandSource source, net.minecraft.network.chat.Component component) {
                        source.sendFeedback(component);
                    }
                    @Override public void copyToClipboard(FabricClientCommandSource source, String value) {
                        source.getClient().keyboardHandler.setClipboard(value);
                    }
                }));
    }
}
