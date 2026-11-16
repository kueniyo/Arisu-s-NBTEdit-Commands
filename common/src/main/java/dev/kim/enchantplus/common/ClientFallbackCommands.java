package dev.kim.enchantplus.common;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.storage.TagValueOutput;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Client-side fallback for servers that do not have Arisu's NBTEdit Commands.
 * It edits a copy of the locally synchronized ItemStack, serializes the complete stack,
 * then asks the server to apply it with vanilla /item. This preserves all unrelated components.
 */
public final class ClientFallbackCommands {
    private static final int MAX_STORED_LEVEL = 255;
    private static final int MAX_COPY_LENGTH = ClipboardPayload.MAX_LENGTH;
    private static final URI MODRINTH_URI = URI.create("https://modrinth.com/mod/nbteditcommands");
    private static final URI DISCORD_URI = URI.create("https://discord.gg/HSbM76c64f");

    private ClientFallbackCommands() {
    }

    @FunctionalInterface
    private interface Action<S> {
        int run(CommandContext<S> context, String input) throws CommandSyntaxException;
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, ClientCommandEnvironment<S> environment) {
        registerGreedy(dispatcher, environment, "enchant", (ctx, raw) -> enchant(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "benchant", (ctx, raw) -> bookEnchant(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "delenchant", (ctx, raw) -> removeEnchant(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "denchant", (ctx, raw) -> removeEnchant(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "itemname", (ctx, raw) -> itemName(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "itemlore", (ctx, raw) -> itemLore(ctx, environment, raw));
        for (String root : List.of("itemattribute", "itemattr", "attrmod")) {
            registerGreedy(dispatcher, environment, root, (ctx, raw) -> attributes(ctx, environment, raw));
        }
        registerGreedy(dispatcher, environment, "potion", (ctx, raw) -> potion(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "durability", (ctx, raw) -> durability(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "copynbt", (ctx, raw) -> copyNbt(ctx, environment, raw, true));
        registerGreedy(dispatcher, environment, "cni", (ctx, raw) -> copyNbt(ctx, environment, raw, false));
        registerGreedy(dispatcher, environment, "cne", (ctx, raw) -> copyEntity(ctx, environment, raw));
        registerGreedy(dispatcher, environment, "cnb", (ctx, raw) -> copyBlock(ctx, environment, raw));
        dispatcher.register(LiteralArgumentBuilder.<S>literal("nbtedit")
                .requires(source -> environment.fallbackEnabled() && environment.isDirectUserCommand(source))
                .executes(ctx -> showInfo(ctx, environment)));
    }

    private static <S> void registerGreedy(CommandDispatcher<S> dispatcher,
                                           ClientCommandEnvironment<S> environment,
                                           String name, Action<S> action) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal(name)
                .requires(source -> environment.fallbackEnabled() && environment.isDirectUserCommand(source))
                .executes(context -> action.run(context, ""));
        RequiredArgumentBuilder<S, String> input = RequiredArgumentBuilder
                .<S, String>argument("arguments", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggest(name, builder.getRemaining(), environment, context.getSource(), builder))
                .executes(context -> action.run(context, StringArgumentType.getString(context, "arguments")));
        root.then(input);
        dispatcher.register(root);
    }

    private static <S> CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            String root, String remaining, ClientCommandEnvironment<S> environment, S source,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String value = remaining == null ? "" : remaining;
        boolean trailingSpace = value.isEmpty() || Character.isWhitespace(value.charAt(value.length() - 1));
        String[] split = tokens(value);
        List<String> completed = new ArrayList<>(List.of(split));
        String prefix = "";
        if (!trailingSpace && !completed.isEmpty()) prefix = completed.remove(completed.size() - 1);

        List<String> candidates = new ArrayList<>();
        switch (root) {
            case "copynbt" -> {
                if (completed.isEmpty()) candidates.addAll(List.of("item", "entity", "block"));
                else if (completed.getFirst().equals("item")) {
                    if (completed.size() == 1) candidates.addAll(playerSuggestions(environment, source));
                    else if (completed.size() == 2 && isPlayerToken(completed.get(1))) candidates.addAll(List.of("nbt", "name"));
                } else if (completed.getFirst().equals("entity")) {
                    if (completed.size() == 1) candidates.addAll(entitySuggestions(environment, source));
                }
            }
            case "cni" -> {
                if (completed.isEmpty()) candidates.addAll(playerSuggestions(environment, source));
                else if (completed.size() == 1) candidates.addAll(List.of("nbt", "name"));
            }
            case "cne" -> {
                if (completed.isEmpty()) candidates.addAll(entitySuggestions(environment, source));
            }
            case "benchant" -> {
                if (completed.isEmpty()) candidates.addAll(List.of("add", "remove"));
                else if (completed.size() == 1) candidates.addAll(playerSuggestions(environment, source));
                else if (completed.size() == 2 && completed.get(0).equals("add")) candidates.addAll(enchantmentSuggestions(environment, source));
                else if (completed.size() == 2 && completed.get(0).equals("remove")) candidates.addAll(enchantmentSuggestions(environment, source));
            }
            case "itemattribute", "itemattr", "attrmod" -> {
                if (completed.isEmpty()) candidates.addAll(List.of("add", "remove"));
                else if (completed.size() == 1) candidates.addAll(playerSuggestions(environment, source));
                else if (completed.size() == 2) candidates.addAll(attributeSuggestions(environment, source));
                else if (completed.size() == 5 && completed.get(0).equals("add")) {
                    candidates.addAll(List.of("add_value", "add_multiplied_base", "add_multiplied_total"));
                } else if (completed.size() == 6 && completed.get(0).equals("add")) {
                    candidates.addAll(List.of("any", "hand", "mainhand", "offhand", "armor", "head", "chest", "legs", "feet", "body", "saddle"));
                } else if (completed.size() == 7 && completed.get(0).equals("add")) {
                    candidates.addAll(List.of("default", "hidden", "override"));
                }
            }
            case "potion" -> {
                if (completed.isEmpty()) candidates.addAll(List.of("add", "remove"));
                else if (completed.size() == 1) candidates.addAll(playerSuggestions(environment, source));
                else if (completed.size() == 2 && completed.get(0).equals("remove")) candidates.addAll(effectSuggestions(environment, source));
                else if (completed.size() == 2 && completed.get(0).equals("add")) candidates.addAll(effectSuggestions(environment, source));
                else if (completed.size() == 3 && completed.get(0).equals("add") && isColorToken(completed.get(2))) candidates.addAll(effectSuggestions(environment, source));
                else if (completed.size() == 5 && completed.get(0).equals("add")) candidates.addAll(List.of("true", "false"));
                else if (completed.size() == 6 && completed.get(0).equals("add")) candidates.addAll(List.of("true", "false"));
                else if (completed.size() == 7 && completed.get(0).equals("add")) candidates.addAll(List.of("true", "false"));
                else if (completed.size() == 6 && completed.get(0).equals("add") && isColorToken(completed.get(2))) candidates.addAll(List.of("true", "false"));
                else if (completed.size() == 7 && completed.get(0).equals("add") && isColorToken(completed.get(2))) candidates.addAll(List.of("true", "false"));
                else if (completed.size() == 8 && completed.get(0).equals("add") && isColorToken(completed.get(2))) candidates.addAll(List.of("true", "false"));
            }
            case "durability", "enchant", "delenchant", "denchant" -> {
                if (completed.isEmpty()) candidates.addAll(playerSuggestions(environment, source));
                else if ((root.equals("enchant") || root.equals("delenchant") || root.equals("denchant"))
                        && completed.size() == 1) candidates.addAll(enchantmentSuggestions(environment, source));
                else if (root.equals("durability") && completed.size() == 2) candidates.addAll(List.of("set", "damage", "add", "remove", "percentage"));
            }
            default -> { }
        }
        String filter = prefix.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> unique = new LinkedHashSet<>(candidates);
        return SharedSuggestionProvider.suggest(unique.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(filter)), builder);
    }

    private static <S> List<String> enchantmentSuggestions(ClientCommandEnvironment<S> env, S source) {
        try {
            return env.registryAccess(source).lookupOrThrow(Registries.ENCHANTMENT).listElementIds()
                    .map(key -> key.identifier().toString()).toList();
        } catch (RuntimeException exception) {
            return List.of("minecraft:sharpness", "minecraft:efficiency", "minecraft:unbreaking", "minecraft:mending");
        }
    }

    private static <S> List<String> entitySuggestions(ClientCommandEnvironment<S> env, S source) {
        List<String> values = new ArrayList<>(List.of("@s", "@p", "@r", "@a", "@e"));
        Player self = env.player(source);
        Level level = env.level(source);
        if (self != null && level != null) {
            AABB area = self.getBoundingBox().inflate(128.0);
            level.getEntities(self, area, entity -> true).forEach(entity -> values.add(entity.getName().getString()));
        }
        values.addAll(playerSuggestions(env, source));
        return values;
    }

    private static boolean isPlayerToken(String value) {
        return value.startsWith("@") || (!value.isBlank() && !value.contains(" "));
    }

    private static <S> List<String> playerSuggestions(ClientCommandEnvironment<S> env, S source) {
        List<String> values = new ArrayList<>(List.of("@s", "@p", "@r", "@a"));
        Level level = env.level(source);
        if (level != null) level.players().forEach(player -> values.add(player.getName().getString()));
        return values;
    }

    private static <S> List<String> attributeSuggestions(ClientCommandEnvironment<S> env, S source) {
        try {
            return env.registryAccess(source).lookupOrThrow(Registries.ATTRIBUTE).listElementIds()
                    .map(key -> key.identifier().toString()).toList();
        } catch (RuntimeException exception) {
            return List.of("minecraft:armor", "minecraft:attack_damage", "minecraft:movement_speed");
        }
    }

    private static <S> List<String> effectSuggestions(ClientCommandEnvironment<S> env, S source) {
        try {
            return env.registryAccess(source).lookupOrThrow(Registries.MOB_EFFECT).listElementIds()
                    .map(key -> key.identifier().toString()).toList();
        } catch (RuntimeException exception) {
            return List.of("minecraft:speed", "minecraft:strength", "minecraft:absorption");
        }
    }

    private static String[] tokens(String input) {
        String trimmed = input == null ? "" : input.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static String afterFirstToken(String input) {
        int index = 0;
        while (index < input.length() && !Character.isWhitespace(input.charAt(index))) index++;
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        return input.substring(index).trim();
    }

    private static CommandSyntaxException fail(String language, String key, Object... args) {
        return new SimpleCommandExceptionType(CommandLocalization.translate(language,
                "commands.enchantplus.clientfallback.error." + key, args)).create();
    }

    private static CommandSyntaxException failKey(String language, String key, Object... args) {
        return new SimpleCommandExceptionType(CommandLocalization.translate(language, key, args)).create();
    }

    private static <S> List<Player> resolvePlayers(CommandContext<S> context,
                                                    ClientCommandEnvironment<S> env, String selector)
            throws CommandSyntaxException {
        Player self = env.player(context.getSource());
        Level level = env.level(context.getSource());
        if (self == null || level == null) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.player_required");

        Map<String, String> options = selectorOptions(selector);
        String base = selectorBase(selector);
        List<Player> selected = switch (base) {
            case "@s" -> new ArrayList<>(List.of(self));
            case "@a", "@p", "@r" -> new ArrayList<>(level.players());
            default -> new ArrayList<>(level.players().stream()
                    .filter(player -> player.getName().getString().equalsIgnoreCase(base)).toList());
        };
        if (options.containsKey("name")) {
            selected.removeIf(player -> !player.getName().getString().equals(options.get("name")));
        }
        applyDistanceFilter(selected, self, options.get("distance"));

        String sort = options.getOrDefault("sort", "arbitrary");
        if (sort.equals("nearest")) selected.sort(Comparator.comparingDouble(player -> player.distanceToSqr(self)));
        else if (sort.equals("furthest")) selected.sort(Comparator.comparingDouble((Player player) -> player.distanceToSqr(self)).reversed());
        else if (sort.equals("random")) java.util.Collections.shuffle(selected);

        int defaultLimit = base.equals("@p") || base.equals("@r") ? 1 : Integer.MAX_VALUE;
        int limit = parseLimit(options.get("limit"), defaultLimit);
        if (base.equals("@p") && !options.containsKey("sort")) {
            selected.sort(Comparator.comparingDouble(player -> player.distanceToSqr(self)));
        } else if (base.equals("@r") && !options.containsKey("sort")) {
            java.util.Collections.shuffle(selected);
        }
        if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
        if (selected.isEmpty()) throw fail(env.language(context.getSource()), "player_not_visible", selector);
        return selected;
    }

    private static <S> List<LivingEntity> resolveLivingTargets(CommandContext<S> context,
                                                                 ClientCommandEnvironment<S> env, String selector)
            throws CommandSyntaxException {
        if (selector.startsWith("@e")) {
            Player self = env.player(context.getSource());
            Level level = env.level(context.getSource());
            if (self == null || level == null) throw fail(env.language(context.getSource()), "no_client_world");
            AABB area = self.getBoundingBox().inflate(128.0);
            List<LivingEntity> candidates = new ArrayList<>();
            candidates.add(self);
            for (Entity entity : level.getEntities(self, area, candidate -> candidate instanceof LivingEntity)) {
                candidates.add((LivingEntity) entity);
            }
            Map<String, String> options = selectorOptions(selector);
            String typeFilter = options.get("type");
            if (typeFilter != null) {
                boolean inverted = typeFilter.startsWith("!");
                String expected = inverted ? typeFilter.substring(1) : typeFilter;
                candidates.removeIf(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(expected) == inverted);
            }
            String nameFilter = options.get("name");
            if (nameFilter != null) {
                boolean inverted = nameFilter.startsWith("!");
                String expected = inverted ? nameFilter.substring(1) : nameFilter;
                candidates.removeIf(entity -> entity.getName().getString().equals(expected) == inverted);
            }
            applyDistanceFilter(candidates, self, options.get("distance"));
            String sort = options.getOrDefault("sort", "arbitrary");
            if (sort.equals("nearest")) candidates.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(self)));
            else if (sort.equals("furthest")) candidates.sort(Comparator.comparingDouble((LivingEntity entity) -> entity.distanceToSqr(self)).reversed());
            else if (sort.equals("random")) java.util.Collections.shuffle(candidates);
            int limit = parseLimit(options.get("limit"), Integer.MAX_VALUE);
            if (candidates.size() > limit) candidates = new ArrayList<>(candidates.subList(0, limit));
            return candidates;
        }
        return new ArrayList<>(resolvePlayers(context, env, selector));
    }

    private static String selectorBase(String selector) {
        int bracket = selector.indexOf('[');
        return bracket >= 0 ? selector.substring(0, bracket) : selector;
    }

    private static int parseLimit(String rawLimit, int defaultLimit) {
        if (rawLimit == null) return defaultLimit;
        try { return Math.max(0, Integer.parseInt(rawLimit)); }
        catch (NumberFormatException ignored) { return defaultLimit; }
    }

    private static void applyDistanceFilter(List<? extends Entity> entities, Player origin, String distance) {
        if (distance == null) return;
        try {
            double min = 0.0;
            double max = Double.POSITIVE_INFINITY;
            int range = distance.indexOf("..");
            if (range >= 0) {
                String left = distance.substring(0, range);
                String right = distance.substring(range + 2);
                if (!left.isEmpty()) min = Double.parseDouble(left);
                if (!right.isEmpty()) max = Double.parseDouble(right);
            } else {
                min = max = Double.parseDouble(distance);
            }
            double minSqr = min * min;
            double maxSqr = max * max;
            entities.removeIf(entity -> {
                double distanceSqr = entity.distanceToSqr(origin);
                return distanceSqr < minSqr || distanceSqr > maxSqr;
            });
        } catch (NumberFormatException ignored) {
            // Unsupported distance syntax is ignored; selector evaluation remains client-bounded.
        }
    }

    private static Map<String, String> selectorOptions(String selector) {
        int start = selector.indexOf('[');
        int end = selector.lastIndexOf(']');
        if (start < 0 || end <= start) return Map.of();
        Map<String, String> options = new java.util.HashMap<>();
        for (String entry : selector.substring(start + 1, end).split(",")) {
            int equals = entry.indexOf('=');
            if (equals > 0) options.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
        }
        return options;
    }

    private static <S> String vanillaTarget(Player target, Player self) {
        if (target.getUUID().equals(self.getUUID())) return "@s";
        return uuidSelector(target.getUUID());
    }

    private static String uuidSelector(UUID uuid) {
        int mostHigh = (int) (uuid.getMostSignificantBits() >>> 32);
        int mostLow = (int) uuid.getMostSignificantBits();
        int leastHigh = (int) (uuid.getLeastSignificantBits() >>> 32);
        int leastLow = (int) uuid.getLeastSignificantBits();
        return "@e[nbt={UUID:[I;" + mostHigh + "," + mostLow + "," + leastHigh + "," + leastLow + "]}]";
    }

    private static <S> void replaceMainHand(CommandContext<S> context, ClientCommandEnvironment<S> env,
                                             Player target, ItemStack updated) throws CommandSyntaxException {
        Player self = env.player(context.getSource());
        if (self == null) throw fail(env.language(context.getSource()), "no_local_player");
        if (updated.isEmpty()) throw fail(env.language(context.getSource()), "empty_replacement");
        String item = itemStackArgument(updated, env.registryAccess(context.getSource()));
        env.sendVanillaCommand(context.getSource(), "item replace entity " + vanillaTarget(target, self)
                + " weapon.mainhand with " + item);
    }

    private static String itemStackArgument(ItemStack stack, net.minecraft.core.RegistryAccess registries) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        output.store(ItemStack.MAP_CODEC, stack);
        CompoundTag encoded = output.buildResult();
        Tag rawComponents = encoded.get("components");
        String componentText = "";
        if (rawComponents instanceof CompoundTag components && !components.isEmpty()) {
            componentText = components.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> {
                        String key = entry.getKey();
                        boolean removed = key.startsWith("!") || entry.getValue().getId() == Tag.TAG_END;
                        if (key.startsWith("!")) key = key.substring(1);
                        return removed ? "!" + key : key + "=" + entry.getValue();
                    })
                    .collect(Collectors.joining(",", "[", "]"));
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return itemId + componentText + " " + stack.getCount();
    }

    private static <S> int enchant(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length < 2 || args.length > 3) throw fail(env.language(context.getSource()), "usage_enchant");
        int level = args.length == 3 ? parseInt(context, env, args[2], "level") : 1;
        if (level < 1 || level > MAX_STORED_LEVEL) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_level", MAX_STORED_LEVEL, level);
        Holder<Enchantment> enchantment = resolveHolder(env, context.getSource(), Registries.ENCHANTMENT, args[1], "enchantment");
        List<LivingEntity> targets = resolveLivingTargets(context, env, args[0]);
        int changed = 0;
        for (LivingEntity target : targets) {
            ItemStack current = target.getMainHandItem();
            if (current.isEmpty()) continue;
            ItemStack updated = current.copy();
            ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(updated.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
            mutable.set(enchantment, level);
            updated.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
            if (target instanceof Player player) replaceMainHand(context, env, player, updated);
            else sendEntityItemReplace(context, env, target, updated);
            feedback(env, context, "commands.enchantplus.success.enchant",
                    target.getDisplayName(), enchantmentId(enchantment), level);
            changed++;
        }
        if (changed == 0) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.no_held_item");
        return changed;
    }

    private static <S> int bookEnchant(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length < 2) throw fail(env.language(context.getSource()), "usage_benchant");
        String mode = args[0].toLowerCase(Locale.ROOT);
        if (mode.equals("add")) {
            if (args.length < 3 || args.length > 4) throw fail(env.language(context.getSource()), "usage_benchant_add");
            int level = args.length == 4 ? parseInt(context, env, args[3], "level") : 1;
            if (level < 1 || level > MAX_STORED_LEVEL) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_level", MAX_STORED_LEVEL, level);
            Holder<Enchantment> enchantment = resolveHolder(env, context.getSource(), Registries.ENCHANTMENT, args[2], "enchantment");
            List<Player> targets = resolvePlayers(context, env, args[1]);
            int changed = 0;
            for (Player target : targets) {
                ItemStack held = target.getMainHandItem();
                if (held.is(Items.ENCHANTED_BOOK)) {
                    ItemStack updated = held.copy();
                    setEnchantment(updated, DataComponents.STORED_ENCHANTMENTS, enchantment, level);
                    replaceMainHand(context, env, target, updated);
                    changed++;
                } else if (held.is(Items.BOOK)) {
                    ItemStack enchanted = held.transmuteCopy(Items.ENCHANTED_BOOK, 1);
                    setEnchantment(enchanted, DataComponents.STORED_ENCHANTMENTS, enchantment, level);
                    if (held.getCount() == 1) {
                        replaceMainHand(context, env, target, enchanted);
                    } else {
                        ItemStack remainder = held.copy();
                        remainder.setCount(held.getCount() - 1);
                        replaceMainHand(context, env, target, remainder);
                        env.sendVanillaCommand(context.getSource(), "give " + vanillaTarget(target, env.player(context.getSource())) + " "
                                + itemStackArgument(enchanted, env.registryAccess(context.getSource())));
                    }
                    changed++;
                }
            }
            if (changed == 0) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.no_book");
            feedback(env, context, "commands.enchantplus.success.book", changed, level);
            return changed;
        }
        if (mode.equals("remove")) {
            if (args.length > 3) throw fail(env.language(context.getSource()), "usage_benchant_remove");
            List<Player> targets = resolvePlayers(context, env, args[1]);
            Holder<Enchantment> enchantment = args.length == 3
                    ? resolveHolder(env, context.getSource(), Registries.ENCHANTMENT, args[2], "enchantment") : null;
            int changed = 0;
            for (Player target : targets) {
                ItemStack held = target.getMainHandItem();
                if (!held.is(Items.ENCHANTED_BOOK)) continue;
                ItemEnchantments current = held.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                if (current.equals(ItemEnchantments.EMPTY)) continue;
                ItemStack updated = held.copy();
                if (enchantment == null) updated.remove(DataComponents.STORED_ENCHANTMENTS);
                else if (!removeFromComponent(updated, DataComponents.STORED_ENCHANTMENTS, enchantment)) continue;
                replaceMainHand(context, env, target, updated);
                if (enchantment == null) {
                    feedback(env, context, "commands.enchantplus.success.book_remove_all", target.getDisplayName());
                } else {
                    feedback(env, context, "commands.enchantplus.success.book_remove_one",
                            target.getDisplayName(), enchantmentId(enchantment));
                }
                changed++;
            }
            if (changed == 0) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.nothing_to_remove");
            return changed;
        }
        throw fail(env.language(context.getSource()), "expected_add_or_remove");
    }

    private static <S> int removeEnchant(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length < 1 || args.length > 2) throw fail(env.language(context.getSource()), "usage_delenchant");
        Holder<Enchantment> enchantment = args.length == 2
                ? resolveHolder(env, context.getSource(), Registries.ENCHANTMENT, args[1], "enchantment") : null;
        List<LivingEntity> targets = resolveLivingTargets(context, env, args[0]);
        int changed = 0;
        for (LivingEntity target : targets) {
            ItemStack held = target.getMainHandItem();
            if (held.isEmpty()) continue;
            ItemStack updated = held.copy();
            boolean removed;
            if (enchantment == null) {
                removed = updated.has(DataComponents.ENCHANTMENTS) || updated.has(DataComponents.STORED_ENCHANTMENTS);
                if (removed) {
                    updated.remove(DataComponents.ENCHANTMENTS);
                    updated.remove(DataComponents.STORED_ENCHANTMENTS);
                }
            } else {
                removed = removeFromComponent(updated, DataComponents.ENCHANTMENTS, enchantment);
                removed |= removeFromComponent(updated, DataComponents.STORED_ENCHANTMENTS, enchantment);
            }
            if (!removed) continue;
            if (target instanceof Player player) replaceMainHand(context, env, player, updated);
            else sendEntityItemReplace(context, env, target, updated);
            if (enchantment == null) {
                feedback(env, context, "commands.enchantplus.success.remove_all", target.getDisplayName());
            } else {
                feedback(env, context, "commands.enchantplus.success.remove_one",
                        target.getDisplayName(), enchantmentId(enchantment));
            }
            changed++;
        }
        if (changed == 0) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.nothing_to_remove");
        return changed;
    }

