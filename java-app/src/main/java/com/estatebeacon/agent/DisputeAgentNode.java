package com.estatebeacon.agent;

import com.estatebeacon.model.DisputeSchema;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Extracts {@link DisputeSchema} details. Mirrors {@code agents.py:dispute_agent}. */
@Component
public class DisputeAgentNode implements NodeAction<PropertyManagementState> {

    private static final Logger log = LoggerFactory.getLogger(DisputeAgentNode.class);

    private final ChatClient chatClient;

    public DisputeAgentNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(PropertyManagementState state) {
        String prompt = """
                You are a tenant dispute specialist for property management. \
                Extract structured data from this tenant dispute email.

                Email body:
                %s""".formatted(state.originalEmailText());

        try {
            DisputeSchema dispute = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(DisputeSchema.class, spec -> spec.useProviderStructuredOutput());
            return Map.of(PropertyManagementState.DISPUTE, dispute);
        } catch (Exception e) {
            log.error("Dispute agent failed", e);
            return Map.of(PropertyManagementState.ERROR, "Dispute agent extraction failed: " + e.getMessage());
        }
    }
}
