package com.estatebeacon.agent;

import com.estatebeacon.model.MaintenanceSchema;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Extracts {@link MaintenanceSchema} details. Mirrors {@code agents.py:maintenance_agent}. */
@Component
public class MaintenanceAgentNode implements NodeAction<PropertyManagementState> {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceAgentNode.class);

    private final ChatClient chatClient;

    public MaintenanceAgentNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(PropertyManagementState state) {
        String prompt = """
                You are a maintenance request specialist for property management. \
                Extract structured data from this maintenance request email.

                Email body:
                %s""".formatted(state.originalEmailText());

        try {
            MaintenanceSchema maintenance = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(MaintenanceSchema.class, spec -> spec.useProviderStructuredOutput());
            return Map.of(PropertyManagementState.MAINTENANCE, maintenance);
        } catch (Exception e) {
            log.error("Maintenance agent failed", e);
            return Map.of(PropertyManagementState.ERROR, "Maintenance agent extraction failed: " + e.getMessage());
        }
    }
}
