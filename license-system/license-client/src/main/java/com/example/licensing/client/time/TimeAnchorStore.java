package com.example.licensing.client.time;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists the latest time this installation has seen, so that turning the
 * clock back is noticed across restarts too (see {@link ClockGuard}).
 */
public interface TimeAnchorStore {

    Optional<Instant> load();

    /** Moves the stored time forward to {@code instant}; never moves it back. */
    void advanceTo(Instant instant);

    /** No persistence: only what the running process has seen counts. */
    static TimeAnchorStore none() {
        return new TimeAnchorStore() {
            @Override
            public Optional<Instant> load() {
                return Optional.empty();
            }

            @Override
            public void advanceTo(Instant instant) {
            }
        };
    }
}
