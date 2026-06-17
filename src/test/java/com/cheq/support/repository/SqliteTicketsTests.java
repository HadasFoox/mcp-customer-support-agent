package com.cheq.support.repository;

import com.cheq.support.model.Ticket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteTicketsTests {

    private record Store(SqliteConnectionFactory connections, TicketWriter writer, TicketReader reader) {
    }

    private Store newStore(Path dir) {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(dir.resolve("test.sqlite").toString());
        return new Store(connections, new TicketWriter(connections), new TicketReader(connections));
    }

    private Ticket ticket(long id, String queue, String language, Integer version) {
        return new Ticket(id, "subject-" + id, "body-" + id, "answer-" + id,
                "Incident", queue, "high", language, version,
                "tag-a", null, null, null, null);
    }

    @Test
    void initSchemaIsIdempotentAndStartsEmpty(@TempDir Path dir) {
        Store store = newStore(dir);

        assertThat(store.reader().isPopulated()).isFalse(); // no file yet
        store.writer().initSchema();
        store.writer().initSchema();                        // second call must not fail

        assertThat(store.reader().count()).isZero();
        assertThat(store.reader().isPopulated()).isFalse(); // schema present, no rows
    }

    @Test
    void insertBatchPersistsRows(@TempDir Path dir) {
        Store store = newStore(dir);
        store.writer().initSchema();

        store.writer().insertBatch(List.of(
                ticket(0, "Technical Support", "en", 120),
                ticket(1, "Billing and Payments", "de", null) // null version tolerated
        ));

        assertThat(store.reader().count()).isEqualTo(2);
        assertThat(store.reader().isPopulated()).isTrue();
    }

    @Test
    void readOnlyConnectionRejectsWrites(@TempDir Path dir) throws SQLException {
        Store store = newStore(dir);
        store.writer().initSchema();

        try (Connection conn = store.connections().openReadOnly();
             Statement st = conn.createStatement()) {
            assertThatThrownBy(() ->
                    st.executeUpdate("INSERT INTO tickets(ticket_id, subject) VALUES (99, 'x')"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
