package com.example.licensing.client.remote;

import com.example.licensing.client.ClusterIdPolicy;
import com.example.licensing.client.DefaultLicenseService;
import com.example.licensing.client.LicenseValidator;
import com.example.licensing.client.RuntimeContext;
import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.client.k8s.KubernetesClusterIdProvider;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One-call setup for the product side: a {@link DefaultLicenseService} that
 * fetches the license from the validator service and verifies it locally.
 *
 * <pre>{@code
 * DefaultLicenseService licensing = ProductLicensing.connect(
 *         URI.create("http://license-validator:8080"), "acme-suite");
 *
 * if (licensing.isModuleEnabled("reports")) { ... }
 * licensing.checkCanAddUser(userRepository.count());
 * }</pre>
 *
 * <p>The product's own ServiceAccount needs the same RBAC as the validator
 * (get on the kube-system namespace) to read the cluster id. Without it, a
 * license bound to a cluster is rejected under {@link ClusterIdPolicy#STRICT};
 * pass {@link ClusterIdPolicy#WARN_ONLY} only if that RBAC really can't be granted.
 */
public final class ProductLicensing {

    private ProductLicensing() {
    }

    public static DefaultLicenseService connect(URI validatorBaseUrl, String productId) {
        return connect(validatorBaseUrl, productId, ClusterIdPolicy.STRICT,
                new KubernetesClusterIdProvider(), Clock.systemUTC());
    }

    public static DefaultLicenseService connect(URI validatorBaseUrl, String productId, ClusterIdPolicy clusterIdPolicy,
                                                Supplier<Optional<UUID>> clusterIdSupplier, Clock clock) {
        // The no-arg SignatureVerifier uses the public keys compiled into the
        // product and runs KeyIntegrityGuard - never keys supplied at runtime.
        // Time: the Clock overload gives an in-memory ClockGuard (signed issuedAt +
        // latest time seen by this process). The validator's persisted time is not
        // used here on purpose - a fake validator could report any value.
        LicenseValidator validator = new LicenseValidator(new SignatureVerifier(), clusterIdPolicy);
        RuntimeContext context = RuntimeContext.withoutInstallationCheck(productId, clusterIdSupplier, clock);
        return new DefaultLicenseService(validator, new HttpLicenseStore(validatorBaseUrl), context,
                DefaultLicenseService.DEFAULT_CACHE_TTL,
                DefaultLicenseService.DEFAULT_EXPIRING_WARNING,
                DefaultLicenseService.DEFAULT_GRACE,
                clock);
    }
}
