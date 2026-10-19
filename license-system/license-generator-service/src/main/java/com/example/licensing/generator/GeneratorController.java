package com.example.licensing.generator;

import com.example.licensing.common.model.LicensePayload;
import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.IssuedLicenseRepository.IssuedLicenseRecord;
import com.example.licensing.issuer.LicenseIssuingService;
import com.example.licensing.issuer.LicenseSigner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Operator API. All logic and input validation live in
 * {@link LicenseIssuingService}; this class only maps HTTP to it.
 *
 * <pre>
 * GET  /api/v1/signing-key             kid + public key (for the product's TrustedKeys)
 * POST /api/v1/requests/preview        parse a pasted request before issuing
 * POST /api/v1/licenses                issue a license, returns the text to email back
 * GET  /api/v1/licenses?limit=50       issuance history (audit)
 * GET  /api/v1/licenses/{id}/text      the exact text that was sent, to resend it
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
class GeneratorController {

    private final LicenseIssuingService issuing;

    GeneratorController(LicenseIssuingService issuing) {
        this.issuing = issuing;
    }

    @GetMapping("/signing-key")
    LicenseIssuingService.SigningKeyInfo signingKey() {
        return issuing.signingKey();
    }

    @PostMapping(path = "/requests/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    PreviewResponse preview(@RequestBody RequestTextBody body) {
        LicenseIssuingService.Preview preview = issuing.preview(body.requestText());
        LicenseRequest r = preview.request();
        return new PreviewResponse(r.requestId(), r.productId(), r.productVersion(), r.installationId(),
                r.clusterId(), r.organization(), r.createdAt().toString(), preview.previouslyIssuedLicenseIds());
    }

    @PostMapping(path = "/licenses", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IssuedResponse> issue(@RequestBody IssueBody body, Principal operator) {
        LicenseSigner.Issued issued = issuing.issue(new LicenseIssuingService.IssueCommand(
                body.requestText(), body.customer(), body.modules(), body.maxUsers(), body.validityDays()),
                operator.getName());
        LicensePayload p = issued.payload();
        return ResponseEntity.status(HttpStatus.CREATED).body(new IssuedResponse(
                p.licenseId(), p.keyId(), p.customer(), p.productId(), p.installationId(), p.clusterId(),
                List.copyOf(p.modules()), p.maxUsers(), p.issuedAt().toString(), p.notBefore().toString(),
                p.notAfter().toString(), issued.armoredLicenseText()));
    }

    @GetMapping("/licenses")
    List<HistoryEntry> history(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return issuing.history(limit).stream().map(HistoryEntry::of).toList();
    }

    // No 'produces': it would also restrict error responses. The content type is set on the response.
    @GetMapping("/licenses/{licenseId}/text")
    ResponseEntity<String> licenseText(@PathVariable("licenseId") UUID licenseId) {
        return issuing.licenseText(licenseId)
                .map(text -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(text))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- request/response bodies (times as ISO-8601 strings) ----

    record RequestTextBody(String requestText) {
    }

    record IssueBody(String requestText, String customer, List<String> modules, int maxUsers, int validityDays) {
    }

    record PreviewResponse(UUID requestId, String productId, String productVersion, UUID installationId,
                           UUID clusterId, String organization, String requestedAt,
                           List<UUID> previouslyIssuedLicenseIds) {
    }

    record IssuedResponse(UUID licenseId, String keyId, String customer, String productId, UUID installationId,
                          UUID clusterId, List<String> modules, int maxUsers, String issuedAt, String notBefore,
                          String notAfter, String licenseText) {
    }

    record HistoryEntry(String issuedAt, UUID licenseId, String customer, String productId, UUID installationId,
                        String clusterId, List<String> modules, int maxUsers, String notAfter, String keyId,
                        String issuedBy) {
        static HistoryEntry of(IssuedLicenseRecord r) {
            return new HistoryEntry(r.issuedAt().toString(), r.licenseId(), r.customer(), r.productId(),
                    r.installationId(), r.clusterId(), r.modules(), r.maxUsers(), r.notAfter().toString(),
                    r.keyId(), r.issuedBy());
        }
    }
}
