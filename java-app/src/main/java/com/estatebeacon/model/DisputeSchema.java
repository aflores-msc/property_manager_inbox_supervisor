package com.estatebeacon.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured data extracted from a tenant dispute email.
 */
public record DisputeSchema(
        @JsonPropertyDescription("Type of dispute, e.g. 'noise', 'lease terms', 'deposit'")
        String disputeType,

        @JsonPropertyDescription("Names or unit numbers of the parties involved")
        String partiesInvolved,

        @JsonPropertyDescription("Summary of the tenant's complaint")
        String tenantComplaint,

        @JsonPropertyDescription("What the tenant wants as a resolution")
        String desiredResolution,

        @JsonPropertyDescription("Whether legal or management escalation is recommended")
        boolean escalationNeeded
) {
}
