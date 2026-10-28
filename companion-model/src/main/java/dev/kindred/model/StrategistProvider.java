package dev.kindred.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * In plain terms: works out which AI service to use from the server owner's settings: one named outright,
 * else one recognised from its key, else the first whose key is set. Each comes with a small, quick default
 * model that can be swapped for another.
 *
 * Technical: Which provider serves the strategist, chosen from the operator's settings (F-19d). An explicit
 * {@code KINDRED_STRATEGIST_PROVIDER} wins; else a generic {@code KINDRED_STRATEGIST_API_KEY} is recognised by its
 * prefix; else the first provider whose own key variable is set, in the order below. Each provider has a small, fast
 * default model of the kind the strategist needs, and {@code KINDRED_STRATEGIST_MODEL} replaces it;
 * {@code KINDRED_STRATEGIST_BASE_URL} points an OpenAI-compatible provider elsewhere (a proxy, or a local server such
 * as Ollama or LM Studio, which need no key). Pure: reads only the map it is given.
 */
public final class StrategistProvider {
    private StrategistProvider() { }

    /** How the reply's shape is asked for: a JSON schema where the provider enforces one, else plain JSON mode. */
    public enum Shape { SDK, JSON_SCHEMA, JSON_OBJECT }

    public enum Provider {
        ANTHROPIC("Anthropic", "ANTHROPIC_API_KEY", ClaudeStrategist.DEFAULT_MODEL, null, Shape.SDK),
        OPENAI("OpenAI", "OPENAI_API_KEY", "gpt-4o-mini", "https://api.openai.com/v1", Shape.JSON_SCHEMA),
        GEMINI("Google Gemini", "GEMINI_API_KEY", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com/v1beta/openai", Shape.JSON_SCHEMA),
        GROQ("Groq", "GROQ_API_KEY", "openai/gpt-oss-20b", "https://api.groq.com/openai/v1", Shape.JSON_SCHEMA),
        MISTRAL("Mistral", "MISTRAL_API_KEY", "mistral-small-latest", "https://api.mistral.ai/v1", Shape.JSON_SCHEMA),
        DEEPSEEK("DeepSeek", "DEEPSEEK_API_KEY", "deepseek-chat", "https://api.deepseek.com", Shape.JSON_OBJECT),
        /** xAI's line-up changes too often to pick for the operator: the model must be named. */
        XAI("xAI", "XAI_API_KEY", null, "https://api.x.ai/v1", Shape.JSON_SCHEMA),
        OPENROUTER("OpenRouter", "OPENROUTER_API_KEY", "openai/gpt-4o-mini", "https://openrouter.ai/api/v1", Shape.JSON_SCHEMA),
        /** Any server speaking OpenAI's chat completions: the base URL and model must be named; a key is optional. */
        COMPATIBLE("OpenAI-compatible", null, null, null, Shape.JSON_SCHEMA);

        final String label, keyVariable, defaultModel, baseUrl;
        final Shape shape;

        Provider(String label, String keyVariable, String defaultModel, String baseUrl, Shape shape) {
            this.label = label;
            this.keyVariable = keyVariable;
            this.defaultModel = defaultModel;
            this.baseUrl = baseUrl;
            this.shape = shape;
        }

        public String label() { return label; }
        public Shape shape() { return shape; }
    }

    /** A provider ready to ask, or why none could be chosen ({@code problem}), or neither when nothing is set. */
    public record Selection(Optional<Choice> choice, Optional<String> problem) {
        static Selection none() { return new Selection(Optional.empty(), Optional.empty()); }
        static Selection problem(String why) { return new Selection(Optional.empty(), Optional.of(why)); }
        static Selection of(Choice choice) { return new Selection(Optional.of(choice), Optional.empty()); }
    }

    /** The key is carried only to the provider's client; it is never part of {@link #toString()}. */
    public record Choice(Provider provider, String apiKey, String model, String baseUrl) {
        @Override public String toString() { return provider.label + " " + model + (baseUrl == null ? "" : " at " + baseUrl); }
    }

