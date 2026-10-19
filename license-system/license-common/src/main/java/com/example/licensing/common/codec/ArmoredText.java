package com.example.licensing.common.codec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * A small "ASCII armor" format, deliberately similar in spirit to PGP
 * armored text: plain text that survives being pasted into an email body or
 * mangled by a mail client's line wrapping, with a checksum to catch
 * accidental corruption.
 *
 * <p>The checksum is NOT a security control - it only catches copy/paste
 * damage. The actual integrity guarantee for a license comes from the
 * signature carried inside the armored body (see PayloadCodec); for a
 * request, there is no signature, only this checksum, because a request
 * only needs to survive transport intact, not resist forgery.
 */
public final class ArmoredText {

    private static final int LINE_WIDTH = 64;

    private ArmoredText() {
    }

    public static String wrap(String label, byte[] data) {
        String base64 = Base64.getEncoder().encodeToString(data);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < base64.length(); i += LINE_WIDTH) {
            sb.append(base64, i, Math.min(i + LINE_WIDTH, base64.length())).append('\n');
        }
        sb.append('=').append(Long.toHexString(crc32(data))).append('\n');
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString();
    }

    public static byte[] unwrap(String label, String armored) {
        String beginMarker = "-----BEGIN " + label + "-----";
        String endMarker = "-----END " + label + "-----";

        int begin = armored.indexOf(beginMarker);
        int end = armored.indexOf(endMarker);
        if (begin < 0 || end < 0 || end < begin) {
            throw new ArmorFormatException("missing " + label + " markers");
        }

        String body = armored.substring(begin + beginMarker.length(), end);
        String[] lines = body.strip().split("\\R");
        if (lines.length < 2) {
            throw new ArmorFormatException("truncated " + label + " body");
        }

        String checksumLine = lines[lines.length - 1].strip();
        if (!checksumLine.startsWith("=")) {
            throw new ArmorFormatException("missing checksum line in " + label);
        }

        StringBuilder base64 = new StringBuilder();
        for (int i = 0; i < lines.length - 1; i++) {
            base64.append(lines[i].strip());
        }

        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.toString());
        } catch (IllegalArgumentException e) {
            throw new ArmorFormatException("invalid base64 in " + label, e);
        }

        long expected;
        try {
            expected = Long.parseLong(checksumLine.substring(1), 16);
        } catch (NumberFormatException e) {
            throw new ArmorFormatException("invalid checksum in " + label, e);
        }
        long actual = crc32(data);
        if (expected != actual) {
            throw new ArmorFormatException(label + " checksum mismatch - the text was corrupted or truncated");
        }

        return data;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    public static final class ArmorFormatException extends RuntimeException {
        public ArmorFormatException(String message) {
            super(message);
        }

        public ArmorFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
