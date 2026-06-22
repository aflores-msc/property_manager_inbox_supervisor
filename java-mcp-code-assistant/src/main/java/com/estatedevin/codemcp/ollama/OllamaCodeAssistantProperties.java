package com.estatedevin.codemcp.ollama;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "code-assistant.ollama")
public record OllamaCodeAssistantProperties(
        @NotBlank String model,
        @NotNull Duration requestTimeout,
        @NotNull Duration connectionTimeout) {
}
