package com.example.licensing.common.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What the issuer signs.
 *
 * <p>This is the only source of truth for what a license grants. The client
 * must never reconstruct these values from anything other than a verified
 * signature over these exact bytes (see PayloadCodec) - not from database
 * columns, not from a cache, not from a previous read.
 */
public record LicensePayload(
        int formatVersion,
        UUID licenseId,
        UUID requestId,
        String keyId,            // which signing key issued this (for rotation)
        String productId,
        String customer,
        UUID installationId,
        UUID clusterId,          // nullable, mirrors LicenseRequest.clusterId
        Instant issuedAt,
        Instant notBefore,
        Instant notAfter,
        Set<String> modules,
        int maxUsers
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public LicensePayload {
        if (licenseId == null) throw new IllegalArgumentException("licenseId is required");
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("keyId is required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId is required");
        if (installationId == null) throw new IllegalArgumentException("installationId is required");
        if (issuedAt == null || notBefore == null || notAfter == null) {
            throw new IllegalArgumentException("issuedAt/notBefore/notAfter are required");
        }
        if (notAfter.isBefore(notBefore)) {
            throw new IllegalArgumentException("notAfter must not be before notBefore");
        }
        modules = modules == null ? Set.of() : Set.copyOf(modules);
        if (maxUsers < 0) throw new IllegalArgumentException("maxUsers must not be negative");
    }
}
