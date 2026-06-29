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

    private final ChatClient chatClient;
    private final OllamaCodeAssistantProperties properties;

    public OllamaCodeAssistantClient(ChatClient.Builder chatClientBuilder, OllamaCodeAssistantProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
    }

    public String complete(String systemPrompt, String userPrompt) {
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new OllamaCodeAssistantException(
                        "Ollama returned an empty response for model %s".formatted(properties.model()), null);
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

    private OllamaCodeAssistantException translate(RuntimeException ex) {
        Throwable root = OllamaUtils.rootCause(ex);
        String model = properties.model();
        String message = switch (root) {
            case ConnectException ignored -> "Cannot connect to Ollama. Verify OLLAMA_HOST and local network reachability.";
            case SocketTimeoutException ignored -> timeoutMessage(properties.requestTimeout());
            case TimeoutException ignored -> timeoutMessage(properties.requestTimeout());
            default -> {
                String rootMessage = root.getMessage() == null ? "unknown error" : root.getMessage();
                if (rootMessage.toLowerCase().contains("model") && rootMessage.contains(model)) {
                    yield "Ollama model %s is unavailable. Run `ollama pull %s` on the target host."
                            .formatted(model, model);
                }
                yield "Ollama request failed for model %s: %s".formatted(model, rootMessage);
            }
        };
        LOGGER.warn(message);
        return new OllamaCodeAssistantException(message, ex);
    }

    private static String timeoutMessage(Duration timeout) {
        return "Timed out waiting for Ollama after %s. Increase OLLAMA_REQUEST_TIMEOUT for high-latency local networks."
                .formatted(timeout);
    }
}
