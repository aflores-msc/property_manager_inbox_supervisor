package com.estatebeacon.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.Serializable;

/**
 * Structured data extracted from a maintenance request email.
 *
 * <p>Implements {@link Serializable} — see {@link UniversalRoutingData} for why.
 */
public record MaintenanceSchema(
        @JsonPropertyDescription("Category of maintenance issue, e.g. 'plumbing', 'electrical'")
        String issueType,

        @JsonPropertyDescription("Where in the unit the issue is located")
        String locationInUnit,

        @JsonPropertyDescription("Tenant-reported severity")
        String reportedSeverity,

        @JsonPropertyDescription("Full description of the issue")
        String description,

        @JsonPropertyDescription("When the tenant is available for repairs")
        String tenantAvailableTimes
) implements Serializable {
}
