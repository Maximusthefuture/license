package com.example.licensing.issuer;

import com.example.licensing.common.model.LicensePayload;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Records every issuance for audit: who issued what, to whom, when. */
public interface IssuedLicenseRepository {

    /** @param issuedBy operator who issued the license (login name) */
    void record(LicensePayload payload, String armoredLicenseText, String issuedBy);

    /** Most recent first. */
    List<IssuedLicenseRecord> list(int limit);

    /** The exact text that was sent, e.g. to resend it. */
    Optional<String> licenseText(UUID licenseId);

    record IssuedLicenseRecord(Instant issuedAt, UUID licenseId, UUID requestId, String keyId, String productId,
                               String customer, UUID installationId, String clusterId,
                               Instant notBefore, Instant notAfter, List<String> modules, int maxUsers,
                               String issuedBy) {
    }
}
