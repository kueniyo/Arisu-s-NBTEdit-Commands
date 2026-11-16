package dev.kim.enchantplus.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

/** Shared Brigadier command implementation used by both loader projects. */
public final class EnchantPlusCommands {
    /** ItemEnchantments' 26.3 codec supports enchantment levels from 1 through 255. */
    public static final int MAX_STORED_LEVEL = 255;
    private static final int DEFAULT_POTION_DURATION = 600;
    private static final URI MODRINTH_URI = URI.create("https://modrinth.com/mod/nbteditcommands");
    private static final URI DISCORD_URI = URI.create("https://discord.gg/HSbM76c64f");
    // Vanilla attribute fallback list for Java 26.3; the live registry supplies modded entries.
    private static final List<String> ATTRIBUTE_SUGGESTIONS = List.of(
            "air_drag_modifier", "armor", "armor_toughness", "attack_damage", "attack_knockback",
            "attack_speed", "below_name_distance", "block_break_speed",
            "block_interaction_range", "bounciness", "burning_time", "camera_distance",
            "entity_interaction_range", "explosion_knockback_resistance", "fall_damage_multiplier",
            "flying_speed", "follow_range", "friction_modifier", "gravity", "jump_strength",
            "knockback_resistance", "luck", "max_absorption", "max_health", "mining_efficiency",
            "movement_efficiency", "movement_speed", "name_tag_distance", "oxygen_bonus",
            "safe_fall_distance", "scale", "sneaking_speed", "spawn_reinforcements", "step_height",
            "submerged_mining_speed", "sweeping_damage_ratio", "tempt_range",
            "water_movement_efficiency", "waypoint_receive_range", "waypoint_transmit_range"
    );
    private static final List<String> ATTRIBUTE_OPERATIONS = List.of(
            "add_value", "add_multiplied_base", "add_multiplied_total");
    private static final List<String> ATTRIBUTE_SLOTS = List.of(
            "any", "hand", "mainhand", "offhand", "armor", "head", "chest", "legs", "feet", "body", "saddle");
    private static final List<String> ATTRIBUTE_DISPLAYS = List.of("default", "hidden", "override");
    private static final List<String> BOOLEAN_SUGGESTIONS = List.of("true", "false");
    private static final List<String> DURABILITY_TYPES = List.of("set", "damage", "add", "remove", "percentage");


    private EnchantPlusCommands() {
    }

