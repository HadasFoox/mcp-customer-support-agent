package com.cheq.support.repository.safesql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlQueryFirewallTest {

    private final SqlQueryFirewall firewall = new SqlQueryFirewall();

    @Test
    void rejectsNullOrBlank() {
        assertThatThrownBy(() -> firewall.sanitize(null)).isInstanceOf(UnsafeSqlException.class);
        assertThatThrownBy(() -> firewall.sanitize("   ")).isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void rejectsNonSelectStatements() {
        String[] writes = {
                "DELETE FROM tickets",
                "UPDATE tickets SET subject = 'x'",
                "INSERT INTO tickets(ticket_id) VALUES (1)",
                "DROP TABLE tickets",
                "ALTER TABLE tickets ADD COLUMN x TEXT",
                "PRAGMA table_info(tickets)",
                "ATTACH DATABASE 'evil.db' AS e"
        };
        for (String sql : writes) {
            assertThatThrownBy(() -> firewall.sanitize(sql))
                    .as("should reject: %s", sql)
                    .isInstanceOf(UnsafeSqlException.class);
        }
    }

    @Test
    void rejectsStatementStacking() {
        assertThatThrownBy(() -> firewall.sanitize("SELECT 1; DROP TABLE tickets"))
                .isInstanceOf(UnsafeSqlException.class);
        assertThatThrownBy(() -> firewall.sanitize("SELECT 1;DROP TABLE tickets;"))
                .isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void rejectsStackingHiddenInComment() {
        assertThatThrownBy(() -> firewall.sanitize("SELECT 1 /* sneaky */ ; DROP TABLE tickets"))
                .isInstanceOf(UnsafeSqlException.class);
        assertThatThrownBy(() -> firewall.sanitize("SELECT 1 -- c\n; DROP TABLE tickets"))
                .isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void stripsSingleTrailingSemicolonOnCleanSelect() {
        String out = firewall.sanitize("SELECT ticket_id FROM tickets;");
        assertThat(out).doesNotContain(";");
        assertThat(out).containsIgnoringCase("LIMIT 50");
    }

    @Test
    void injectsLimitAndWrapsWhenAbsent() {
        String out = firewall.sanitize("SELECT ticket_id FROM tickets");
        assertThat(out).startsWithIgnoringCase("SELECT * FROM (");
        assertThat(out).endsWithIgnoringCase("LIMIT 50");
    }

    @Test
    void allowsSemicolonInsideStringLiteral() {
        // The ';' here is data, not a statement separator — must not be rejected.
        String out = firewall.sanitize("SELECT ticket_id FROM tickets WHERE body = 'a; b'");
        assertThat(out).containsIgnoringCase("LIMIT 50");
    }

    @Test
    void acceptsCleanSelect() {
        String out = firewall.sanitize("SELECT queue, COUNT(*) FROM tickets GROUP BY queue");
        assertThat(out).containsIgnoringCase("SELECT * FROM (");
        assertThat(out).containsIgnoringCase("GROUP BY queue");
    }
}
