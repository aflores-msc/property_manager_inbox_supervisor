package com.estatebeacon.agent;

import com.estatebeacon.model.CityNoticeSchema;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Extracts {@link CityNoticeSchema} details. Mirrors {@code agents.py:legal_agent}. */
@Component
public class LegalAgentNode implements NodeAction<PropertyManagementState> {

    private static final Logger log = LoggerFactory.getLogger(LegalAgentNode.class);

    private final ChatClient chatClient;

    public LegalAgentNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(PropertyManagementState state) {
        String prompt = """
                You are a legal notice specialist for property management. \
                Extract structured data from this city/government notice email.

                Email body:
                %s""".formatted(state.originalEmailText());

        try {
            CityNoticeSchema cityNotice = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(CityNoticeSchema.class, spec -> spec.useProviderStructuredOutput());
            return Map.of(PropertyManagementState.CITY_NOTICE, cityNotice);
        } catch (Exception e) {
            log.error("Legal agent failed", e);
            return Map.of(PropertyManagementState.ERROR, "Legal agent extraction failed: " + e.getMessage());
        }
    }
}
