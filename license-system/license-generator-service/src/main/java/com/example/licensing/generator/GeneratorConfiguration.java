package com.example.licensing.generator;

import com.example.licensing.issuer.FileIssuedLicenseRepository;
import com.example.licensing.issuer.IssuedLicenseRepository;
import com.example.licensing.issuer.LicenseIssuingService;
import com.example.licensing.issuer.LicenseSigner;
import com.example.licensing.issuer.keystore.KeyEventLog;
import com.example.licensing.issuer.keystore.KeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class GeneratorConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratorConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IssuedLicenseRepository issuedLicenseRepository(GeneratorProperties properties) {
        if (properties.auditDir() == null) {
            throw new IllegalStateException("license.generator.audit-dir is not set");
        }
        return new FileIssuedLicenseRepository(properties.auditDir());
    }

    /**
     * Opens the keys directory. A missing active key file stops startup (see
     * KeyRing); no active key at all is fine - the service starts, issuing is
     * refused, and an administrator creates/activates a key on the web page.
     */
    @Bean
    KeyRing keyRing(GeneratorProperties properties, IssuedLicenseRepository repository, Clock clock) {
        if (properties.keysDir() == null) {
            throw new IllegalStateException("license.generator.keys-dir is not set");
        }
        if (properties.keystorePassword() == null || properties.keystorePassword().isEmpty()) {
            throw new IllegalStateException("license.generator.keystore-password is not set "
                    + "(expected from the LICENSE_KEYSTORE_PASSWORD secret)");
        }
        KeyRing ring = new KeyRing(properties.keysDir(), properties.keystorePassword().toCharArray(),
                () -> !repository.list(1).isEmpty(), new KeyEventLog(properties.auditDir(), clock));
        ring.active().ifPresentOrElse(
                key -> LOG.info("Active signing key: {}", key.keyId()),
                () -> LOG.warn("No active signing key in {} - licenses cannot be issued until an administrator "
                        + "creates and activates one on the web page", properties.keysDir()));
        return ring;
    }

    @Bean
    LicenseIssuingService licenseIssuingService(KeyRing keyRing, IssuedLicenseRepository repository, Clock clock) {
        // Looked up on every call: activating another key takes effect without a restart.
        return new LicenseIssuingService(() -> keyRing.active().map(key -> new LicenseSigner(key, clock)), repository);
    }
}
