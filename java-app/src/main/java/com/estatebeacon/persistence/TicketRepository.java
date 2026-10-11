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
 *
 * <p>Mirrors {@code database.py}: a fresh, short-lived JDBC connection per call rather than one
 * held-open connection. Note this buys nothing for concurrency <em>within</em> this process —
 * SQLite serializes writes at the file level regardless of how many local connections exist —
 * but it does matter for the DB file being genuinely shared <em>across processes</em> with the
 * separate Python app: every connection sets a busy timeout so a call here that lands while the
 * Python side (or vice versa) briefly holds the file's write lock retries instead of failing
 * outright with {@code SQLITE_BUSY}.
 */
@Repository
public class TicketRepository {

    private static final Logger log = LoggerFactory.getLogger(TicketRepository.class);
    private static final String DB_FILENAME = "property_manager.db";
    private static final int BUSY_TIMEOUT_MILLIS = 5000;

    static {
        // DriverManager normally finds this via META-INF/services/java.sql.Driver, but that
        // ServiceLoader-based auto-registration doesn't fire inside the jpackage'd app image
        // (its classloading setup differs from a plain `java -cp` launch) — load it explicitly
        // so `jdbc:sqlite:...` resolves in both environments.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found on classpath", e);
        }
    }

    private final String jdbcUrl;

    public TicketRepository() {
        this.jdbcUrl = "jdbc:sqlite:" + AppPaths.appFile(DB_FILENAME);
        initialize();
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
        }
        return conn;
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

    /**
     * Inserts a ticket and records the email as processed in a single transaction, so a crash
     * between the two writes can't leave an email ticketed but still eligible for (and billed
     * for) reclassification on the next fetch. Ignores duplicate {@code emailId}s. Returns
     * {@code true} if a new ticket row was inserted.
     */
    public boolean insertTicketAndMarkProcessed(
            String emailId,
            String dateReceived,
            String sender,
            String subject,
            String classification,
            String priority,
            String extractedJson,
            String rawBody
    ) {
        String insertSql = """
                INSERT OR IGNORE INTO tickets (
                    email_id, date_received, sender, subject,
                    classification, priority, extracted_json, raw_body, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
                """;
        String markSql = "INSERT OR IGNORE INTO processed_emails (email_id, processed_at) VALUES (?, ?)";

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                boolean inserted;
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, emailId);
                    ps.setString(2, dateReceived);
                    ps.setString(3, sender);
                    ps.setString(4, subject);
                    ps.setString(5, classification);
                    ps.setString(6, priority);
                    ps.setString(7, extractedJson);
                    ps.setString(8, rawBody);
                    inserted = ps.executeUpdate() > 0;
                }
                try (PreparedStatement ps = conn.prepareStatement(markSql)) {
                    ps.setString(1, emailId);
                    ps.setString(2, dateReceived);
                    ps.executeUpdate();
                }
                conn.commit();
                return inserted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record ticket " + emailId, e);
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
