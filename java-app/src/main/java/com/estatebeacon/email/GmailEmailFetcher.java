package com.estatebeacon.email;

import com.estatebeacon.config.AppPaths;
import com.estatebeacon.model.InboundEmail;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Connects to Gmail via OAuth2 and fetches unread emails.
 * Mirrors {@code email_client.py:EmailFetcher}.
 */
@Component
public class GmailEmailFetcher {

    private static final Logger log = LoggerFactory.getLogger(GmailEmailFetcher.class);
    private static final List<String> SCOPES = List.of(GmailScopes.GMAIL_READONLY);
    private static final String APPLICATION_NAME = "Estate Beacon";
    // Gmail allows up to 100 inner requests per batch call, but that many still trips its
    // separate per-user *concurrent* request limit (confirmed live: batches of 100 against a
    // 228-message inbox failed roughly half their inner requests with "Too many concurrent
    // requests for user"). Google's backend fans a batch's inner requests out concurrently, so a
    // much smaller batch size keeps in-flight requests under that limit, with a retry pass below
    // as a safety net for whatever still gets rate-limited.
    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_FETCH_RETRIES = 4;
    private static final long RETRY_BACKOFF_MILLIS = 1500;

    private Gmail service;

    public void connect(Consumer<String> onStatus) throws IOException, GeneralSecurityException {
        onStatus.accept("Connecting to Gmail...");
        var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        var jsonFactory = GsonFactory.getDefaultInstance();

        var credentialsFile = AppPaths.appFile("credentials.json").toFile();
        if (!credentialsFile.isFile()) {
            throw new FileNotFoundException(
                    "OAuth credentials file not found at '" + credentialsFile
                            + "'. Download it from the Google Cloud Console and place it there.");
        }

        GoogleClientSecrets clientSecrets;
        try (var reader = new FileReader(credentialsFile)) {
            clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
        }

        var tokenStore = new FileDataStoreFactory(AppPaths.appFile("tokens").toFile());
        var flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets, SCOPES)
                .setDataStoreFactory(tokenStore)
                .setAccessType("offline")
                .build();
        var receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        onStatus.accept("Authenticating with Google (a browser window may open for sign-in)...");
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        this.service = new Gmail.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
        onStatus.accept("Connected to Gmail.");
    }

    public void disconnect() {
        this.service = null;
    }

    /** Retrieves unread emails from the Gmail inbox. */
    public List<InboundEmail> fetchUnreadEmails(Consumer<String> onStatus) throws IOException {
        if (service == null) {
            throw new IllegalStateException("Not connected — call connect() first.");
        }

        onStatus.accept("Checking inbox for unread emails...");
        List<Message> messageRefs = listAllUnreadRefs(onStatus);
        if (messageRefs.isEmpty()) {
            return List.of();
        }

        onStatus.accept("Downloading " + messageRefs.size() + " email(s)...");
        List<InboundEmail> emails = new ArrayList<>();
        List<Message> pending = messageRefs;
        for (int attempt = 0; attempt <= MAX_FETCH_RETRIES && !pending.isEmpty(); attempt++) {
            if (attempt > 0) {
                log.info("Retrying {} Gmail message(s) after rate limiting (attempt {}/{})",
                        pending.size(), attempt, MAX_FETCH_RETRIES);
                onStatus.accept("Rate limited by Gmail — retrying " + pending.size()
                        + " email(s) (attempt " + attempt + "/" + MAX_FETCH_RETRIES + ")...");
                try {
                    Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            pending = fetchBatched(pending, emails, messageRefs.size(), onStatus);
        }
        if (!pending.isEmpty()) {
            log.warn("Giving up on {} Gmail message(s) after {} retries due to rate limiting",
                    pending.size(), MAX_FETCH_RETRIES);
        }
        return emails;
    }

    /** Fetches one round of messages in {@link #MAX_BATCH_SIZE}-sized batches, appending successes to {@code emails}. */
    private List<Message> fetchBatched(List<Message> refs, List<InboundEmail> emails, int total, Consumer<String> onStatus)
            throws IOException {
        List<Message> failed = new ArrayList<>();
        for (List<Message> chunk : partition(refs, MAX_BATCH_SIZE)) {
            BatchRequest batch = service.batch();
            for (var ref : chunk) {
                service.users().messages().get("me", ref.getId()).setFormat("full")
                        .queue(batch, new JsonBatchCallback<Message>() {
                            @Override
                            public void onSuccess(Message msg, HttpHeaders responseHeaders) {
                                emails.add(toInboundEmail(ref.getId(), msg));
                            }

                            @Override
                            public void onFailure(GoogleJsonError error, HttpHeaders responseHeaders) {
                                log.debug("Failed to fetch Gmail message {}: {}", ref.getId(), error.getMessage());
                                failed.add(ref);
                            }
                        });
            }
            batch.execute();
            onStatus.accept("Downloaded " + emails.size() + " / " + total + " email(s)...");
        }
        return failed;
    }

    /** Gmail rejects a batch with more than 100 inner requests, and far fewer avoids its concurrent-request limit too. */
    private static List<List<Message>> partition(List<Message> messages, int chunkSize) {
        List<List<Message>> chunks = new ArrayList<>();
        for (int i = 0; i < messages.size(); i += chunkSize) {
            chunks.add(messages.subList(i, Math.min(i + chunkSize, messages.size())));
        }
        return chunks;
    }

    /** Lists every UNREAD/INBOX message id, following pagination — the list call itself never returns bodies. */
    private List<Message> listAllUnreadRefs(Consumer<String> onStatus) throws IOException {
        List<Message> messageRefs = new ArrayList<>();
        String pageToken = null;
        do {
            var listResponse = service.users().messages()
                    .list("me")
                    .setLabelIds(List.of("UNREAD", "INBOX"))
                    .setMaxResults(100L)
                    .setPageToken(pageToken)
                    .execute();
            if (listResponse.getMessages() != null) {
                messageRefs.addAll(listResponse.getMessages());
            }
            pageToken = listResponse.getNextPageToken();
            if (pageToken != null) {
                onStatus.accept("Found " + messageRefs.size() + " unread email(s) so far, listing more...");
            }
        } while (pageToken != null);
        return messageRefs;
    }

    private static InboundEmail toInboundEmail(String messageId, Message msg) {
        Map<String, String> headers = msg.getPayload().getHeaders().stream()
                .collect(Collectors.toMap(
                        h -> h.getName().toLowerCase(),
                        h -> h.getValue(),
                        (first, second) -> first));

        String subject = headers.getOrDefault("subject", "(no subject)");
        String sender = headers.getOrDefault("from", "");
        String body = extractBody(msg.getPayload());
        LocalDateTime dateReceived = msg.getInternalDate() != null
                ? Instant.ofEpochMilli(msg.getInternalDate()).atZone(ZoneId.systemDefault()).toLocalDateTime()
                : LocalDateTime.now();

        return new InboundEmail(messageId, subject, sender, body, dateReceived);
    }

    /** Prefers {@code text/plain}; falls back to a lightly stripped {@code text/html} part if that's all there is. */
    private static String extractBody(MessagePart payload) {
        String plainText = extractPartByMimeType(payload, "text/plain");
        if (!plainText.isEmpty()) {
            return plainText;
        }
        String html = extractPartByMimeType(payload, "text/html");
        return html.isEmpty() ? "" : stripHtml(html);
    }

    /** Recursively walks a Gmail message payload for the first part matching {@code mimeType}. */
    private static String extractPartByMimeType(MessagePart part, String mimeType) {
        if (part == null) {
            return "";
        }
        if (mimeType.equals(part.getMimeType())) {
            String data = part.getBody() != null ? part.getBody().getData() : null;
            if (data != null && !data.isEmpty()) {
                return new String(Base64.getUrlDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8);
            }
            return "";
        }
        List<MessagePart> parts = part.getParts();
        if (parts != null) {
            for (MessagePart child : parts) {
                String text = extractPartByMimeType(child, mimeType);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * Reduces an HTML email body to plain-ish text for the classification prompt. Not a real HTML
     * parser — just enough to keep an HTML-only email's content from being silently discarded.
     */
    private static String stripHtml(String html) {
        String withoutBlocks = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        String withoutTags = withoutBlocks.replaceAll("(?s)<[^>]+>", " ");
        String unescaped = withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return unescaped.replaceAll("[ \\t\\x0B\\f\\r]+", " ").strip();
    }
}
