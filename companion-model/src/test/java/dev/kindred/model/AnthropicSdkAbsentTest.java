package dev.kindred.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: the release jar carries this module but not the Anthropic SDK (release-manifest.json). Loaded as the
 * release jar loads it, with no SDK on the classpath, asking for the Anthropic provider fails with a LinkageError at
 * the moment the strategist is built, the error the server's bridge catches at start
 * ({@code StrategistBridge.fromEnvironment}, which then plays without a strategist); every other provider is built
 * as before. Nothing else in the module reaches the SDK (F-19f).
 */
class AnthropicSdkAbsentTest {
    /** This module's classes, core's and Gson's, as the release jar has them: no SDK. */
    private static ClassLoader releaseShaped() {
        URL[] urls = {
                Strategists.class.getProtectionDomain().getCodeSource().getLocation(),
                dev.kindred.core.survival.StrategyAdvice.class.getProtectionDomain().getCodeSource().getLocation(),
                com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation()};
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    private static Object build(ClassLoader loader, Map<String, String> env) throws Throwable {
        var built = Class.forName("dev.kindred.model.Strategists", true, loader).getMethod("fromEnvironment", Map.class);
        try {
            return built.invoke(null, env);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void withoutTheSdkTheAnthropicProviderFailsAsALinkageErrorWhenBuiltAndTheOthersAreUntouched() throws Throwable {
        ClassLoader loader = releaseShaped();
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.anthropic.client.AnthropicClient", false, loader),
                "the SDK is not on this classpath");
        // A placeholder, not a key: the build fails before any client exists or any request is made.
        var anthropic = Map.of("KINDRED_STRATEGIST_PROVIDER", "anthropic", "ANTHROPIC_API_KEY", "placeholder");
        LinkageError missing = assertThrows(LinkageError.class, () -> build(loader, anthropic));
        assertTrue(String.valueOf(missing.getMessage()).contains("com/anthropic"), "names the SDK: " + missing);

        // Another provider, the same classpath: built, no SDK needed.
        Object other = build(loader, Map.of("KINDRED_STRATEGIST_PROVIDER", "openai", "OPENAI_API_KEY", "placeholder"));
        Object strategist = other.getClass().getMethod("strategist").invoke(other);
        assertTrue(strategist instanceof Optional<?> o && o.isPresent(), "the OpenAI-compatible strategist built: " + other);
        assertEquals("dev.kindred.model.OpenAiCompatibleStrategist", ((Optional<?>) strategist).get().getClass().getName());
    }
}
