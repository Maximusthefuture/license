package com.example.licensing.client;

import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.client.time.ClockGuard;
import com.example.licensing.client.time.InMemoryTimeAnchorStore;
import com.example.licensing.client.time.MutableClock;
import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicensePayload;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLicenseServiceTest {

    private static final String KID = "test-key-1";
    private static final String PRODUCT_ID = "acme-suite";

    @Test
    void statusMovesThroughTheExpiryLifecycle() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        Instant notBefore = Instant.parse("2026-01-01T00:00:00Z");
        Instant notAfter = Instant.parse("2026-07-01T00:00:00Z");
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, installationId, notBefore, notAfter));

        assertThat(serviceAt(keyPair, installationId, store, "2026-03-01T00:00:00Z").status())
                .isEqualTo(LicenseStatus.VALID);
        assertThat(serviceAt(keyPair, installationId, store, "2026-06-25T00:00:00Z").status())
                .isEqualTo(LicenseStatus.EXPIRING);
        assertThat(serviceAt(keyPair, installationId, store, "2026-07-05T00:00:00Z").status())
                .isEqualTo(LicenseStatus.GRACE);
        assertThat(serviceAt(keyPair, installationId, store, "2026-08-01T00:00:00Z").status())
                .isEqualTo(LicenseStatus.EXPIRED);
    }

    @Test
    void noLicenseStoredMeansNoLicenseStatus() {
        InMemoryLicenseStore emptyStore = new InMemoryLicenseStore(null);
        assertThat(serviceAt(Ed25519.generateKeyPair(), UUID.randomUUID(), emptyStore, "2026-03-01T00:00:00Z").status())
                .isEqualTo(LicenseStatus.NO_LICENSE);
    }

    @Test
    void aLicenseForAnotherInstallationIsInvalid() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID licensedInstallation = UUID.randomUUID();
        UUID actualInstallation = UUID.randomUUID();
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, licensedInstallation,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")));

        DefaultLicenseService service = serviceAt(keyPair, actualInstallation, store, "2026-03-01T00:00:00Z");

        assertThat(service.status()).isEqualTo(LicenseStatus.INVALID);
        assertThat(service.isModuleEnabled("reports")).isFalse();
    }

    @Test
    void snapshotExplainsWhyALicenseIsInvalid() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, UUID.randomUUID(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")));

        LicenseSnapshot snapshot = serviceAt(keyPair, UUID.randomUUID(), store, "2026-03-01T00:00:00Z").snapshot();

        assertThat(snapshot.status()).isEqualTo(LicenseStatus.INVALID);
        assertThat(snapshot.reason().orElseThrow()).isEqualTo(LicenseValidationReason.WRONG_INSTALLATION);
        assertThat(snapshot.license().isPresent()).isFalse();
    }

    @Test
    void aLicenseThatIsNotValidYetIsInvalidButStillShowsWhatItGrants() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        // issued in February, starting in May
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, installationId,
                Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"), Instant.parse("2027-05-01T00:00:00Z")));

        LicenseSnapshot snapshot = serviceAt(keyPair, installationId, store, "2026-03-01T00:00:00Z").snapshot();

        assertThat(snapshot.status()).isEqualTo(LicenseStatus.INVALID);
        assertThat(snapshot.detail()).contains("not valid before");
        assertThat(snapshot.license().orElseThrow().maxUsers()).isEqualTo(50);
    }

    @Test
    void storeFailureBeforeAnythingWasVerifiedIsUnavailableNotAnException() {
        LicenseStore failing = new LicenseStore() {
            @Override
            public Optional<StoredLicense> load() {
                throw new IllegalStateException("database is down");
            }

            @Override
            public boolean save(StoredLicense license, Optional<StoredLicense> expectedPrevious) {
                return false;
            }
        };

        LicenseSnapshot snapshot = serviceAt(Ed25519.generateKeyPair(), UUID.randomUUID(), failing,
                "2026-03-01T00:00:00Z").snapshot();

        assertThat(snapshot.status()).isEqualTo(LicenseStatus.UNAVAILABLE);
        assertThat(snapshot.detail()).contains("database is down");
    }

    @Test
    void turningTheClockBackDoesNotReviveAnExpiredLicense() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, installationId,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")));
        InMemoryTimeAnchorStore persistedTime = new InMemoryTimeAnchorStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z")); // past notAfter + grace

        DefaultLicenseService running = serviceWithGuard(keyPair, installationId, store, clock, persistedTime);
        assertThat(running.status()).isEqualTo(LicenseStatus.EXPIRED);

        // customer sets the clock back into the validity period
        clock.set(Instant.parse("2026-03-01T00:00:00Z"));
        LicenseSnapshot sameProcess = running.snapshot();
        assertThat(sameProcess.status()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(sameProcess.clockWarning()).contains("turned back");
        assertThat(running.isModuleEnabled("reports")).isFalse();

        // ... and restarts, hoping to lose the in-memory state
        DefaultLicenseService restarted = serviceWithGuard(keyPair, installationId, store, clock, persistedTime);
        LicenseSnapshot afterRestart = restarted.snapshot();
        assertThat(afterRestart.status()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(afterRestart.clockWarning()).contains("recorded by this installation");
    }

    @Test
    void aClockSlightlyBehindTheVendorStillAcceptsAFreshLicense() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        // issued "now" by the vendor; the cluster clock is 10 minutes behind
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, installationId,
                Instant.parse("2026-10-19T12:00:00Z"), Instant.parse("2027-10-19T12:00:00Z")));
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T11:50:00Z"));

        LicenseSnapshot snapshot = serviceWithGuard(keyPair, installationId, store, clock,
                new InMemoryTimeAnchorStore()).snapshot();

        assertThat(snapshot.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(snapshot.clockWarning()).isEqualTo(null);
    }

    private DefaultLicenseService serviceWithGuard(KeyPair keyPair, UUID installationId, LicenseStore store,
                                                   MutableClock clock, InMemoryTimeAnchorStore persistedTime) {
        SignatureVerifier verifier = new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded()));
        LicenseValidator validator = new LicenseValidator(verifier, ClusterIdPolicy.WARN_ONLY);
        RuntimeContext context = new RuntimeContext(PRODUCT_ID, installationId, Optional::empty, clock);
        ClockGuard guard = new ClockGuard(clock, persistedTime, Duration.ofHours(1), Duration.ZERO);
        return new DefaultLicenseService(validator, store, context,
                Duration.ZERO, Duration.ofDays(14), Duration.ofDays(14), guard);
    }

    @Test
    void moduleAccessStopsOnlyAfterGraceEnds() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        Instant notBefore = Instant.parse("2026-01-01T00:00:00Z");
        Instant notAfter = Instant.parse("2026-07-01T00:00:00Z");
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, installationId, notBefore, notAfter));

        LicenseService duringGrace = serviceAt(keyPair, installationId, store, "2026-07-05T00:00:00Z");
        assertThat(duringGrace.isModuleEnabled("reports")).isTrue();

        LicenseService afterGrace = serviceAt(keyPair, installationId, store, "2026-08-01T00:00:00Z");
        assertThat(afterGrace.isModuleEnabled("reports")).isFalse();
    }

    @Test
    void userLimitIsEnforced() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        InMemoryLicenseStore store = new InMemoryLicenseStore(issue(keyPair, installationId,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z")));
        DefaultLicenseService service = serviceAt(keyPair, installationId, store, "2026-03-01T00:00:00Z");

        assertThat(service.maxUsers()).isEqualTo(50);
        service.checkCanAddUser(49); // fine, room for one more

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.checkCanAddUser(50))
                .isInstanceOf(DefaultLicenseService.LicenseLimitExceededException.class);
    }

    private DefaultLicenseService serviceAt(KeyPair keyPair, UUID installationId, LicenseStore store, String instant) {
        SignatureVerifier verifier = new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded()));
        LicenseValidator validator = new LicenseValidator(verifier, ClusterIdPolicy.WARN_ONLY);
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        RuntimeContext context = new RuntimeContext(PRODUCT_ID, installationId, Optional::empty, clock);
        return new DefaultLicenseService(validator, store, context,
                Duration.ofMinutes(1), Duration.ofDays(14), Duration.ofDays(14), clock);
    }

    private String issue(KeyPair keyPair, UUID installationId, Instant notBefore, Instant notAfter) {
        return issue(keyPair, installationId, notBefore, notBefore, notAfter);
    }

    private String issue(KeyPair keyPair, UUID installationId, Instant issuedAt, Instant notBefore, Instant notAfter) {
        LicensePayload payload = new LicensePayload(
                LicensePayload.CURRENT_FORMAT_VERSION, UUID.randomUUID(), UUID.randomUUID(), KID,
                PRODUCT_ID, "Test Customer", installationId, null,
                issuedAt, notBefore, notAfter, Set.of("reports"), 50);
        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(keyPair.getPrivate(), payloadBytes);
        return PayloadCodec.encodeSigned(payloadBytes, signature);
    }

    private static final class InMemoryLicenseStore implements LicenseStore {
        private StoredLicense current;

        InMemoryLicenseStore(String rawText) {
            this.current = rawText == null ? null : new StoredLicense(rawText, 1);
        }

        @Override
        public Optional<StoredLicense> load() {
            return Optional.ofNullable(current);
        }

        @Override
        public boolean save(StoredLicense license, Optional<StoredLicense> expectedPrevious) {
            this.current = license;
            return true;
        }
    }
}
