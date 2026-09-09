package com.estatebeacon.agent;

import com.estatebeacon.model.UniversalRoutingData;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Extracts universal routing data from the raw email. Mirrors {@code agents.py:supervisor_node}. */
@Component
public class SupervisorNode implements NodeAction<PropertyManagementState> {

    private static final Logger log = LoggerFactory.getLogger(SupervisorNode.class);

    private final ChatClient chatClient;

    public SupervisorNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(PropertyManagementState state) {
        String prompt = """
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

        try {
            UniversalRoutingData routing = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(UniversalRoutingData.class, spec -> spec.useProviderStructuredOutput());
            return Map.of(PropertyManagementState.ROUTING_DATA, routing);
        } catch (Exception e) {
            log.error("Supervisor node failed", e);
            return Map.of(PropertyManagementState.ERROR, "Supervisor extraction failed: " + e.getMessage());
        }
    }
}
