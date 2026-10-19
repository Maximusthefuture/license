package com.example.licensing.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the license from a file - e.g. a Kubernetes Secret mounted as a
 * volume - instead of a database.
 *
 * <p>No write support: a GitOps-managed deployment updates the license by
 * changing the Secret and letting Kubernetes re-mount it, not by a call
 * from inside the running product. Pair this with a short cache TTL in
 * {@link LicenseService} (or a file watch) so a changed Secret is picked up
 * without requiring a pod restart.
 */
public final class FileLicenseStore implements LicenseStore {

    private final Path path;

    public FileLicenseStore(Path path) {
        this.path = path;
    }

    @Override
    public Optional<StoredLicense> load() {
        if (!Files.isReadable(path)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            long version = Files.getLastModifiedTime(path).toMillis();
            return Optional.of(new StoredLicense(text, version));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read license file: " + path, e);
        }
    }

    @Override
    public boolean save(StoredLicense license, Optional<StoredLicense> expectedPrevious) {
        throw new UnsupportedOperationException(
                "FileLicenseStore is read-only - update the mounted Secret instead");
    }
}
