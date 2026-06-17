package com.cheq.support.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The single place that opens SQLite connections, so the read-only vs writable distinction
 * lives in exactly one class.
 *
 * <ul>
 *   <li>{@link #openWritable()} — used only during {@code @PostConstruct} ingestion.</li>
 *   <li>{@link #openReadOnly()} — the only mode used at query time, opened with
 *       {@code SQLITE_OPEN_READONLY} so any write fails at the driver level (defense in depth,
 *       even if a mutation somehow slips past the SQL firewall).</li>
 * </ul>
 */
@Component
public class SqliteConnectionFactory {

    private final String dbPath;

    public SqliteConnectionFactory(@Value("${support.sqlite.path}") String dbPath) {
        this.dbPath = dbPath;
    }

    /** Writable connection — ingestion only. */
    public Connection openWritable() throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setBusyTimeout(3000);
        return dataSource(cfg).getConnection();
    }

    /** Read-only connection — the only mode used at query time. */
    public Connection openReadOnly() throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        cfg.setBusyTimeout(3000);
        return dataSource(cfg).getConnection();
    }

    private SQLiteDataSource dataSource(SQLiteConfig cfg) {
        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    public String dbPath() {
        return dbPath;
    }
}
