package dev.kim.enchantplus.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the mod's messages on the dedicated server using each command sender's
 * Minecraft client language. The returned components are literal text, so clients
 * do not need this mod or its language JSON files installed.
 */
public final class CommandLocalization {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?s");

    private CommandLocalization() {
    }

    public static MutableComponent translate(CommandSourceStack source, String key, Object... arguments) {
        Entity entity = source.getEntity();
        String language = entity instanceof ServerPlayer player
                ? player.clientInformation().language()
                : DEFAULT_LANGUAGE;
        return translate(language, key, arguments);
    }

    public static MutableComponent translate(String language, String key, Object... arguments) {
        String normalized = normalizeLanguage(language);
        String template = LANGUAGES.computeIfAbsent(normalized, CommandLocalization::loadLanguage).get(key);
        if (template == null && !DEFAULT_LANGUAGE.equals(normalized)) {
            template = LANGUAGES.computeIfAbsent(DEFAULT_LANGUAGE, CommandLocalization::loadLanguage).get(key);
        }
        if (template == null) {
            template = key;
        }
        return format(template, arguments);
    }

    public static void sendSuccess(CommandContext<CommandSourceStack> context, boolean broadcastToAdmins,
                                   String key, Object... arguments) {
        Component message = translate(context.getSource(), key, arguments);
        context.getSource().sendSuccess(() -> message, broadcastToAdmins);
    }

    public static CommandSyntaxException error(CommandContext<CommandSourceStack> context,
                                               String key, Object... arguments) {
        return new SimpleCommandExceptionType(translate(context.getSource(), key, arguments)).create();
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = language.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[a-z]{2,3}_[a-z]{2,4}")) {
            return DEFAULT_LANGUAGE;
        }
        return normalized;
    }

    private static Map<String, String> loadLanguage(String language) {
        String path = "/assets/enchantplus/lang/" + language + ".json";
        try (InputStream stream = CommandLocalization.class.getResourceAsStream(path)) {
            if (stream == null) {
                return Map.of();
            }
            JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> translations = new java.util.HashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return Map.copyOf(translations);
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
    }

    private static MutableComponent format(String template, Object... arguments) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        MutableComponent result = Component.empty();
        int previousEnd = 0;
        int nextSequentialArgument = 0;
        while (matcher.find()) {
            if (matcher.start() > previousEnd) {
                result.append(Component.literal(template.substring(previousEnd, matcher.start())));
            }
            int argumentIndex;
            if (matcher.group(1) != null) {
                argumentIndex = Integer.parseInt(matcher.group(1)) - 1;
            } else {
                argumentIndex = nextSequentialArgument++;
            }
            if (argumentIndex >= 0 && argumentIndex < arguments.length) {
                Object argument = arguments[argumentIndex];
                result.append(argument instanceof Component component
                        ? component
                        : Component.literal(String.valueOf(argument)));
            } else {
                result.append(Component.literal(matcher.group()));
            }
            previousEnd = matcher.end();
        }
        if (previousEnd < template.length()) {
            result.append(Component.literal(template.substring(previousEnd)));
        }
        return result;
    }
}
