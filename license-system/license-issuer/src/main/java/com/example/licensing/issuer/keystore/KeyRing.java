package com.example.licensing.issuer.keystore;

import com.example.licensing.common.crypto.Ed25519;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The signing keys kept in one directory on the generator's volume - one
 * {@code <kid>.p12} per key, all encrypted with the same keystore password -
 * plus which of them is active (signs new licenses), recorded in the file
 * {@value #ACTIVE_KEY_FILE} in the same directory.
 *
 * <p>Creating a key and making it active are deliberately two separate
 * steps. A new key is only useful once the public key is in TrustedKeys of a
 * build that customers have installed; signing with it earlier would make
 * every customer on an older build reject the license.
 *
 * <p>Safety rules, because a new key that nobody in the field trusts is the
 * one mistake here that can't be undone quietly:
 * <ul>
 *   <li>An existing key file is never overwritten.</li>
 *   <li>A key is never created automatically - only by an explicit call.</li>
 *   <li>If the directory has no keys at all but licenses were already issued,
 *       creating a key is refused: the keys were lost or the wrong volume is
 *       mounted, and they must be restored from backup instead.</li>
 *   <li>If the active-key marker names a key whose file is missing, the ring
 *       refuses to start rather than run without it.</li>
 * </ul>
 */
public final class KeyRing {

    public static final String ACTIVE_KEY_FILE = "active-key";
    private static final String SUFFIX = ".p12";

    private final Path keysDir;
    private final char[] password;
    private final BooleanSupplier licensesWereIssued;
    private final KeyEventLog eventLog;

    private volatile SigningKey active;

    /**
     * @param licensesWereIssued whether the audit log holds any issued license (for the
     *                           "keys missing but licenses exist" check)
     */
    public KeyRing(Path keysDir, char[] password, BooleanSupplier licensesWereIssued, KeyEventLog eventLog) {
        if (password == null || password.length < SigningKeyStore.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("keystore password must be set and at least "
                    + SigningKeyStore.MIN_PASSWORD_LENGTH + " characters");
        }
        try {
            Files.createDirectories(keysDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create keys directory " + keysDir
                    + " (is the volume mounted and writable?)", e);
        }
        this.keysDir = keysDir;
        this.password = password.clone();
        this.licensesWereIssued = licensesWereIssued;
        this.eventLog = eventLog;
        this.active = loadActiveKey();
    }

    /** The key that signs new licenses; empty until one is activated. */
    public Optional<SigningKey> active() {
        return Optional.ofNullable(active);
    }

    /** All keys in the directory, newest first. A key that can't be opened is listed with the reason. */
    public synchronized List<KeyInfo> list() {
        List<KeyInfo> result = new ArrayList<>();
        String activeId = active == null ? null : active.keyId();
        for (String kid : keyIds()) {
            try {
                SigningKey key = SigningKeyStore.load(fileFor(kid), Optional.of(kid), password.clone());
                result.add(KeyInfo.of(key, kid.equals(activeId)));
            } catch (RuntimeException e) {
                result.add(new KeyInfo(kid, null, null, null, kid.equals(activeId),
                        "cannot open: " + e.getMessage()));
            }
        }
        result.sort((a, b) -> {
            Instant x = a.createdAt() == null ? Instant.MIN : a.createdAt();
            Instant y = b.createdAt() == null ? Instant.MIN : b.createdAt();
            return y.compareTo(x);
        });
        return result;
    }

    /** Creates a new key in the directory. It is NOT activated. */
    public synchronized KeyInfo generate(String keyId, String actor) {
        SigningKeyStore.requireValidKeyId(keyId);
        if (Files.exists(fileFor(keyId))) {
            throw new KeyRingException("key '" + keyId + "' already exists");
        }
        if (keyIds().isEmpty() && licensesWereIssued.getAsBoolean()) {
            throw new KeyRingException("There are no keys in " + keysDir + ", but the audit log shows that "
                    + "licenses were already issued. The keys were lost or this is not the right volume: "
                    + "restore the keys from a backup. A new key would not be trusted by products in the field.");
        }
        SigningKey key = SigningKeyStore.create(fileFor(keyId), keyId, password.clone());
        eventLog.record("GENERATE", keyId, actor);
        return KeyInfo.of(key, false);
    }

    /**
     * Makes a key the one that signs new licenses. Callers must make sure its
     * public key is already trusted by the builds customers run.
     */
    public synchronized KeyInfo activate(String keyId, String actor) {
        SigningKeyStore.requireValidKeyId(keyId);
        if (!Files.exists(fileFor(keyId))) {
            throw new KeyRingException("no key '" + keyId + "' in " + keysDir);
        }
        SigningKey key = SigningKeyStore.load(fileFor(keyId), Optional.of(keyId), password.clone());
        byte[] probe = "key-ring-self-check".getBytes(StandardCharsets.UTF_8);
        if (!Ed25519.verify(key.publicKey(), probe, Ed25519.sign(key.privateKey(), probe))) {
            throw new KeyRingException("key '" + keyId + "' failed its self-check (private and public key differ)");
        }
        writeActiveMarker(keyId);
        active = key;
        eventLog.record("ACTIVATE", keyId, actor);
        return KeyInfo.of(key, true);
    }

    /** The encrypted .p12 file as stored, for an offline backup. */
    public byte[] backup(String keyId, String actor) {
        SigningKeyStore.requireValidKeyId(keyId);
        try {
            byte[] bytes = Files.readAllBytes(fileFor(keyId));
            eventLog.record("BACKUP", keyId, actor);
            return bytes;
        } catch (java.nio.file.NoSuchFileException e) {
            throw new KeyRingException("no key '" + keyId + "' in " + keysDir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read key file for '" + keyId + "'", e);
        }
    }

    /**
     * Creates a reserve key that is never written to this volume: the caller
     * gets the .p12 bytes (encrypted with {@code reservePassword}) to store
     * offline, and the public key to embed in TrustedKeys.
     */
    public ReserveKey generateReserve(String keyId, char[] reservePassword, String actor) {
        SigningKeyStore.requireValidKeyId(keyId);
        if (Files.exists(fileFor(keyId))) {
            throw new KeyRingException("key id '" + keyId + "' is already used by a key on this volume");
        }
        SigningKeyStore.GeneratedKeystore generated = SigningKeyStore.createInMemory(keyId, reservePassword);
        eventLog.record("GENERATE_RESERVE", keyId, actor);
        return new ReserveKey(KeyInfo.of(generated.key(), false), generated.pkcs12());
    }

    public List<KeyEventLog.KeyEvent> events(int limit) {
        return eventLog.recent(limit);
    }

    // ---- internals ----

    private SigningKey loadActiveKey() {
        Path marker = keysDir.resolve(ACTIVE_KEY_FILE);
        if (!Files.exists(marker)) {
            return null; // nothing activated yet
        }
        String keyId;
        try {
            keyId = Files.readString(marker, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + marker, e);
        }
        SigningKeyStore.requireValidKeyId(keyId);
        if (!Files.exists(fileFor(keyId))) {
            throw new IllegalStateException("The active signing key is '" + keyId + "' (" + marker + "), but "
                    + fileFor(keyId) + " is missing. Restore it from your backup - do NOT create a new key: "
                    + "products in the field would reject every license signed with it.");
        }
        return SigningKeyStore.load(fileFor(keyId), Optional.of(keyId), password.clone());
    }

    private void writeActiveMarker(String keyId) {
        Path marker = keysDir.resolve(ACTIVE_KEY_FILE);
        Path tmp = keysDir.resolve(ACTIVE_KEY_FILE + ".tmp");
        try {
            Files.writeString(tmp, keyId + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to record the active key in " + marker, e);
        }
    }

    private List<String> keyIds() {
        try (Stream<Path> files = Files.list(keysDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .filter(kid -> SigningKeyStore.KEY_ID_PATTERN.matcher(kid).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list keys directory " + keysDir, e);
        }
    }

    private Path fileFor(String keyId) {
        return keysDir.resolve(keyId + SUFFIX); // keyId is pattern-checked: no path separators
    }

    /** Public facts about a key - nothing secret. */
    public record KeyInfo(String keyId, Instant createdAt, String publicKeyBase64, String trustedKeysLine,
                          boolean active, String problem) {
        static KeyInfo of(SigningKey key, boolean active) {
            return new KeyInfo(key.keyId(), key.createdAt(), key.publicKeyBase64(), key.trustedKeysLine(),
                    active, null);
        }
    }

    public record ReserveKey(KeyInfo info, byte[] pkcs12) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ReserveKey other && info.equals(other.info) && Arrays.equals(pkcs12, other.pkcs12);
        }

        @Override
        public int hashCode() {
            return 31 * info.hashCode() + Arrays.hashCode(pkcs12);
        }

        @Override
        public String toString() {
            return "ReserveKey[" + info.keyId() + "]";
        }
    }

    public static final class KeyRingException extends RuntimeException {
        public KeyRingException(String message) {
            super(message);
        }
    }
}
