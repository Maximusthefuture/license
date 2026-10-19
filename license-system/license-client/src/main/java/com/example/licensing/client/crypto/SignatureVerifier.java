package com.example.licensing.client.crypto;

import com.example.licensing.client.integrity.KeyIntegrityGuard;
import com.example.licensing.common.crypto.Ed25519;

import java.security.PublicKey;
import java.util.Map;

public final class SignatureVerifier {

    private final Map<String, byte[]> trustedKeys;

    /** Production constructor: uses the embedded, integrity-checked trusted keys. */
    public SignatureVerifier() {
        // Called from a constructor, not a standalone "checkIntegrity()"
        // method that's easy to spot and skip when reading a decompiled
        // call graph - verification happens on the way to getting a
        // usable verifier, not as a separate, droppable step.
        KeyIntegrityGuard.verifyOrDie();
        this.trustedKeys = TrustedKeys.all();
    }

    /** For tests and tools that need to verify against a different key set than the embedded one. */
    public SignatureVerifier(Map<String, byte[]> trustedKeys) {
        this.trustedKeys = Map.copyOf(trustedKeys);
    }

    public boolean verify(String kid, byte[] data, byte[] signature) {
        byte[] keyBytes = trustedKeys.get(kid);
        if (keyBytes == null) {
            return false;
        }
        PublicKey key = Ed25519.publicKeyFromX509(keyBytes);
        return Ed25519.verify(key, data, signature);
    }

    public boolean isKnownKey(String kid) {
        return trustedKeys.containsKey(kid);
    }
}
