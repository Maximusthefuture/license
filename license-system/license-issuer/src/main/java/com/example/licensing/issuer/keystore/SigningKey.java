package com.example.licensing.issuer.keystore;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;

/**
 * A signing key loaded from a keystore: the private key used to sign
 * licenses, the matching public key (used by the issuer to self-check every
 * signature it produces), and the key id ("kid") written into each license
 * so clients know which of their trusted public keys to verify against.
 *
 * @param createdAt when the key was generated (from its certificate); null if unknown
 */
public record SigningKey(String keyId, PrivateKey privateKey, PublicKey publicKey, Instant createdAt) {

    public SigningKey {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId is required");
        }
        if (privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("both private and public key are required");
        }
    }

    public SigningKey(String keyId, PrivateKey privateKey, PublicKey publicKey) {
        this(keyId, privateKey, publicKey, null);
    }

    /** X.509 public key, base64 - the form the client's TrustedKeys embeds. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /** The exact line to paste into the client's TrustedKeys.java. */
    public String trustedKeysLine() {
        return trustedKeysLine(keyId, publicKeyBase64());
    }

    public static String trustedKeysLine(String keyId, String publicKeyBase64) {
        return "\"" + keyId + "\", Base64.getDecoder().decode(\"" + publicKeyBase64 + "\"),";
    }

    @Override
    public String toString() {
        // never let a stray log statement print key material
        return "SigningKey[keyId=" + keyId + "]";
    }
}
