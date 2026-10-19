package com.example.licensing.common.codec;

import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicensePayload;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadCodecTest {

    @Test
    void signedLicenseRoundTripsAndVerifies() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        LicensePayload payload = new LicensePayload(
                LicensePayload.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026-01",
                "acme-suite",
                "Acme Corp",
                UUID.randomUUID(),
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"),
                Set.of("reports", "integration-1c"),
                50);

        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(keyPair.getPrivate(), payloadBytes);
        String armored = PayloadCodec.encodeSigned(payloadBytes, signature);

        PayloadCodec.SignedLicense decoded = PayloadCodec.decodeSigned(armored);
        LicensePayload decodedPayload = PayloadCodec.parsePayload(decoded.payloadBytes());

        assertThat(decodedPayload).isEqualTo(payload);
        assertThat(Ed25519.verify(keyPair.getPublic(), decoded.payloadBytes(), decoded.signature())).isTrue();
    }

    @Test
    void verificationFailsIfPayloadBytesDontMatchSignature() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        LicensePayload payloadA = samplePayload(50);
        LicensePayload payloadB = samplePayload(999);

        byte[] bytesA = PayloadCodec.canonicalBytes(payloadA);
        byte[] bytesB = PayloadCodec.canonicalBytes(payloadB);
        byte[] signatureForA = Ed25519.sign(keyPair.getPrivate(), bytesA);

        assertThat(Ed25519.verify(keyPair.getPublic(), bytesB, signatureForA)).isFalse();
    }

    private LicensePayload samplePayload(int maxUsers) {
        return new LicensePayload(
                LicensePayload.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026-01",
                "acme-suite",
                "Acme Corp",
                UUID.randomUUID(),
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"),
                Set.of("reports"),
                maxUsers);
    }
}
