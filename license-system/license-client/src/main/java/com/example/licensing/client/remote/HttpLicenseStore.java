package com.example.licensing.client.remote;

import com.example.licensing.client.LicenseStore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Reads the signed license text from the license validator service, for use
 * inside the product.
 *
 * <p>The validator is just the courier here: whatever this returns is
 * checked by {@link com.example.licensing.client.LicenseValidator} in the
 * product itself (signature against the public keys compiled into the
 * product, product id, cluster id read by the product from the Kubernetes
 * API). A validator replaced by a stub that answers "valid" gains nothing,
 * because it cannot produce a signed license.
 *
 * <p>Uses only the JDK's HttpClient; the response is plain text, so there is
 * no JSON library to pull into the product either.
 */
public final class HttpLicenseStore implements LicenseStore {

    public static final String SIGNED_LICENSE_PATH = "api/v1/license/signed";

    private final HttpClient httpClient;
    private final URI signedLicenseUri;
    private final Duration requestTimeout;

    public HttpLicenseStore(URI validatorBaseUrl) {
        this(validatorBaseUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(5));
    }

    public HttpLicenseStore(URI validatorBaseUrl, HttpClient httpClient, Duration requestTimeout) {
        String base = validatorBaseUrl.toString();
        this.signedLicenseUri = URI.create(base.endsWith("/") ? base : base + "/").resolve(SIGNED_LICENSE_PATH);
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    /**
     * @return the license text, or empty if the validator has none (HTTP 404)
     * @throws LicenseSourceUnavailableException if the validator can't be reached or answers
     *                                           with anything else; {@link com.example.licensing.client.DefaultLicenseService}
     *                                           then keeps its last verified state
     */
    @Override
    public Optional<StoredLicense> load() {
        HttpRequest request = HttpRequest.newBuilder(signedLicenseUri)
                .timeout(requestTimeout)
                .header("Accept", "text/plain")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LicenseSourceUnavailableException("cannot reach license validator at " + signedLicenseUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LicenseSourceUnavailableException("interrupted while calling license validator", e);
        }
        return switch (response.statusCode()) {
            case 200 -> Optional.of(new StoredLicense(response.body(), 0));
            case 404 -> Optional.empty();
            default -> throw new LicenseSourceUnavailableException(
                    "license validator answered HTTP " + response.statusCode() + " for " + signedLicenseUri);
        };
    }

    @Override
    public boolean save(StoredLicense license, Optional<StoredLicense> expectedPrevious) {
        throw new UnsupportedOperationException(
                "licenses are activated in the license validator service, not from the product");
    }

    public static final class LicenseSourceUnavailableException extends RuntimeException {
        public LicenseSourceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public LicenseSourceUnavailableException(String message) {
            super(message);
        }
    }
}
