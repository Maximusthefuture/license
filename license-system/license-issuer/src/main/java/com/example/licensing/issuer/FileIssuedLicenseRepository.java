package com.example.licensing.issuer;

import com.example.licensing.common.model.LicensePayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only audit trail backed by plain files: the full signed license
 * text for each issuance (one file per licenseId, so support can always
 * see exactly what was sent), plus a tab-separated manifest line per
 * issuance - "who issued what, to whom, when".
 *
 * <p>Good enough for a few dozen issuances a year by a handful of
 * operators, with the generator running as a single instance; move to a
 * database if volume grows or several generator instances must share it.
 */
public final class FileIssuedLicenseRepository implements IssuedLicenseRepository {

    private static final String MANIFEST = "manifest.tsv";

    private final Path directory;

    public FileIssuedLicenseRepository(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create audit directory: " + directory, e);
        }
    }

    @Override
    public synchronized void record(LicensePayload payload, String armoredLicenseText, String issuedBy) {
        try {
            Path licenseFile = directory.resolve(payload.licenseId() + ".license.txt");
            Files.writeString(licenseFile, armoredLicenseText, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);

            String line = String.join("\t",
                    payload.issuedAt().toString(),
                    payload.licenseId().toString(),
                    payload.requestId().toString(),
                    payload.keyId(),
                    payload.productId(),
                    sanitize(payload.customer()),
                    payload.installationId().toString(),
                    String.valueOf(payload.clusterId()),
                    payload.notBefore().toString(),
                    payload.notAfter().toString(),
                    String.join(",", payload.modules()),
                    String.valueOf(payload.maxUsers()),
                    sanitize(issuedBy == null ? "" : issuedBy)) + System.lineSeparator();
            Files.writeString(directory.resolve(MANIFEST), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("failed to record issued license", e);
        }
    }

    @Override
    public synchronized List<IssuedLicenseRecord> list(int limit) {
        List<String> lines;
        try {
            lines = Files.readAllLines(directory.resolve(MANIFEST), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read audit manifest", e);
        }
        List<IssuedLicenseRecord> result = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
            String line = lines.get(i);
            if (!line.isBlank()) {
                result.add(parse(line));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Optional<String> licenseText(UUID licenseId) {
        Path file = directory.resolve(licenseId + ".license.txt"); // UUID.toString() can't escape the directory
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read issued license " + licenseId, e);
        }
    }

    private static IssuedLicenseRecord parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 12) {
            throw new IllegalStateException("malformed audit manifest line: " + line);
        }
        List<String> modules = f[10].isEmpty() ? List.of() : Arrays.asList(f[10].split(","));
        return new IssuedLicenseRecord(
                Instant.parse(f[0]), UUID.fromString(f[1]), UUID.fromString(f[2]), f[3], f[4], f[5],
                UUID.fromString(f[6]), "null".equals(f[7]) ? null : f[7],
                Instant.parse(f[8]), Instant.parse(f[9]), modules, Integer.parseInt(f[11]),
                f.length > 12 ? f[12] : ""); // lines written before issuedBy existed have 12 fields
    }

    private static String sanitize(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
