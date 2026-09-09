package com.estatebeacon.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.Serializable;

/**
 * Structured data extracted from a city / legal notice email.
 *
 * <p>Implements {@link Serializable} — see {@link UniversalRoutingData} for why.
 */
public record CityNoticeSchema(
        @JsonPropertyDescription("Type of notice, e.g. 'code violation', 'permit'")
        String noticeType,

        @JsonPropertyDescription("City department or agency that issued the notice")
        String issuingAuthority,

        @JsonPropertyDescription("Compliance deadline if stated (ISO-8601 date)")
        String deadline,

        @JsonPropertyDescription("Summary of the violation or notice content")
        String violationDescription,

        @JsonPropertyDescription("Action the property manager must take")
        String requiredAction,

        @JsonPropertyDescription("Name of the contact person or official at the issuing authority")
        String entityName,

        @JsonPropertyDescription("Contact phone number for the issuing authority")
        String entityPhone,

        @JsonPropertyDescription("Contact email address for the issuing authority")
        String entityEmail
) implements Serializable {
}
