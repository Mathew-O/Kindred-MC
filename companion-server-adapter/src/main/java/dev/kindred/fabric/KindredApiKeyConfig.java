package dev.kindred.fabric;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Manages external LLM API keys for autonomous player reasoning and speech.
 * Keys are persisted in config/kindred_api_keys.properties and masked in all chat outputs.
 */
public final class KindredApiKeyConfig {

    private static final Path CONFIG_FILE = Paths.get("config", "kindred_api_keys.properties");

    private KindredApiKeyConfig() { }

    public static String getAnthropicKey() {
        String prop = System.getProperty("ANTHROPIC_API_KEY");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        return loadStoredProperty("anthropic_key");
    }

    public static String getOpenAiKey() {
        String prop = System.getProperty("OPENAI_API_KEY");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("OPENAI_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        return loadStoredProperty("openai_key");
    }

    public static synchronized boolean setKey(String provider, String key) {
        String propName = provider.equalsIgnoreCase("openai") ? "openai_key" : "anthropic_key";
        String sysProp = provider.equalsIgnoreCase("openai") ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY";
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Properties props = new Properties();
            if (Files.exists(CONFIG_FILE)) {
                try (var in = Files.newInputStream(CONFIG_FILE)) {
                    props.load(in);
                }
            }
            props.setProperty(propName, key.trim());
            try (var out = Files.newOutputStream(CONFIG_FILE)) {
                props.store(out, "Kindred AI Player API Configuration");
            }
            System.setProperty(sysProp, key.trim());
            return true;
        } catch (IOException e) {
            KindredMod.LOGGER.error("Failed to save API key to {}", CONFIG_FILE, e);
            return false;
        }
    }

    public static String mask(String key) {
        if (key == null || key.isBlank()) return "NOT SET";
        int len = key.length();
        if (len <= 8) return "****";
        String prefix = key.substring(0, Math.min(7, len));
        String suffix = key.substring(Math.max(0, len - 4));
        return prefix + "..." + suffix;
    }

    private static String loadStoredProperty(String propName) {
        if (!Files.exists(CONFIG_FILE)) return null;
        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(CONFIG_FILE)) {
                props.load(in);
            }
            String val = props.getProperty(propName);
            return (val != null && !val.isBlank()) ? val.trim() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    public static void displayStatusAndPrompts(ServerCommandSource source) {
        String anthropic = getAnthropicKey();
        String openai = getOpenAiKey();

        source.sendFeedback(() -> Text.literal("§6--- Kindred AI Player API Configuration ---"), false);
        source.sendFeedback(() -> Text.literal("Anthropic Claude API: ")
                .append(Text.literal(mask(anthropic)).formatted(anthropic != null ? Formatting.GREEN : Formatting.RED)), false);
        source.sendFeedback(() -> Text.literal("OpenAI GPT API:      ")
                .append(Text.literal(mask(openai)).formatted(openai != null ? Formatting.GREEN : Formatting.RED)), false);

        Text anthropicBtn = Text.literal("§e▶ §6[Click to Enter Anthropic Claude API Key]")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.GOLD)
                        .withUnderline(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/aiplayer apikey anthropic "))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to pre-fill chat command with /aiplayer apikey anthropic <your_key>"))));

        Text openaiBtn = Text.literal("§b▶ §3[Click to Enter OpenAI API Key]")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.AQUA)
                        .withUnderline(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/aiplayer apikey openai "))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to pre-fill chat command with /aiplayer apikey openai <your_key>"))));

        source.sendFeedback(() -> anthropicBtn, false);
        source.sendFeedback(() -> openaiBtn, false);
        source.sendFeedback(() -> Text.literal("§7Keys are stored locally in config/kindred_api_keys.properties and never logged publicly."), false);
    }
}
