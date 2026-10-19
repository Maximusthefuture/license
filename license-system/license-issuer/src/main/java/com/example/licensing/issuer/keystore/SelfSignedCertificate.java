package com.example.licensing.issuer.keystore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds a minimal self-signed X.509 certificate for an Ed25519 key pair.
 *
 * <p>Why this exists: a PKCS#12 keystore can only hold a private key
 * together with a certificate chain, and the JDK has no public API for
 * creating certificates ({@code keytool} does it with internal classes).
 * The certificate here is purely a container that lets the key live in a
 * standard, password-protected .p12 file that {@code keytool -list} can
 * inspect; it is never used to validate anything, and its validity period
 * has no effect on the licenses the key signs.
 *
 * <p>Ed25519 keeps this small: no algorithm parameters, and the JDK's own
 * encoding of the public key is already the SubjectPublicKeyInfo structure a
 * certificate needs.
 */
final class SelfSignedCertificate {

    private static final byte[] ED25519_ALGORITHM_ID = {0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70};
    private static final byte[] OID_COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};

    private SelfSignedCertificate() {
    }

    static X509Certificate create(KeyPair keyPair, String commonName, Instant notBefore, Instant notAfter) {
        try {
            byte[] name = name(commonName);
            byte[] tbs = sequence(
                    explicitTag0(integer(new byte[]{0x02})),        // version: v3
                    integer(serialNumber()),
                    ED25519_ALGORITHM_ID,
                    name,                                            // issuer
                    sequence(time(notBefore), time(notAfter)),       // validity
                    name,                                            // subject (self-signed)
                    keyPair.getPublic().getEncoded());               // SubjectPublicKeyInfo

            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(tbs);
            byte[] signature = signer.sign();

            byte[] der = sequence(tbs, ED25519_ALGORITHM_ID, bitString(signature));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            certificate.verify(keyPair.getPublic());
            return certificate;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to create self-signed certificate", e);
        }
    }

    // ---- minimal DER encoding ----

    private static byte[] serialNumber() {
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F; // keep it a positive INTEGER
        serial[0] |= 0x01; // and avoid a leading zero byte, which DER would require us to strip
        return serial;
    }

    private static byte[] name(String commonName) {
        byte[] value = tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)); // UTF8String
        byte[] attribute = sequence(OID_COMMON_NAME, value);
        byte[] rdn = tlv(0x31, attribute);                                    // SET OF
        return sequence(rdn);
    }

    private static byte[] time(Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        // RFC 5280: UTCTime through 2049, GeneralizedTime from 2050 on.
        if (utc.getYear() < 2050) {
            String s = utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + "Z";
            return tlv(0x17, s.getBytes(StandardCharsets.US_ASCII));
        }
        String s = utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "Z";
        return tlv(0x18, s.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(byte[] value) {
        return tlv(0x02, value);
    }

    private static byte[] bitString(byte[] value) {
        byte[] withUnusedBits = new byte[value.length + 1];
        System.arraycopy(value, 0, withUnusedBits, 1, value.length); // first byte: 0 unused bits
        return tlv(0x03, withUnusedBits);
    }

    private static byte[] explicitTag0(byte[] inner) {
        return tlv(0xA0, inner);
    }

    private static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            content.writeBytes(part);
        }
        return tlv(0x30, content.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        } else {
            throw new IllegalArgumentException("DER value too long for this encoder: " + length);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
