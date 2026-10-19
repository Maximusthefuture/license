package com.example.licensing.validator;

import com.example.licensing.client.DefaultLicenseService;
import com.example.licensing.client.LicenseSnapshot;
import com.example.licensing.client.jdbc.JdbcInstallationIdProvider;
import com.example.licensing.client.k8s.KubernetesClusterIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Logs the license state once at startup (after the database schema is in
 * place), and warns early about the one misconfiguration that is easy to
 * miss: no RBAC to read the cluster id, which makes license requests
 * unbound to the cluster.
 */
@Component
class StartupStatusLogger implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupStatusLogger.class);

    private final DefaultLicenseService licenseService;
    private final JdbcInstallationIdProvider installationIdProvider;
    private final KubernetesClusterIdProvider clusterIdProvider;

    StartupStatusLogger(DefaultLicenseService licenseService, JdbcInstallationIdProvider installationIdProvider,
                        KubernetesClusterIdProvider clusterIdProvider) {
        this.licenseService = licenseService;
        this.installationIdProvider = installationIdProvider;
        this.clusterIdProvider = clusterIdProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("Installation id: {}", installationIdProvider.installationId());
        if (clusterIdProvider.get().isEmpty()) {
            LOG.warn("Cannot read the cluster id (UID of namespace kube-system). Either this is not running in "
                    + "Kubernetes, or the ServiceAccount lacks 'get' on namespaces/kube-system. License requests "
                    + "created now will not be bound to this cluster.");
        }
        LicenseSnapshot snapshot = licenseService.snapshot();
        LOG.info("License status: {}{}", snapshot.status(),
                snapshot.detail() == null ? "" : " - " + snapshot.detail());
        if (snapshot.clockWarning() != null) {
            LOG.warn("Clock: {}", snapshot.clockWarning());
        }
    }
}
