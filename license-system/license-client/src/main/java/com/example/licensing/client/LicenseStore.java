package com.example.licensing.client;

import java.util.Optional;

/**
 * Persists the raw, signed license text - never parsed fields.
 *
 * <p>Store the armored text exactly as activated and re-verify its
 * signature on every read (LicenseValidator does this); never cache parsed
 * fields in a way that lets anything trust them without that check. A
 * database row that's easy to hand-edit is the single cheapest way to
 * "extend" a license, and the only real defense against it is that doing
 * so breaks the signature.
 */
public interface LicenseStore {

    Optional<StoredLicense> load();

    /**
     * @param expectedPrevious the value this write assumes is currently stored (empty for "nothing
     *                         stored yet"), used for optimistic concurrency control.
     * @return false if a concurrent writer already changed the stored value since expectedPrevious was
     * read - the caller should re-read and decide whether to retry.
     */
    boolean save(StoredLicense license, Optional<StoredLicense> expectedPrevious);

    record StoredLicense(String rawText, long version) {
    }
}
