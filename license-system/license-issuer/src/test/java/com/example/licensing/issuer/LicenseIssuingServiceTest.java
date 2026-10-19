package com.example.licensing.issuer;

import com.example.licensing.common.codec.RequestCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.keystore.SigningKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseIssuingServiceTest {

    @TempDir
    Path auditDir;

    private KeyPair keyPair;
    private LicenseIssuingService service;
    private String requestText;

    @BeforeEach
    void setUp() {
        keyPair = Ed25519.generateKeyPair();
        LicenseSigner signer = new LicenseSigner(new SigningKey("2026-01", keyPair.getPrivate(), keyPair.getPublic()),
                Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));
        service = new LicenseIssuingService(signer, new FileIssuedLicenseRepository(auditDir));
        requestText = RequestCodec.encode(new LicenseRequest(LicenseRequest.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(), "acme-suite", "3.2.1", UUID.randomUUID(), UUID.randomUUID(),
                "Рога и Копыта", Instant.parse("2026-10-19T10:00:00Z")));
    }

    @Test
    void issuesARecordedLicenseAndShowsItInHistory() {
        LicenseSigner.Issued issued = service.issue(command("", List.of("reports", " integration-1c "), 25, 365), "alice");

        assertThat(issued.payload().customer()).isEqualTo("Рога и Копыта"); // defaulted from the request
        assertThat(issued.payload().modules()).containsExactlyInAnyOrder("reports", "integration-1c");
        assertThat(issued.payload().notAfter()).isEqualTo(Instant.parse("2027-10-19T12:00:00Z"));

        List<IssuedLicenseRepository.IssuedLicenseRecord> history = service.history(10);
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).issuedBy()).isEqualTo("alice");
        assertThat(service.licenseText(issued.payload().licenseId()).orElseThrow())
                .isEqualTo(issued.armoredLicenseText());
    }

    @Test
    void previewWarnsAboutARequestThatWasAlreadyIssued() {
        assertThat(service.preview(requestText).previouslyIssuedLicenseIds().isEmpty()).isTrue();

        LicenseSigner.Issued issued = service.issue(command("Acme", List.of("reports"), 5, 30), "alice");

        assertThat(service.preview(requestText).previouslyIssuedLicenseIds())
                .containsExactlyInAnyOrder(issued.payload().licenseId());
    }

    @Test
    void signingKeyInfoGivesTheLineForTrustedKeys() {
        LicenseIssuingService.SigningKeyInfo info = service.signingKey();

        assertThat(info.keyId()).isEqualTo("2026-01");
        assertThat(Base64.getDecoder().decode(info.publicKeyBase64())).isEqualTo(keyPair.getPublic().getEncoded());
        assertThat(info.trustedKeysLine()).contains("\"2026-01\", Base64.getDecoder().decode(");
    }

    @Test
    void rejectsBadOperatorInput() {
        assertThatThrownBy(() -> service.issue(command("Acme", List.of(), 5, 30), "alice"))
                .isInstanceOf(LicenseIssuingService.IssueValidationException.class)
                .hasMessageContaining("at least one module");
        assertThatThrownBy(() -> service.issue(command("Acme", List.of("Reports"), 5, 30), "alice"))
                .isInstanceOf(LicenseIssuingService.IssueValidationException.class)
                .hasMessageContaining("invalid module id");
        assertThatThrownBy(() -> service.issue(command("Acme", List.of("reports"), 0, 30), "alice"))
                .isInstanceOf(LicenseIssuingService.IssueValidationException.class)
                .hasMessageContaining("maxUsers");
        assertThatThrownBy(() -> service.issue(command("Acme", List.of("reports"), 5, 0), "alice"))
                .isInstanceOf(LicenseIssuingService.IssueValidationException.class)
                .hasMessageContaining("validityDays");
        assertThatThrownBy(() -> service.preview("  "))
                .isInstanceOf(LicenseIssuingService.IssueValidationException.class)
                .hasMessageContaining("request text is empty");
        assertThatThrownBy(() -> service.preview("Hello, please send us a license"))
                .isInstanceOf(RequestParser.RequestParseException.class);
        assertThat(service.history(10).isEmpty()).isTrue(); // nothing was recorded for rejected input
    }

    @Test
    void withoutAnActiveKeyNothingIsIssuedButRequestsCanStillBePreviewed() {
        LicenseIssuingService noKey = new LicenseIssuingService(java.util.Optional::empty,
                new FileIssuedLicenseRepository(auditDir));

        assertThat(noKey.preview(requestText).request().productId()).isEqualTo("acme-suite");
        assertThatThrownBy(() -> noKey.issue(command("Acme", List.of("reports"), 5, 30), "alice"))
                .isInstanceOf(LicenseIssuingService.NoActiveKeyException.class);
        assertThatThrownBy(noKey::signingKey)
                .isInstanceOf(LicenseIssuingService.NoActiveKeyException.class);
        assertThat(noKey.hasIssuedLicenses()).isFalse();
    }

    private LicenseIssuingService.IssueCommand command(String customer, List<String> modules, int maxUsers, int days) {
        return new LicenseIssuingService.IssueCommand(requestText, customer, modules, maxUsers, days);
    }
}
