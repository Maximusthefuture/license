package com.example.licensing.client;

public final class LicenseValidationException extends RuntimeException {

    private final LicenseValidationReason reason;

    public LicenseValidationException(LicenseValidationReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LicenseValidationReason reason() {
        return reason;
    }
}
