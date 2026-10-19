package com.example.licensing.client;

import com.example.licensing.client.crypto.SignatureVerifier;
import com.example.licensing.common.codec.PayloadCodec;
import com.example.licensing.common.model.LicensePayload;

import java.util.Optional;
import java.util.UUID;

/**
 * Checks that a license key is authentic and bound to this installation -
 * nothing about whether it is *currently* in force.
 *
 * <p>Authenticity and time validity are deliberately kept apart. A license
 * that is cryptographically genuine but simply expired is a completely
 * different situation from one that was never genuine at all, and callers
 * - in particular {@link LicenseService}'s expiring/grace/expired state
 * machine - need to tell them apart to do anything useful (an expired
 * license can still tell you who the customer is and what they're entitled
 * to once renewed; a forged one tells you nothing).
 */
public final class LicenseValidator {

    private final SignatureVerifier signatureVerifier;
    private final ClusterIdPolicy clusterIdPolicy;

    public LicenseValidator(SignatureVerifier signatureVerifier, ClusterIdPolicy clusterIdPolicy) {
        this.signatureVerifier = signatureVerifier;
        this.clusterIdPolicy = clusterIdPolicy;
    }

    /**
     * @throws LicenseValidationException if the license is not authentic or not bound to this
     *                                     installation/cluster/product. Does NOT check notBefore/notAfter.
     */
    public VerifiedLicense verify(String armoredLicenseText, RuntimeContext context) {
        PayloadCodec.SignedLicense signed;
        LicensePayload payload;
        try {
            signed = PayloadCodec.decodeSigned(armoredLicenseText);
            payload = PayloadCodec.parsePayload(signed.payloadBytes());
        } catch (RuntimeException e) {
            throw new LicenseValidationException(LicenseValidationReason.MALFORMED, "license text is malformed");
        }

        if (!signatureVerifier.isKnownKey(payload.keyId())) {
            throw new LicenseValidationException(LicenseValidationReason.UNKNOWN_KEY,
                    "license was signed with an unrecognized key: " + payload.keyId());
        }
        if (!signatureVerifier.verify(payload.keyId(), signed.payloadBytes(), signed.signature())) {
            throw new LicenseValidationException(LicenseValidationReason.BAD_SIGNATURE,
                    "license signature is invalid");
        }
        if (!payload.productId().equals(context.productId())) {
            throw new LicenseValidationException(LicenseValidationReason.WRONG_PRODUCT,
                    "license was issued for a different product");
        }
        if (context.checksInstallation() && !payload.installationId().equals(context.installationId())) {
            throw new LicenseValidationException(LicenseValidationReason.WRONG_INSTALLATION,
                    "license was issued for a different installation");
        }

        checkClusterBinding(payload, context);

        return new VerifiedLicense(payload);
    }

    private void checkClusterBinding(LicensePayload payload, RuntimeContext context) {
        UUID licensedCluster = payload.clusterId();
        if (licensedCluster == null) {
            return; // this license was not bound to a specific cluster
        }
        Optional<UUID> actual = context.clusterId();
        boolean mismatch = actual.isEmpty() || !actual.get().equals(licensedCluster);
        if (!mismatch) {
            return;
        }
        if (clusterIdPolicy == ClusterIdPolicy.WARN_ONLY) {
            // Accepted on identity/signature grounds; callers that care about
            // surfacing the mismatch to an operator can re-derive it themselves
            // from VerifiedLicense + a fresh cluster id read, since both are
            // cheap and this keeps LicenseValidator's contract to a single
            // pass/fail outcome.
            return;
        }
        if (actual.isEmpty()) {
            throw new LicenseValidationException(LicenseValidationReason.CLUSTER_ID_UNAVAILABLE,
                    "could not read the current cluster identity to check against the license");
        }
        throw new LicenseValidationException(LicenseValidationReason.WRONG_CLUSTER,
                "license was issued for a different Kubernetes cluster");
    }
}
