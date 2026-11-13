package dev.kim.enchantplus.neoforge;

import dev.kim.enchantplus.common.EnchantPlusCommands;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(EnchantPlusNeoForge.MOD_ID)
public final class EnchantPlusNeoForge {
    public static final String MOD_ID = "enchantplus";

    public EnchantPlusNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        EnchantPlusCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}
