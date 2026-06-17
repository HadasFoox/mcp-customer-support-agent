package com.cheq.support.repository;

import com.cheq.support.model.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

/**
 * Write side of the ticket store: owns the schema DDL and bulk insert.
 * Uses writable connections, and is exercised only during {@code @PostConstruct} ingestion.
 */
@Component
public class TicketWriter {

    private static final Logger log = LoggerFactory.getLogger(TicketWriter.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS tickets (
                ticket_id INTEGER PRIMARY KEY,
                subject   TEXT,
                body      TEXT,
                answer    TEXT,
                type      TEXT,
                queue     TEXT,
                priority  TEXT,
                language  TEXT,
                version   INTEGER,
                tag_1     TEXT,
                tag_2     TEXT,
                tag_3     TEXT,
                tag_4     TEXT,
                tag_5     TEXT
            )
            """;

    // Index the categorical columns the inner LLM is likely to filter on in a WHERE clause:
    // queue/type (department analog), language, priority, and each of the five tag columns.
    // body/answer are deliberately NOT indexed — they are large free text handled by semantic
    // search over embeddings, never by equality filters.
    private static final List<String> CREATE_INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_tickets_queue    ON tickets(queue)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_type     ON tickets(type)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_language ON tickets(language)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_priority ON tickets(priority)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_tag_1    ON tickets(tag_1)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_tag_2    ON tickets(tag_2)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_tag_3    ON tickets(tag_3)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_tag_4    ON tickets(tag_4)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_tag_5    ON tickets(tag_5)"
    );

    private static final String INSERT_SQL = """
            INSERT OR REPLACE INTO tickets
                (ticket_id, subject, body, answer, type, queue, priority, language, version,
                 tag_1, tag_2, tag_3, tag_4, tag_5)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final SqliteConnectionFactory connections;

    public TicketWriter(SqliteConnectionFactory connections) {
        this.connections = connections;
    }

    /** Create the {@code tickets} table and indexes if absent (idempotent). */
    public void initSchema() {
        try (Connection conn = connections.openWritable(); Statement st = conn.createStatement()) {
            st.execute(CREATE_TABLE);
            for (String idx : CREATE_INDEXES) {
                st.execute(idx);
            }
            log.info("SQLite schema ready at {}", connections.dbPath());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize SQLite schema at " + connections.dbPath(), e);
        }
    }

    /** Bulk-insert tickets in a single transaction. */
    public void insertBatch(List<Ticket> tickets) {
        try (Connection conn = connections.openWritable()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (Ticket t : tickets) {
                    ps.setLong(1, t.ticketId());
                    ps.setString(2, t.subject());
                    ps.setString(3, t.body());
                    ps.setString(4, t.answer());
                    ps.setString(5, t.type());
                    ps.setString(6, t.queue());
                    ps.setString(7, t.priority());
                    ps.setString(8, t.language());
                    if (t.version() == null) {
                        ps.setNull(9, Types.INTEGER);
                    } else {
                        ps.setInt(9, t.version());
                    }
                    ps.setString(10, t.tag1());
                    ps.setString(11, t.tag2());
                    ps.setString(12, t.tag3());
                    ps.setString(13, t.tag4());
                    ps.setString(14, t.tag5());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert tickets", e);
        }
    }
}
