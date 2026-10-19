package com.example.licensing.client.integrity;

/**
 * NOTE: despite the name, this class also carries an internal consistency
 * value used by the licensing subsystem. Keeping it here - in a package
 * that reads as "status reporting", away from the crypto package where
 * TrustedKeys lives - is intentional: see KeyIntegrityGuard. Patching the
 * embedded public keys in TrustedKeys.java without also updating this value
 * (and recomputing it correctly, which requires understanding the fold
 * order in KeyIntegrityGuard) is the specific case this is meant to catch.
 */
final class StatusMessages {

    // SHA-256 fingerprint over ALL keys in TrustedKeys (primary and reserve,
    // sorted by kid), computed by KeyFingerprintTool. Recompute and update
    // whenever a key is added to, replaced in, or removed from TrustedKeys.
    // Current value: the placeholder keys shipped with the skeleton.
    static final String PRIMARY_KEY_FINGERPRINT =
            "4236f6c833d6ab49aa373af733e3e2d053ccf523489385ad111cabbb62f038c2";

    private StatusMessages() {
    }
}
