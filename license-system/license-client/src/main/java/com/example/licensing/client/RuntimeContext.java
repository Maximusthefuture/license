package com.example.licensing.client;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What a license is checked against, gathered fresh each time rather than
 * trusted from storage.
 *
 * <p>clusterId in particular must come from a live read (the Kubernetes API
 * - see {@link com.example.licensing.client.k8s.KubernetesClusterIdProvider})
 * rather than from a database. A database backup restored into a different
 * cluster would otherwise carry a stale, matching clusterId along with the
 * stale license, and the binding would fail to catch exactly the case it
 * exists for.
 *
 * <p>Two kinds of context:
 * <ul>
 *   <li>{@link #forInstallation} - used by the license validator service,
 *       which owns the installation id (generated once and kept in its
 *       database) and checks that the license was issued for it.</li>
 *   <li>{@link #withoutInstallationCheck} - used inside the product, which
 *       gets the license from the validator service and re-checks signature,
 *       product and cluster itself. The installation id would have to come
 *       from the validator too, so checking it in the product would add
 *       nothing: a fake validator would simply report a matching id. The
 *       checks that do hold against a fake validator are the signature (it
 *       cannot forge one) and the cluster id (the product reads it from the
 *       Kubernetes API itself).</li>
 * </ul>
 */
public final class RuntimeContext {

    private final String productId;
    private final Supplier<UUID> installationIdSupplier; // null when installation is not checked
    private final Supplier<Optional<UUID>> clusterIdSupplier;
    private final Clock clock;

    /** Installation id known up front. */
    public RuntimeContext(String productId, UUID installationId,
                          Supplier<Optional<UUID>> clusterIdSupplier, Clock clock) {
        this(productId, constant(Objects.requireNonNull(installationId, "installationId")), clusterIdSupplier, clock);
    }

    private RuntimeContext(String productId, Supplier<UUID> installationIdSupplier,
                           Supplier<Optional<UUID>> clusterIdSupplier, Clock clock) {
        this.productId = Objects.requireNonNull(productId, "productId");
        this.installationIdSupplier = installationIdSupplier;
        this.clusterIdSupplier = Objects.requireNonNull(clusterIdSupplier, "clusterIdSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Installation id read lazily (e.g. from a database that may not be
     * initialized yet when this object is created) and checked against every
     * license.
     */
    public static RuntimeContext forInstallation(String productId, Supplier<UUID> installationId,
                                                 Supplier<Optional<UUID>> clusterIdSupplier, Clock clock) {
        return new RuntimeContext(productId, Objects.requireNonNull(installationId, "installationId"),
                clusterIdSupplier, clock);
    }

    /** For use inside the product - see the class comment for why this is not a loophole. */
    public static RuntimeContext withoutInstallationCheck(String productId,
                                                          Supplier<Optional<UUID>> clusterIdSupplier, Clock clock) {
        return new RuntimeContext(productId, (Supplier<UUID>) null, clusterIdSupplier, clock);
    }

    public String productId() {
        return productId;
    }

    public boolean checksInstallation() {
        return installationIdSupplier != null;
    }

    /** @throws IllegalStateException if this context does not check installations */
    public UUID installationId() {
        if (installationIdSupplier == null) {
            throw new IllegalStateException("this context does not check the installation id");
        }
        return Objects.requireNonNull(installationIdSupplier.get(), "installation id supplier returned null");
    }

    public Optional<UUID> clusterId() {
        return clusterIdSupplier.get();
    }

    public Clock clock() {
        return clock;
    }

    private static Supplier<UUID> constant(UUID value) {
        return () -> value;
    }
}
