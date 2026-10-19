package com.example.licensing.issuer.keystore;

import com.example.licensing.common.crypto.Ed25519;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Keeps a license-signing key in a password-protected PKCS#12 (.p12) file.
 *
 * <p>Conventions:
 * <ul>
 *   <li>One key per file, so the primary and the reserve key can be stored
 *       (and backed up) in physically different places.</li>
 *   <li>The keystore alias is the key id ("kid") written into licenses.
 *       Key ids are restricted to lowercase letters, digits, '.', '_' and '-',
 *       because PKCS#12 aliases are case-insensitive in the JDK and a kid that
 *       silently changes case between the issuer and the client would break
 *       every license signed with it.</li>
 *   <li>The JDK 21 default PKCS#12 protection is used: the key is encrypted
 *       with PBES2 / AES-256 and the file is integrity-protected with
 *       HMAC-SHA256, both keyed from the password.</li>
 * </ul>
 *
 * <p>The same files can also be created and inspected with the JDK's
 * {@code keytool}, e.g.
 * {@code keytool -genkeypair -keyalg Ed25519 -alias <kid> -storetype PKCS12 -keystore <file>.p12 -dname CN=License-Issuer}.
 */
public final class SigningKeyStore {

    public static final Pattern KEY_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    public static final int MIN_PASSWORD_LENGTH = 12;

    private static final String STORE_TYPE = "PKCS12";
    // Only a container for the key (see SelfSignedCertificate) - its expiry
    // does not affect licenses, so it is simply made long enough never to matter.
    private static final Duration CERTIFICATE_VALIDITY = Duration.ofDays(365L * 20);

    private SigningKeyStore() {
    }

