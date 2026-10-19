package com.example.licensing.client;

import java.util.Optional;

/**
 * Everything worth showing an operator about the current license in one
 * consistent read: the status, the verified license if there is one (also
 * for EXPIRED - it still says who the customer is and what to renew), and
 * why it was rejected if it was.
 *
 * @param license      present whenever the stored license is authentic and bound to this
 *                     installation, regardless of its validity period
 * @param reason       present when the stored license failed authenticity/binding checks
 * @param detail       human-readable explanation for INVALID (failed check or not yet valid), else null
 * @param clockWarning set when the system clock is behind a known point in time (it was turned
 *                     back); the status is then computed against that later time, not the clock
 */
public record LicenseSnapshot(LicenseStatus status,
                              Optional<VerifiedLicense> license,
                              Optional<LicenseValidationReason> reason,
                              String detail,
                              String clockWarning) {
}
