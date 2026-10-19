package com.example.licensing.client;

import com.example.licensing.common.model.LicensePayload;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A license payload whose signature and identity bindings (product,
 * installation, cluster) have already been checked by LicenseValidator.
 *
 * <p>There is no public constructor. The only way to obtain an instance is
 * through {@link LicenseValidator#verify}, so the rest of the product
 * cannot accidentally - or be made to, by a patched caller - treat
 * unverified data as a valid license. Note that this says nothing about
 * whether the license is *currently in force*: see {@link LicenseService}
 * for the time-based state machine (expiring / grace / expired) built on
 * top of this.
 */
public final class VerifiedLicense {

    private final LicensePayload payload;

    VerifiedLicense(LicensePayload payload) {
        this.payload = payload;
    }

    public UUID licenseId() {
        return payload.licenseId();
    }

    public String customer() {
        return payload.customer();
    }

    public Instant issuedAt() {
        return payload.issuedAt();
    }

    public Instant notBefore() {
        return payload.notBefore();
    }

    public Instant notAfter() {
        return payload.notAfter();
    }

    public Set<String> modules() {
        return payload.modules();
    }

    public int maxUsers() {
        return payload.maxUsers();
    }

    public boolean hasModule(String moduleId) {
        return payload.modules().contains(moduleId);
    }
}
