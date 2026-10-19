package com.example.licensing.client;

import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicensePayload;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseValidatorTest {

    private static final String KID = "test-key-1";
    private static final String PRODUCT_ID = "acme-suite";

    private KeyPair keyPair;
    private LicenseValidator validator;
    private UUID installationId;
    private RuntimeContext context;

    @BeforeEach
    void setUp() {
        keyPair = Ed25519.generateKeyPair();
        SignatureVerifier verifier = new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded()));
        validator = new LicenseValidator(verifier, ClusterIdPolicy.WARN_ONLY);
        installationId = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        context = new RuntimeContext(PRODUCT_ID, installationId, Optional::empty, clock);
    }

    @Test
    void acceptsAValidLicense() {
        String text = issue(installationId, PRODUCT_ID, null, 50);

        VerifiedLicense verified = validator.verify(text, context);

        assertThat(verified.hasModule("reports")).isTrue();
        assertThat(verified.maxUsers()).isEqualTo(50);
    }

    @Test
    void rejectsAPayloadSignatureMismatch() {
        LicensePayload payloadA = payload(installationId, PRODUCT_ID, null, 10);
        LicensePayload payloadB = payload(installationId, PRODUCT_ID, null, 999);

        byte[] bytesA = PayloadCodec.canonicalBytes(payloadA);
        byte[] bytesB = PayloadCodec.canonicalBytes(payloadB);
        byte[] signatureForA = Ed25519.sign(keyPair.getPrivate(), bytesA);

        // payload B's bytes paired with a signature computed over payload A:
        // a stand-in for "a signature that doesn't match the bytes it's
        // attached to", however that mismatch came about.
        String mismatchedText = PayloadCodec.encodeSigned(bytesB, signatureForA);

        assertThatThrownBy(() -> validator.verify(mismatchedText, context))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.BAD_SIGNATURE);
    }

    @Test
    void rejectsAWrongInstallation() {
        String text = issue(UUID.randomUUID(), PRODUCT_ID, null, 50);

        assertThatThrownBy(() -> validator.verify(text, context))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.WRONG_INSTALLATION);
    }

    @Test
    void rejectsAWrongProduct() {
        String text = issue(installationId, "some-other-product", null, 50);

        assertThatThrownBy(() -> validator.verify(text, context))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.WRONG_PRODUCT);
    }

    @Test
    void rejectsAnUnknownKeyId() {
        KeyPair otherKeyPair = Ed25519.generateKeyPair();
        LicensePayload payload = payload("some-other-kid", installationId, PRODUCT_ID, null, 50);
        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(otherKeyPair.getPrivate(), payloadBytes);
        String text = PayloadCodec.encodeSigned(payloadBytes, signature);

        assertThatThrownBy(() -> validator.verify(text, context))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.UNKNOWN_KEY);
    }

    @Test
    void rejectsAForeignKeyThatClaimsATrustedKeyId() {
        // Someone with their own key pair writing our kid into the license:
        // the kid is known, so this must fail on the signature itself.
        KeyPair otherKeyPair = Ed25519.generateKeyPair();
        LicensePayload payload = payload(installationId, PRODUCT_ID, null, 50);
        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(otherKeyPair.getPrivate(), payloadBytes);
        String text = PayloadCodec.encodeSigned(payloadBytes, signature);

        assertThatThrownBy(() -> validator.verify(text, context))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.BAD_SIGNATURE);
    }

    @Test
    void strictClusterPolicyRejectsMismatch() {
        SignatureVerifier verifier = new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded()));
        LicenseValidator strictValidator = new LicenseValidator(verifier, ClusterIdPolicy.STRICT);
        UUID licensedCluster = UUID.randomUUID();
        UUID actualCluster = UUID.randomUUID();
        String text = issue(installationId, PRODUCT_ID, licensedCluster, 10);
        RuntimeContext strictContext = new RuntimeContext(PRODUCT_ID, installationId,
                () -> Optional.of(actualCluster), context.clock());

        assertThatThrownBy(() -> strictValidator.verify(text, strictContext))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.WRONG_CLUSTER);
    }

    @Test
    void strictClusterPolicyRejectsUnreadableClusterId() {
        SignatureVerifier verifier = new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded()));
        LicenseValidator strictValidator = new LicenseValidator(verifier, ClusterIdPolicy.STRICT);
        String text = issue(installationId, PRODUCT_ID, UUID.randomUUID(), 10);
        RuntimeContext noClusterContext = new RuntimeContext(PRODUCT_ID, installationId,
                Optional::empty, context.clock());

        assertThatThrownBy(() -> strictValidator.verify(text, noClusterContext))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.CLUSTER_ID_UNAVAILABLE);
    }

    @Test
    void warnOnlyClusterPolicyToleratesMismatch() {
        UUID licensedCluster = UUID.randomUUID();
        UUID actualCluster = UUID.randomUUID();
        String text = issue(installationId, PRODUCT_ID, licensedCluster, 10);
        RuntimeContext warnOnlyContext = new RuntimeContext(PRODUCT_ID, installationId,
                () -> Optional.of(actualCluster), context.clock());

        VerifiedLicense verified = validator.verify(text, warnOnlyContext);

        assertThat(verified.maxUsers()).isEqualTo(10);
    }

    @Test
    void rejectsCorruptedArmorText() {
        String text = issue(installationId, PRODUCT_ID, null, 50);
        String[] lines = text.split("\n");
        char flipped = lines[1].charAt(0) == 'A' ? 'B' : 'A';
        lines[1] = flipped + lines[1].substring(1);
        String corrupted = String.join("\n", lines) + "\n";

        assertThatThrownBy(() -> validator.verify(corrupted, context))
                .isInstanceOf(LicenseValidationException.class)
                .extracting(e -> ((LicenseValidationException) e).reason())
                .isEqualTo(LicenseValidationReason.MALFORMED);
    }

    private String issue(UUID installationId, String productId, UUID clusterId, int maxUsers) {
        LicensePayload payload = payload(installationId, productId, clusterId, maxUsers);
        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(keyPair.getPrivate(), payloadBytes);
        return PayloadCodec.encodeSigned(payloadBytes, signature);
    }

    private LicensePayload payload(UUID installationId, String productId, UUID clusterId, int maxUsers) {
        return payload(KID, installationId, productId, clusterId, maxUsers);
    }

    private LicensePayload payload(String kid, UUID installationId, String productId, UUID clusterId, int maxUsers) {
        return new LicensePayload(
                LicensePayload.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                kid,
                productId,
                "Test Customer",
                installationId,
                clusterId,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"),
                Set.of("reports", "integration-1c"),
                maxUsers);
    }
}
