package dev.kim.enchantplus.neoforge;

import dev.kim.enchantplus.common.ClipboardPayload;
import dev.kim.enchantplus.common.ClipboardTransport;
import dev.kim.enchantplus.common.EnchantPlusCommands;
import dev.kim.enchantplus.common.ServerModPresentPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(EnchantPlusNeoForge.MOD_ID)
public final class EnchantPlusNeoForge {
    public static final String MOD_ID = "enchantplus";

    public EnchantPlusNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::registerPayloads);
        ClipboardTransport.setSender((player, value) -> {
            if (!player.connection.hasChannel(ClipboardPayload.TYPE)) {
                return false;
            }
            player.connection.send(new ClipboardPayload(value));
            return true;
        });
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.optional().playToClient(ClipboardPayload.TYPE, ClipboardPayload.STREAM_CODEC);
        registrar.optional().playToClient(ServerModPresentPayload.TYPE, ServerModPresentPayload.STREAM_CODEC);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        EnchantPlusCommands.register(event.getDispatcher(), event.getBuildContext());
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.connection.hasChannel(ServerModPresentPayload.TYPE)) {
            player.connection.send(new ServerModPresentPayload());
        }
    }
}
