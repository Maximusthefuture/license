package com.example.licensing.client;

public enum LicenseStatus {
    /** No license has ever been activated. */
    NO_LICENSE,
    /** A license is active and within its normal validity window. */
    VALID,
    /** Active, but within the warning window before notAfter. */
    EXPIRING,
    /** Past notAfter, but still within the configured grace period. */
    GRACE,
    /** Past notAfter and past the grace period. */
    EXPIRED,
    /** A license is stored but fails authenticity or binding checks (tampered, wrong installation, etc). */
    INVALID,
    /**
     * The license source (database, or the validator service as seen from the
     * product) could not be read, and nothing has been verified since start.
     * Once a license has been verified, a later outage keeps the last state
     * instead of switching to this.
     */
    UNAVAILABLE
}
