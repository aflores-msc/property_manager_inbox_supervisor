package com.estatebeacon.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.Serializable;

/**
 * Core routing envelope extracted from every inbound email.
 *
 * <p>Unlike the Python {@code UniversalRoutingData}, this has no {@code emailId}
 * field: the prompt never gave the model the real Gmail message id to extract,
 * so that field only ever hallucinated a value that was discarded downstream.
 * The real id is threaded through as plain data instead (see {@link InboundEmail}).
 *
 * <p>Implements {@link Serializable} because LangGraph4j snapshots
 * {@code AgentState} values with Java's built-in serialization between graph
 * steps — any state value that isn't {@code Serializable} fails at runtime.
 */
public record UniversalRoutingData(
        @JsonPropertyDescription("Type of sender, e.g. 'tenant', 'city', 'vendor'")
        String senderType,

        @JsonPropertyDescription("Property street address mentioned in email")
        String propertyAddress,

        @JsonPropertyDescription("Unit or apartment number if applicable")
        String unitNumber,

        @JsonPropertyDescription("Category the email falls into")
        Classification classification,

        @JsonPropertyDescription("Urgency level of the email")
        PriorityLevel priorityLevel
) implements Serializable {
}
