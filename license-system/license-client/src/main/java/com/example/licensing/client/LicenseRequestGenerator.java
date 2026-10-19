package com.example.licensing.client;

import com.example.licensing.common.codec.RequestCodec;
import com.example.licensing.common.model.LicenseRequest;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds the armored request text an operator emails to the issuer.
 */
public final class LicenseRequestGenerator {

    private final String productId;
    private final String productVersion;
    private final InstallationIdProvider installationIdProvider;
    private final Supplier<Optional<UUID>> clusterIdSupplier;
    private final Clock clock;

    public LicenseRequestGenerator(String productId, String productVersion,
                                    InstallationIdProvider installationIdProvider,
                                    Supplier<Optional<UUID>> clusterIdSupplier,
                                    Clock clock) {
        this.productId = productId;
        this.productVersion = productVersion;
        this.installationIdProvider = installationIdProvider;
        this.clusterIdSupplier = clusterIdSupplier;
        this.clock = clock;
    }

    /** @param organization free-text name the operator types in; purely informational for the issuer. */
    public String generate(String organization) {
        LicenseRequest request = new LicenseRequest(
                LicenseRequest.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(),
                productId,
                productVersion,
                installationIdProvider.installationId(),
                clusterIdSupplier.get().orElse(null),
                organization,
                clock.instant());
        return RequestCodec.encode(request);
    }
}
