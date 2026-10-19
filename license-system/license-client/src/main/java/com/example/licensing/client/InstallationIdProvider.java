package com.example.licensing.client;

import java.util.UUID;

/**
 * Supplies a stable identifier for this installation of the product - not
 * for a machine, a pod, or a replica, which in Kubernetes come and go, but
 * for the deployment as a whole. See
 * {@link com.example.licensing.client.jdbc.JdbcInstallationIdProvider} for
 * the usual implementation: generated once, on first use, and read back
 * from then on by every replica.
 */
public interface InstallationIdProvider {
    UUID installationId();
}
