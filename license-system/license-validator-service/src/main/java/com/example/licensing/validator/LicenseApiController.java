package com.example.licensing.validator;

import com.example.licensing.client.ActivationService;
import com.example.licensing.client.DefaultLicenseService;
import com.example.licensing.client.LicenseRequestGenerator;
import com.example.licensing.client.LicenseSnapshot;
import com.example.licensing.client.LicenseStore;
import com.example.licensing.client.VerifiedLicense;
import com.example.licensing.client.jdbc.JdbcInstallationIdProvider;
import com.example.licensing.client.jdbc.JdbcLicenseStore;
import com.example.licensing.client.k8s.KubernetesClusterIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * POST /api/v1/license-request   {"organization": "..."}  -> request text to email to the vendor
 * PUT  /api/v1/license           license text from the vendor's reply (text/plain) -> activation result
 * GET  /api/v1/license/status    status for admins and monitoring
 * GET  /api/v1/license/signed    the signed license text, for the product to verify itself
 * GET  /api/v1/installation      this installation's id
 * </pre>
 *
 * <p>Nothing here needs to be secret or authenticated for licensing to hold:
 * activation only accepts licenses signed by the vendor for this
 * installation, and the product never trusts {@code /status} - it fetches
 * {@code /signed} and verifies the signature itself. Restricting access with a
 * NetworkPolicy (product + admin tooling only) is still good hygiene.
 */
@RestController
@RequestMapping("/api/v1")
class LicenseApiController {

    private static final Logger LOG = LoggerFactory.getLogger(LicenseApiController.class);

    private final LicenseRequestGenerator requestGenerator;
    private final ActivationService activationService;
    private final DefaultLicenseService licenseService;
    private final JdbcLicenseStore licenseStore;
    private final JdbcInstallationIdProvider installationIdProvider;
    private final KubernetesClusterIdProvider clusterIdProvider;
    private final ValidatorProperties properties;

    LicenseApiController(LicenseRequestGenerator requestGenerator, ActivationService activationService,
                         DefaultLicenseService licenseService, JdbcLicenseStore licenseStore,
                         JdbcInstallationIdProvider installationIdProvider,
                         KubernetesClusterIdProvider clusterIdProvider, ValidatorProperties properties) {
        this.requestGenerator = requestGenerator;
        this.activationService = activationService;
        this.licenseService = licenseService;
        this.licenseStore = licenseStore;
        this.installationIdProvider = installationIdProvider;
        this.clusterIdProvider = clusterIdProvider;
        this.properties = properties;
    }

    // No 'produces' on these mappings: it would also restrict what an error handler may return
    // (a JSON error body for a text/plain endpoint). Content types are set on each response instead.
    @PostMapping(path = "/license-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> createRequest(@RequestBody LicenseRequestBody body) {
        if (body.organization() == null || body.organization().isBlank()) {
            throw new IllegalArgumentException("organization is required - it tells the vendor who is asking");
        }
        String requestText = requestGenerator.generate(body.organization().strip());
        LOG.info("License request created for organization '{}'", body.organization().strip());
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(requestText);
    }

    @PutMapping(path = "/license", consumes = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<ActivationResponse> activate(@RequestBody String licenseText) {
        ActivationService.ActivationResult result = activationService.activate(licenseText);
        if (!result.accepted()) {
            LOG.warn("License activation refused: {} ({})", result.message(), result.reason());
            return ResponseEntity.status(422).body(new ActivationResponse(false,
                    result.reason() == null ? null : result.reason().name(), result.message(), null));
        }
        licenseService.invalidate();
        LOG.info("License {} activated for '{}', valid until {}", result.license().licenseId(),
                result.license().customer(), result.license().notAfter());
        return ResponseEntity.ok(new ActivationResponse(true, null, result.message(),
                LicenseView.of(result.license())));
    }

    @GetMapping("/license/status")
    StatusResponse status() {
        LicenseSnapshot snapshot = licenseService.snapshot();
        return new StatusResponse(
                snapshot.status().name(),
                snapshot.detail(),
                snapshot.reason().map(Enum::name).orElse(null),
                snapshot.clockWarning(),
                properties.productId(),
                installationIdProvider.installationId(),
                clusterIdProvider.get().orElse(null),
                snapshot.license().map(LicenseView::of).orElse(null));
    }

    @GetMapping("/license/signed")
    ResponseEntity<String> signedLicense() {
        Optional<LicenseStore.StoredLicense> stored = licenseStore.load();
        return stored
                .map(s -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(s.rawText()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/installation")
    ResponseEntity<String> installation() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(installationIdProvider.installationId().toString());
    }

    // ---- bodies (times as ISO-8601 strings) ----

    record LicenseRequestBody(String organization) {
    }

    record ActivationResponse(boolean accepted, String reason, String message, LicenseView license) {
    }

    record StatusResponse(String status, String detail, String reason, String clockWarning, String productId,
                          UUID installationId, UUID clusterId, LicenseView license) {
    }

    record LicenseView(UUID licenseId, String customer, List<String> modules, int maxUsers,
                       String issuedAt, String notBefore, String notAfter) {
        static LicenseView of(VerifiedLicense l) {
            return new LicenseView(l.licenseId(), l.customer(), l.modules().stream().sorted().toList(),
                    l.maxUsers(), l.issuedAt().toString(), l.notBefore().toString(), l.notAfter().toString());
        }
    }
}
