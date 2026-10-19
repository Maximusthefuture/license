package com.example.licensing.client;

/**
 * Why {@link LicenseValidator#verify} rejected a license. Covers only
 * authenticity and identity binding - time validity (not yet active,
 * expired, in grace) is a separate, policy-level concern handled by
 * {@link LicenseService}, not by the validator. See LicenseValidator's
 * class comment for why that split matters.
 */
public enum LicenseValidationReason {
    MALFORMED,
    UNKNOWN_KEY,
    BAD_SIGNATURE,
    WRONG_PRODUCT,
    WRONG_INSTALLATION,
    WRONG_CLUSTER,
    CLUSTER_ID_UNAVAILABLE
}
