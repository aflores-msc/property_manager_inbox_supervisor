package com.estatebeacon.email;

import com.estatebeacon.config.AppPaths;
import com.estatebeacon.model.InboundEmail;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Connects to Gmail via OAuth2 and fetches unread emails.
 * Mirrors {@code email_client.py:EmailFetcher}.
 */
@Component
public class GmailEmailFetcher {

    private static final List<String> SCOPES = List.of(GmailScopes.GMAIL_READONLY);
    private static final String APPLICATION_NAME = "Estate Beacon";

    private Gmail service;

    public void connect() throws IOException, GeneralSecurityException {
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
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        this.service = new Gmail.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    public void disconnect() {
        this.service = null;
    }

    /** Retrieves unread emails from the Gmail inbox. */
    public List<InboundEmail> fetchUnreadEmails() throws IOException {
        if (service == null) {
            throw new IllegalStateException("Not connected — call connect() first.");
        }

        var listResponse = service.users().messages()
                .list("me")
                .setLabelIds(List.of("UNREAD", "INBOX"))
                .setMaxResults(50L)
                .execute();

        var messageRefs = listResponse.getMessages();
        if (messageRefs == null || messageRefs.isEmpty()) {
            return List.of();
        }

        List<InboundEmail> emails = new ArrayList<>();
        for (var ref : messageRefs) {
            Message msg = service.users().messages().get("me", ref.getId()).setFormat("full").execute();
            Map<String, String> headers = msg.getPayload().getHeaders().stream()
                    .collect(Collectors.toMap(
                            h -> h.getName().toLowerCase(),
                            h -> h.getValue(),
                            (first, second) -> first));

            String subject = headers.getOrDefault("subject", "(no subject)");
            String sender = headers.getOrDefault("from", "");
            String body = extractPlainText(msg.getPayload());

            emails.add(new InboundEmail(ref.getId(), subject, sender, body));
        }
        return emails;
    }

    /** Recursively walks a Gmail message payload and returns plain text. */
    private static String extractPlainText(MessagePart payload) {
        if (payload == null) {
            return "";
        }
        if ("text/plain".equals(payload.getMimeType())) {
            String data = payload.getBody() != null ? payload.getBody().getData() : null;
            if (data != null && !data.isEmpty()) {
                return new String(Base64.getUrlDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8);
            }
            return "";
        }
        List<MessagePart> parts = payload.getParts();
        if (parts != null) {
            for (MessagePart part : parts) {
                String text = extractPlainText(part);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }
}
