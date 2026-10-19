package com.example.licensing.issuer.keystore;

import com.example.licensing.common.crypto.Ed25519;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRingTest {

    private static final char[] PASSWORD = "correct-horse-battery".toCharArray();

    @TempDir
    Path dir;

    private Path keysDir;
    private KeyEventLog eventLog;
    private boolean licensesWereIssued;

    @BeforeEach
    void setUp() {
        keysDir = dir.resolve("keys");
        eventLog = new KeyEventLog(dir.resolve("audit"), Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC));
        licensesWereIssued = false;
    }

    private KeyRing ring() {
        return new KeyRing(keysDir, PASSWORD, () -> licensesWereIssued, eventLog);
    }

    @Test
    void newVolumeStartsWithNoActiveKey() {
        KeyRing ring = ring();

        assertThat(ring.active().isPresent()).isFalse();
        assertThat(ring.list().isEmpty()).isTrue();
    }

    @Test
    void generatedKeyIsStoredButNotActiveUntilActivated() {
        KeyRing ring = ring();

        KeyRing.KeyInfo created = ring.generate("2026-01", "alice");

        assertThat(Files.exists(keysDir.resolve("2026-01.p12"))).isTrue();
        assertThat(created.active()).isFalse();
        assertThat(ring.active().isPresent()).isFalse();
        assertThat(created.trustedKeysLine()).contains("\"2026-01\", Base64.getDecoder().decode(");

        ring.activate("2026-01", "alice");

        assertThat(ring.active().orElseThrow().keyId()).isEqualTo("2026-01");
        assertThat(ring.list().get(0).active()).isTrue();
    }

    @Test
    void activeKeySurvivesARestart() {
        KeyRing first = ring();
        first.generate("2026-01", "alice");
        first.activate("2026-01", "alice");
        String publicKey = first.list().get(0).publicKeyBase64();

        KeyRing afterRestart = ring();

        SigningKey active = afterRestart.active().orElseThrow();
        assertThat(active.keyId()).isEqualTo("2026-01");
        assertThat(active.publicKeyBase64()).isEqualTo(publicKey);
        byte[] data = "x".getBytes();
        assertThat(Ed25519.verify(active.publicKey(), data, Ed25519.sign(active.privateKey(), data))).isTrue();
    }

    @Test
    void rotationKeepsTheOldKeyActiveUntilTheNewOneIsActivated() {
        KeyRing ring = ring();
        ring.generate("2026-01", "alice");
        ring.activate("2026-01", "alice");

        ring.generate("2027-01", "alice");
        assertThat(ring.active().orElseThrow().keyId()).isEqualTo("2026-01");

        ring.activate("2027-01", "bob");
        assertThat(ring.active().orElseThrow().keyId()).isEqualTo("2027-01");
        assertThat(ring.list().size()).isEqualTo(2);
        assertThat(Files.exists(keysDir.resolve("2026-01.p12"))).isTrue(); // old key kept for older builds
    }

    @Test
    void neverOverwritesAnExistingKey() throws IOException {
        KeyRing ring = ring();
        ring.generate("2026-01", "alice");
        byte[] original = Files.readAllBytes(keysDir.resolve("2026-01.p12"));

        assertThatThrownBy(() -> ring.generate("2026-01", "alice"))
                .isInstanceOf(KeyRing.KeyRingException.class)
                .hasMessageContaining("already exists");
        assertThat(Files.readAllBytes(keysDir.resolve("2026-01.p12"))).isEqualTo(original);
    }

    @Test
    void refusesToCreateAKeyWhenKeysAreMissingButLicensesWereIssued() {
        licensesWereIssued = true; // audit log has licenses, keys directory is empty: wrong/lost volume

        assertThatThrownBy(() -> ring().generate("2026-01", "alice"))
                .isInstanceOf(KeyRing.KeyRingException.class)
                .hasMessageContaining("restore the keys from a backup");
        assertThat(Files.exists(keysDir.resolve("2026-01.p12"))).isFalse();
    }

    @Test
    void refusesToStartWhenTheActiveKeyFileIsMissing() throws IOException {
        KeyRing ring = ring();
        ring.generate("2026-01", "alice");
        ring.activate("2026-01", "alice");
        Files.delete(keysDir.resolve("2026-01.p12"));

        assertThatThrownBy(this::ring)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do NOT create a new key");
    }

    @Test
    void aKeyWithAnotherPasswordIsListedWithTheProblemAndCannotBeActivated() throws IOException {
        Files.createDirectories(keysDir); // e.g. a .p12 made with KeyGenTool and copied onto the volume
        SigningKeyStore.create(keysDir.resolve("odd.p12"), "odd", "another-password-123".toCharArray());
        KeyRing ring = ring();

        List<KeyRing.KeyInfo> keys = ring.list();
        assertThat(keys.size()).isEqualTo(1);
        assertThat(keys.get(0).problem()).contains("wrong password");
        assertThatThrownBy(() -> ring.activate("odd", "alice"))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class);
        assertThat(ring.active().isPresent()).isFalse();
    }

    @Test
    void backupIsTheEncryptedFileAsStored() throws IOException {
        KeyRing ring = ring();
        ring.generate("2026-01", "alice");

        byte[] backup = ring.backup("2026-01", "alice");

        assertThat(backup).isEqualTo(Files.readAllBytes(keysDir.resolve("2026-01.p12")));
        assertThatThrownBy(() -> ring.backup("nope", "alice")).isInstanceOf(KeyRing.KeyRingException.class);
    }

    @Test
    void reserveKeyIsHandedOutButNeverWrittenToTheVolume() throws IOException {
        KeyRing ring = ring();
        char[] reservePassword = "reserve-password-456".toCharArray();

        KeyRing.ReserveKey reserve = ring.generateReserve("2026-01-reserve", reservePassword, "alice");

        assertThat(Files.exists(keysDir.resolve("2026-01-reserve.p12"))).isFalse();
        assertThat(ring.list().isEmpty()).isTrue();
        // the downloaded bytes are a working keystore under the reserve password
        Path restored = dir.resolve("restored.p12");
        Files.write(restored, reserve.pkcs12());
        SigningKey loaded = SigningKeyStore.load(restored, Optional.empty(), reservePassword);
        assertThat(loaded.keyId()).isEqualTo("2026-01-reserve");
        assertThat(loaded.publicKeyBase64()).isEqualTo(reserve.info().publicKeyBase64());
    }

    @Test
    void everyKeyOperationIsLoggedWithWhoDidIt() {
        KeyRing ring = ring();
        ring.generate("2026-01", "alice");
        ring.activate("2026-01", "bob");
        ring.backup("2026-01", "carol");

        List<KeyEventLog.KeyEvent> events = ring.events(10);

        assertThat(events.size()).isEqualTo(3);
        assertThat(events.get(0).action()).isEqualTo("BACKUP");
        assertThat(events.get(0).actor()).isEqualTo("carol");
        assertThat(events.get(1).action()).isEqualTo("ACTIVATE");
        assertThat(events.get(2).actor()).isEqualTo("alice");
    }

    @Test
    void rejectsKeyIdsThatCouldEscapeTheDirectory() {
        assertThatThrownBy(() -> ring().generate("../evil", "alice"))
                .isInstanceOf(SigningKeyStore.SigningKeyStoreException.class)
                .hasMessageContaining("invalid key id");
    }
}
