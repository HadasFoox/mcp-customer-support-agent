package com.cheq.support.repository.safesql;

import com.cheq.support.model.Ticket;
import com.cheq.support.repository.SqliteConnectionFactory;
import com.cheq.support.repository.TicketWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeSqlExecutorTest {

    private SafeSqlExecutor executorWith(Path dir, int rowCount) {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(dir.resolve("exec.sqlite").toString());
        TicketWriter writer = new TicketWriter(connections);
        writer.initSchema();
        List<Ticket> tickets = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            tickets.add(new Ticket(i, "s" + i, "body", "answer", "Incident", "Technical Support",
                    "high", "en", 100, null, null, null, null, null));
        }
        writer.insertBatch(tickets);
        return new SafeSqlExecutor(connections, new SqlQueryFirewall());
    }

    @Test
    void capsResultsToFiftyWhenNoLimit(@TempDir Path dir) {
        SafeSqlExecutor exec = executorWith(dir, 60);
        List<Map<String, Object>> rows = exec.execute("SELECT ticket_id FROM tickets");
        assertThat(rows).hasSize(50);
    }

    @Test
    void capsLargerLimitDownToFifty(@TempDir Path dir) {
        SafeSqlExecutor exec = executorWith(dir, 60);
        List<Map<String, Object>> rows = exec.execute("SELECT ticket_id FROM tickets LIMIT 500");
        assertThat(rows).hasSize(50);
    }

    @Test
    void preservesSmallerInnerLimit(@TempDir Path dir) {
        SafeSqlExecutor exec = executorWith(dir, 60);
        List<Map<String, Object>> rows = exec.execute("SELECT ticket_id FROM tickets LIMIT 10");
        assertThat(rows).hasSize(10);
    }

    @Test
    void rejectsNonSelectThroughExecutor(@TempDir Path dir) {
        SafeSqlExecutor exec = executorWith(dir, 5);
        assertThatThrownBy(() -> exec.execute("DELETE FROM tickets"))
                .isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void returnsRequestedColumns(@TempDir Path dir) {
        SafeSqlExecutor exec = executorWith(dir, 3);
        List<Map<String, Object>> rows = exec.execute("SELECT ticket_id, queue FROM tickets");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).containsKeys("ticket_id", "queue");
    }
}