    /**
     * Register all commands. Registering the same Brigadier argument path as vanilla /enchant
     * intentionally merges into that node and replaces its execution callbacks, bypassing the
     * vanilla item-compatibility, conflict, duplicate and enchantment-max-level checks.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext) {
        registerEnchantedItemCommand(dispatcher, buildContext);
        registerBookCommand(dispatcher, buildContext);
        registerRemovalCommand(dispatcher, buildContext, "delenchant");
        registerRemovalCommand(dispatcher, buildContext, "denchant");
        registerAttributeCommands(dispatcher, buildContext);
        registerPotionCommand(dispatcher, buildContext);
        registerDurabilityCommand(dispatcher);
        registerItemNameCommand(dispatcher);
        registerItemLoreCommand(dispatcher);
        registerInfoCommand(dispatcher);
        registerCopyNbtCommands(dispatcher);
    }

    // -------------------------------------------------------------------------
    // Copy NBT data. The full data is shown in chat and has vanilla's
    // copy_to_clipboard click event. Clients with this mod also receive a
    // negotiated custom payload and copy the value immediately.
    // -------------------------------------------------------------------------

    private static void registerCopyNbtCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerCopyItemCommand(dispatcher, "copynbt", true);
        registerCopyItemCommand(dispatcher, "cni", false);
        registerCopyEntityCommand(dispatcher, "copynbt", true);
        registerCopyEntityCommand(dispatcher, "cne", false);
        registerCopyBlockCommand(dispatcher, "copynbt", true);
        registerCopyBlockCommand(dispatcher, "cnb", false);
    }

    private static void registerCopyItemCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                 String root, boolean withSubcommand) {
        var itemCommand = Commands.literal("item")
                .executes(context -> copyHeldItem(context, "nbt"))
                .then(buildOptionalItemTargetBranch());

        var command = Commands.literal(root).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        if (withSubcommand) {
            command.then(itemCommand);
        } else {
            command.executes(context -> copyHeldItem(context, "nbt"))
                    .then(buildOptionalItemTargetBranch());
        }
        dispatcher.register(command);
    }

    /**
     * The first optional token is parsed as a string so its suggestions can be
     * restricted to player targets/selectors. "nbt" and "name" remain valid
     * manual shorthand tokens for copying the command sender's own item.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    buildOptionalItemTargetBranch() {
        return Commands.argument("target", StringArgumentType.word())
                .suggests((context, builder) -> {
                    List<String> suggestions = new ArrayList<>();
                    suggestions.add("@s");
                    suggestions.add("@p");
                    suggestions.add("@r");
                    suggestions.add("@a");
                    context.getSource().getServer().getPlayerList().getPlayers()
                            .forEach(player -> suggestions.add(player.getGameProfile().name()));
                    return SharedSuggestionProvider.suggest(suggestions, builder);
                })
                .executes(EnchantPlusCommands::copyItemFromOptionalTargetToken)
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                List.of("nbt", "name"), builder))
                        .executes(EnchantPlusCommands::copyItemFromTargetAndType));
    }

    private static int copyItemFromOptionalTargetToken(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String token = StringArgumentType.getString(context, "target");
        if (token.equalsIgnoreCase("nbt") || token.equalsIgnoreCase("name")) {
            return copyHeldItem(context, token.toLowerCase(Locale.ROOT));
        }
        return copyTargetItem(context, resolveSinglePlayer(context, token), "nbt");
    }

    private static int copyItemFromTargetAndType(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String targetToken = StringArgumentType.getString(context, "target");
        if (targetToken.equalsIgnoreCase("nbt") || targetToken.equalsIgnoreCase("name")) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.copy_target_required");
        }
        String type = StringArgumentType.getString(context, "type").toLowerCase(Locale.ROOT);
        if (!type.equals("nbt") && !type.equals("name")) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.copy_invalid_type");
        }
        return copyTargetItem(context, resolveSinglePlayer(context, targetToken), type);
    }

    private static ServerPlayer resolveSinglePlayer(CommandContext<CommandSourceStack> context, String token)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        EntitySelector selector = new EntitySelectorParser(new StringReader(token), true).parse();
        return selector.findSinglePlayer(context.getSource());
    }

    private static void registerCopyEntityCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                  String root, boolean withSubcommand) {
        var entityArgument = Commands.argument("target", EntityArgument.entity())
                .executes(context -> {
                    Entity entity = EntityArgument.getEntity(context, "target");
                    TagValueOutput output = TagValueOutput.createWithContext(
                            ProblemReporter.DISCARDING, entity.registryAccess());
                    entity.saveWithoutId(output);
                    Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                    if (entityId != null) {
                        output.putString("id", entityId.toString());
                    }
                    CompoundTag tag = output.buildResult();
                    return sendClipboard(context, tag.toString());
                });
        var command = Commands.literal(root).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        if (withSubcommand) {
            command.then(Commands.literal("entity").then(entityArgument));
        } else {
            command.then(entityArgument);
        }
        dispatcher.register(command);
    }

    private static void registerCopyBlockCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                 String root, boolean withSubcommand) {
        var positionArgument = Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> {
                    BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                    var level = context.getSource().getLevel();
                    BlockState state = level.getBlockState(pos);
                    StringBuilder value = new StringBuilder(serializeBlockState(state));
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        CompoundTag tag = blockEntity.saveWithFullMetadata(context.getSource().registryAccess());
                        value.append(tag);
                    }
                    return sendClipboard(context, value.toString());
                });
        var command = Commands.literal(root).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        if (withSubcommand) {
            command.then(Commands.literal("block").then(positionArgument));
        } else {
            command.then(positionArgument);
        }
        dispatcher.register(command);
    }

    private static String serializeBlockState(BlockState state) {
        return state.getValues()
                .map(value -> serializeStateProperty(value.property(), value.value()))
                .sorted()
                .collect(Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String serializeStateProperty(Property<?> property, Comparable<?> value) {
        Property rawProperty = property;
        return property.getName() + "=" + rawProperty.getName((Comparable) value);
    }

    private static int copyHeldItem(CommandContext<CommandSourceStack> context, String type)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return copyItem(context, player, type);
    }

    private static int copyTargetItem(CommandContext<CommandSourceStack> context, ServerPlayer target, String type)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return copyItem(context, target, type);
    }

    private static int copyItem(CommandContext<CommandSourceStack> context, ServerPlayer target, String type)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_held_item");
        }
        String value;
        if ("name".equals(type)) {
            value = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, stack.getHoverName())
                    .result().orElseThrow().toString();
        } else {
            TagValueOutput output = TagValueOutput.createWithContext(
                    ProblemReporter.DISCARDING, context.getSource().registryAccess());
            output.store(ItemStack.MAP_CODEC, stack);
            value = output.buildResult().toString();
        }
        return sendClipboard(context, value);
    }

    private static int sendClipboard(CommandContext<CommandSourceStack> context, String value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (value.getBytes(StandardCharsets.UTF_8).length > ClipboardPayload.MAX_LENGTH) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.copy_too_large",
                    ClipboardPayload.MAX_LENGTH);
        }

        // Only send a custom packet when the command sender has this mod installed.
        // Vanilla clients simply receive the normal clickable chat component below.
        if (context.getSource().getEntity() instanceof ServerPlayer sender) {
            ClipboardTransport.sendIfSupported(sender, value);
        }

        Component clickableData = Component.literal(value)
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(value))
                        .withHoverEvent(new HoverEvent.ShowText(
                                CommandLocalization.translate(context.getSource(), "commands.enchantplus.copy.hover"))));
        Component message = CommandLocalization.translate(context.getSource(), "commands.enchantplus.copy.ready")
                .append(Component.literal("\n"))
                .append(clickableData);
        context.getSource().sendSuccess(() -> message, false);
        return 1;
    }

    /** Shows official project links. This informational command is intentionally available without admin permission. */
    private static void registerInfoCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nbtedit")
                .executes(context -> {
                    Component message = CommandLocalization.translate(context.getSource(), "commands.enchantplus.info.header")
                            .append(Component.literal("\n"))
                            .append(CommandLocalization.translate(context.getSource(), "commands.enchantplus.info.modrinth")
                                    .withStyle(style -> style
                                            .withColor(ChatFormatting.GREEN)
                                            .withUnderlined(true)
                                            .withClickEvent(new ClickEvent.OpenUrl(MODRINTH_URI))
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                    CommandLocalization.translate(context.getSource(), "commands.enchantplus.info.hover_modrinth")))))
                            .append(Component.literal("  |  "))
                            .append(CommandLocalization.translate(context.getSource(), "commands.enchantplus.info.discord")
                                    .withStyle(style -> style
                                            .withColor(ChatFormatting.LIGHT_PURPLE)
                                            .withUnderlined(true)
                                            .withClickEvent(new ClickEvent.OpenUrl(DISCORD_URI))
                                            .withHoverEvent(new HoverEvent.ShowText(
                                                    CommandLocalization.translate(context.getSource(), "commands.enchantplus.info.hover_discord")))));
                    context.getSource().sendSuccess(() -> message, false);
                    return 1;
                }));
    }

    private static void registerEnchantedItemCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                     CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("enchant")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("enchantment", ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                                .executes(context -> apply(context, 1, DataComponents.ENCHANTMENTS))
                                // Keep the same argument type/name as vanilla so this node merges cleanly.
                                .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                        .executes(context -> apply(context,
                                                IntegerArgumentType.getInteger(context, "level"),
                                                DataComponents.ENCHANTMENTS))))));
    }

    private static void registerBookCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                            CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("benchant")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                // New explicit subcommands, matching /potion and /itemattribute.
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("enchantment", ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                                        .executes(context -> applyToBooks(context, 1))
                                        .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                                .executes(context -> applyToBooks(context,
                                                        IntegerArgumentType.getInteger(context, "level")))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(EnchantPlusCommands::removeAllBookEnchantments)
                                .then(Commands.argument("enchantment", ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                                        .executes(EnchantPlusCommands::removeOneBookEnchantment)))));
    }

    private static void registerRemovalCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                               CommandBuildContext buildContext,
                                               String commandName) {
        dispatcher.register(Commands.literal(commandName)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("targets", EntityArgument.entities())
                        // No enchantment argument = clear every active and stored enchantment.
                        .executes(EnchantPlusCommands::removeAll)
                        .then(Commands.argument("enchantment", ResourceArgument.resource(buildContext, Registries.ENCHANTMENT))
                                .executes(EnchantPlusCommands::removeOne))));
    }

    private static int apply(CommandContext<CommandSourceStack> context,
                             int level,
                             DataComponentType<ItemEnchantments> component) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        checkLevel(context, level);
        Holder<Enchantment> enchantment = ResourceArgument.getEnchantment(context, "enchantment");
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        int changed = 0;

        for (Entity target : targets) {
            if (!(target instanceof LivingEntity living)) {
                continue;
            }
            ItemStack stack = living.getMainHandItem();
            if (stack.isEmpty()) {
                continue;
            }

            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(
                    stack.getOrDefault(component, ItemEnchantments.EMPTY));
            // set() replaces an existing level but does not enforce compatibility or exclusivity.
            enchantments.set(enchantment, level);
            stack.set(component, enchantments.toImmutable());
            changed++;
            Component targetName = target.getDisplayName();
            Component enchantmentName = Component.literal(enchantment.unwrapKey()
                    .map(ResourceKey::identifier).map(Identifier::toString).orElse("unknown"));
            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.enchant", targetName, enchantmentName, level);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_held_item");
        }
        return changed;
    }

    private static int applyToBooks(CommandContext<CommandSourceStack> context,
                                    int level) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        checkLevel(context, level);
        Holder<Enchantment> enchantment = ResourceArgument.getEnchantment(context, "enchantment");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int changed = 0;

        for (ServerPlayer player : targets) {
            ItemStack held = player.getMainHandItem();
            if (held.is(Items.ENCHANTED_BOOK)) {
                applyStoredEnchantment(held, enchantment, level);
                changed++;
                continue;
            }
            if (!held.is(Items.BOOK)) {
                continue;
            }

            // Convert exactly one ordinary book. If the player held a stack, keep the remainder
            // in hand and put the new enchanted book into inventory (or drop it if full).
            ItemStack enchantedBook = held.transmuteCopy(Items.ENCHANTED_BOOK, 1);
            held.shrink(1);
            applyStoredEnchantment(enchantedBook, enchantment, level);
            if (held.isEmpty()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, enchantedBook);
            } else if (!player.getInventory().add(enchantedBook)) {
                player.spawnAtLocation(player.level(), enchantedBook);
            }
            changed++;
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_book");
        }
        final int changedCount = changed;
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.book", changedCount, level);
        return changed;
    }

    private static void applyStoredEnchantment(ItemStack stack, Holder<Enchantment> enchantment, int level) {
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(
                stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
        enchantments.set(enchantment, level);
        stack.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
    }

    private static int removeAllBookEnchantments(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int changed = 0;

        for (ServerPlayer player : targets) {
            ItemStack stack = player.getMainHandItem();
            if (!stack.is(Items.ENCHANTED_BOOK)) {
                continue;
            }

            ItemEnchantments stored = stack.getOrDefault(
                    DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (stored.equals(ItemEnchantments.EMPTY)) {
                continue;
            }

            // Remove only the book's stored enchantments. Never touch ordinary item enchantments.
            stack.remove(DataComponents.STORED_ENCHANTMENTS);
            changed++;
            Component playerName = player.getDisplayName();
            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.book_remove_all", playerName);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.nothing_to_remove");
        }
        return changed;
    }

    private static int removeOneBookEnchantment(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Holder<Enchantment> enchantment = ResourceArgument.getEnchantment(context, "enchantment");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int changed = 0;

        for (ServerPlayer player : targets) {
            ItemStack stack = player.getMainHandItem();
            if (!stack.is(Items.ENCHANTED_BOOK)) {
                continue;
            }

            if (removeFromComponent(stack, DataComponents.STORED_ENCHANTMENTS, enchantment)) {
                changed++;
                Component playerName = player.getDisplayName();
                Component enchantmentName = Component.literal(enchantment.unwrapKey()
                        .map(ResourceKey::identifier).map(Identifier::toString).orElse("unknown"));
                CommandLocalization.sendSuccess(context, true,
                        "commands.enchantplus.success.book_remove_one", playerName, enchantmentName);
            }
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.nothing_to_remove");
        }
        return changed;
    }

    private static void checkLevel(CommandContext<CommandSourceStack> context, int level) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (level < 1 || level > MAX_STORED_LEVEL) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_level", MAX_STORED_LEVEL, level);
        }
    }

    private static int removeAll(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        int changed = 0;

        for (Entity target : targets) {
            if (!(target instanceof LivingEntity living)) {
                continue;
            }
            ItemStack stack = living.getMainHandItem();
            if (stack.isEmpty()) {
                continue;
            }
            boolean removed = stack.remove(DataComponents.ENCHANTMENTS) != null;
            removed |= stack.remove(DataComponents.STORED_ENCHANTMENTS) != null;
            if (removed) {
                changed++;
                Component targetName = target.getDisplayName();
                CommandLocalization.sendSuccess(context, true,
                        "commands.enchantplus.success.remove_all", targetName);
            }
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.nothing_to_remove");
        }
        return changed;
    }

    private static int removeOne(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Holder<Enchantment> enchantment = ResourceArgument.getEnchantment(context, "enchantment");
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        int changed = 0;

        for (Entity target : targets) {
            if (!(target instanceof LivingEntity living)) {
                continue;
            }
            ItemStack stack = living.getMainHandItem();
            if (stack.isEmpty()) {
                continue;
            }
            boolean removed = removeFromComponent(stack, DataComponents.ENCHANTMENTS, enchantment);
            removed |= removeFromComponent(stack, DataComponents.STORED_ENCHANTMENTS, enchantment);
            if (removed) {
                changed++;
                Component targetName = target.getDisplayName();
                Component enchantmentName = Component.literal(enchantment.unwrapKey()
                        .map(ResourceKey::identifier).map(Identifier::toString).orElse("unknown"));
                CommandLocalization.sendSuccess(context, true,
                        "commands.enchantplus.success.remove_one", targetName, enchantmentName);
            }
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.nothing_to_remove");
        }
        return changed;
    }

    private static boolean removeFromComponent(ItemStack stack,
                                               DataComponentType<ItemEnchantments> component,
                                               Holder<Enchantment> enchantment) {
        ItemEnchantments current = stack.getOrDefault(component, ItemEnchantments.EMPTY);
        if (current.getLevel(enchantment) <= 0) {
            return false;
        }
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
        mutable.removeIf(existing -> existing.equals(enchantment));
        stack.set(component, mutable.toImmutable());
        return true;
    }

    // -------------------------------------------------------------------------
    // Item name and lore (26.3 data components)
    // -------------------------------------------------------------------------

    private static void registerItemNameCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("itemname")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("reset")
                        .executes(EnchantPlusCommands::resetItemName))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(EnchantPlusCommands::noSuggestions)
                        .executes(EnchantPlusCommands::setItemName)));
    }

    private static void registerItemLoreCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("itemlore")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("reset")
                        .executes(EnchantPlusCommands::resetItemLore))
                .then(Commands.argument("line", IntegerArgumentType.integer(1, ItemLore.MAX_LINES))
                        .suggests(EnchantPlusCommands::noSuggestions)
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                .suggests(EnchantPlusCommands::noSuggestions)
                                .executes(EnchantPlusCommands::setItemLoreLine))));
    }

    private static ItemStack getCommandingPlayerMainHand(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = context.getSource().getPlayerOrException().getMainHandItem();
        if (stack.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_held_item");
        }
        return stack;
    }

    private static Component parseJsonTextComponent(CommandContext<CommandSourceStack> context, String json)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            var parsed = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
            if (parsed.result().isEmpty()) {
                throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_text_component");
            }
            return parsed.result().get();
        } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_text_component");
        }
    }

    private static int setItemName(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = getCommandingPlayerMainHand(context);
        String json = StringArgumentType.getString(context, "name").trim();
        if (json.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_text_component");
        }
        Component name = parseJsonTextComponent(context, json);
        stack.set(DataComponents.ITEM_NAME, name);
        Component playerName = context.getSource().getPlayerOrException().getDisplayName();
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.itemname_set", playerName);
        return 1;
    }

    private static int resetItemName(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = getCommandingPlayerMainHand(context);

        // ITEM_NAME is part of the item's prototype/default component map in modern Minecraft.
        // ItemStack.remove(...) creates an explicit removal patch for prototype components,
        // which can make the name appear empty. Restore the prototype value instead: the
        // patched component map collapses values equal to the prototype, so no item-specific
        // item_name override remains and the item falls back to its original translated name.
        Component originalItemName = stack.getPrototype().get(DataComponents.ITEM_NAME);
        if (originalItemName != null) {
            stack.set(DataComponents.ITEM_NAME, originalItemName);
        } else {
            // For unusual items without a prototype item_name, remove only an item-specific value.
            stack.remove(DataComponents.ITEM_NAME);
        }

        Component playerName = context.getSource().getPlayerOrException().getDisplayName();
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.itemname_reset", playerName);
        return 1;
    }

    private static int setItemLoreLine(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = getCommandingPlayerMainHand(context);
        int lineNumber = IntegerArgumentType.getInteger(context, "line");
        String input = StringArgumentType.getString(context, "description").trim();
        if (input.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_text_component");
        }

        ItemLore currentLore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> lines = new ArrayList<>(currentLore.lines());
        String operation = input.toLowerCase(Locale.ROOT);
        String messageKey;

        if (operation.equals("remove")) {
            if (lineNumber > lines.size()) {
                throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_lore_line", lineNumber);
            }
            lines.remove(lineNumber - 1);
            messageKey = "commands.enchantplus.success.itemlore_remove";
        } else if (operation.equals("clear")) {
            if (lineNumber > lines.size()) {
                throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_lore_line", lineNumber);
            }
            lines.set(lineNumber - 1, Component.empty());
            messageKey = "commands.enchantplus.success.itemlore_clear";
        } else {
            Component description = parseJsonTextComponent(context, input);
            // Setting a later line fills intervening positions with empty components so that
            // line numbers remain stable instead of silently shifting existing lore entries.
            while (lines.size() < lineNumber - 1) {
                lines.add(Component.empty());
            }
            if (lineNumber <= lines.size()) {
                lines.set(lineNumber - 1, description);
            } else {
                lines.add(description);
            }
            messageKey = "commands.enchantplus.success.itemlore_set";
        }

        if (lines.isEmpty()) {
            stack.remove(DataComponents.LORE);
        } else {
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }

        Component playerName = context.getSource().getPlayerOrException().getDisplayName();
        CommandLocalization.sendSuccess(context, true, messageKey, playerName, lineNumber);
        return 1;
    }

    private static int resetItemLore(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = getCommandingPlayerMainHand(context);
        stack.remove(DataComponents.LORE);
        Component playerName = context.getSource().getPlayerOrException().getDisplayName();
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.itemlore_reset", playerName);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Item attribute modifiers
    // -------------------------------------------------------------------------

    private static void registerAttributeCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                                  CommandBuildContext buildContext) {
        for (String name : List.of("itemattribute", "itemattr", "attrmod")) {
            dispatcher.register(Commands.literal(name)
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.literal("add")
                            .then(Commands.argument("target", EntityArgument.player())
                                    .then(createAttributeTypeNode(buildContext))))
                    .then(Commands.literal("remove")
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(EnchantPlusCommands::removeAllAttributes)
                                    .then(Commands.argument("type", ResourceArgument.resource(buildContext, Registries.ATTRIBUTE))
                                            .suggests((context, builder) -> suggestAttributeTypes(context, builder))
                                            .executes(EnchantPlusCommands::removeAttribute)
                                            .then(Commands.argument("id", StringArgumentType.word())
                                                    .suggests((context, builder) -> suggestAttributeIds(context, builder))
                                                    .executes(EnchantPlusCommands::removeAttribute))))));
        }
    }

    /**
     * Attribute arguments are deliberately individual Brigadier nodes, not one greedy string.
     * This keeps each completion list confined to its own positional field:
     * type -> amount -> id -> operation -> slot -> display.
     * Amount and ID may each be omitted; the other fields use their documented defaults when
     * the command is executed at an earlier node.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>
    createAttributeTypeNode(CommandBuildContext buildContext) {
        var type = Commands.argument("type", ResourceArgument.resource(buildContext, Registries.ATTRIBUTE))
                .suggests((context, builder) -> suggestAttributeTypes(context, builder))
                .executes(EnchantPlusCommands::addAttribute);

        // Amount can be skipped. Both positions deliberately have empty completion lists.
        type.then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                .suggests(EnchantPlusCommands::noSuggestions)
                .executes(EnchantPlusCommands::addAttribute)
                .then(createAttributeIdNode()));
        type.then(createAttributeIdNode());
        return type;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    createAttributeIdNode() {
        var id = Commands.argument("id", StringArgumentType.word())
                .suggests(EnchantPlusCommands::noSuggestions)
                .executes(EnchantPlusCommands::addAttribute);
        id.then(createAttributeOperationNode());
        return id;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    createAttributeOperationNode() {
        var operation = Commands.argument("operation", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ATTRIBUTE_OPERATIONS, builder))
                .executes(EnchantPlusCommands::addAttribute);
        operation.then(createAttributeSlotNode());
        return operation;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    createAttributeSlotNode() {
        var slot = Commands.argument("slot", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ATTRIBUTE_SLOTS, builder))
                .executes(EnchantPlusCommands::addAttribute);
        slot.then(createAttributeDisplayNode());
        return slot;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    createAttributeDisplayNode() {
        return Commands.argument("display", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(ATTRIBUTE_DISPLAYS, builder))
                .executes(EnchantPlusCommands::addAttribute);
    }

    private static CompletableFuture<Suggestions> noSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // An explicit empty provider hides Brigadier's fallback parameter-name hints
        // (for example "color", "effect", "amount" or "show_particles").
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestAttributeTypes(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        // Prefer the live registry so attributes added by other mods are included.
        try {
            HolderLookup.RegistryLookup<Attribute> registry = context.getSource().registryAccess()
                    .lookupOrThrow(Registries.ATTRIBUTE);
            registry.listElementIds().forEach(key -> {
                String id = key.identifier().toString();
                candidates.add(id);
                candidates.add(key.identifier().getPath());
            });
        } catch (RuntimeException ignored) {
            // The static vanilla list remains available before registry lookups are ready.
        }
        for (String id : ATTRIBUTE_SUGGESTIONS) {
            candidates.add("minecraft:" + id);
            candidates.add(id);
        }
        return SharedSuggestionProvider.suggest(candidates, builder);
    }

    private static CompletableFuture<Suggestions> suggestAttributeIds(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(context, "target");
            ItemStack stack = target.getMainHandItem();
            Holder.Reference<Attribute> attribute = ResourceArgument.getResource(context, "type", Registries.ATTRIBUTE);
            ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                    ItemAttributeModifiers.EMPTY);
            current.modifiers().stream()
                    .filter(entry -> entry.attribute().equals(attribute))
                    .forEach(entry -> builder.suggest(entry.modifier().id().toString()));
            return builder.buildFuture();
        } catch (Exception ignored) {
            // Suggestions are advisory; command execution still validates the supplied ID.
        }
        return builder.buildFuture();
    }

    private static int addAttribute(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_attribute_item");
        }

        Holder.Reference<Attribute> attribute = ResourceArgument.getResource(context, "type", Registries.ATTRIBUTE);
        String typeInput = attribute.unwrapKey().map(ResourceKey::identifier)
                .map(Identifier::toString).orElse("unknown");
        double amount = doubleArgumentOrDefault(context, "amount", 1.0);
        if (!Double.isFinite(amount)) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_attribute_amount");
        }

        String idInput = stringArgumentOrDefault(context, "id", typeInput);
        Identifier modifierId = Identifier.tryParse(idInput);
        if (modifierId == null) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_attribute_id");
        }

        String operationInput = stringArgumentOrDefault(context, "operation", "add_value");
        if (!isOperation(operationInput)) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_attribute_operation");
        }
        AttributeModifier.Operation operation = parseOperation(operationInput);

        String slotInput = stringArgumentOrDefault(context, "slot", "any");
        if (!isSlot(slotInput)) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_attribute_slot");
        }
        EquipmentSlotGroup slot = parseSlot(slotInput);

        String displayName = stringArgumentOrDefault(context, "display", "default");
        if (!isDisplay(displayName)) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_attribute_display");
        }
        displayName = displayName.toLowerCase(Locale.ROOT);

        ItemAttributeModifiers.Display display = switch (displayName) {
            case "hidden" -> ItemAttributeModifiers.Display.hidden();
            case "override" -> ItemAttributeModifiers.Display.override(Component.literal(modifierId.toString()));
            default -> ItemAttributeModifiers.Display.attributeModifiers();
        };

        ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        List<ItemAttributeModifiers.Entry> entries = new ArrayList<>(current.modifiers());
        Identifier finalModifierId = modifierId;
        entries.removeIf(entry -> entry.matches(attribute, finalModifierId));
        entries.add(new ItemAttributeModifiers.Entry(attribute,
                new AttributeModifier(modifierId, amount, operation), slot, display));
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(entries));

        final double finalAmount = amount;
        final String finalId = modifierId.toString();
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.attribute_add", target.getName(), typeInput,
                finalAmount, finalId);
        return 1;
    }

    private static double doubleArgumentOrDefault(CommandContext<CommandSourceStack> context,
                                                   String name, double fallback) {
        try {
            return context.getArgument(name, Double.class);
        } catch (IllegalArgumentException missingArgument) {
            return fallback;
        }
    }

    private static String stringArgumentOrDefault(CommandContext<CommandSourceStack> context,
                                                   String name, String fallback) {
        try {
            return context.getArgument(name, String.class);
        } catch (IllegalArgumentException missingArgument) {
            return fallback;
        }
    }

    private static int removeAllAttributes(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_attribute_item");
        }

        ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        if (current.modifiers().isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_attribute_modifier");
        }

        // Keep an explicit empty component so prototype/default modifiers are also suppressed.
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.attribute_remove_all", target.getDisplayName());
        return 1;
    }

    private static int removeAttribute(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_attribute_item");
        }

        Holder.Reference<Attribute> attribute = ResourceArgument.getResource(context, "type", Registries.ATTRIBUTE);
        String typeInput = attribute.unwrapKey().map(ResourceKey::identifier)
                .map(Identifier::toString).orElse("unknown");
        String idInput = stringArgumentOrDefault(context, "id", "");
        Identifier requestedId = idInput.isEmpty() ? null : Identifier.tryParse(idInput);
        if (!idInput.isEmpty() && requestedId == null) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_attribute_id");
        }

        ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        List<ItemAttributeModifiers.Entry> entries = new ArrayList<>(current.modifiers());
        Identifier idToRemove = requestedId;
        boolean removed;
        if (idToRemove == null) {
            removed = entries.removeIf(entry -> entry.attribute().equals(attribute));
        } else {
            removed = entries.removeIf(entry -> entry.matches(attribute, idToRemove));
        }
        if (!removed) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_attribute_modifier");
        }
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(entries));
        CommandLocalization.sendSuccess(context, true,
                "commands.enchantplus.success.attribute_remove", target.getName(), typeInput);
        return 1;
    }

    private static boolean isOperation(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "add_value", "add_multiplied_base", "add_multiplied_total" -> true;
            default -> false;
        };
    }

    private static AttributeModifier.Operation parseOperation(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static boolean isSlot(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "any", "hand", "mainhand", "offhand", "armor", "head", "chest", "legs", "feet", "body", "saddle" -> true;
            default -> false;
        };
    }

    private static EquipmentSlotGroup parseSlot(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "hand" -> EquipmentSlotGroup.HAND;
            case "mainhand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "head" -> EquipmentSlotGroup.HEAD;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "body" -> EquipmentSlotGroup.BODY;
            case "saddle" -> EquipmentSlotGroup.SADDLE;
            default -> EquipmentSlotGroup.ANY;
        };
    }

    private static boolean isDisplay(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "default", "hidden", "override" -> true;
            default -> false;
        };
    }

        // -------------------------------------------------------------------------
    // Potion contents (potions and tipped arrows share this data component)
    // -------------------------------------------------------------------------

    private static void registerPotionCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                              CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("potion")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.players())
                                // A greedy vanilla string argument accepts '#RRGGBB' without
                                // advertising a custom argument type to the client. The tail is
                                // parsed by this command and suggestions are scoped by token index.
                                .then(Commands.argument("arguments", StringArgumentType.greedyString())
                                        .suggests(EnchantPlusCommands::suggestPotionArguments)
                                        .executes(EnchantPlusCommands::executePotionAdd))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(EnchantPlusCommands::removeAllPotionEffects)
                                .then(Commands.argument("effect", ResourceArgument.resource(buildContext, Registries.MOB_EFFECT))
                                        .suggests((context, builder) -> suggestPotionEffects(context, builder))
                                        .executes(EnchantPlusCommands::removePotionEffect)))));
    }

    private static CompletableFuture<Suggestions> suggestPotionArguments(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        String completedText = lastSpace < 0 ? "" : remaining.substring(0, lastSpace).trim();
        String currentToken = lastSpace < 0 ? remaining : remaining.substring(lastSpace + 1);
        String[] completed = completedText.isEmpty() ? new String[0] : completedText.split("\\s+");
        int currentIndex = completed.length;
        int suggestionStart = lastSpace < 0 ? builder.getStart() : builder.getStart() + lastSpace + 1;
        SuggestionsBuilder currentBuilder = builder.createOffset(suggestionStart);

        // A color token is deliberately free-form: don't suggest "color" or any values here.
        boolean currentLooksLikeColor = currentIndex == 0 && isColorToken(currentToken);
        boolean hasColor = completed.length > 0 && isColorToken(completed[0]);
        int effectIndex = hasColor ? 1 : 0;
        if (!currentLooksLikeColor && currentIndex == effectIndex) {
            return suggestPotionEffects(context, currentBuilder);
        }

        // Only the three boolean positions show candidates, and only true/false are displayed.
        int firstBooleanIndex = effectIndex + 3; // effect, amplifier, duration precede show_particles
        if (currentIndex >= firstBooleanIndex && currentIndex <= firstBooleanIndex + 2) {
            return SharedSuggestionProvider.suggest(BOOLEAN_SUGGESTIONS, currentBuilder);
        }
        return currentBuilder.buildFuture();
    }

    private static boolean isColorToken(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.startsWith("#")) return true;
        return token.chars().allMatch(Character::isDigit);
    }

    private static int executePotionAdd(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String raw = StringArgumentType.getString(context, "arguments").trim();
        if (raw.isEmpty()) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_options");

        String[] tokens = raw.split("\\s+");
        int cursor = 0;
        String colorInput = null;
        if (isColorToken(tokens[0])) {
            colorInput = tokens[cursor++];
        }
        if (cursor >= tokens.length) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_options");

        String effectId = tokens[cursor++];
        Holder<MobEffect> effect = resolvePotionEffect(context, effectId);
        int amplifier = cursor < tokens.length ? parsePotionInteger(context, tokens[cursor++], "amplifier") : 0;
        int duration = cursor < tokens.length ? parsePotionInteger(context, tokens[cursor++], "duration") : DEFAULT_POTION_DURATION;
        boolean showParticles = cursor < tokens.length ? parseBoolean(context, tokens[cursor++]) : true;
        boolean showIcon = cursor < tokens.length ? parseBoolean(context, tokens[cursor++]) : true;
        boolean ambient = cursor < tokens.length ? parseBoolean(context, tokens[cursor++]) : false;
        if (cursor != tokens.length) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_options");

        return applyPotion(context, effect, colorInput, amplifier, duration, showParticles, showIcon, ambient);
    }

    private static int parsePotionInteger(CommandContext<CommandSourceStack> context, String token, String argumentName)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException exception) {
            if ("amplifier".equals(argumentName)) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_amplifier", token);
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_duration", token);
        }
    }

    private static Holder<MobEffect> resolvePotionEffect(CommandContext<CommandSourceStack> context,
                                                           String effectId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Identifier identifier = Identifier.tryParse(effectId);
        if (identifier == null) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_effect", effectId);
        ResourceKey<MobEffect> key = ResourceKey.create(Registries.MOB_EFFECT, identifier);
        var found = context.getSource().registryAccess().lookupOrThrow(Registries.MOB_EFFECT).get(key);
        if (found.isEmpty()) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_effect", effectId);
        return found.get();
    }

    private static CompletableFuture<Suggestions> suggestPotionEffects(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        try {
            HolderLookup.RegistryLookup<MobEffect> registry = context.getSource().registryAccess()
                    .lookupOrThrow(Registries.MOB_EFFECT);
            registry.listElementIds().forEach(key -> builder.suggest(key.identifier().toString()));
        } catch (RuntimeException ignored) {
            // The server's live registry is normally available during command completion.
        }
        return builder.buildFuture();
    }

    private static int applyPotion(CommandContext<CommandSourceStack> context,
                                   Holder<MobEffect> effect,
                                   String colorInput,
                                   int amplifier,
                                   int duration,
                                   boolean showParticles,
                                   boolean showIcon,
                                   boolean ambient)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        if (amplifier < 0 || amplifier > 255) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_amplifier", amplifier);
        }
        if (duration < -1) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_duration", duration);
        }
        Integer parsedColor = colorInput == null ? null : parseColor(context, colorInput);
        int changed = 0;

        for (ServerPlayer player : targets) {
            ItemStack stack = player.getMainHandItem();
            if (!isPotionItem(stack)) {
                continue;
            }

            PotionContents current = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            List<MobEffectInstance> effects = new ArrayList<>();
            for (MobEffectInstance existing : current.getAllEffects()) {
                if (!existing.is(effect)) {
                    effects.add(new MobEffectInstance(existing));
                }
            }
            effects.add(new MobEffectInstance(effect, duration, amplifier, ambient, showParticles, showIcon));

            Optional<Integer> color = parsedColor == null ? current.customColor() : Optional.of(parsedColor);
            PotionContents updated = new PotionContents(Optional.empty(), color, effects, current.customName());
            stack.set(DataComponents.POTION_CONTENTS, updated);
            changed++;

            Component playerName = player.getDisplayName();
            Component itemKind = Component.translatable(stack.getItem().getDescriptionId());
            Component effectName = Component.literal(effect.unwrapKey().map(ResourceKey::identifier)
                    .map(Identifier::toString).orElse(""));
            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.potion", playerName, itemKind, effectName,
                    amplifier, duration);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.potion_item_required");
        }
        return changed;
    }

    private static int removeAllPotionEffects(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int changed = 0;

        for (ServerPlayer player : targets) {
            ItemStack stack = player.getMainHandItem();
            if (!isPotionItem(stack)) {
                continue;
            }

            PotionContents current = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (!current.hasEffects()) {
                continue;
            }

            // Clear both the base potion and custom effect list, but keep its custom color/name.
            PotionContents updated = new PotionContents(Optional.empty(), current.customColor(), List.of(),
                    current.customName());
            stack.set(DataComponents.POTION_CONTENTS, updated);
            changed++;

            Component itemKind = Component.translatable(stack.getItem().getDescriptionId());
            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.potion_remove_all", player.getDisplayName(), itemKind);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.potion_effect_not_found");
        }
        return changed;
    }

    private static int removePotionEffect(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        Holder<MobEffect> effect = ResourceArgument.getResource(context, "effect", Registries.MOB_EFFECT);
        int changed = 0;

        for (ServerPlayer player : targets) {
            ItemStack stack = player.getMainHandItem();
            if (!isPotionItem(stack)) {
                continue;
            }

            PotionContents current = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            List<MobEffectInstance> effects = new ArrayList<>();
            boolean removed = false;
            for (MobEffectInstance existing : current.getAllEffects()) {
                if (existing.is(effect)) {
                    removed = true;
                } else {
                    effects.add(new MobEffectInstance(existing));
                }
            }
            if (!removed) {
                continue;
            }
            PotionContents updated = new PotionContents(Optional.empty(), current.customColor(), effects, current.customName());
            stack.set(DataComponents.POTION_CONTENTS, updated);
            changed++;

            Component playerName = player.getDisplayName();
            Component itemKind = Component.translatable(stack.getItem().getDescriptionId());
            Component effectName = Component.literal(effect.unwrapKey().map(ResourceKey::identifier)
                    .map(Identifier::toString).orElse(""));
            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.potion_remove", playerName, itemKind, effectName);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.potion_effect_not_found");
        }
        return changed;
    }

    // -------------------------------------------------------------------------
    // Item durability
    // -------------------------------------------------------------------------

    private static void registerDurabilityCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("durability")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("target", EntityArgument.players())
                        .then(Commands.literal("repair")
                                .executes(EnchantPlusCommands::repairDurability))
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                .suggests(EnchantPlusCommands::noSuggestions)
                                .executes(EnchantPlusCommands::setDurability)
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(DURABILITY_TYPES, builder))
                                        .executes(EnchantPlusCommands::setDurability)))));
    }

    private static int repairDurability(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "target");
        int changed = 0;

        for (ServerPlayer target : targets) {
            ItemStack stack = target.getMainHandItem();
            if (stack.isEmpty() || stack.getMaxDamage() <= 0) {
                continue;
            }

            int maximum = stack.getMaxDamage();
            stack.setDamageValue(0);
            changed++;
            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.durability_repair", target.getDisplayName(), maximum);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_durability_item");
        }
        return changed;
    }

    private static int setDurability(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "target");
        int value = IntegerArgumentType.getInteger(context, "value");
        String type = stringArgumentOrDefault(context, "type", "set").toLowerCase(Locale.ROOT);
        if (!DURABILITY_TYPES.contains(type)) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_durability_type");
        }
        if (type.equals("percentage") && value > 100) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_durability_percentage", value);
        }

        int changed = 0;
        for (ServerPlayer target : targets) {
            ItemStack stack = target.getMainHandItem();
            if (stack.isEmpty()) {
                continue;
            }

            int maximum = stack.getMaxDamage();
            if (maximum <= 0) {
                continue;
            }

            int currentRemaining = Math.max(0, maximum - stack.getDamageValue());
            long requestedRemaining = switch (type) {
                case "damage" -> (long) maximum - value;
                case "add" -> (long) currentRemaining + value;
                case "remove" -> (long) currentRemaining - value;
                case "percentage" -> Math.round(maximum * (value / 100.0));
                default -> value; // set
            };
            int newRemaining = (int) Math.max(0L, Math.min((long) maximum, requestedRemaining));
            stack.setDamageValue(maximum - newRemaining);
            changed++;

            CommandLocalization.sendSuccess(context, true,
                    "commands.enchantplus.success.durability", target.getDisplayName(), newRemaining, maximum);
        }

        if (changed == 0) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.no_durability_item");
        }
        return changed;
    }

    private static boolean isPotionItem(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)
                || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW);
    }

    private static int parseColor(CommandContext<CommandSourceStack> context, String token)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            int value;
            if (token.startsWith("#")) {
                if (token.length() != 7) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_color", token);
                value = Integer.parseInt(token.substring(1), 16);
            } else {
                value = Integer.parseInt(token);
            }
            if (value < 0 || value > 0xFFFFFF) throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_color", token);
            return value;
        } catch (NumberFormatException exception) {
            throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_color", token);
        }
    }

    private static boolean parseBoolean(CommandContext<CommandSourceStack> context, String token)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "1b", "true", "yes" -> true;
            case "0b", "false", "no" -> false;
            default -> throw CommandLocalization.error(context, "commands.enchantplus.error.invalid_potion_boolean");
        };
    }

}
