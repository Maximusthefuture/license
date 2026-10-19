package com.example.licensing.common.codec;

import com.example.licensing.common.model.LicenseRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes/decodes the text a client product emails to the issuer. Just JSON
 * in an armored envelope - no signature, since a request carries no
 * entitlement that needs protecting, only an identity the issuer looks up.
 */
public final class RequestCodec {

    private static final String LABEL = "LICENSE REQUEST";

    private RequestCodec() {
    }

    public static String encode(LicenseRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("formatVersion", request.formatVersion());
        fields.put("requestId", request.requestId().toString());
        fields.put("productId", request.productId());
        fields.put("productVersion", request.productVersion());
        fields.put("installationId", request.installationId().toString());
        fields.put("clusterId", request.clusterId() == null ? null : request.clusterId().toString());
        fields.put("organization", request.organization());
        fields.put("createdAt", request.createdAt().toString());

        String json = MiniJson.writeObject(fields);
        return ArmoredText.wrap(LABEL, ArmoredText.utf8(json));
    }

    public static LicenseRequest decode(String armoredText) {
        byte[] jsonBytes = ArmoredText.unwrap(LABEL, armoredText);
        Map<String, Object> fields;
        try {
            fields = MiniJson.parseObject(ArmoredText.utf8(jsonBytes));
        } catch (RuntimeException e) {
            throw new ArmoredText.ArmorFormatException("malformed license request JSON", e);
        }
        try {
            String clusterIdStr = (String) fields.get("clusterId");
            return new LicenseRequest(
                    ((Number) fields.get("formatVersion")).intValue(),
                    UUID.fromString((String) fields.get("requestId")),
                    (String) fields.get("productId"),
                    (String) fields.get("productVersion"),
                    UUID.fromString((String) fields.get("installationId")),
                    clusterIdStr == null ? null : UUID.fromString(clusterIdStr),
                    (String) fields.get("organization"),
                    Instant.parse((String) fields.get("createdAt")));
        } catch (RuntimeException e) {
            throw new ArmoredText.ArmorFormatException("malformed license request fields", e);
        }
    }
}
