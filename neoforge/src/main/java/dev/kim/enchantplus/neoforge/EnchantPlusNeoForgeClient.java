package dev.kim.enchantplus.neoforge;

import dev.kim.enchantplus.common.ClientFallbackState;
import dev.kim.enchantplus.common.ClipboardPayload;
import dev.kim.enchantplus.common.ServerModPresentPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid = EnchantPlusNeoForge.MOD_ID, value = Dist.CLIENT)
public final class EnchantPlusNeoForgeClient {
    private EnchantPlusNeoForgeClient() {
    }

    @SubscribeEvent
    public static void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        ClientFallbackState.setStateChangeListener(EnchantPlusNeoForgeClientCommands::refreshClientCommands);
        event.register(ClipboardPayload.TYPE, (payload, context) ->
                Minecraft.getInstance().keyboardHandler.setClipboard(payload.value()));
        event.register(ServerModPresentPayload.TYPE, (payload, context) ->
                ClientFallbackState.markServerPresent());
    }
}
