package com.example.licensing.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code license.generator.*} settings.
 *
 * @param keysDir          directory on the persistent volume holding {@code <kid>.p12} files and the
 *                         {@code active-key} marker; keys are created there from the web page
 * @param keystorePassword encrypts every key in {@code keysDir}; from a secret ({@code LICENSE_KEYSTORE_PASSWORD})
 * @param auditDir         issued licenses, the audit manifest and the key event log (persistent volume)
 * @param operators        who may log in; passwords as {@code {bcrypt}$2a$...} hashes;
 *                         {@code admin: true} additionally allows managing signing keys
 */
@ConfigurationProperties("license.generator")
public record GeneratorProperties(Path keysDir,
                                  String keystorePassword,
                                  Path auditDir,
                                  List<Operator> operators) {

    public record Operator(String username, String password, Boolean admin) {
        boolean isAdmin() {
            return Boolean.TRUE.equals(admin);
        }
    }
}
