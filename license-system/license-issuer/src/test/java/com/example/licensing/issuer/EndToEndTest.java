package com.example.licensing.issuer;

import com.example.licensing.client.ActivationService;
import com.example.licensing.client.ClusterIdPolicy;
import com.example.licensing.client.DefaultLicenseService;
import com.example.licensing.client.LicenseRequestGenerator;
import com.example.licensing.client.LicenseStatus;
import com.example.licensing.client.LicenseStore;
import com.example.licensing.client.LicenseValidationReason;
import com.example.licensing.client.LicenseValidator;
import com.example.licensing.client.RuntimeContext;
import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.keystore.SigningKey;
import com.example.licensing.issuer.keystore.SigningKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole email round trip with real keystores: the client generates a
 * request, the issuer signs it with a key loaded from a .p12 file, and the
 * client activates and enforces the result. Also covers the reserve-key
 * scenario: the primary key is "lost" and renewals are signed with the
 * reserve key, which installations in the field already trust.
 */
class EndToEndTest {

    private static final String PRODUCT_ID = "acme-suite";
    private static final char[] PASSWORD = "correct-horse-battery".toCharArray();

    @TempDir
    Path dir;

    private SigningKey primary;
    private SigningKey reserve;
    private UUID installationId;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        SigningKeyStore.create(dir.resolve("primary.p12"), "2026-01", PASSWORD.clone());
        SigningKeyStore.create(dir.resolve("reserve.p12"), "2026-01-reserve", PASSWORD.clone());
        // load them back the way IssuerCli does, rather than using what create() returned
        primary = SigningKeyStore.load(dir.resolve("primary.p12"), Optional.empty(), PASSWORD.clone());
        reserve = SigningKeyStore.load(dir.resolve("reserve.p12"), Optional.empty(), PASSWORD.clone());
        installationId = UUID.randomUUID();
        clusterId = UUID.randomUUID();
    }

    @Test
    void licenseSignedWithThePrimaryKeyIsActivatedAndEnforced() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        String license = issue(primary, clock, 25);

        LicenseValidator validator = clientValidator(primary, reserve);
        RuntimeContext context = clientContext(clock);
        InMemoryLicenseStore store = new InMemoryLicenseStore();

        ActivationService.ActivationResult result = new ActivationService(validator, store, context).activate(license);
        assertThat(result.accepted()).isTrue();

        DefaultLicenseService service = new DefaultLicenseService(validator, store, context,
                Duration.ofMinutes(1), Duration.ofDays(30), Duration.ofDays(14), clock);
        assertThat(service.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(service.isModuleEnabled("reports")).isTrue();
        assertThat(service.isModuleEnabled("inventory")).isFalse();
        assertThat(service.maxUsers()).isEqualTo(25);
    }

    @Test
    void afterLosingThePrimaryKeyRenewalsSignedWithTheReserveKeyAreAccepted() {
        Clock firstIssue = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        Clock renewal = Clock.fixed(Instant.parse("2027-09-01T12:00:00Z"), ZoneOffset.UTC);

        LicenseValidator validator = clientValidator(primary, reserve); // what shipped in the product
        InMemoryLicenseStore store = new InMemoryLicenseStore();

        assertThat(new ActivationService(validator, store, clientContext(firstIssue))
                .activate(issue(primary, firstIssue, 25)).accepted()).isTrue();

        // primary key lost: the issuer switches to the reserve keystore, no product update
        ActivationService.ActivationResult renewed = new ActivationService(validator, store, clientContext(renewal))
                .activate(issue(reserve, renewal, 40));

        assertThat(renewed.accepted()).isTrue();
        assertThat(renewed.license().maxUsers()).isEqualTo(40);
    }

    @Test
    void licenseSignedWithAKeyTheProductDoesNotTrustIsRejected() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        SigningKeyStore.create(dir.resolve("rogue.p12"), "2026-01", PASSWORD.clone()); // same kid, different key
        SigningKey rogue = SigningKeyStore.load(dir.resolve("rogue.p12"), Optional.empty(), PASSWORD.clone());

        ActivationService.ActivationResult result = new ActivationService(
                clientValidator(primary, reserve), new InMemoryLicenseStore(), clientContext(clock))
                .activate(issue(rogue, clock, 1000));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo(LicenseValidationReason.BAD_SIGNATURE);
    }

    private String issue(SigningKey key, Clock clock, int maxUsers) {
        String requestText = new LicenseRequestGenerator(PRODUCT_ID, "3.2.1", () -> installationId,
                () -> Optional.of(clusterId), clock).generate("Test Org");
        LicenseRequest request = RequestParser.parse(requestText);
        IssueDecision decision = new IssueDecision("Test Customer", Set.of("reports", "integration-1c"),
                maxUsers, Duration.ofDays(365));
        return new LicenseSigner(key, clock).issue(request, decision).armoredLicenseText();
    }

    private LicenseValidator clientValidator(SigningKey... trusted) {
        Map<String, byte[]> keys = new java.util.HashMap<>();
        for (SigningKey key : trusted) {
            keys.put(key.keyId(), key.publicKey().getEncoded());
        }
        return new LicenseValidator(new SignatureVerifier(keys), ClusterIdPolicy.STRICT);
    }

    private RuntimeContext clientContext(Clock clock) {
        return new RuntimeContext(PRODUCT_ID, installationId, () -> Optional.of(clusterId), clock);
    }

    private static final class InMemoryLicenseStore implements LicenseStore {
        private StoredLicense current;

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
