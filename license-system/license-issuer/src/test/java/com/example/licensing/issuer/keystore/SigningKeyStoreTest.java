package com.example.licensing.issuer.keystore;

import com.example.licensing.common.crypto.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyStoreTest {

    private static final char[] PASSWORD = "correct-horse-battery".toCharArray();

    @Test
    void createdKeyCanBeLoadedBackAndSigns(@TempDir Path dir) {
        Path file = dir.resolve("primary.p12");

        SigningKey created = SigningKeyStore.create(file, "2026-01", PASSWORD.clone());
        SigningKey loaded = SigningKeyStore.load(file, Optional.empty(), PASSWORD.clone());

        assertThat(loaded.keyId()).isEqualTo("2026-01");
        assertThat(loaded.publicKey().getEncoded()).isEqualTo(created.publicKey().getEncoded());

        byte[] data = "payload".getBytes();
        byte[] signature = Ed25519.sign(loaded.privateKey(), data);
        assertThat(Ed25519.verify(created.publicKey(), data, signature)).isTrue();
    }

    @Test
    void loadsByExplicitKeyId(@TempDir Path dir) {
        Path file = dir.resolve("reserve.p12");
        SigningKeyStore.create(file, "2026-01-reserve", PASSWORD.clone());

        SigningKey loaded = SigningKeyStore.load(file, Optional.of("2026-01-reserve"), PASSWORD.clone());

        assertThat(loaded.keyId()).isEqualTo("2026-01-reserve");
    }

    @Test
    void wrongPasswordIsReportedAsSuch(@TempDir Path dir) {
        Path file = dir.resolve("primary.p12");
        SigningKeyStore.create(file, "2026-01", PASSWORD.clone());

        assertThatThrownBy(() -> SigningKeyStore.load(file, Optional.empty(), "not-the-password".toCharArray()))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class)
                .hasMessageContaining("wrong password");
    }

    @Test
    void unknownKeyIdIsRejected(@TempDir Path dir) {
        Path file = dir.resolve("primary.p12");
        SigningKeyStore.create(file, "2026-01", PASSWORD.clone());

        assertThatThrownBy(() -> SigningKeyStore.load(file, Optional.of("2026-02"), PASSWORD.clone()))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class)
                .hasMessageContaining("no key with id '2026-02'");
    }

    @Test
    void refusesToOverwriteAnExistingKeystore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("primary.p12");
        SigningKeyStore.create(file, "2026-01", PASSWORD.clone());
        byte[] original = Files.readAllBytes(file);

        assertThatThrownBy(() -> SigningKeyStore.create(file, "2026-01", PASSWORD.clone()))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class)
                .hasMessageContaining("refusing to overwrite");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    void rejectsShortPasswords(@TempDir Path dir) {
        Path file = dir.resolve("primary.p12");

        assertThatThrownBy(() -> SigningKeyStore.create(file, "2026-01", "short".toCharArray()))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class)
                .hasMessageContaining("at least");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void rejectsKeyIdsThatTheKeystoreWouldSilentlyLowercase(@TempDir Path dir) {
        // The JDK's PKCS#12 keystore lowercases aliases ("MyKey" comes back as
        // "mykey"); a kid that changes on the way through would no longer match
        // the client's TrustedKeys.
        assertThatThrownBy(() -> SigningKeyStore.create(dir.resolve("k.p12"), "2026-01-Reserve", PASSWORD.clone()))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class)
                .hasMessageContaining("invalid key id");
    }

    @Test
    void keystoreFileIsReadableByOwnerOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("primary.p12");
        SigningKeyStore.create(file, "2026-01", PASSWORD.clone());

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .isEqualTo("rw-------");
        }
    }
}
