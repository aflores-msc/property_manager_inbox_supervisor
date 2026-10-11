package com.estatebeacon.agent;

import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/**
 * Shared shape for every graph node that extracts one structured record from the email with a
 * single LLM call: build a prompt from state, ask the model for provider-native structured
 * output, store the result under one state key on success, or record {@link PropertyManagementState#ERROR}
 * on failure. {@code SupervisorNode}, {@code LegalAgentNode}, {@code MaintenanceAgentNode}, and
 * {@code DisputeAgentNode} differ only in their prompt, target record type, and state key.
 */
abstract class AbstractLlmExtractionNode<T> implements NodeAction<PropertyManagementState> {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ChatClient chatClient;
    private final Class<T> resultType;
    private final String resultKey;
    private final String name;

    protected AbstractLlmExtractionNode(ChatClient chatClient, Class<T> resultType, String resultKey, String name) {
        this.chatClient = chatClient;
        this.resultType = resultType;
        this.resultKey = resultKey;
        this.name = name;
    }

    /** Builds the user prompt for this node's extraction call from the current state. */
    protected abstract String buildPrompt(PropertyManagementState state);

    @Override
    public final Map<String, Object> apply(PropertyManagementState state) {
        try {
            T result = chatClient.prompt()
                    .user(buildPrompt(state))
                    .call()
                    .entity(resultType, spec -> spec.useProviderStructuredOutput());
            return Map.of(resultKey, result);
        } catch (Exception e) {
            log.error("{} failed", name, e);
            return Map.of(PropertyManagementState.ERROR, name + " extraction failed: " + e.getMessage());
        }
    }
}
