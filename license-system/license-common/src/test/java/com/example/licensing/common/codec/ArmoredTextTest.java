package com.example.licensing.common.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArmoredTextTest {

    @Test
    void roundTripsArbitraryBytes() {
        byte[] data = "hello, license text with \n newlines and \u00fcnic\u00f6de".getBytes(StandardCharsets.UTF_8);

        String armored = ArmoredText.wrap("TEST BLOCK", data);
        byte[] decoded = ArmoredText.unwrap("TEST BLOCK", armored);

        assertThat(decoded).isEqualTo(data);
    }

    @Test
    void survivesBeingEmbeddedInSurroundingEmailText() {
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        String armored = ArmoredText.wrap("TEST BLOCK", data);
        String email = "Hi,\n\nhere is our request:\n\n" + armored + "\nThanks!\n";

        byte[] decoded = ArmoredText.unwrap("TEST BLOCK", email);

        assertThat(decoded).isEqualTo(data);
    }

    @Test
    void rejectsCorruptedBody() {
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        String armored = ArmoredText.wrap("TEST BLOCK", data);
        String corrupted = armored.replaceFirst("\\n[A-Za-z0-9+/=]+\\n", "\nCORRUPTED===\n");

        assertThatThrownBy(() -> ArmoredText.unwrap("TEST BLOCK", corrupted))
                .isInstanceOf(ArmoredText.ArmorFormatException.class);
    }

    @Test
    void rejectsMissingMarkers() {
        assertThatThrownBy(() -> ArmoredText.unwrap("TEST BLOCK", "not armored at all"))
                .isInstanceOf(ArmoredText.ArmorFormatException.class);
    }
}
