package com.example.licensing.client.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClockGuardTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void normalClockIsUsedAsIs() {
        MutableClock clock = new MutableClock(T0);
        ClockGuard guard = guard(clock, TimeAnchorStore.none());

        ClockGuard.Reading reading = guard.read(T0.minus(Duration.ofDays(10)));

        assertThat(reading.effectiveNow()).isEqualTo(T0);
        assertThat(reading.clockRolledBack()).isFalse();
    }

    @Test
    void clockTurnedBackWhileRunningDoesNotMoveTimeBack() {
        MutableClock clock = new MutableClock(T0);
        ClockGuard guard = guard(clock, TimeAnchorStore.none());
        guard.read(null);

        clock.set(T0.minus(Duration.ofDays(60)));
        ClockGuard.Reading reading = guard.read(null);

        assertThat(reading.effectiveNow()).isEqualTo(T0);
        assertThat(reading.clockRolledBack()).isTrue();
        assertThat(reading.warning()).contains("turned back");
    }

    @Test
    void persistedTimeCatchesARollbackAfterRestart() {
        InMemoryTimeAnchorStore store = new InMemoryTimeAnchorStore();
        MutableClock clock = new MutableClock(T0);
        guard(clock, store).read(null);                       // first run records T0
        assertThat(store.value).isEqualTo(T0);

        clock.set(T0.minus(Duration.ofDays(60)));
        ClockGuard afterRestart = guard(clock, store);         // new process, empty memory
        ClockGuard.Reading reading = afterRestart.read(null);

        assertThat(reading.effectiveNow()).isEqualTo(T0);
        assertThat(reading.clockRolledBack()).isTrue();
        assertThat(store.value).isEqualTo(T0);                 // never moved back
    }

    @Test
    void signedIssuedAtIsAFloorEvenWithoutAnyStoredTime() {
        Instant issuedAt = T0;
        MutableClock clock = new MutableClock(T0.minus(Duration.ofDays(30)));

        ClockGuard.Reading reading = guard(clock, TimeAnchorStore.none()).read(issuedAt);

        assertThat(reading.effectiveNow()).isEqualTo(issuedAt);
        assertThat(reading.warning()).contains("the time the license was issued");
    }

    @Test
    void smallSkewIsAbsorbedWithoutAWarning() {
        // cluster clock 10 minutes behind the vendor's: a fresh license must not look "not yet valid"
        MutableClock clock = new MutableClock(T0.minus(Duration.ofMinutes(10)));

        ClockGuard.Reading reading = guard(clock, TimeAnchorStore.none()).read(T0);

        assertThat(reading.effectiveNow()).isEqualTo(T0);
        assertThat(reading.clockRolledBack()).isFalse();
    }

    @Test
    void anUnavailableStoreDoesNotBreakAnything() {
        TimeAnchorStore broken = new TimeAnchorStore() {
            @Override
            public Optional<Instant> load() {
                throw new IllegalStateException("database is down");
            }

            @Override
            public void advanceTo(Instant instant) {
                throw new IllegalStateException("database is down");
            }
        };

        ClockGuard.Reading reading = guard(new MutableClock(T0), broken).read(null);

        assertThat(reading.effectiveNow()).isEqualTo(T0);
    }

    private static ClockGuard guard(Clock clock, TimeAnchorStore store) {
        return new ClockGuard(clock, store, Duration.ofHours(1), Duration.ZERO);
    }
}
