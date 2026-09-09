package com.estatebeacon.persistence;

import com.estatebeacon.config.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DAO for the {@code tickets} table, sharing {@code ~/.property_manager_ai/property_manager.db}
 * with the Python implementation so both clients read and write the same data.
 * Mirrors {@code database.py}: a fresh, short-lived JDBC connection per call
 * rather than one held-open connection, matching that module's stated
 * rationale for safe concurrent access from multiple threads.
 */
@Repository
public class TicketRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketRepository.class);
    private static final String DB_FILENAME = "property_manager.db";

    private final String jdbcUrl;

    public TicketRepository() {
        this.jdbcUrl = "jdbc:sqlite:" + AppPaths.appFile(DB_FILENAME);
        initialize();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initialize() {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tickets (
                        id INTEGER PRIMARY KEY,
                        email_id TEXT UNIQUE,
                        date_received TEXT,
                        sender TEXT,
                        subject TEXT,
                        classification TEXT,
                        priority TEXT,
                        extracted_json TEXT,
                        raw_body TEXT,
                        status TEXT DEFAULT 'OPEN'
                    )
                    """);
            migrateRawBody(conn);

            // Separate from `tickets`: IGNORED emails are deliberately never
            // given a ticket row, but the pipeline still needs to remember it
            // already classified them so a re-fetch of the same still-unread
            // Gmail message (this app only holds the readonly scope, so it
            // never marks messages read) doesn't re-run — and re-bill — Gemini
            // on every real, unrelated unread email sitting in the inbox too.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS processed_emails (
                        email_id TEXT PRIMARY KEY,
                        processed_at TEXT
                    )
                    """);
            st.execute("""
                    INSERT OR IGNORE INTO processed_emails (email_id, processed_at)
                    SELECT email_id, date_received FROM tickets
                    """);

            log.info("Database initialized at {}", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize " + jdbcUrl, e);
        }
    }

    private void migrateRawBody(Connection conn) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(tickets)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        if (!columns.contains("raw_body")) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE tickets ADD COLUMN raw_body TEXT");
            }
        }
    }

    /**
     * Every {@code email_id} the pipeline has ever run to completion for —
     * ticketed or IGNORED alike — so callers can skip re-classifying an email
     * Gmail still reports as unread. Does <em>not</em> include emails whose
     * last attempt threw (transient failures should still be retried).
     */
    public Set<String> getProcessedEmailIds() {
        Set<String> ids = new HashSet<>();
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT email_id FROM processed_emails")) {
            while (rs.next()) {
                ids.add(rs.getString("email_id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read processed email ids", e);
        }
        return ids;
    }

    /** Records that {@code emailId} was classified (regardless of outcome), for {@link #getProcessedEmailIds()}. */
    public void markProcessed(String emailId, String processedAt) {
        String sql = "INSERT OR IGNORE INTO processed_emails (email_id, processed_at) VALUES (?, ?)";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emailId);
            ps.setString(2, processedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mark email processed: " + emailId, e);
        }
    }

    /** Inserts a ticket, ignoring duplicates by {@code emailId}. Returns {@code true} if a new row was inserted. */
    public boolean insertTicket(
            String emailId,
            String dateReceived,
            String sender,
            String subject,
            String classification,
            String priority,
            String extractedJson,
            String rawBody
    ) {
        String sql = """
                INSERT OR IGNORE INTO tickets (
                    email_id, date_received, sender, subject,
                    classification, priority, extracted_json, raw_body, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emailId);
            ps.setString(2, dateReceived);
            ps.setString(3, sender);
            ps.setString(4, subject);
            ps.setString(5, classification);
            ps.setString(6, priority);
            ps.setString(7, extractedJson);
            ps.setString(8, rawBody);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert ticket " + emailId, e);
        }
    }

    public List<Ticket> getAllTickets() {
        String sql = """
                SELECT id, email_id, date_received, sender, subject,
                       classification, priority, extracted_json, raw_body, status
                FROM tickets
                ORDER BY id DESC
                """;
        List<Ticket> tickets = new ArrayList<>();
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tickets.add(new Ticket(
                        rs.getLong("id"),
                        rs.getString("email_id"),
                        rs.getString("date_received"),
                        rs.getString("sender"),
                        rs.getString("subject"),
                        rs.getString("classification"),
                        rs.getString("priority"),
                        rs.getString("extracted_json"),
                        rs.getString("raw_body"),
                        rs.getString("status")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read tickets", e);
        }
        return tickets;
    }

    public String getRawBody(String emailId) {
        String sql = "SELECT raw_body FROM tickets WHERE email_id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, emailId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String body = rs.getString("raw_body");
                    return body != null ? body : "";
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read raw body for " + emailId, e);
        }
        return "";
    }
}
