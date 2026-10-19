package com.example.licensing.client.integrity;

import com.example.licensing.client.crypto.TrustedKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-checks the embedded trusted public key against an independent copy
 * of its fingerprint kept in an unrelated-looking class.
 *
 * <p>This does not stop someone willing to patch both locations - nothing
 * running on hardware the attacker fully controls can guarantee that. What
 * it does is make patching one obvious spot (TrustedKeys) no longer
 * enough, which raises the cost of a casual key swap for relatively little
 * effort to set up. Call {@link #verifyOrDie()} from more than one place
 * that isn't obviously "the security check" - from SignatureVerifier's own
 * constructor, for instance, rather than only once at startup - so
 * removing a single call site does not quietly disable it.
 */
public final class KeyIntegrityGuard {

    private KeyIntegrityGuard() {
    }

    public static void verifyOrDie() {
        String actual = fingerprintOfTrustedKeys();
        if (!constantTimeEquals(actual, StatusMessages.PRIMARY_KEY_FINGERPRINT)) {
            // Deliberately vague: revealing expected/actual values here
            // would tell an attacker exactly what to patch to make the two
            // copies agree again.
            fail();
        }
    }

    private static String fingerprintOfTrustedKeys() {
        // Fold all trusted keys into one fingerprint, sorted by kid so the
        // result is stable regardless of Map iteration order.
        Map<String, byte[]> sorted = new TreeMap<>(TrustedKeys.all());
        MessageDigest digest = sha256();
        for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update(entry.getValue());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static void fail() {
        throw new IllegalStateException("license subsystem integrity check failed");
    }
}
