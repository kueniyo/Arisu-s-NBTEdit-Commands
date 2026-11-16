package dev.kim.enchantplus.fabric;

import dev.kim.enchantplus.common.ClipboardPayload;
import dev.kim.enchantplus.common.ClipboardTransport;
import dev.kim.enchantplus.common.EnchantPlusCommands;
import dev.kim.enchantplus.common.ServerModPresentPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class EnchantPlusFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(ClipboardPayload.TYPE, ClipboardPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ServerModPresentPayload.TYPE, ServerModPresentPayload.STREAM_CODEC);
        ClipboardTransport.setSender((player, value) -> {
            if (!ServerPlayNetworking.canSend(player, ClipboardPayload.TYPE)) {
                return false;
            }
            ServerPlayNetworking.send(player, new ClipboardPayload(value));
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, ServerModPresentPayload.TYPE)) {
                ServerPlayNetworking.send(handler.player, new ServerModPresentPayload());
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EnchantPlusCommands.register(dispatcher, registryAccess));
    }
}
