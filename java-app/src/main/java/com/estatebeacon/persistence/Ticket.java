package com.estatebeacon.persistence;

/** One row of the {@code tickets} table. */
public record Ticket(
        long id,
        String emailId,
        String dateReceived,
        String sender,
        String subject,
        String classification,
        String priority,
        String extractedJson,
        String rawBody,
        String status
) {
}
