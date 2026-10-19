package com.example.licensing.client;

import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicensePayload;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationServiceTest {

    private static final String KID = "test-key-1";
    private static final String PRODUCT_ID = "acme-suite";

    @Test
    void firstActivationIsAccepted() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        ActivationService activation = activationService(keyPair, installationId);

        String text = issueAt(keyPair, installationId, Instant.parse("2026-01-01T00:00:00Z"), 10);
        ActivationService.ActivationResult result = activation.activate(text);

        assertThat(result.accepted()).isTrue();
        assertThat(result.license().maxUsers()).isEqualTo(10);
    }

    @Test
    void activationRefusesAnOlderKeyThanWhatsActive() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        ActivationService activation = activationService(keyPair, installationId);

        String newer = issueAt(keyPair, installationId, Instant.parse("2026-06-01T00:00:00Z"), 50);
        String older = issueAt(keyPair, installationId, Instant.parse("2026-01-01T00:00:00Z"), 50);

        assertThat(activation.activate(newer).accepted()).isTrue();
        assertThat(activation.activate(older).accepted()).isFalse();
    }

    @Test
    void renewalWithANewerIssuedAtIsAccepted() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        ActivationService activation = activationService(keyPair, installationId);

        String first = issueAt(keyPair, installationId, Instant.parse("2026-01-01T00:00:00Z"), 10);
        String renewal = issueAt(keyPair, installationId, Instant.parse("2026-06-01T00:00:00Z"), 100);

        activation.activate(first);
        ActivationService.ActivationResult result = activation.activate(renewal);

        assertThat(result.accepted()).isTrue();
        assertThat(result.license().maxUsers()).isEqualTo(100);
    }

    @Test
    void activationRejectsAnInvalidKey() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        UUID installationId = UUID.randomUUID();
        ActivationService activation = activationService(keyPair, installationId);

        String forOtherInstallation = issueAt(keyPair, UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"), 10);
        ActivationService.ActivationResult result = activation.activate(forOtherInstallation);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo(LicenseValidationReason.WRONG_INSTALLATION);
    }

    private ActivationService activationService(KeyPair keyPair, UUID installationId) {
        SignatureVerifier verifier = new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded()));
        LicenseValidator validator = new LicenseValidator(verifier, ClusterIdPolicy.WARN_ONLY);
        RuntimeContext context = new RuntimeContext(PRODUCT_ID, installationId, Optional::empty,
                Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC));
        return new ActivationService(validator, new InMemoryLicenseStore(), context);
    }

    private String issueAt(KeyPair keyPair, UUID installationId, Instant issuedAt, int maxUsers) {
        LicensePayload payload = new LicensePayload(
                LicensePayload.CURRENT_FORMAT_VERSION, UUID.randomUUID(), UUID.randomUUID(), KID,
                PRODUCT_ID, "Test Customer", installationId, null,
                issuedAt, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"),
                Set.of("reports"), maxUsers);
        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(keyPair.getPrivate(), payloadBytes);
        return PayloadCodec.encodeSigned(payloadBytes, signature);
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
