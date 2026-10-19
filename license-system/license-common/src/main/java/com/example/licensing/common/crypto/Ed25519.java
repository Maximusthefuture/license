package com.example.licensing.common.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Thin wrapper around the JDK's built-in Ed25519 support (available since
 * Java 15 - no external crypto library needed). Used for signing license
 * payloads (issuer side, with the private key) and verifying them (client
 * side, with the public key only).
 */
public final class Ed25519 {

    public static final String ALGORITHM = "Ed25519";

    private Ed25519() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available - requires Java 15+", e);
        }
    }

    public static PublicKey publicKeyFromX509(byte[] encoded) {
        try {
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            return kf.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid Ed25519 public key bytes", e);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] data) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to sign data", e);
        }
    }

    public static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
