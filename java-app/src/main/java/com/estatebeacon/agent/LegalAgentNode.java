package com.estatebeacon.agent;

import com.estatebeacon.model.CityNoticeSchema;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** Extracts {@link CityNoticeSchema} details. Mirrors {@code agents.py:legal_agent}. */
@Component
public class LegalAgentNode extends AbstractLlmExtractionNode<CityNoticeSchema> {

    public LegalAgentNode(ChatClient chatClient) {
        super(chatClient, CityNoticeSchema.class, PropertyManagementState.CITY_NOTICE, "Legal agent");
    }

    @Override
    protected String buildPrompt(PropertyManagementState state) {
        return """
                You are a legal notice specialist for property management. \
                Extract structured data from this city/government notice email.

                Email body:
                %s""".formatted(state.originalEmailText());
    }
}
