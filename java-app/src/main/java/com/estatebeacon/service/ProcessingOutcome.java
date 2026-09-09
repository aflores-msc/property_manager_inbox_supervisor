package com.estatebeacon.service;

/** Result of running one email through the pipeline. */
public record ProcessingOutcome(
        boolean ignored,
        boolean newlyInserted,
        String classification,
        String priority,
        String errorMessage
) {
}
