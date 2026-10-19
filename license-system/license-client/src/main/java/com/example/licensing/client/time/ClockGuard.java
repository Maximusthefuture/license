package com.example.licensing.client.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides what time to evaluate a license's validity period against, so that
 * turning the system clock back does not extend a license.
 *
 * <p>The "effective" time is the latest of:
 * <ul>
 *   <li>the system clock;</li>
 *   <li>the latest time this process has seen since it started;</li>
 *   <li>the latest time persisted in a {@link TimeAnchorStore} (survives restarts);</li>
 *   <li>the license's {@code issuedAt} - signed by the vendor, so it can't be forged.</li>
 * </ul>
 * Turning the clock back therefore doesn't count: the license still expires
 * when it would have. If the clock is behind those anchors by more than the
 * tolerance, the reading carries a warning to show to operators.
 *
 * <p>Limits: the persisted anchor lives in the customer's database, so
 * someone who rolls the clock back AND resets that row AND restarts the
 * process is only stopped by the signed issuedAt - they can't go back
 * before the license was issued, but can keep the clock somewhere inside
 * its validity period. Living permanently in the past also breaks
 * certificates and much else in a cluster, which makes it impractical.
 *
 * <p>A clock that was wrongly ahead and then corrected keeps the effective
 * time ahead until real time catches up - a license may look up to that
 * much older. That is the safe direction to err in.
 */
public final class ClockGuard {

    public static final Duration DEFAULT_TOLERANCE = Duration.ofHours(1);
    public static final Duration DEFAULT_PERSIST_INTERVAL = Duration.ofMinutes(5);

    private static final System.Logger LOG = System.getLogger(ClockGuard.class.getName());

    private final Clock clock;
    private final TimeAnchorStore store;
    private final Duration tolerance;
    private final long persistIntervalNanos;

    private Instant seenMax;       // latest system time this process has seen
    private Instant persisted;     // last value read from / written to the store
    private long lastSyncNanos;
    private boolean synced;

    public ClockGuard(Clock clock, TimeAnchorStore store, Duration tolerance, Duration persistInterval) {
        this.clock = clock;
        this.store = store;
        this.tolerance = tolerance;
        this.persistIntervalNanos = persistInterval.toNanos();
    }

    /** In-memory only, default tolerance. */
    public static ClockGuard inMemory(Clock clock) {
        return new ClockGuard(clock, TimeAnchorStore.none(), DEFAULT_TOLERANCE, DEFAULT_PERSIST_INTERVAL);
    }

    /**
     * @param licenseIssuedAt issuedAt of the verified license, or null if there is none
     */
    public synchronized Reading read(Instant licenseIssuedAt) {
        Instant now = clock.instant();
        syncWithStore(now);

        Instant anchor = null;
        String anchorSource = null;
        if (seenMax != null) {
            anchor = seenMax;
            anchorSource = "the latest time seen since start";
        }
        if (persisted != null && (anchor == null || persisted.isAfter(anchor))) {
            anchor = persisted;
            anchorSource = "the latest time recorded by this installation";
        }
        if (licenseIssuedAt != null && (anchor == null || licenseIssuedAt.isAfter(anchor))) {
            anchor = licenseIssuedAt;
            anchorSource = "the time the license was issued";
        }

        if (seenMax == null || now.isAfter(seenMax)) {
            seenMax = now;
        }

        if (anchor == null || !anchor.isAfter(now)) {
            return new Reading(now, now, null);
        }
        String warning = null;
        if (now.plus(tolerance).isBefore(anchor)) {
            warning = "system clock (" + now + ") is behind " + anchorSource + " (" + anchor + ") by more than "
                    + tolerance + " - it was probably turned back; the license validity is counted from " + anchor;
        }
        return new Reading(anchor, now, warning);
    }

    private void syncWithStore(Instant now) {
        long nanos = System.nanoTime(); // monotonic, unaffected by changes to the wall clock
        if (synced && nanos - lastSyncNanos < persistIntervalNanos) {
            return;
        }
        synced = true;
        lastSyncNanos = nanos;
        try {
            Optional<Instant> stored = store.load();
            if (stored.isPresent() && (persisted == null || stored.get().isAfter(persisted))) {
                persisted = stored.get();
            }
            if (persisted == null || now.isAfter(persisted)) {
                store.advanceTo(now);
                persisted = now;
            }
        } catch (RuntimeException e) {
            // The anchor is a safeguard; an unavailable store must not break licensing.
            LOG.log(System.Logger.Level.WARNING, "time anchor store unavailable: " + e.getMessage());
        }
    }

    /**
     * @param effectiveNow the time to evaluate the license against
     * @param clockNow     what the system clock said
     * @param warning      set when the clock is behind a known anchor by more than the tolerance
     */
    public record Reading(Instant effectiveNow, Instant clockNow, String warning) {
        public boolean clockRolledBack() {
            return warning != null;
        }
    }
}
