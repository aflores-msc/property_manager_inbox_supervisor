package com.estatebeacon.agent;

import com.estatebeacon.model.MaintenanceSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Extracts {@link MaintenanceSchema} details. Mirrors {@code agents.py:maintenance_agent}. */
@Component
public class MaintenanceAgentNode extends AbstractLlmExtractionNode<MaintenanceSchema> {

    public MaintenanceAgentNode(ChatClient chatClient) {
        super(chatClient, MaintenanceSchema.class, PropertyManagementState.MAINTENANCE, "Maintenance agent");
    }

    @Override
    protected String buildPrompt(PropertyManagementState state) {
        return """
                You are a maintenance request specialist for property management. \
                Extract structured data from this maintenance request email.

                Email body:
                %s""".formatted(state.originalEmailText());
    }
}
