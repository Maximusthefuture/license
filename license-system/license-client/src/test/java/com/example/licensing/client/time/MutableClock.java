package com.example.licensing.client.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can turn back, like someone changing the system time. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
