package com.example.licensing.client;

import com.example.licensing.client.time.ClockGuard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Caches the result of re-verifying the stored license for a short time, so
 * frequent checks (e.g. on every request that gates a module) don't hit the
 * store or re-run signature verification constantly.
 *
 * <p>Status by expiry is always recomputed against the current clock, never
 * cached, so an expiring license doesn't read as valid past its time just
 * because the authenticity cache hasn't refreshed yet.
 *
 * <p>If the store itself fails (database or validator service unreachable),
 * the last successful read keeps being used until the store answers again.
 * That is safe because what's cached is a signature-verified license whose
 * validity period is still checked against the clock on every call; an
 * outage of the store must not switch licensing off in a running product.
 */
public final class DefaultLicenseService implements LicenseService {

    /** Defaults shared by the validator service and the product, so both report the same status. */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_EXPIRING_WARNING = Duration.ofDays(30);
    public static final Duration DEFAULT_GRACE = Duration.ofDays(14);

    private static final System.Logger LOG = System.getLogger(DefaultLicenseService.class.getName());

    private final LicenseValidator validator;
    private final LicenseStore store;
    private final RuntimeContext context;
    private final Duration cacheTtl;
    private final Duration expiringWarningWindow;
    private final Duration graceDuration;
    private final ClockGuard clockGuard;

    private volatile CacheEntry cache;
    private volatile boolean clockWarningLogged;

    /** With an in-memory {@link ClockGuard} only (nothing persisted across restarts). */
    public DefaultLicenseService(LicenseValidator validator, LicenseStore store, RuntimeContext context,
                                 Duration cacheTtl, Duration expiringWarningWindow, Duration graceDuration,
                                 Clock clock) {
        this(validator, store, context, cacheTtl, expiringWarningWindow, graceDuration, ClockGuard.inMemory(clock));
    }

    /**
     * @param clockGuard decides the time the validity period is checked against, so that turning
     *                   the system clock back does not extend a license
     */
    public DefaultLicenseService(LicenseValidator validator, LicenseStore store, RuntimeContext context,
                                 Duration cacheTtl, Duration expiringWarningWindow, Duration graceDuration,
                                 ClockGuard clockGuard) {
        this.validator = validator;
        this.store = store;
        this.context = context;
        this.cacheTtl = cacheTtl;
        this.expiringWarningWindow = expiringWarningWindow;
        this.graceDuration = graceDuration;
        this.clockGuard = clockGuard;
    }

    @Override
    public LicenseStatus status() {
        return snapshot().status();
    }

    /** Status plus the license and, if it was rejected, why - for status pages and support. */
    public LicenseSnapshot snapshot() {
        CacheEntry entry = current();
        Optional<VerifiedLicense> verified = entry.license();
        ClockGuard.Reading time = clockGuard.read(verified.map(VerifiedLicense::issuedAt).orElse(null));
        String clockWarning = time.warning();
        if (time.clockRolledBack() != clockWarningLogged) { // log on change, not on every check
            clockWarningLogged = time.clockRolledBack();
            LOG.log(System.Logger.Level.WARNING, clockWarningLogged ? clockWarning : "system clock is back in order");
        }

        if (entry.unavailable()) {
            return new LicenseSnapshot(LicenseStatus.UNAVAILABLE, Optional.empty(), Optional.empty(),
                    entry.failureMessage(), clockWarning);
        }
        if (verified.isEmpty()) {
            return entry.stored()
                    ? new LicenseSnapshot(LicenseStatus.INVALID, Optional.empty(),
                    Optional.ofNullable(entry.failure()), entry.failureMessage(), clockWarning)
                    : new LicenseSnapshot(LicenseStatus.NO_LICENSE, Optional.empty(), Optional.empty(), null,
                    clockWarning);
        }
        VerifiedLicense license = verified.get();
        Instant now = time.effectiveNow();
        if (now.isBefore(license.notBefore())) {
            // Not active yet: a license issued with a start date in the future.
            // (A clock merely behind the vendor's can't cause this: the effective
            // time is never earlier than the license's issuedAt.)
            return new LicenseSnapshot(LicenseStatus.INVALID, entry.license(), Optional.empty(),
                    "license is not valid before " + license.notBefore(), clockWarning);
        }
        LicenseStatus status;
        if (now.isAfter(license.notAfter().plus(graceDuration))) {
            status = LicenseStatus.EXPIRED;
        } else if (now.isAfter(license.notAfter())) {
            status = LicenseStatus.GRACE;
        } else if (now.isAfter(license.notAfter().minus(expiringWarningWindow))) {
            status = LicenseStatus.EXPIRING;
        } else {
            status = LicenseStatus.VALID;
        }
        return new LicenseSnapshot(status, entry.license(), Optional.empty(), null, clockWarning);
    }

