package com.example.licensing.client.jdbc;

import com.example.licensing.client.time.TimeAnchorStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Keeps the latest time the validator has seen in a single row, so a clock
 * turned back is noticed after a restart too. The UPDATE only ever moves the
 * value forward, even with several replicas writing. Schema (Postgres):
 *
 * <pre>{@code
 * CREATE TABLE license_clock (
 *   id        smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
 *   last_seen timestamptz NOT NULL
 * );
 * }</pre>
 */
public final class JdbcTimeAnchorStore implements TimeAnchorStore {

    private final DataSource dataSource;

    public JdbcTimeAnchorStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Instant> load() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT last_seen FROM license_clock WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(rs.getObject(1, OffsetDateTime.class).toInstant());
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read license_clock", e);
        }
    }

    @Override
    public void advanceTo(Instant instant) {
        String sql = """
                INSERT INTO license_clock (id, last_seen) VALUES (1, ?)
                ON CONFLICT (id) DO UPDATE SET last_seen = EXCLUDED.last_seen
                WHERE license_clock.last_seen < EXCLUDED.last_seen
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update license_clock", e);
        }
    }
}
