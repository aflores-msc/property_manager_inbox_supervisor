package com.estatebeacon.service;

import com.estatebeacon.agent.EstateBeaconGraph;
import com.estatebeacon.agent.PropertyManagementState;
import com.estatebeacon.model.InboundEmail;
import com.estatebeacon.persistence.TicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
        var compiledGraph = graphFactory.compile();

        Map<String, Object> initialState = Map.of(
                PropertyManagementState.ORIGINAL_EMAIL_SUBJECT, email.subject(),
                PropertyManagementState.ORIGINAL_EMAIL_SENDER, email.sender(),
                PropertyManagementState.ORIGINAL_EMAIL_TEXT, email.body()
        );

        PropertyManagementState result = compiledGraph.invoke(initialState)
                .orElseThrow(() -> new IllegalStateException("Graph produced no final state"));

        var routing = result.routingData().orElse(null);
        String classification = routing != null ? routing.classification().name() : "UNKNOWN";
        String dateReceived = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // Strictly discard IGNORED emails — no DB row — but still remember the
        // email_id was classified, or it'll be reclassified on every re-fetch.
        if ("IGNORED".equals(classification)) {
            ticketRepository.markProcessed(email.emailId(), dateReceived);
            return new ProcessingOutcome(true, false, classification, "", result.error().orElse(null));
        }

        String priority = routing != null ? routing.priorityLevel().name() : "";
        String extractedJson = serialize(result);

        boolean inserted = ticketRepository.insertTicket(
                email.emailId(),
                dateReceived,
                email.sender(),
                email.subject().isBlank() ? "(no subject)" : email.subject(),
                classification,
                priority,
                extractedJson,
                email.body()
        );
        ticketRepository.markProcessed(email.emailId(), dateReceived);

        return new ProcessingOutcome(false, inserted, classification, priority, result.error().orElse(null));
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