    /** The service the settings point to, or why none could be chosen. */
    public static Selection choose(Map<String, String> env) {
        String named = value(env, "KINDRED_STRATEGIST_PROVIDER");
        String generic = value(env, "KINDRED_STRATEGIST_API_KEY");
        Provider provider;
        String key;
        if (named != null) {
            provider = parse(named);
            if (provider == null) return Selection.problem("unknown KINDRED_STRATEGIST_PROVIDER '" + named + "'");
            key = generic != null ? generic : provider.keyVariable == null ? null : keyFor(env, provider);
        } else if (generic != null) {
            provider = byPrefix(generic);
            if (provider == null) return Selection.problem("KINDRED_STRATEGIST_API_KEY's provider is not recognisable from the key; set KINDRED_STRATEGIST_PROVIDER");
            key = generic;
        } else {
            provider = null;
            key = null;
            for (Provider candidate : Provider.values()) {
                if (candidate.keyVariable == null) continue;
                String found = keyFor(env, candidate);
                if (found != null) { provider = candidate; key = found; break; }
            }
            if (provider == null) {
                // A local server needs no key: a base URL and a model are enough.
                if (value(env, "KINDRED_STRATEGIST_BASE_URL") != null) provider = Provider.COMPATIBLE;
                else return Selection.none();
            }
        }
        if (key == null && provider != Provider.COMPATIBLE) return Selection.problem(provider.label + " needs an API key (" + provider.keyVariable + ")");
        String model = value(env, "KINDRED_STRATEGIST_MODEL");
        if (model == null) model = provider.defaultModel;
        if (model == null) return Selection.problem(provider.label + " needs KINDRED_STRATEGIST_MODEL");
        String baseUrl = value(env, "KINDRED_STRATEGIST_BASE_URL");
        if (baseUrl == null) baseUrl = provider.baseUrl;
        if (baseUrl == null && provider != Provider.ANTHROPIC) return Selection.problem(provider.label + " needs KINDRED_STRATEGIST_BASE_URL");
        if (baseUrl != null) baseUrl = baseUrl.replaceAll("/+$", "");
        return Selection.of(new Choice(provider, key, model, provider == Provider.ANTHROPIC ? null : baseUrl));
    }

    /** The provider a key belongs to, by the prefixes the providers use; DeepSeek's and Mistral's are not distinctive. */
    static Provider byPrefix(String key) {
        if (key.startsWith("sk-ant-")) return Provider.ANTHROPIC;
        if (key.startsWith("sk-or-")) return Provider.OPENROUTER;
        if (key.startsWith("gsk_")) return Provider.GROQ;
        if (key.startsWith("xai-")) return Provider.XAI;
        if (key.startsWith("AIza")) return Provider.GEMINI;
        if (key.startsWith("sk-")) return Provider.OPENAI;
        return null;
    }

    private static Provider parse(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (n) {
            case "anthropic", "claude" -> Provider.ANTHROPIC;
            case "openai", "gpt" -> Provider.OPENAI;
            case "gemini", "google" -> Provider.GEMINI;
            case "groq" -> Provider.GROQ;
            case "mistral" -> Provider.MISTRAL;
            case "deepseek" -> Provider.DEEPSEEK;
            case "xai", "grok" -> Provider.XAI;
            case "openrouter" -> Provider.OPENROUTER;
            case "openai-compatible", "compatible", "ollama", "lmstudio", "lm-studio", "local" -> Provider.COMPATIBLE;
            default -> null;
        };
    }

    private static String keyFor(Map<String, String> env, Provider provider) {
        String key = value(env, provider.keyVariable);
        if (key == null && provider == Provider.GEMINI) key = value(env, "GOOGLE_API_KEY");
        return key;
    }

    private static String value(Map<String, String> env, String name) {
        if (name == null) return null;
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
