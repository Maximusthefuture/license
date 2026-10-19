package com.example.licensing.issuer;

import com.example.licensing.common.model.LicensePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileIssuedLicenseRepositoryTest {

    @Test
    void recordsALicenseFileAndAManifestLine(@TempDir Path dir) throws IOException {
        FileIssuedLicenseRepository repository = new FileIssuedLicenseRepository(dir);
        LicensePayload payload = payload("Customer\tWith\nWeird Whitespace", Instant.parse("2026-01-01T00:00:00Z"));

        repository.record(payload, "-----BEGIN LICENSE KEY-----\n...\n-----END LICENSE KEY-----\n", "alice");

        Path licenseFile = dir.resolve(payload.licenseId() + ".license.txt");
        assertThat(licenseFile).exists();
        assertThat(Files.readString(licenseFile)).contains("BEGIN LICENSE KEY");

        String manifestContent = Files.readString(dir.resolve("manifest.tsv"));
        assertThat(manifestContent).contains(payload.licenseId().toString());
        assertThat(manifestContent).doesNotContain("\n\n"); // sanitized embedded newline didn't create a blank line
    }

    @Test
    void listsMostRecentFirstWithOperatorAndReadsBackTheText(@TempDir Path dir) {
        FileIssuedLicenseRepository repository = new FileIssuedLicenseRepository(dir);
        LicensePayload first = payload("First", Instant.parse("2026-01-01T00:00:00Z"));
        LicensePayload second = payload("Second", Instant.parse("2026-02-01T00:00:00Z"));
        repository.record(first, "text-1", "alice");
        repository.record(second, "text-2", "bob");

        List<IssuedLicenseRepository.IssuedLicenseRecord> records = repository.list(10);

        assertThat(records.size()).isEqualTo(2);
        assertThat(records.get(0).customer()).isEqualTo("Second");
        assertThat(records.get(0).issuedBy()).isEqualTo("bob");
        assertThat(records.get(1).issuedBy()).isEqualTo("alice");
        assertThat(records.get(1).modules()).containsExactlyInAnyOrder("reports");
        assertThat(repository.list(1).size()).isEqualTo(1);
        assertThat(repository.licenseText(first.licenseId()).orElseThrow()).isEqualTo("text-1");
        assertThat(repository.licenseText(UUID.randomUUID()).isPresent()).isFalse();
    }

    @Test
    void emptyRepositoryListsNothing(@TempDir Path dir) {
        assertThat(new FileIssuedLicenseRepository(dir).list(10).isEmpty()).isTrue();
    }

    private static LicensePayload payload(String customer, Instant issuedAt) {
        return new LicensePayload(
                1, UUID.randomUUID(), UUID.randomUUID(), "2026-01", "acme-suite",
                customer, UUID.randomUUID(), null,
                issuedAt, issuedAt, issuedAt.plusSeconds(86400L * 365), Set.of("reports"), 10);
    }
}
