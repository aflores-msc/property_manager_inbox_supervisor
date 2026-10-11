package com.estatebeacon.service;

import com.estatebeacon.agent.EstateBeaconGraph;
import com.estatebeacon.agent.PropertyManagementState;
import com.estatebeacon.model.InboundEmail;
import com.estatebeacon.persistence.TicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs one email through the compiled LangGraph4j pipeline and persists the
 * result. Combines what {@code ProcessEmailWorker} and the ticket-construction
 * logic in {@code ui.py:_on_email_processed} do in the Python app.
 */
@Service
public class TicketProcessingService {

    private final EstateBeaconGraph graphFactory;
    private final TicketRepository ticketRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TicketProcessingService(EstateBeaconGraph graphFactory, TicketRepository ticketRepository) {
        this.graphFactory = graphFactory;
        this.ticketRepository = ticketRepository;
    }

    public ProcessingOutcome process(InboundEmail email) throws Exception {
        Map<String, Object> initialState = Map.of(
                PropertyManagementState.ORIGINAL_EMAIL_SUBJECT, email.subject(),
                PropertyManagementState.ORIGINAL_EMAIL_SENDER, email.sender(),
                PropertyManagementState.ORIGINAL_EMAIL_TEXT, email.body()
        );

        PropertyManagementState result = graphFactory.compiledGraph().invoke(initialState)
                .orElseThrow(() -> new IllegalStateException("Graph produced no final state"));

        var routing = result.routingData().orElse(null);

        // The supervisor itself failed (LLM error, no routing data) — there is nothing to
        // ticket. Deliberately don't mark the email processed, so the next fetch retries it
        // instead of leaving a permanent blank-priority "UNKNOWN" ticket behind.
        if (routing == null) {
            return new ProcessingOutcome(false, true, false, "UNKNOWN", "",
                    result.error().orElse("Classification produced no routing data"));
        }

        String classification = routing.classification().name();
        String dateReceived = email.dateReceived().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // Strictly discard IGNORED emails — no DB row — but still remember the
        // email_id was classified, or it'll be reclassified on every re-fetch.
        if ("IGNORED".equals(classification)) {
            ticketRepository.markProcessed(email.emailId(), dateReceived);
            return new ProcessingOutcome(true, false, false, classification, "", result.error().orElse(null));
        }

        String priority = routing.priorityLevel().name();
        String extractedJson = serialize(result);

        boolean inserted = ticketRepository.insertTicketAndMarkProcessed(
                email.emailId(),
                dateReceived,
                email.sender(),
                email.subject().isBlank() ? "(no subject)" : email.subject(),
                classification,
                priority,
                extractedJson,
                email.body()
        );

        return new ProcessingOutcome(false, false, inserted, classification, priority, result.error().orElse(null));
    }

    /** Mirrors {@code ui.py:_serialize_result} — a plain JSON-serialisable payload. */
    private String serialize(PropertyManagementState result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("routingData", result.routingData().orElse(null));
        payload.put("cityNotice", result.cityNotice().orElse(null));
        payload.put("maintenance", result.maintenance().orElse(null));
        payload.put("dispute", result.dispute().orElse(null));
        result.error().ifPresent(error -> payload.put("error", error));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize processing result", e);
        }
    }
}
