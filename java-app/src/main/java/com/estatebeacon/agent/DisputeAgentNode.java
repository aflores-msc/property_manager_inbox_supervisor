package com.estatebeacon.agent;

import com.estatebeacon.model.DisputeSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Extracts {@link DisputeSchema} details. Mirrors {@code agents.py:dispute_agent}. */
@Component
public class DisputeAgentNode extends AbstractLlmExtractionNode<DisputeSchema> {

    public DisputeAgentNode(ChatClient chatClient) {
        super(chatClient, DisputeSchema.class, PropertyManagementState.DISPUTE, "Dispute agent");
    }

    @Override
    protected String buildPrompt(PropertyManagementState state) {
        return """
                You are a tenant dispute specialist for property management. \
                Extract structured data from this tenant dispute email.

                Email body:
                %s""".formatted(state.originalEmailText());
    }
}
