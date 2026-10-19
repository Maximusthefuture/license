-- Applied at every startup (spring.sql.init.mode=always); idempotent.
-- Single-row tables: one installation id and one active license per validator database.

CREATE TABLE IF NOT EXISTS license_installation (
    id              smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    installation_id uuid     NOT NULL
);

CREATE TABLE IF NOT EXISTS license (
    id           smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    raw_text     text        NOT NULL,   -- the signed text exactly as activated; re-verified on every read
    version      bigint      NOT NULL,
    activated_at timestamptz NOT NULL DEFAULT now()
);

-- Latest time this installation has seen; only ever moves forward. If the system
-- clock is later found behind it, the clock was turned back, and license validity
-- is counted from this value instead (see ClockGuard).
CREATE TABLE IF NOT EXISTS license_clock (
    id        smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_seen timestamptz NOT NULL
);
