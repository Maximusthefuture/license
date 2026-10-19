package com.example.licensing.client.jdbc;

import com.example.licensing.client.InstallationIdProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Creates a random installation id the first time any replica reaches this
 * code, and has every later replica - and every later call - read back the
 * same value.
 *
 * <p>This matters specifically in Kubernetes: there is no single "first
 * boot" the way there would be on one machine, and several pods can race to
 * initialize this at startup. The insert is written so only one of them
 * wins; the rest read back whatever was actually inserted. Schema
 * (Postgres):
 *
 * <pre>{@code
 * CREATE TABLE license_installation (
 *   id              smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
 *   installation_id uuid NOT NULL
 * );
 * }</pre>
 *
 * Adapt the SQL for your actual database, as with {@link JdbcLicenseStore}.
 */
public final class JdbcInstallationIdProvider implements InstallationIdProvider {

    private final DataSource dataSource;
    private volatile UUID cached;

    public JdbcInstallationIdProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public UUID installationId() {
        UUID local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            cached = readOrCreate();
            return cached;
        }
    }

    private UUID readOrCreate() {
        UUID candidate = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO license_installation (id, installation_id)
                    VALUES (1, ?)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                insert.setObject(1, candidate);
                insert.executeUpdate();
            }
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT installation_id FROM license_installation WHERE id = 1");
                 ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("installation id row missing immediately after insert");
                }
                return (UUID) rs.getObject("installation_id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read or create installation id", e);
        }
    }
}
