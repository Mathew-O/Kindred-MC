package dev.kindred.model;

import dev.kindred.model.StrategistProvider.Provider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how the server picks which optional AI service to use from its settings: each
 * service's own key picks it with a small, fast default, a service named outright wins, a general key is
 * recognised by how it starts, a server on the same machine needs no key, nothing set means no adviser, and
 * keys never show in the log.
 */
class StrategistProviderTest {
    private static StrategistProvider.Choice chosen(Map<String, String> env) {
        var selection = StrategistProvider.choose(env);
        assertTrue(selection.choice().isPresent(), "a provider: " + selection.problem());
        return selection.choice().get();
    }

    private static String problem(Map<String, String> env) {
        var selection = StrategistProvider.choose(env);
        assertTrue(selection.choice().isEmpty());
        return selection.problem().orElse("");
    }

    @Test
    void eachProvidersOwnKeyBringsItsSmallFastModel() {
        var claude = chosen(Map.of("ANTHROPIC_API_KEY", "sk-ant-x"));
        assertEquals(Provider.ANTHROPIC, claude.provider());
        assertEquals("claude-haiku-5-5", claude.model());
        assertNull(claude.baseUrl());
        var openai = chosen(Map.of("OPENAI_API_KEY", "sk-proj-x"));
        assertEquals("gpt-4o-mini", openai.model());
        assertEquals("https://api.openai.com/v1", openai.baseUrl());
        assertEquals(Provider.GEMINI, chosen(Map.of("GOOGLE_API_KEY", "AIza-x")).provider());
        assertEquals("gemini-3.5-flash-lite", chosen(Map.of("GEMINI_API_KEY", "AIza-x")).model());
        assertEquals("openai/gpt-oss-20b", chosen(Map.of("GROQ_API_KEY", "gsk_x")).model());
        assertEquals("mistral-small-latest", chosen(Map.of("MISTRAL_API_KEY", "m")).model());
        assertEquals("deepseek-chat", chosen(Map.of("DEEPSEEK_API_KEY", "sk-d")).model());
        assertEquals("openai/gpt-4o-mini", chosen(Map.of("OPENROUTER_API_KEY", "sk-or-x")).model());
    }

    @Test
    void anExplicitProviderWinsAndKeysAreTriedInOrder() {
        var both = Map.of("ANTHROPIC_API_KEY", "sk-ant-x", "OPENAI_API_KEY", "sk-proj-y");
        assertEquals(Provider.ANTHROPIC, chosen(both).provider(), "Anthropic first in the order");
        var openai = chosen(Map.of("ANTHROPIC_API_KEY", "sk-ant-x", "OPENAI_API_KEY", "sk-proj-y", "KINDRED_STRATEGIST_PROVIDER", "OpenAI"));
        assertEquals(Provider.OPENAI, openai.provider());
        assertEquals("sk-proj-y", openai.apiKey(), "its own key");
        assertTrue(problem(Map.of("KINDRED_STRATEGIST_PROVIDER", "openai")).contains("OPENAI_API_KEY"));
        assertTrue(problem(Map.of("KINDRED_STRATEGIST_PROVIDER", "nope", "OPENAI_API_KEY", "k")).contains("unknown"));
    }

    @Test
    void aGenericKeyIsRecognisedByItsPrefix() {
        assertEquals(Provider.ANTHROPIC, chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "sk-ant-api03-x")).provider());
        assertEquals(Provider.OPENAI, chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "sk-proj-x")).provider());
        assertEquals(Provider.GROQ, chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "gsk_x")).provider());
        assertEquals(Provider.OPENROUTER, chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "sk-or-v1-x")).provider());
        assertEquals(Provider.GEMINI, chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "AIzaSy")).provider());
        assertEquals(Provider.XAI, chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "xai-x", "KINDRED_STRATEGIST_MODEL", "grok-m")).provider());
        assertTrue(problem(Map.of("KINDRED_STRATEGIST_API_KEY", "plainkey")).contains("KINDRED_STRATEGIST_PROVIDER"));
        // A key without a distinctive prefix, with its provider named.
        var mistral = chosen(Map.of("KINDRED_STRATEGIST_API_KEY", "plainkey", "KINDRED_STRATEGIST_PROVIDER", "mistral"));
        assertEquals(Provider.MISTRAL, mistral.provider());
        assertEquals("plainkey", mistral.apiKey());
    }

    @Test
    void modelAndAddressCanBeNamedAndSomeProvidersNeedThem() {
        assertTrue(problem(Map.of("XAI_API_KEY", "xai-x")).contains("KINDRED_STRATEGIST_MODEL"), "xAI's model must be named");
        var named = chosen(Map.of("OPENAI_API_KEY", "sk-x", "KINDRED_STRATEGIST_MODEL", "gpt-4.1-nano",
                "KINDRED_STRATEGIST_BASE_URL", "https://proxy.example/v1/"));
        assertEquals("gpt-4.1-nano", named.model());
        assertEquals("https://proxy.example/v1", named.baseUrl(), "a trailing slash is dropped");
        // A local server: no key at all.
        var local = chosen(Map.of("KINDRED_STRATEGIST_BASE_URL", "http://localhost:11434/v1", "KINDRED_STRATEGIST_MODEL", "llama3.2"));
        assertEquals(Provider.COMPATIBLE, local.provider());
        assertNull(local.apiKey());
        assertTrue(problem(Map.of("KINDRED_STRATEGIST_BASE_URL", "http://localhost:11434/v1")).contains("KINDRED_STRATEGIST_MODEL"));
        assertTrue(problem(Map.of("KINDRED_STRATEGIST_PROVIDER", "ollama", "KINDRED_STRATEGIST_MODEL", "m")).contains("BASE_URL"));
    }

    @Test
    void nothingSetChoosesNothingAndKeysNeverShowInTheLog() {
        var none = StrategistProvider.choose(Map.of());
        assertTrue(none.choice().isEmpty() && none.problem().isEmpty());
        assertFalse(chosen(Map.of("OPENAI_API_KEY", "sk-secret-123")).toString().contains("secret"));
    }
}
