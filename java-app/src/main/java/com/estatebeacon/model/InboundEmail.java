package com.estatebeacon.model;

/** One fetched Gmail message, trimmed to what the pipeline needs. */
public record InboundEmail(String emailId, String subject, String sender, String body) {
}
