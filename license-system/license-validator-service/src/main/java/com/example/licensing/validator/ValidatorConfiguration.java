package com.example.licensing.validator;

import com.example.licensing.client.ActivationService;
import com.example.licensing.client.ClusterIdPolicy;
import com.example.licensing.client.DefaultLicenseService;
import com.example.licensing.client.LicenseRequestGenerator;
import com.example.licensing.client.LicenseValidator;
import com.example.licensing.client.RuntimeContext;
import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.client.jdbc.JdbcInstallationIdProvider;
import com.example.licensing.client.jdbc.JdbcLicenseStore;
import com.example.licensing.client.jdbc.JdbcTimeAnchorStore;
import com.example.licensing.client.k8s.KubernetesClusterIdProvider;
import com.example.licensing.client.time.ClockGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
class ValidatorConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    KubernetesClusterIdProvider clusterIdProvider() {
        return new KubernetesClusterIdProvider();
    }

    @Bean
    JdbcInstallationIdProvider installationIdProvider(DataSource dataSource) {
        return new JdbcInstallationIdProvider(dataSource);
    }

    @Bean
    JdbcLicenseStore licenseStore(DataSource dataSource) {
        return new JdbcLicenseStore(dataSource);
    }

    @Bean
    LicenseValidator licenseValidator(ValidatorProperties properties) {
        // No-arg SignatureVerifier: public keys compiled into this build, checked by KeyIntegrityGuard.
        return new LicenseValidator(new SignatureVerifier(),
                Objects.requireNonNullElse(properties.clusterIdPolicy(), ClusterIdPolicy.STRICT));
    }

    /**
     * The installation id is read lazily: the database schema is created by
     * Spring's SQL initialization, which is not guaranteed to have run when
     * this bean is created.
     */
    @Bean
    RuntimeContext runtimeContext(ValidatorProperties properties, JdbcInstallationIdProvider installationIdProvider,
                                  KubernetesClusterIdProvider clusterIdProvider, Clock clock) {
        return RuntimeContext.forInstallation(requireProductId(properties),
                installationIdProvider::installationId, clusterIdProvider, clock);
    }

    @Bean
    ActivationService activationService(LicenseValidator validator, JdbcLicenseStore store, RuntimeContext context) {
        return new ActivationService(validator, store, context);
    }

    /**
     * Validity is counted from the latest of: the clock, the latest time seen
     * by this process, the latest time recorded in the database, and the
     * license's signed issuedAt - so turning the clock back doesn't extend it.
     */
    @Bean
    ClockGuard clockGuard(DataSource dataSource, ValidatorProperties properties, Clock clock) {
        return new ClockGuard(clock, new JdbcTimeAnchorStore(dataSource),
                orDefault(properties.clockTolerance(), ClockGuard.DEFAULT_TOLERANCE),
                ClockGuard.DEFAULT_PERSIST_INTERVAL);
    }

    @Bean
    DefaultLicenseService licenseService(LicenseValidator validator, JdbcLicenseStore store, RuntimeContext context,
                                         ValidatorProperties properties, ClockGuard clockGuard) {
        return new DefaultLicenseService(validator, store, context,
                orDefault(properties.cacheTtl(), DefaultLicenseService.DEFAULT_CACHE_TTL),
                orDefault(properties.expiringWarning(), DefaultLicenseService.DEFAULT_EXPIRING_WARNING),
                orDefault(properties.grace(), DefaultLicenseService.DEFAULT_GRACE),
                clockGuard);
    }

    @Bean
    LicenseRequestGenerator licenseRequestGenerator(ValidatorProperties properties,
                                                    JdbcInstallationIdProvider installationIdProvider,
                                                    KubernetesClusterIdProvider clusterIdProvider, Clock clock) {
        return new LicenseRequestGenerator(requireProductId(properties),
                Objects.requireNonNullElse(properties.productVersion(), "unknown"),
                installationIdProvider, clusterIdProvider, clock);
    }

    private static String requireProductId(ValidatorProperties properties) {
        if (properties.productId() == null || properties.productId().isBlank()) {
            throw new IllegalStateException("license.validator.product-id is not set");
        }
        return properties.productId();
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
