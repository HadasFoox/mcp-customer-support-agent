package com.cheq.support.repository;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Read side of the ticket store. Strictly read-only — every query runs over a
 * {@link SqliteConnectionFactory#openReadOnly()} connection.
 *
 * <p>The guarded arbitrary-SELECT executor (firewall execution side) is added in Step 3 and
 * also reads through this factory; this class holds the fixed bookkeeping reads.
 */
@Component
public class TicketReader {

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM tickets";

    private final SqliteConnectionFactory connections;

    public TicketReader(SqliteConnectionFactory connections) {
        this.connections = connections;
    }

    /** True if the file exists and the {@code tickets} table has at least one row. */
    public boolean isPopulated() {
        if (!Files.exists(Path.of(connections.dbPath()))) {
            return false;
        }
        try (Connection conn = connections.openReadOnly();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(COUNT_SQL)) {
            return rs.next() && rs.getLong(1) > 0;
        } catch (SQLException e) {
            // Missing table / unreadable file → treat as not populated.
            return false;
        }
    }

    public long count() {
        try (Connection conn = connections.openReadOnly();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(COUNT_SQL)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count tickets", e);
        }
    }
}
