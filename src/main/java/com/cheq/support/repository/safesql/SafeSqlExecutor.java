package com.cheq.support.repository.safesql;

import com.cheq.support.repository.SqliteConnectionFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The execution side of the SQL guardrails: sanitize a candidate query through the
 * {@link SqlQueryFirewall}, then run it on a strictly read-only connection with a 3-second
 * query timeout. The firewall (validation) and this executor (read-only + timeout) together
 * are the full defense around LLM-generated SQL.
 */
@Component
public class SafeSqlExecutor {

    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final SqliteConnectionFactory connections;
    private final SqlQueryFirewall firewall;

    public SafeSqlExecutor(SqliteConnectionFactory connections, SqlQueryFirewall firewall) {
        this.connections = connections;
        this.firewall = firewall;
    }

    /**
     * Sanitize and execute a candidate SELECT.
     *
     * @return up to 50 rows, each an ordered column-label → value map
     * @throws UnsafeSqlException if the firewall rejects the query
     */
    public List<Map<String, Object>> execute(String candidateSql) {
        String safeSql = firewall.sanitize(candidateSql);
        try (Connection conn = connections.openReadOnly();
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(safeSql)) {
                return mapRows(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Read-only query failed", e);
        }
    }

    private static List<Map<String, Object>> mapRows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(md.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
