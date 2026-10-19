package com.example.licensing.client;

import java.util.Optional;

/**
 * Accepts a license key text pasted (or dropped as a file) by an operator
 * and, if it passes validation, makes it the active license.
 *
 * <p>This runs entirely offline - the issuer is not reachable from inside
 * the cluster - so every check has to be self-contained. No network calls
 * back to anyone.
 */
public final class ActivationService {

    private final LicenseValidator validator;
    private final LicenseStore store;
    private final RuntimeContext context;

    public ActivationService(LicenseValidator validator, LicenseStore store, RuntimeContext context) {
        this.validator = validator;
        this.store = store;
        this.context = context;
    }

    public ActivationResult activate(String armoredLicenseText) {
        VerifiedLicense candidate;
        try {
            candidate = validator.verify(armoredLicenseText, context);
        } catch (LicenseValidationException e) {
            return ActivationResult.rejected(e.reason(), e.getMessage());
        }

        Optional<LicenseStore.StoredLicense> previous = store.load();
        if (previous.isPresent()) {
            VerifiedLicense currentlyActive = tryVerifyQuietly(previous.get().rawText());
            if (currentlyActive != null && !candidate.issuedAt().isAfter(currentlyActive.issuedAt())) {
                return ActivationResult.rejected(null,
                        "this key is not newer than the currently active license (issued "
                                + currentlyActive.issuedAt()
                                + "); activation refused to avoid rolling back to older terms");
            }
        }

        LicenseStore.StoredLicense toSave = new LicenseStore.StoredLicense(
                armoredLicenseText, previous.map(p -> p.version() + 1).orElse(1L));
        boolean saved = store.save(toSave, previous);
        if (!saved) {
            return ActivationResult.rejected(null,
                    "another activation happened at the same time - please retry");
        }
        return ActivationResult.accepted(candidate);
    }

    private VerifiedLicense tryVerifyQuietly(String rawText) {
        try {
            return validator.verify(rawText, context);
        } catch (LicenseValidationException e) {
            // The currently stored license no longer validates (e.g. it was
            // issued for a different installation that got cloned into this
            // one) - treat this as "no usable baseline to compare against",
            // not as a reason to block activating a fresh, valid key.
            return null;
        }
    }

    public record ActivationResult(boolean accepted, VerifiedLicense license,
                                    LicenseValidationReason reason, String message) {
        static ActivationResult accepted(VerifiedLicense license) {
            return new ActivationResult(true, license, null, "license activated");
        }

        static ActivationResult rejected(LicenseValidationReason reason, String message) {
            return new ActivationResult(false, null, reason, message);
        }
    }
}