    private static void setEnchantment(ItemStack stack, net.minecraft.core.component.DataComponentType<ItemEnchantments> component,
                                       Holder<Enchantment> enchantment, int level) {
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(stack.getOrDefault(component, ItemEnchantments.EMPTY));
        mutable.set(enchantment, level);
        stack.set(component, mutable.toImmutable());
    }

    private static boolean removeFromComponent(ItemStack stack,
                                               net.minecraft.core.component.DataComponentType<ItemEnchantments> component,
                                               Holder<Enchantment> enchantment) {
        ItemEnchantments current = stack.getOrDefault(component, ItemEnchantments.EMPTY);
        if (current.getLevel(enchantment) <= 0) return false;
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
        mutable.removeIf(existing -> existing.equals(enchantment));
        stack.set(component, mutable.toImmutable());
        return true;
    }

    private static String enchantmentId(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey().map(ResourceKey::identifier).map(Identifier::toString).orElse("unknown");
    }

    private static Component effectId(Holder<MobEffect> effect) {
        return Component.literal(effect.unwrapKey().map(ResourceKey::identifier)
                .map(Identifier::toString).orElse("unknown"));
    }

    private static <S> int itemName(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        Player player = requireSelf(context, env);
        ItemStack current = player.getMainHandItem();
        if (current.isEmpty()) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.no_held_item");
        ItemStack updated = current.copy();
        if (raw.trim().equalsIgnoreCase("reset")) {
            Component original = updated.getPrototype().get(DataComponents.ITEM_NAME);
            if (original != null) updated.set(DataComponents.ITEM_NAME, original);
            else updated.remove(DataComponents.ITEM_NAME);
            // The success message is emitted after the vanilla replacement request below.
        } else {
            Component name;
            try {
                var result = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(raw.trim()));
                name = result.result().orElseThrow(() -> new IllegalArgumentException("invalid text component"));
            } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
                throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_text_component");
            }
            updated.set(DataComponents.ITEM_NAME, name);
            // The success message is emitted after the vanilla replacement request below.
        }
        replaceMainHand(context, env, player, updated);
        feedback(env, context, raw.trim().equalsIgnoreCase("reset")
                        ? "commands.enchantplus.success.itemname_reset"
                        : "commands.enchantplus.success.itemname_set",
                player.getDisplayName());
        return 1;
    }

    private static <S> int itemLore(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        Player player = requireSelf(context, env);
        ItemStack current = player.getMainHandItem();
        if (current.isEmpty()) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.no_held_item");
        ItemStack updated = current.copy();
        if (raw.trim().equalsIgnoreCase("reset")) {
            updated.remove(DataComponents.LORE);
            replaceMainHand(context, env, player, updated);
            feedback(env, context, "commands.enchantplus.success.itemlore_reset", player.getDisplayName());
            return 1;
        }
        String[] first = tokens(raw);
        if (first.length < 2) throw fail(env.language(context.getSource()), "usage_itemlore");
        int line;
        try { line = Integer.parseInt(first[0]); } catch (NumberFormatException exception) { throw fail(env.language(context.getSource()), "lore_line_integer"); }
        if (line < 1 || line > ItemLore.MAX_LINES) throw fail(env.language(context.getSource()), "lore_line_range", ItemLore.MAX_LINES);
        String description = afterFirstToken(raw);
        List<Component> lines = new ArrayList<>(updated.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
        String messageKey;
        if (description.equalsIgnoreCase("remove") || description.equalsIgnoreCase("clear")) {
            if (line > lines.size()) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_lore_line", line);
            if (description.equalsIgnoreCase("remove")) {
                lines.remove(line - 1);
                messageKey = "commands.enchantplus.success.itemlore_remove";
            } else {
                lines.set(line - 1, Component.empty());
                messageKey = "commands.enchantplus.success.itemlore_clear";
            }
        } else {
            Component component;
            try {
                component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(description))
                        .result().orElseThrow(() -> new IllegalArgumentException("invalid text component"));
            } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
                throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_text_component");
            }
            while (lines.size() < line - 1) lines.add(Component.empty());
            if (line <= lines.size()) lines.set(line - 1, component);
            else lines.add(component);
            messageKey = "commands.enchantplus.success.itemlore_set";
        }
        if (lines.isEmpty()) updated.remove(DataComponents.LORE);
        else updated.set(DataComponents.LORE, new ItemLore(lines));
        replaceMainHand(context, env, player, updated);
        feedback(env, context, messageKey, player.getDisplayName(), line);
        return 1;
    }

    private static <S> int attributes(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length < 2) throw fail(env.language(context.getSource()), "usage_itemattribute");
        String mode = args[0].toLowerCase(Locale.ROOT);
        List<Player> targets = resolvePlayers(context, env, args[1]);
        int changed = 0;
        boolean foundNonEmptyItem = false;
        for (Player target : targets) {
            ItemStack held = target.getMainHandItem();
            if (held.isEmpty()) continue;
            foundNonEmptyItem = true;
            ItemStack updated = held.copy();
            String successKey;
            Object[] successArgs;
            if (mode.equals("remove")) {
                if (args.length == 2) {
                    if (updated.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers().isEmpty()) continue;
                    updated.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                    successKey = "commands.enchantplus.success.attribute_remove_all";
                    successArgs = new Object[]{target.getDisplayName()};
                } else {
                    Holder.Reference<Attribute> attribute = resolveAttribute(env, context.getSource(), args[2]);
                    Identifier requestedId = args.length >= 4 ? Identifier.tryParse(args[3]) : null;
                    if (args.length >= 4 && requestedId == null) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_attribute_id");
                    ItemAttributeModifiers current = updated.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                    List<ItemAttributeModifiers.Entry> entries = new ArrayList<>(current.modifiers());
                    boolean removed = requestedId == null
                            ? entries.removeIf(entry -> entry.attribute().equals(attribute))
                            : entries.removeIf(entry -> entry.matches(attribute, requestedId));
                    if (!removed) continue;
                    updated.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(entries));
                    String typeId = attribute.unwrapKey().map(ResourceKey::identifier)
                            .map(Identifier::toString).orElse("unknown");
                    successKey = "commands.enchantplus.success.attribute_remove";
                    successArgs = new Object[]{target.getName(), typeId};
                }
            } else if (mode.equals("add")) {
                if (args.length < 3) throw fail(env.language(context.getSource()), "usage_itemattribute_add");
                Holder.Reference<Attribute> attribute = resolveAttribute(env, context.getSource(), args[2]);
                String typeId = attribute.unwrapKey().map(ResourceKey::identifier).map(Identifier::toString).orElse("minecraft:unknown");
                double amount = 1.0;
                String modifierIdText = typeId;
                String operationText = "add_value";
                String slotText = "any";
                String displayText = "default";
                int cursor = 3;
                boolean amountSet = false, idSet = false, operationSet = false, slotSet = false, displaySet = false;
                while (cursor < args.length) {
                    String token = args[cursor++];
                    if (!operationSet && isOperation(token)) {
                        operationText = token.toLowerCase(Locale.ROOT); operationSet = true;
                    } else if (!slotSet && isSlot(token)) {
                        slotText = token.toLowerCase(Locale.ROOT); slotSet = true;
                    } else if (!displaySet && isDisplay(token)) {
                        displayText = token.toLowerCase(Locale.ROOT); displaySet = true;
                    } else if (!amountSet) {
                        try { amount = Double.parseDouble(token); amountSet = true; }
                        catch (NumberFormatException ignored) {
                            if (!idSet) { modifierIdText = token; idSet = true; }
                            else throw fail(env.language(context.getSource()), "invalid_attribute_modifier_argument", token);
                        }
                    } else if (!idSet) {
                        modifierIdText = token; idSet = true;
                    } else {
                        throw fail(env.language(context.getSource()), "unexpected_attribute_modifier_argument", token);
                    }
                }
                if (!Double.isFinite(amount)) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_attribute_amount");
                Identifier modifierId = Identifier.tryParse(modifierIdText);
                if (modifierId == null) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_attribute_id");
                AttributeModifier.Operation operation = switch (operationText) {
                    case "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    case "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                    default -> AttributeModifier.Operation.ADD_VALUE;
                };
                EquipmentSlotGroup slot = switch (slotText) {
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
                ItemAttributeModifiers.Display display = switch (displayText) {
                    case "hidden" -> ItemAttributeModifiers.Display.hidden();
                    case "override" -> ItemAttributeModifiers.Display.override(Component.literal(modifierId.toString()));
                    default -> ItemAttributeModifiers.Display.attributeModifiers();
                };
                ItemAttributeModifiers current = updated.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                List<ItemAttributeModifiers.Entry> entries = new ArrayList<>(current.modifiers());
                entries.removeIf(entry -> entry.matches(attribute, modifierId));
                entries.add(new ItemAttributeModifiers.Entry(attribute, new AttributeModifier(modifierId, amount, operation), slot, display));
                updated.set(DataComponents.ATTRIBUTE_MODIFIERS, new ItemAttributeModifiers(entries));
                successKey = "commands.enchantplus.success.attribute_add";
                successArgs = new Object[]{target.getName(), typeId, amount, modifierId.toString()};
            } else {
                throw fail(env.language(context.getSource()), "expected_add_or_remove");
            }
            replaceMainHand(context, env, target, updated);
            feedback(env, context, successKey, successArgs);
            changed++;
        }
        if (changed == 0) throw failKey(env.language(context.getSource()),
                foundNonEmptyItem && mode.equals("remove")
                        ? "commands.enchantplus.error.no_attribute_modifier"
                        : "commands.enchantplus.error.no_attribute_item");
        return changed;
    }

    private static boolean isOperation(String token) {
        return token.equalsIgnoreCase("add_value") || token.equalsIgnoreCase("add_multiplied_base")
                || token.equalsIgnoreCase("add_multiplied_total");
    }

    private static boolean isSlot(String token) {
        return List.of("any", "hand", "mainhand", "offhand", "armor", "head", "chest", "legs", "feet", "body", "saddle")
                .contains(token.toLowerCase(Locale.ROOT));
    }

    private static boolean isDisplay(String token) {
        return List.of("default", "hidden", "override").contains(token.toLowerCase(Locale.ROOT));
    }

    private static <S> Holder.Reference<Attribute> resolveAttribute(ClientCommandEnvironment<S> env, S source, String text)
            throws CommandSyntaxException {
        Identifier id = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text);
        if (id == null) throw fail(env.language(source), "invalid_attribute_type_id", text);
        Optional<Holder.Reference<Attribute>> resolved = env.registryAccess(source).lookupOrThrow(Registries.ATTRIBUTE)
                .get(ResourceKey.create(Registries.ATTRIBUTE, id));
        if (resolved.isEmpty()) throw failKey(env.language(source), "commands.enchantplus.error.invalid_attribute", text);
        return resolved.get();
    }

    private static <S> int potion(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length < 2) throw fail(env.language(context.getSource()), "usage_potion");
        String mode = args[0].toLowerCase(Locale.ROOT);
        List<Player> targets = resolvePlayers(context, env, args[1]);
        int changed = 0;
        if (mode.equals("remove")) {
            Holder<MobEffect> effect = args.length >= 3
                    ? resolveHolder(env, context.getSource(), Registries.MOB_EFFECT, args[2], "effect") : null;
            if (args.length > 3) throw fail(env.language(context.getSource()), "usage_potion_remove");
            for (Player target : targets) {
                ItemStack held = target.getMainHandItem();
                if (!isPotionItem(held)) continue;
                ItemStack updated = held.copy();
                PotionContents current = updated.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                List<MobEffectInstance> effects = new ArrayList<>();
                boolean removed = false;
                for (MobEffectInstance existing : current.getAllEffects()) {
                    if (effect == null || existing.is(effect)) removed = true;
                    else effects.add(new MobEffectInstance(existing));
                }
                if (!removed) continue;
                updated.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), current.customColor(), effects, current.customName()));
                replaceMainHand(context, env, target, updated);
                Component itemKind = Component.translatable(held.getItem().getDescriptionId());
                if (effect == null) {
                    feedback(env, context, "commands.enchantplus.success.potion_remove_all",
                            target.getDisplayName(), itemKind);
                } else {
                    feedback(env, context, "commands.enchantplus.success.potion_remove",
                            target.getDisplayName(), itemKind, effectId(effect));
                }
                changed++;
            }
        } else if (mode.equals("add")) {
            if (args.length < 3) throw fail(env.language(context.getSource()), "usage_potion_add");
            int cursor = 2;
            String colorToken = null;
            if (isColorToken(args[cursor])) colorToken = args[cursor++];
            if (cursor >= args.length) throw fail(env.language(context.getSource()), "potion_effect_required");
            Holder<MobEffect> effect = resolveHolder(env, context.getSource(), Registries.MOB_EFFECT, args[cursor++], "effect");
            int amplifier = cursor < args.length ? parseInt(context, env, args[cursor++], "amplifier") : 0;
            int duration = cursor < args.length ? parseInt(context, env, args[cursor++], "duration") : 600;
            boolean showParticles = cursor < args.length ? parseBoolean(args[cursor++], env.language(context.getSource())) : true;
            boolean showIcon = cursor < args.length ? parseBoolean(args[cursor++], env.language(context.getSource())) : true;
            boolean ambient = cursor < args.length ? parseBoolean(args[cursor++], env.language(context.getSource())) : false;
            if (cursor != args.length) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_potion_options");
            if (amplifier < 0 || amplifier > 255) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_potion_amplifier", amplifier);
            if (duration < -1) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_potion_duration", duration);
            Integer color = colorToken == null ? null : parseColor(context, env, colorToken);
            for (Player target : targets) {
                ItemStack held = target.getMainHandItem();
                if (!isPotionItem(held)) continue;
                ItemStack updated = held.copy();
                PotionContents current = updated.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                List<MobEffectInstance> effects = new ArrayList<>();
                for (MobEffectInstance old : current.getAllEffects()) if (!old.is(effect)) effects.add(new MobEffectInstance(old));
                effects.add(new MobEffectInstance(effect, duration, amplifier, ambient, showParticles, showIcon));
                Optional<Integer> newColor = color == null ? current.customColor() : Optional.of(color);
                updated.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), newColor, effects, current.customName()));
                replaceMainHand(context, env, target, updated);
                Component itemKind = Component.translatable(held.getItem().getDescriptionId());
                feedback(env, context, "commands.enchantplus.success.potion",
                        target.getDisplayName(), itemKind, effectId(effect), amplifier, duration);
                changed++;
            }
        } else {
            throw fail(env.language(context.getSource()), "expected_add_or_remove");
        }
        if (changed == 0) throw failKey(env.language(context.getSource()), mode.equals("add")
                ? "commands.enchantplus.error.potion_item_required"
                : "commands.enchantplus.error.potion_effect_not_found");
        return changed;
    }

    private static boolean isPotionItem(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION)
                || stack.is(Items.LINGERING_POTION) || stack.is(Items.TIPPED_ARROW);
    }

    private static boolean isColorToken(String token) {
        return token.startsWith("#") || token.matches("[0-9]{1,8}");
    }

    private static <S> int parseColor(CommandContext<S> context, ClientCommandEnvironment<S> env, String token)
            throws CommandSyntaxException {
        try {
            int value = token.startsWith("#") ? Integer.parseInt(token.substring(1), 16) : Integer.parseInt(token);
            if (value < 0 || value > 0xFFFFFF) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_potion_color", token);
        }
    }

    private static boolean parseBoolean(String value, String language) throws CommandSyntaxException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "1b", "yes" -> true;
            case "false", "0b", "no" -> false;
            default -> throw failKey(language, "commands.enchantplus.error.invalid_potion_boolean");
        };
    }

    private static <S> int durability(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length < 2 || args.length > 3) throw fail(env.language(context.getSource()), "usage_durability");
        List<Player> targets = resolvePlayers(context, env, args[0]);
        String valueText = args[1];
        String type = args.length == 3 ? args[2].toLowerCase(Locale.ROOT) : "set";
        if (!List.of("set", "damage", "add", "remove", "percentage").contains(type)) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_durability_type");
        int changed = 0;
        for (Player target : targets) {
            ItemStack held = target.getMainHandItem();
            if (held.isEmpty() || held.getMaxDamage() <= 0) continue;
            ItemStack updated = held.copy();
            int max = updated.getMaxDamage();
            boolean repair = valueText.equalsIgnoreCase("repair");
            if (repair) updated.setDamageValue(0);
            else {
                int value = parseInt(context, env, valueText, "durability");
                if (value < 0) throw fail(env.language(context.getSource()), "invalid_durability_value");
                if (type.equals("percentage") && value > 100) {
                    throw failKey(env.language(context.getSource()), "commands.enchantplus.error.invalid_durability_percentage", value);
                }
                int remaining = Math.max(0, max - updated.getDamageValue());
                long next = switch (type) {
                    case "damage" -> (long) max - value;
                    case "add" -> (long) remaining + value;
                    case "remove" -> (long) remaining - value;
                    case "percentage" -> Math.round(max * value / 100.0);
                    default -> value;
                };
                int newRemaining = (int) Math.max(0L, Math.min((long) max, next));
                updated.setDamageValue(max - newRemaining);
            }
            replaceMainHand(context, env, target, updated);
            if (repair) {
                feedback(env, context, "commands.enchantplus.success.durability_repair",
                        target.getDisplayName(), max);
            } else {
                int remaining = max - updated.getDamageValue();
                feedback(env, context, "commands.enchantplus.success.durability",
                        target.getDisplayName(), remaining, max);
            }
            changed++;
        }
        if (changed == 0) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.no_durability_item");
        return changed;
    }

    private static <S> int copyNbt(CommandContext<S> context, ClientCommandEnvironment<S> env,
                                   String raw, boolean withSubcommand) throws CommandSyntaxException {
        String input = raw.trim();
        if (withSubcommand) {
            String[] leading = tokens(input);
            if (leading.length == 0) throw fail(env.language(context.getSource()), "usage_copynbt");
            String mode = leading[0].toLowerCase(Locale.ROOT);
            String tail = afterFirstToken(input);
            if (mode.equals("item")) return copyItem(context, env, tail);
            if (mode.equals("entity")) return copyEntity(context, env, tail);
            if (mode.equals("block")) return copyBlock(context, env, tail);
            throw fail(env.language(context.getSource()), "expected_copy_kind");
        }
        return copyItem(context, env, input);
    }

    private static <S> int copyItem(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        Player target;
        String type = "nbt";
        if (args.length == 0) target = requireSelf(context, env);
        else if (args.length == 1 && (args[0].equalsIgnoreCase("nbt") || args[0].equalsIgnoreCase("name"))) {
            target = requireSelf(context, env); type = args[0].toLowerCase(Locale.ROOT);
        } else {
            if (args.length > 2) throw fail(env.language(context.getSource()), "usage_copynbt_item");
            List<Player> selected = resolvePlayers(context, env, args[0]);
            if (selected.size() != 1) throw fail(env.language(context.getSource()), "exactly_one_player");
            target = selected.getFirst();
            if (args.length == 2) type = args[1].toLowerCase(Locale.ROOT);
        }
        if (!type.equals("nbt") && !type.equals("name")) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.copy_invalid_type");
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) throw failKey(env.language(context.getSource()), "commands.enchantplus.error.no_held_item");
        String value;
        if (type.equals("name")) {
            value = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, stack.getHoverName()).result()
                    .orElseThrow(() -> new IllegalStateException("Unable to encode item name.")).toString();
        } else {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, env.registryAccess(context.getSource()));
            output.store(ItemStack.MAP_CODEC, stack);
            value = output.buildResult().toString();
        }
        return sendClipboard(context, env, value);
    }

    private static <S> int copyEntity(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String selector = raw.trim();
        if (selector.isEmpty()) throw fail(env.language(context.getSource()), "usage_copynbt_entity");
        Entity entity = resolveEntity(context, env, selector);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (entityId != null) output.putString("id", entityId.toString());
        return sendClipboard(context, env, output.buildResult().toString());
    }

    private static <S> Entity resolveEntity(CommandContext<S> context, ClientCommandEnvironment<S> env, String selector)
            throws CommandSyntaxException {
        Player self = requireSelf(context, env);
        Level level = env.level(context.getSource());
        if (level == null) throw fail(env.language(context.getSource()), "no_client_world");
        String base = selectorBase(selector);
        Map<String, String> options = selectorOptions(selector);
        List<Entity> matches = new ArrayList<>();

        switch (base) {
            case "@s" -> matches.add(self);
            case "@p", "@r", "@a" -> {
                matches.addAll(level.players());
                applyDistanceFilter(matches, self, options.get("distance"));
                String sort = options.getOrDefault("sort", base.equals("@p") ? "nearest" : base.equals("@r") ? "random" : "arbitrary");
                if (sort.equals("nearest")) matches.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(self)));
                else if (sort.equals("furthest")) matches.sort(Comparator.comparingDouble((Entity entity) -> entity.distanceToSqr(self)).reversed());
                else if (sort.equals("random")) java.util.Collections.shuffle(matches);
                int limit = parseLimit(options.get("limit"), base.equals("@a") ? Integer.MAX_VALUE : 1);
                if (matches.size() > limit) matches = new ArrayList<>(matches.subList(0, limit));
            }
            case "@e" -> {
                AABB area = self.getBoundingBox().inflate(128.0);
                matches.add(self);
                matches.addAll(level.getEntities(self, area, entity -> true));
                String type = options.get("type");
                if (type != null) {
                    boolean inverted = type.startsWith("!");
                    String expected = inverted ? type.substring(1) : type;
                    matches.removeIf(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(expected) == inverted);
                }
                String name = options.get("name");
                if (name != null) {
                    boolean inverted = name.startsWith("!");
                    String expected = inverted ? name.substring(1) : name;
                    matches.removeIf(entity -> entity.getName().getString().equals(expected) == inverted);
                }
                applyDistanceFilter(matches, self, options.get("distance"));
                String sort = options.getOrDefault("sort", "arbitrary");
                if (sort.equals("nearest")) matches.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(self)));
                else if (sort.equals("furthest")) matches.sort(Comparator.comparingDouble((Entity entity) -> entity.distanceToSqr(self)).reversed());
                else if (sort.equals("random")) java.util.Collections.shuffle(matches);
                int limit = parseLimit(options.get("limit"), Integer.MAX_VALUE);
                if (matches.size() > limit) matches = new ArrayList<>(matches.subList(0, limit));
            }
            default -> {
                AABB area = self.getBoundingBox().inflate(128.0);
                if (self.getName().getString().equalsIgnoreCase(selector)) matches.add(self);
                matches.addAll(level.getEntities(self, area, entity -> entity.getName().getString().equalsIgnoreCase(selector)));
                if (matches.isEmpty()) {
                    try {
                        UUID uuid = UUID.fromString(selector);
                        if (self.getUUID().equals(uuid)) matches.add(self);
                        else matches.addAll(level.getEntities(self, area, entity -> entity.getUUID().equals(uuid)));
                    } catch (IllegalArgumentException ignored) { }
                }
            }
        }

        if (matches.size() != 1) throw fail(env.language(context.getSource()), matches.isEmpty()
                ? "entity_not_tracked" : "entity_multiple_matches");
        return matches.getFirst();
    }

    private static <S> int copyBlock(CommandContext<S> context, ClientCommandEnvironment<S> env, String raw)
            throws CommandSyntaxException {
        String[] args = tokens(raw);
        if (args.length == 0) throw fail(env.language(context.getSource()), "usage_copynbt_block");
        if (args.length < 3) throw fail(env.language(context.getSource()), "block_coordinates_three");
        BlockPos pos = parseBlockPos(context, env, args);
        Level level = env.level(context.getSource());
        if (level == null || !level.hasChunkAt(pos)) {
            throw fail(env.language(context.getSource()), "block_not_loaded");
        }
        BlockState state = level.getBlockState(pos);
        StringBuilder value = new StringBuilder(serializeBlockState(state));
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) value.append(blockEntity.saveWithFullMetadata(env.registryAccess(context.getSource())));
        return sendClipboard(context, env, value.toString());
    }

    private static <S> BlockPos parseBlockPos(CommandContext<S> context, ClientCommandEnvironment<S> env, String[] args)
            throws CommandSyntaxException {
        if (args.length != 3) throw fail(env.language(context.getSource()), "block_coordinates_exactly_three");
        Player player = requireSelf(context, env);
        double[] origin = { player.getX(), player.getY(), player.getZ() };
        int[] values = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                String token = args[i];
                if (token.startsWith("^")) throw fail(env.language(context.getSource()), "local_coordinates_unsupported");
                values[i] = token.startsWith("~")
                        ? (int) Math.floor(origin[i] + (token.length() == 1 ? 0.0 : Double.parseDouble(token.substring(1))))
                        : Integer.parseInt(token);
            }
        } catch (NumberFormatException exception) {
            throw fail(env.language(context.getSource()), "invalid_block_position");
        }
        return new BlockPos(values[0], values[1], values[2]);
    }

    private static String serializeBlockState(BlockState state) {
        return state.getValues().map(value -> serializeStateProperty(value.property(), value.value()))
                .sorted().collect(Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String serializeStateProperty(Property<?> property, Comparable<?> value) {
        Property raw = property;
        return property.getName() + "=" + raw.getName((Comparable) value);
    }

    private static <S> int sendClipboard(CommandContext<S> context, ClientCommandEnvironment<S> env, String value)
            throws CommandSyntaxException {
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_COPY_LENGTH) {
            throw failKey(env.language(context.getSource()), "commands.enchantplus.error.copy_too_large", MAX_COPY_LENGTH);
        }
        env.copyToClipboard(context.getSource(), value);
        String language = env.language(context.getSource());
        Component clickable = Component.literal(value).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withUnderlined(true).withClickEvent(new ClickEvent.CopyToClipboard(value))
                .withHoverEvent(new HoverEvent.ShowText(
                        CommandLocalization.translate(language, "commands.enchantplus.copy.hover"))));
        Component message = CommandLocalization.translate(language, "commands.enchantplus.copy.ready")
                .append(Component.literal("\n"))
                .append(clickable);
        env.sendFeedback(context.getSource(), message);
        return 1;
    }

    private static <S> int showInfo(CommandContext<S> context, ClientCommandEnvironment<S> env) {
        String language = env.language(context.getSource());
        Component message = CommandLocalization.translate(language, "commands.enchantplus.info.header")
                .append(Component.literal("\n"))
                .append(CommandLocalization.translate(language, "commands.enchantplus.info.modrinth")
                        .withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(MODRINTH_URI))
                                .withHoverEvent(new HoverEvent.ShowText(CommandLocalization.translate(
                                        language, "commands.enchantplus.info.hover_modrinth")))))
                .append(Component.literal("  |  "))
                .append(CommandLocalization.translate(language, "commands.enchantplus.info.discord")
                        .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(DISCORD_URI))
                                .withHoverEvent(new HoverEvent.ShowText(CommandLocalization.translate(
                                        language, "commands.enchantplus.info.hover_discord")))));
        env.sendFeedback(context.getSource(), message);
        return 1;
    }

    private static <S> void sendEntityItemReplace(CommandContext<S> context, ClientCommandEnvironment<S> env,
                                                   Entity entity, ItemStack updated) throws CommandSyntaxException {
        Player self = requireSelf(context, env);
        String selector;
        if (entity.getUUID().equals(self.getUUID())) {
            selector = "@s";
        } else {
            selector = uuidSelector(entity.getUUID());
        }
        String item = itemStackArgument(updated, env.registryAccess(context.getSource()));
        env.sendVanillaCommand(context.getSource(), "item replace entity " + selector + " weapon.mainhand with " + item);
    }

    private static <S> Holder.Reference<Attribute> resolveAttributeFallback(ClientCommandEnvironment<S> env, S source, String text)
            throws CommandSyntaxException {
        return resolveAttribute(env, source, text);
    }

    private static <S> Holder<MobEffect> resolveEffectFallback(ClientCommandEnvironment<S> env, S source, String text)
            throws CommandSyntaxException {
        return resolveHolder(env, source, Registries.MOB_EFFECT, text, "effect");
    }

    private static <S> Holder<Enchantment> resolveEnchantmentFallback(ClientCommandEnvironment<S> env, S source, String text)
            throws CommandSyntaxException {
        return resolveHolder(env, source, Registries.ENCHANTMENT, text, "enchantment");
    }

    private static <S, T> Holder.Reference<T> resolveHolder(ClientCommandEnvironment<S> env, S source,
                                                             net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> registryKey,
                                                             String text, String kind) throws CommandSyntaxException {
        Identifier identifier = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text);
        if (identifier == null) throw fail(env.language(source), "invalid_identifier", kind, text);
        ResourceKey<T> key = ResourceKey.create(registryKey, identifier);
        Optional<Holder.Reference<T>> resolved = env.registryAccess(source).lookupOrThrow(registryKey).get(key);
        if (resolved.isEmpty()) throw fail(env.language(source), "unknown_identifier", kind, text);
        return resolved.get();
    }

    private static <S> int parseInt(CommandContext<S> context, ClientCommandEnvironment<S> env, String value, String what)
            throws CommandSyntaxException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw fail(env.language(context.getSource()), "invalid_number", what, value); }
    }

    private static <S> Player requireSelf(CommandContext<S> context, ClientCommandEnvironment<S> env)
            throws CommandSyntaxException {
        Player player = env.player(context.getSource());
        if (player == null) throw fail(env.language(context.getSource()), "in_world_player_required");
        return player;
    }

    private static <S> void feedback(ClientCommandEnvironment<S> env, CommandContext<S> context,
                                     String key, Object... args) {
        env.sendFeedback(context.getSource(), CommandLocalization.translate(
                env.language(context.getSource()), key, args));
    }
}
