package com.example.licensing.common.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Data a client product sends to the issuer when asking for a license.
 *
 * <p>Everything here is produced by the client and MUST be treated as
 * untrusted input by the issuer: it establishes identity (who is asking,
 * from which installation), never entitlement (what they are allowed to
 * use). Entitlement is decided by the issuer's own policy, never copied
 * from the request.
 */
public record LicenseRequest(
        int formatVersion,
        UUID requestId,
        String productId,
        String productVersion,
        UUID installationId,
        UUID clusterId,          // nullable: not every deployment can read it
        String organization,
        Instant createdAt
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public LicenseRequest {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId is required");
        if (installationId == null) throw new IllegalArgumentException("installationId is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
    }
}
