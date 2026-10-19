package com.example.licensing.client.tools;

import com.example.licensing.client.crypto.TrustedKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Run this after changing TrustedKeys (a new signing key added, an old one
 * retired) and paste the printed fingerprint into
 * {@code integrity.StatusMessages.PRIMARY_KEY_FINGERPRINT}.
 *
 * <p>This is deliberately a manual step rather than something wired into
 * every build: an automatic step would regenerate both copies from the
 * same source on every compile, which means the "independent" copy would
 * no longer be independent of the thing it's supposed to cross-check.
 */
public final class KeyFingerprintTool {

    private KeyFingerprintTool() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, byte[]> sorted = new TreeMap<>(TrustedKeys.all());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update(entry.getValue());
        }
        System.out.println(HexFormat.of().formatHex(digest.digest()));
    }
}
