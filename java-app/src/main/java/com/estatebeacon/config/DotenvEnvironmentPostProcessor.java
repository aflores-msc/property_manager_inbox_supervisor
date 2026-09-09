package com.estatebeacon.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reads {@code ~/.property_manager_ai/.env} — the same file the Python
 * implementation uses — and maps its flat KEY=VALUE entries onto the Spring
 * property names the rest of the app expects. This lets both implementations
 * share one set of user-provided secrets.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Map<String, String> KEY_MAPPING = Map.of(
            "GOOGLE_API_KEY", "spring.ai.google.genai.api-key",
            "GEMINI_MODEL_NAME", "spring.ai.google.genai.chat.model",
            "MANAGER_EMAIL", "estate-beacon.manager-email",
            "EMAIL_USERNAME", "estate-beacon.email-username"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = AppPaths.appFile(".env");
        if (!Files.isRegularFile(envFile)) {
            return;
        }

        Properties raw = new Properties();
        try (var reader = Files.newBufferedReader(envFile)) {
            raw.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + envFile, e);
        }

        Map<String, Object> mapped = new HashMap<>();
        raw.forEach((key, value) -> mapped.put(
                KEY_MAPPING.getOrDefault(String.valueOf(key), String.valueOf(key)),
                value
        ));

        environment.getPropertySources().addFirst(new MapPropertySource("estate-beacon-dotenv", mapped));
    }
}