    @Override
    public boolean isModuleEnabled(String moduleId) {
        LicenseSnapshot s = snapshot();
        return usable(s.status()) && s.license().map(l -> l.hasModule(moduleId)).orElse(false);
    }

    @Override
    public int maxUsers() {
        LicenseSnapshot s = snapshot();
        return usable(s.status()) ? s.license().map(VerifiedLicense::maxUsers).orElse(0) : 0;
    }

    @Override
    public void checkCanAddUser(int currentUserCount) {
        int max = maxUsers();
        if (currentUserCount >= max) {
            throw new LicenseLimitExceededException("user limit reached: license allows " + max + " users");
        }
    }

    /** Drops the cache, e.g. right after a new license was activated. */
    public void invalidate() {
        cache = null;
    }

    /** VALID, EXPIRING and GRACE all still allow the product to function; only EXPIRED/INVALID/NO_LICENSE don't. */
    private static boolean usable(LicenseStatus s) {
        return s == LicenseStatus.VALID || s == LicenseStatus.EXPIRING || s == LicenseStatus.GRACE;
    }

    private CacheEntry current() {
        CacheEntry local = cache;
        long now = System.currentTimeMillis();
        if (local != null && (now - local.fetchedAtMillis()) < cacheTtl.toMillis()) {
            return local;
        }
        CacheEntry fresh;
        try {
            fresh = loadAndVerify(now);
        } catch (RuntimeException e) {
            // Retried after the next TTL, not on every call.
            if (local == null || local.unavailable()) {
                LOG.log(System.Logger.Level.WARNING, "license store unavailable: " + e.getMessage());
                fresh = new CacheEntry(true, false, Optional.empty(), null,
                        "license source unavailable: " + e.getMessage(), now);
            } else {
                LOG.log(System.Logger.Level.WARNING,
                        "license store unavailable, keeping the last verified state: " + e.getMessage());
                fresh = local.refreshedAt(now);
            }
        }
        cache = fresh;
        return fresh;
    }

    private CacheEntry loadAndVerify(long now) {
        Optional<LicenseStore.StoredLicense> stored = store.load();
        if (stored.isEmpty()) {
            return new CacheEntry(false, false, Optional.empty(), null, null, now);
        }
        try {
            VerifiedLicense license = validator.verify(stored.get().rawText(), context);
            return new CacheEntry(false, true, Optional.of(license), null, null, now);
        } catch (LicenseValidationException e) {
            return new CacheEntry(false, true, Optional.empty(), e.reason(), e.getMessage(), now);
        }
    }

    private record CacheEntry(boolean unavailable, boolean stored, Optional<VerifiedLicense> license,
                              LicenseValidationReason failure, String failureMessage, long fetchedAtMillis) {
        CacheEntry refreshedAt(long millis) {
            return new CacheEntry(unavailable, stored, license, failure, failureMessage, millis);
        }
    }

    public static final class LicenseLimitExceededException extends RuntimeException {
        public LicenseLimitExceededException(String message) {
            super(message);
        }
    }
}
