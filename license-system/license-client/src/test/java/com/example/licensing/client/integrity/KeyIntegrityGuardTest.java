package com.example.licensing.client.integrity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class KeyIntegrityGuardTest {

    @Test
    void embeddedTrustedKeysMatchTheirFingerprint() {
        // Guards against the easy-to-make mistake of changing TrustedKeys
        // without updating StatusMessages.PRIMARY_KEY_FINGERPRINT to match
        // (use KeyFingerprintTool to recompute it). If this test fails right
        // after you replaced or added a signing key, that's this check doing
        // its job.
        assertThatCode(KeyIntegrityGuard::verifyOrDie).doesNotThrowAnyException();
    }
}
