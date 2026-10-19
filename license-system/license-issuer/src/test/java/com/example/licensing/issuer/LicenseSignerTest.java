package com.example.licensing.issuer;

import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.keystore.SigningKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseSignerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void issuedLicenseCarriesTheRequestsIdentityAndTheDecisionsEntitlement() {
        KeyPair keyPair = Ed25519.generateKeyPair();
        LicenseSigner signer = new LicenseSigner(
                new SigningKey("2026-01", keyPair.getPrivate(), keyPair.getPublic()), CLOCK);

        UUID installationId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        LicenseRequest request = request(installationId, clusterId);
        IssueDecision decision = new IssueDecision(
                "Actual Customer Name", Set.of("reports", "integration-1c"), 25, Duration.ofDays(365));

        LicenseSigner.Issued issued = signer.issue(request, decision);

        // identity comes from the request, not the operator's decision
        assertThat(issued.payload().installationId()).isEqualTo(installationId);
        assertThat(issued.payload().clusterId()).isEqualTo(clusterId);
        assertThat(issued.payload().requestId()).isEqualTo(request.requestId());
        assertThat(issued.payload().productId()).isEqualTo("acme-suite");
        assertThat(issued.payload().keyId()).isEqualTo("2026-01");

        // entitlement comes from the decision, not the request
        assertThat(issued.payload().customer()).isEqualTo("Actual Customer Name");
        assertThat(issued.payload().modules()).containsExactlyInAnyOrder("reports", "integration-1c");
        assertThat(issued.payload().maxUsers()).isEqualTo(25);
        assertThat(issued.payload().notBefore()).isEqualTo(Instant.parse("2026-10-09T12:00:00Z"));
        assertThat(issued.payload().notAfter()).isEqualTo(Instant.parse("2027-10-09T12:00:00Z"));

        PayloadCodec.SignedLicense decoded = PayloadCodec.decodeSigned(issued.armoredLicenseText());
        assertThat(Ed25519.verify(keyPair.getPublic(), decoded.payloadBytes(), decoded.signature())).isTrue();
    }

    @Test
    void refusesToIssueWhenPrivateAndPublicKeyDoNotMatch() {
        KeyPair one = Ed25519.generateKeyPair();
        KeyPair other = Ed25519.generateKeyPair();
        LicenseSigner signer = new LicenseSigner(
                new SigningKey("2026-01", one.getPrivate(), other.getPublic()), CLOCK);
        IssueDecision decision = new IssueDecision("Customer", Set.of("reports"), 5, Duration.ofDays(30));

        assertThatThrownBy(() -> signer.issue(request(UUID.randomUUID(), null), decision))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("self-check failed");
    }

    private static LicenseRequest request(UUID installationId, UUID clusterId) {
        return new LicenseRequest(
                LicenseRequest.CURRENT_FORMAT_VERSION, UUID.randomUUID(), "acme-suite", "3.2.1",
                installationId, clusterId, "Requested Org Name", Instant.parse("2026-10-09T10:00:00Z"));
    }
}
