package com.example.licensing.client.remote;

import com.example.licensing.client.ClusterIdPolicy;
import com.example.licensing.client.DefaultLicenseService;
import com.example.licensing.client.LicenseSnapshot;
import com.example.licensing.client.LicenseStatus;
import com.example.licensing.client.LicenseValidationReason;
import com.example.licensing.client.LicenseValidator;
import com.example.licensing.client.RuntimeContext;
import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicensePayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product side: license text fetched over real HTTP from a stand-in for
 * the validator service, verified locally by the product.
 */
class RemoteLicensingTest {

    private static final String KID = "test-key-1";
    private static final String PRODUCT_ID = "acme-suite";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    private final KeyPair keyPair = Ed25519.generateKeyPair();
    private final UUID clusterId = UUID.randomUUID();

    @Test
    void productAcceptsALicenseServedByTheValidator() throws IOException {
        try (FakeValidator validator = FakeValidator.serving(sign(keyPair, clusterId))) {
            DefaultLicenseService licensing = product(validator.baseUrl(), Duration.ofMinutes(1));

            assertThat(licensing.status()).isEqualTo(LicenseStatus.VALID);
            assertThat(licensing.isModuleEnabled("reports")).isTrue();
            assertThat(licensing.maxUsers()).isEqualTo(25);
        }
    }

    @Test
    void noLicenseOnTheValidatorMeansNoLicense() throws IOException {
        try (FakeValidator validator = FakeValidator.serving(null)) {
            assertThat(product(validator.baseUrl(), Duration.ofMinutes(1)).status())
                    .isEqualTo(LicenseStatus.NO_LICENSE);
        }
    }

    @Test
    void validatorUnreachableFromTheStartIsReportedAsUnavailable() throws IOException {
        URI deadUrl;
        try (FakeValidator validator = FakeValidator.serving(null)) {
            deadUrl = validator.baseUrl(); // closed right away: nothing listens there any more
        }
        LicenseSnapshot snapshot = product(deadUrl, Duration.ofMinutes(1)).snapshot();

        assertThat(snapshot.status()).isEqualTo(LicenseStatus.UNAVAILABLE);
        assertThat(snapshot.detail()).contains("unavailable");
    }

    @Test
    void validatorOutageAfterAVerifiedReadKeepsTheLastState() throws IOException {
        DefaultLicenseService licensing;
        try (FakeValidator validator = FakeValidator.serving(sign(keyPair, clusterId))) {
            licensing = product(validator.baseUrl(), Duration.ZERO); // re-read on every call
            assertThat(licensing.status()).isEqualTo(LicenseStatus.VALID);
        }
        // validator gone now
        assertThat(licensing.status()).isEqualTo(LicenseStatus.VALID);
        assertThat(licensing.isModuleEnabled("reports")).isTrue();
    }

    @Test
    void licenseForAnotherClusterIsRejectedByTheProductItself() throws IOException {
        try (FakeValidator validator = FakeValidator.serving(sign(keyPair, UUID.randomUUID()))) {
            LicenseSnapshot snapshot = product(validator.baseUrl(), Duration.ofMinutes(1)).snapshot();

            assertThat(snapshot.status()).isEqualTo(LicenseStatus.INVALID);
            assertThat(snapshot.reason().orElseThrow()).isEqualTo(LicenseValidationReason.WRONG_CLUSTER);
        }
    }

    @Test
    void aFakeValidatorCannotMakeTheProductAcceptASelfMadeLicense() throws IOException {
        // Someone replaces the validator with their own service and signs a
        // generous license with their own key pair. ProductLicensing uses the
        // public keys compiled into the product, so the license is rejected.
        KeyPair attackersKey = Ed25519.generateKeyPair();
        try (FakeValidator validator = FakeValidator.serving(sign(attackersKey, clusterId))) {
            DefaultLicenseService licensing = ProductLicensing.connect(validator.baseUrl(), PRODUCT_ID,
                    ClusterIdPolicy.STRICT, () -> Optional.of(clusterId), CLOCK);

            LicenseSnapshot snapshot = licensing.snapshot();
            assertThat(snapshot.status()).isEqualTo(LicenseStatus.INVALID);
            assertThat(snapshot.reason().orElseThrow()).isEqualTo(LicenseValidationReason.UNKNOWN_KEY);
            assertThat(licensing.isModuleEnabled("reports")).isFalse();
        }
    }

    private DefaultLicenseService product(URI validatorUrl, Duration cacheTtl) {
        LicenseValidator validator = new LicenseValidator(
                new SignatureVerifier(Map.of(KID, keyPair.getPublic().getEncoded())), ClusterIdPolicy.STRICT);
        RuntimeContext context = RuntimeContext.withoutInstallationCheck(PRODUCT_ID, () -> Optional.of(clusterId), CLOCK);
        return new DefaultLicenseService(validator, new HttpLicenseStore(validatorUrl), context,
                cacheTtl, Duration.ofDays(30), Duration.ofDays(14), CLOCK);
    }

    private static String sign(KeyPair key, UUID boundCluster) {
        LicensePayload payload = new LicensePayload(LicensePayload.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(), UUID.randomUUID(), KID, PRODUCT_ID, "Test Customer",
                UUID.randomUUID(), // installation id: not checked on the product side
                boundCluster,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-06-01T00:00:00Z"), Set.of("reports"), 25);
        byte[] bytes = PayloadCodec.canonicalBytes(payload);
        return PayloadCodec.encodeSigned(bytes, Ed25519.sign(key.getPrivate(), bytes));
    }

    /** Minimal HTTP stand-in for the validator's GET /api/v1/license/signed. */
    private static final class FakeValidator implements AutoCloseable {
        private final HttpServer server;

        private FakeValidator(HttpServer server) {
            this.server = server;
        }

        static FakeValidator serving(String licenseText) throws IOException {
            AtomicReference<String> body = new AtomicReference<>(licenseText);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/" + HttpLicenseStore.SIGNED_LICENSE_PATH, exchange -> {
                String text = body.get();
                if (text == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
                exchange.close();
            });
            server.start();
            return new FakeValidator(server);
        }

        URI baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
