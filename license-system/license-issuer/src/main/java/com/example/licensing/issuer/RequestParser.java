package com.example.licensing.issuer;

import com.example.licensing.common.codec.ArmoredText;
import com.example.licensing.common.codec.RequestCodec;
import com.example.licensing.common.model.LicenseRequest;

/**
 * Turns the pasted request email text into a {@link LicenseRequest}.
 *
 * <p>Everything this returns is still untrusted input from the client's
 * point of view - it establishes who is asking and from where, not what
 * they're entitled to. The operator (or {@link IssueDecision}) decides
 * entitlement separately.
 */
public final class RequestParser {

    private RequestParser() {
    }

    public static LicenseRequest parse(String armoredRequestText) {
        LicenseRequest request;
        try {
            request = RequestCodec.decode(armoredRequestText);
        } catch (ArmoredText.ArmorFormatException e) {
            throw new RequestParseException(
                    "the request text is malformed or was corrupted in transit: " + e.getMessage(), e);
        }
        if (request.formatVersion() != LicenseRequest.CURRENT_FORMAT_VERSION) {
            throw new RequestParseException(
                    "unsupported request format version " + request.formatVersion()
                            + " (this issuer understands version " + LicenseRequest.CURRENT_FORMAT_VERSION + ")");
        }
        return request;
    }

    public static final class RequestParseException extends RuntimeException {
        public RequestParseException(String message) {
            super(message);
        }

        public RequestParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
