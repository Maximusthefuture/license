package com.example.licensing.client.jdbc;

import com.example.licensing.client.LicenseStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Stores the license as a single row, so every replica of the product sees
 * the same activated license. Schema (Postgres):
 *
 * <pre>{@code
 * CREATE TABLE license (
 *   id           smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
 *   raw_text     text NOT NULL,
 *   version      bigint NOT NULL,
 *   activated_at timestamptz NOT NULL DEFAULT now()
 * );
 * }</pre>
 *
 * <p>The single-row-by-check-constraint trick keeps this store
 * product-agnostic; swap it for however your product already keys
 * singleton config rows. This class is written against plain JDBC and
 * Postgres' {@code ON CONFLICT} syntax as a reference implementation -
 * adapt the SQL for your actual database, and prefer going through
 * whatever connection/transaction management your product already uses
 * (e.g. Spring's {@code JdbcTemplate} / {@code TransactionTemplate}) rather
 * than using this verbatim.
 */
public final class JdbcLicenseStore implements LicenseStore {

    private final DataSource dataSource;

    public JdbcLicenseStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<StoredLicense> load() {
        String sql = "SELECT raw_text, version FROM license WHERE id = 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new StoredLicense(rs.getString("raw_text"), rs.getLong("version")));
        } catch (SQLException e) {
            throw new LicenseStoreException("failed to load license", e);
        }
    }

    @Override
    public boolean save(StoredLicense license, Optional<StoredLicense> expectedPrevious) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean ok = expectedPrevious.isEmpty()
                        ? insert(c, license)
                        : updateIfVersionMatches(c, license, expectedPrevious.get().version());
                c.commit();
                return ok;
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LicenseStoreException("failed to save license", e);
        }
    }

    private boolean insert(Connection c, StoredLicense license) throws SQLException {
        String sql = """
                INSERT INTO license (id, raw_text, version)
                VALUES (1, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, license.rawText());
            ps.setLong(2, license.version());
            return ps.executeUpdate() == 1;
        }
    }

    private boolean updateIfVersionMatches(Connection c, StoredLicense license, long expectedVersion)
            throws SQLException {
        String sql = """
                UPDATE license SET raw_text = ?, version = ?, activated_at = now()
                WHERE id = 1 AND version = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, license.rawText());
            ps.setLong(2, license.version());
            ps.setLong(3, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    public static final class LicenseStoreException extends RuntimeException {
        public LicenseStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
