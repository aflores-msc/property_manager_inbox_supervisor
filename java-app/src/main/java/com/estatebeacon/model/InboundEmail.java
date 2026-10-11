package com.estatebeacon.model;

import java.time.LocalDateTime;

/** One fetched Gmail message, trimmed to what the pipeline needs. */
public record InboundEmail(String emailId, String subject, String sender, String body, LocalDateTime dateReceived) {
}
