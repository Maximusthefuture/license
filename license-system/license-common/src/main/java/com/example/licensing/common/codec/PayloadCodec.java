package com.example.licensing.common.codec;

import com.example.licensing.common.model.LicensePayload;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Encodes/decodes the signed license text: base64(payload-json) + "." +
 * base64(signature), wrapped in armor.
 *
 * <p>The payload bytes that get signed are exactly the bytes produced by
 * {@link #canonicalBytes}. Verification MUST use these same bytes, byte for
 * byte - never a re-serialized copy of the parsed object, or a change in
 * field order / whitespace would silently break every signature.
 */
public final class PayloadCodec {

    private static final String LABEL = "LICENSE KEY";

    private PayloadCodec() {
    }

    public static byte[] canonicalBytes(LicensePayload payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("formatVersion", payload.formatVersion());
        fields.put("licenseId", payload.licenseId().toString());
        fields.put("requestId", payload.requestId().toString());
        fields.put("keyId", payload.keyId());
        fields.put("productId", payload.productId());
        fields.put("customer", payload.customer());
        fields.put("installationId", payload.installationId().toString());
        fields.put("clusterId", payload.clusterId() == null ? null : payload.clusterId().toString());
        fields.put("issuedAt", payload.issuedAt().toString());
        fields.put("notBefore", payload.notBefore().toString());
        fields.put("notAfter", payload.notAfter().toString());
        fields.put("modules", payload.modules());
        fields.put("maxUsers", payload.maxUsers());

        String json = MiniJson.writeObject(fields);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Combines payload bytes + signature into the text that goes in the email. */
    public static String encodeSigned(byte[] payloadBytes, byte[] signature) {
        String combined = Base64.getEncoder().encodeToString(payloadBytes)
                + "."
                + Base64.getEncoder().encodeToString(signature);
        return ArmoredText.wrap(LABEL, combined.getBytes(StandardCharsets.US_ASCII));
    }

    public static SignedLicense decodeSigned(String armoredText) {
        byte[] inner = ArmoredText.unwrap(LABEL, armoredText);
        String combined = new String(inner, StandardCharsets.US_ASCII);
        int dot = combined.indexOf('.');
        if (dot < 0) {
            throw new ArmoredText.ArmorFormatException("malformed license key: missing signature separator");
        }
        byte[] payloadBytes = Base64.getDecoder().decode(combined.substring(0, dot));
        byte[] signature = Base64.getDecoder().decode(combined.substring(dot + 1));
        return new SignedLicense(payloadBytes, signature);
    }

    @SuppressWarnings("unchecked")
    public static LicensePayload parsePayload(byte[] payloadBytes) {
        Map<String, Object> fields;
        try {
            fields = MiniJson.parseObject(new String(payloadBytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ArmoredText.ArmorFormatException("malformed license payload JSON", e);
        }
        try {
            String clusterIdStr = (String) fields.get("clusterId");
            Object modulesRaw = fields.get("modules");
            Set<String> modules = modulesRaw == null ? Set.of() : Set.copyOf((List<String>) modulesRaw);
            return new LicensePayload(
                    ((Number) fields.get("formatVersion")).intValue(),
                    UUID.fromString((String) fields.get("licenseId")),
                    UUID.fromString((String) fields.get("requestId")),
                    (String) fields.get("keyId"),
                    (String) fields.get("productId"),
                    (String) fields.get("customer"),
                    UUID.fromString((String) fields.get("installationId")),
                    clusterIdStr == null ? null : UUID.fromString(clusterIdStr),
                    Instant.parse((String) fields.get("issuedAt")),
                    Instant.parse((String) fields.get("notBefore")),
                    Instant.parse((String) fields.get("notAfter")),
                    modules,
                    ((Number) fields.get("maxUsers")).intValue());
        } catch (RuntimeException e) {
            throw new ArmoredText.ArmorFormatException("malformed license payload fields", e);
        }
    }

    public record SignedLicense(byte[] payloadBytes, byte[] signature) {
    }
}
