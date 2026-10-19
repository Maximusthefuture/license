package com.example.licensing.client.time;

import java.time.Instant;
import java.util.Optional;

/** Stands in for the validator's database row; like it, only ever moves forward. */
public final class InMemoryTimeAnchorStore implements TimeAnchorStore {

    public volatile Instant value;

    @Override
    public Optional<Instant> load() {
        return Optional.ofNullable(value);
    }

    @Override
    public synchronized void advanceTo(Instant instant) {
        if (value == null || instant.isAfter(value)) {
            value = instant;
        }
    }
}
