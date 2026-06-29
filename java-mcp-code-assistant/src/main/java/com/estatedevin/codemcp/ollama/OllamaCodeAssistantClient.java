package com.estatedevin.codemcp.ollama;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class OllamaCodeAssistantClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaCodeAssistantClient.class);

    static final int MAX_SYSTEM_PROMPT_LENGTH = 50_000;
    static final int MAX_USER_PROMPT_LENGTH = 100_000;

    private final ChatClient chatClient;
    private final OllamaCodeAssistantProperties properties;

    public OllamaCodeAssistantClient(ChatClient.Builder chatClientBuilder, OllamaCodeAssistantProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
    }

    public String complete(String systemPrompt, String userPrompt) {
        validatePrompts(systemPrompt, userPrompt);
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new OllamaCodeAssistantException("Ollama returned an empty response.", null);
            }
            return content;
        }
        catch (OllamaCodeAssistantException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw translate(ex);
        }
    }

    private static void validatePrompts(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be null or blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be null or blank");
        }
        if (systemPrompt.length() > MAX_SYSTEM_PROMPT_LENGTH) {
            throw new IllegalArgumentException(
                    "systemPrompt exceeds maximum length of %d characters".formatted(MAX_SYSTEM_PROMPT_LENGTH));
        }
        if (userPrompt.length() > MAX_USER_PROMPT_LENGTH) {
            throw new IllegalArgumentException(
                    "userPrompt exceeds maximum length of %d characters".formatted(MAX_USER_PROMPT_LENGTH));
        }
    }

    private OllamaCodeAssistantException translate(RuntimeException ex) {
        Throwable root = rootCause(ex);
        LOGGER.warn("Ollama request failed: {}", root.getMessage(), ex);
        String message = switch (root) {
            case ConnectException ignored -> "Cannot connect to Ollama. Verify OLLAMA_HOST and local network reachability.";
            case SocketTimeoutException ignored -> timeoutMessage(properties.requestTimeout());
            case TimeoutException ignored -> timeoutMessage(properties.requestTimeout());
            default -> {
                String rootMessage = root.getMessage() == null ? "" : root.getMessage().toLowerCase();
                if (rootMessage.contains("model")) {
                    yield "The configured Ollama model is unavailable. Verify it has been pulled on the target host.";
                }
                yield "Ollama request failed. Check server logs for details.";
            }
        };
        return new OllamaCodeAssistantException(message, ex);
    }

    private static String timeoutMessage(Duration timeout) {
        return "Timed out waiting for Ollama after %s. Increase OLLAMA_REQUEST_TIMEOUT for high-latency local networks."
                .formatted(timeout);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
