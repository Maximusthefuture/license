package com.example.licensing.client.crypto;

import java.util.Base64;
import java.util.Map;

/**
 * Public keys this build of the product trusts, keyed by "kid" (key id).
 *
 * <p>Two keys are embedded on purpose:
 * <ul>
 *   <li><b>primary</b> - signs every license in normal operation;</li>
 *   <li><b>reserve</b> - generated at the same time, stored separately
 *       (different place, ideally a different person), and never used
 *       while the primary is available. If the primary key is lost, the
 *       issuer switches to the reserve and every installation already in
 *       the field keeps accepting new licenses without a product update.</li>
 * </ul>
 * If a key is <i>compromised</i> (rather than lost), a product release is
 * still needed: remove its kid here and re-issue all licenses that were
 * signed with it.
 *
 * <p>These are public keys - nothing here is secret. What matters is that
 * this exact set is what ships; {@link com.example.licensing.client.integrity.KeyIntegrityGuard}
 * cross-checks it against an independent fingerprint. After any change
 * here, run {@code KeyFingerprintTool} and update
 * {@code integrity.StatusMessages.PRIMARY_KEY_FINGERPRINT}.
 *
 * <p><b>PLACEHOLDERS:</b> the two keys below were generated only so the
 * skeleton compiles and its tests run; their private halves were thrown
 * away, so no license can ever verify against them. Replace both with your
 * own (see README, "Signing keys") - until then, every activation fails
 * with UNKNOWN_KEY.
 */
public final class TrustedKeys {

    private static final Map<String, byte[]> KEYS = Map.of(
            // primary
            "placeholder-primary", Base64.getDecoder().decode("MCowBQYDK2VwAyEAgAznb3Swo3/LYDd4Rn/eg+1dc1CJbvnlvb25+TkqjCY="),
            // reserve - do not sign with it while the primary key is available
            "placeholder-reserve", Base64.getDecoder().decode("MCowBQYDK2VwAyEABW47hP3Za/mA+HZkLsBnZCIuIOnmoQWzj1PTeTFaK1Y=")
    );

    private TrustedKeys() {
    }

    public static Map<String, byte[]> all() {
        return KEYS;
    }
}
