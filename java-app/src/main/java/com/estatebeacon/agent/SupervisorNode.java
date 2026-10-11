package com.estatebeacon.agent;

import com.estatebeacon.model.UniversalRoutingData;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Extracts universal routing data from the raw email. Mirrors {@code agents.py:supervisor_node}. */
@Component
public class SupervisorNode extends AbstractLlmExtractionNode<UniversalRoutingData> {

    public SupervisorNode(ChatClient chatClient) {
        super(chatClient, UniversalRoutingData.class, PropertyManagementState.ROUTING_DATA, "Supervisor node");
    }

    @Override
    protected String buildPrompt(PropertyManagementState state) {
        return """
                You are a property management email classifier. Analyze the following email \
                and extract structured routing data.

                Subject: %s
                From: %s

                Body:
                %s

                Classify the email as exactly one of: MAINTENANCE, CITY_NOTICE, TENANT_DISPUTE, or IGNORED.
                If the email is a system alert (e.g., Google sign-in alerts), a newsletter, \
                marketing spam, or anything unrelated to physical property management, you MUST \
                classify it as 'IGNORED'.
                Determine the priority level as one of: LOW, MEDIUM, HIGH, or URGENT.
                Extract the property address and unit number if mentioned.
                Determine the sender type (e.g. 'tenant', 'city', 'vendor', 'contractor').
                """.formatted(state.originalEmailSubject(), state.originalEmailSender(), state.originalEmailText());
    }
}
