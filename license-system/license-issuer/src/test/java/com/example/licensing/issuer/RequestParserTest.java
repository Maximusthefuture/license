package com.example.licensing.issuer;

import com.example.licensing.common.codec.RequestCodec;
import com.example.licensing.common.model.LicenseRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestParserTest {

    @Test
    void parsesAWellFormedRequest() {
        LicenseRequest request = new LicenseRequest(
                LicenseRequest.CURRENT_FORMAT_VERSION, UUID.randomUUID(), "acme-suite", "3.2.1",
                UUID.randomUUID(), UUID.randomUUID(), "Acme Corp", Instant.parse("2026-01-01T00:00:00Z"));
        String text = RequestCodec.encode(request);

        LicenseRequest parsed = RequestParser.parse(text);

        assertThat(parsed).isEqualTo(request);
    }

    @Test
    void rejectsGarbageText() {
        assertThatThrownBy(() -> RequestParser.parse("this is not a license request at all"))
                .isInstanceOf(RequestParser.RequestParseException.class);
    }

    @Test
    void rejectsAnUnsupportedFormatVersion() {
        LicenseRequest request = new LicenseRequest(
                99, UUID.randomUUID(), "acme-suite", "3.2.1",
                UUID.randomUUID(), null, "Acme Corp", Instant.parse("2026-01-01T00:00:00Z"));
        String text = RequestCodec.encode(request);

        assertThatThrownBy(() -> RequestParser.parse(text))
                .isInstanceOf(RequestParser.RequestParseException.class)
                .hasMessageContaining("format version");
    }
}
