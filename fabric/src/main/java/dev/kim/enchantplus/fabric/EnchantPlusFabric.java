package dev.kim.enchantplus.fabric;

import dev.kim.enchantplus.common.EnchantPlusCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class EnchantPlusFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EnchantPlusCommands.register(dispatcher, registryAccess));
    }
}
