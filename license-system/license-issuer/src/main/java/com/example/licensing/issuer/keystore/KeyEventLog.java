package com.example.licensing.issuer.keystore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only log of everything done to signing keys - created, activated,
 * backup downloaded, reserve key created - and by whom. Lives next to the
 * license audit log ({@code key-events.tsv}).
 */
public final class KeyEventLog {

    public static final String FILE_NAME = "key-events.tsv";

    private final Path file;
    private final Clock clock;

    public KeyEventLog(Path auditDir, Clock clock) {
        try {
            Files.createDirectories(auditDir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create audit directory: " + auditDir, e);
        }
        this.file = auditDir.resolve(FILE_NAME);
        this.clock = clock;
    }

    public synchronized void record(String action, String keyId, String actor) {
        String line = String.join("\t", clock.instant().toString(), action, keyId, sanitize(actor))
                + System.lineSeparator();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write key event log " + file, e);
        }
    }

    /** Most recent first. */
    public synchronized List<KeyEvent> recent(int limit) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read key event log " + file, e);
        }
        List<KeyEvent> result = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
            String[] f = lines.get(i).split("\t", -1);
            if (f.length >= 4) {
                result.add(new KeyEvent(Instant.parse(f[0]), f[1], f[2], f[3]));
            }
        }
        return result;
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public record KeyEvent(Instant at, String action, String keyId, String actor) {
    }
}
