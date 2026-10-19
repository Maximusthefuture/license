package com.example.licensing.client;

/**
 * How strictly a license's clusterId binding is enforced - see
 * LicenseValidator.
 */
public enum ClusterIdPolicy {
    /**
     * A license bound to a clusterId is rejected if the current cluster's
     * id can't be read, or doesn't match. Use when every license is issued
     * per-cluster and you want a restored backup or a copied database to
     * stop working on a different cluster.
     */
    STRICT,

    /**
     * A mismatch or an unreadable clusterId is tolerated: the license is
     * still accepted on identity/signature grounds. Use when RBAC access
     * to read the cluster identity can't be guaranteed everywhere, and a
     * missing clusterId check should degrade to "no extra protection"
     * rather than "product stops working".
     */
    WARN_ONLY
}
