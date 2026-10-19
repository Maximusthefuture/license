package com.example.licensing.issuer;

import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.crypto.Ed25519;
import com.example.licensing.common.model.LicensePayload;
import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.keystore.SigningKey;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns a request plus an operator's decision into a signed license.
 *
 * <p>This is the only place in the whole system that uses the private key.
 * Keep it that way: never let this class, or the key material it holds,
 * end up in anything that ships to a customer.
 *
 * <p>Every signature is verified against the key's own public key before
 * the license is handed out. If a keystore were ever damaged or assembled
 * wrongly (private and public halves not matching), this catches it at
 * issuance time instead of at the customer's activation.
 */
public final class LicenseSigner {

    private final SigningKey signingKey;
    private final Clock clock;

    public LicenseSigner(SigningKey signingKey, Clock clock) {
        this.signingKey = signingKey;
        this.clock = clock;
    }

    public String keyId() {
        return signingKey.keyId();
    }

    /** X.509 (SubjectPublicKeyInfo) encoding, the format the client's TrustedKeys expects. */
    public byte[] publicKeyEncoded() {
        return signingKey.publicKey().getEncoded();
    }

    public Issued issue(LicenseRequest request, IssueDecision decision) {
        Instant now = clock.instant();
        LicensePayload payload = new LicensePayload(
                LicensePayload.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(),
                request.requestId(),
                signingKey.keyId(),
                request.productId(),
                decision.customer(),
                request.installationId(),
                request.clusterId(),
                now,
                now,
                now.plus(decision.validity()),
                decision.modules(),
                decision.maxUsers());

        byte[] payloadBytes = PayloadCodec.canonicalBytes(payload);
        byte[] signature = Ed25519.sign(signingKey.privateKey(), payloadBytes);
        if (!Ed25519.verify(signingKey.publicKey(), payloadBytes, signature)) {
            throw new IllegalStateException("self-check failed: the signature does not verify against key '"
                    + signingKey.keyId() + "' - the keystore's private and public keys do not match");
        }
        String armoredLicense = PayloadCodec.encodeSigned(payloadBytes, signature);
        return new Issued(payload, armoredLicense);
    }

    public record Issued(LicensePayload payload, String armoredLicenseText) {
    }
}
