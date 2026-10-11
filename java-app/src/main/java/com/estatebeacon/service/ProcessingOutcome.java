package com.estatebeacon.service;

/**
 * Result of running one email through the pipeline.
 *
 * <p>{@code failed} means classification itself didn't produce a usable result (e.g. the
 * supervisor's LLM call errored) — no ticket was written and the email was deliberately left
 * out of {@code processed_emails} so the next fetch retries it, distinct from {@code ignored}
 * (classified successfully as not worth a ticket) and a real {@code newlyInserted} ticket.
 */
public record ProcessingOutcome(
        boolean ignored,
        boolean failed,
        boolean newlyInserted,
        String classification,
        String priority,
        String errorMessage
) {
}