    /**
     * Generates a new Ed25519 key pair and writes it to a new keystore file.
     * Refuses to overwrite an existing file: replacing a signing key by
     * accident is exactly the kind of mistake that can't be undone.
     */
    public static SigningKey create(Path file, String keyId, char[] password) {
        GeneratedKeystore generated = createInMemory(keyId, password);
        boolean fileCreated = false;
        try {
            createOwnerOnlyFile(file);
            fileCreated = true;
            Files.write(file, generated.pkcs12(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            throw new SigningKeyStoreException("refusing to overwrite existing keystore: " + file, e);
        } catch (IOException e) {
            if (fileCreated) {
                deleteQuietly(file); // don't leave a half-written file that blocks the next attempt
            }
            throw new SigningKeyStoreException("failed to write keystore " + file, e);
        }
        return generated.key();
    }

    /**
     * Generates a new key pair as a password-protected PKCS#12 file in memory,
     * for keys that must not be written to the server's disk (e.g. a reserve
     * key that is handed out as a download and stored elsewhere).
     */
    public static GeneratedKeystore createInMemory(String keyId, char[] password) {
        requireValidKeyId(keyId);
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new SigningKeyStoreException(
                    "keystore password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        KeyPair keyPair = Ed25519.generateKeyPair();
        Instant now = Instant.now();
        X509Certificate certificate = SelfSignedCertificate.create(
                keyPair, "License Issuer " + keyId, now, now.plus(CERTIFICATE_VALIDITY));
        try {
            KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
            keyStore.load(null, null);
            keyStore.setKeyEntry(keyId, keyPair.getPrivate(), password, new Certificate[]{certificate});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keyStore.store(out, password);
            return new GeneratedKeystore(
                    new SigningKey(keyId, keyPair.getPrivate(), keyPair.getPublic(), certificate.getNotBefore().toInstant()),
                    out.toByteArray());
        } catch (IOException | GeneralSecurityException e) {
            throw new SigningKeyStoreException("failed to create keystore for key '" + keyId + "'", e);
        }
    }

    /** A freshly generated key together with its encrypted PKCS#12 encoding. */
    public record GeneratedKeystore(SigningKey key, byte[] pkcs12) {
    }

    /**
     * Loads a signing key. If {@code keyId} is empty, the keystore must
     * contain exactly one key, whose alias is then used as the key id.
     */
    public static SigningKey load(Path file, Optional<String> keyId, char[] password) {
        KeyStore keyStore = open(file, password);
        // The alias becomes the kid in every license, so it has to satisfy the
        // same rules even when it comes from a keystore made with keytool.
        String alias = requireValidKeyId(keyId.orElseGet(() -> singleKeyAlias(keyStore, file)));

        try {
            if (!keyStore.isKeyEntry(alias)) {
                throw new SigningKeyStoreException("no key with id '" + alias + "' in " + file
                        + " (available: " + keyAliases(keyStore) + ")");
            }
            Key key = keyStore.getKey(alias, password);
            if (!(key instanceof PrivateKey privateKey) || !isEd25519(privateKey)) {
                throw new SigningKeyStoreException("key '" + alias + "' in " + file + " is not an Ed25519 private key");
            }
            Certificate certificate = keyStore.getCertificate(alias);
            if (certificate == null) {
                throw new SigningKeyStoreException("key '" + alias + "' in " + file + " has no certificate/public key");
            }
            Instant createdAt = certificate instanceof X509Certificate x509 ? x509.getNotBefore().toInstant() : null;
            return new SigningKey(alias, privateKey, certificate.getPublicKey(), createdAt);
        } catch (UnrecoverableKeyException e) {
            throw new SigningKeyStoreException("wrong password for key '" + alias + "' in " + file, e);
        } catch (GeneralSecurityException e) {
            throw new SigningKeyStoreException("failed to read key '" + alias + "' from " + file, e);
        }
    }

    public static String requireValidKeyId(String keyId) {
        if (keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new SigningKeyStoreException("invalid key id '" + keyId
                    + "': use lowercase letters, digits, '.', '_' or '-' (max 64 chars), e.g. 2026-01");
        }
        return keyId;
    }

    private static KeyStore open(Path file, char[] password) {
        if (!Files.isRegularFile(file)) {
            throw new SigningKeyStoreException("keystore not found: " + file);
        }
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
            keyStore.load(in, password);
            return keyStore;
        } catch (IOException e) {
            // PKCS#12 reports a wrong password as an IOException whose cause is
            // UnrecoverableKeyException (failed integrity check).
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw new SigningKeyStoreException("wrong password for keystore " + file, e);
            }
            throw new SigningKeyStoreException("failed to read keystore " + file + " (corrupted or not PKCS#12?)", e);
        } catch (GeneralSecurityException e) {
            throw new SigningKeyStoreException("failed to read keystore " + file, e);
        }
    }

    private static String singleKeyAlias(KeyStore keyStore, Path file) {
        List<String> aliases = keyAliases(keyStore);
        if (aliases.size() != 1) {
            throw new SigningKeyStoreException("keystore " + file + " contains " + aliases.size()
                    + " keys " + aliases + "; specify which key id to use");
        }
        return aliases.get(0);
    }

    private static List<String> keyAliases(KeyStore keyStore) {
        try {
            List<String> result = new ArrayList<>();
            for (String alias : Collections.list(keyStore.aliases())) {
                if (keyStore.isKeyEntry(alias)) {
                    result.add(alias);
                }
            }
            Collections.sort(result);
            return result;
        } catch (GeneralSecurityException e) {
            throw new SigningKeyStoreException("failed to list keystore entries", e);
        }
    }

    private static boolean isEd25519(PrivateKey key) {
        // The JDK reports Ed25519 keys as algorithm "EdDSA" (or "Ed25519"
        // depending on how they were created).
        String algorithm = key.getAlgorithm();
        return "EdDSA".equals(algorithm) || "Ed25519".equals(algorithm);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort; the original error is what gets reported
        }
    }

    private static void createOwnerOnlyFile(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file); // non-POSIX file system (e.g. Windows): rely on the directory's ACLs
        }
    }

    public static final class SigningKeyStoreException extends RuntimeException {
        public SigningKeyStoreException(String message) {
            super(message);
        }

        public SigningKeyStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
