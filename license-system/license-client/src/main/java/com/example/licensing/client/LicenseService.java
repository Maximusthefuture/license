package com.example.licensing.client;

/**
 * The single entry point the rest of the product should use to ask what it
 * is licensed to do. Deliberately not a bare {@code isValid()} boolean:
 * when every gated decision goes through named methods backed by the
 * actual verified payload, disabling licensing means patching several call
 * sites with different shapes rather than one flag.
 */
public interface LicenseService {

    LicenseStatus status();

    boolean isModuleEnabled(String moduleId);

    int maxUsers();

    /** @throws DefaultLicenseService.LicenseLimitExceededException if adding one more user would exceed the license. */
    void checkCanAddUser(int currentUserCount);
}
