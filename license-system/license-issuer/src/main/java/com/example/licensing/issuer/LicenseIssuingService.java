package com.example.licensing.issuer;

import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.keystore.SigningKey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the generator does, independent of how it's exposed (HTTP service,
 * CLI): preview a pasted request, issue a license for it, show the history.
 *
 * <p>Input from the operator is validated here rather than in the web
 * layer, so every entry point gets the same rules.
 */
public final class LicenseIssuingService {

    public static final Pattern MODULE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    public static final int MAX_VALIDITY_DAYS = 3650;
    public static final int MAX_USERS = 1_000_000;

    private final Supplier<Optional<LicenseSigner>> signer;
    private final IssuedLicenseRepository repository;

    /** Fixed signer (CLI, tests). */
    public LicenseIssuingService(LicenseSigner signer, IssuedLicenseRepository repository) {
        this(() -> Optional.of(signer), repository);
    }

    /**
     * Signer looked up per call, e.g. the active key of a KeyRing, which can
     * change at runtime and can be absent until a key is activated.
     */
    public LicenseIssuingService(Supplier<Optional<LicenseSigner>> signer, IssuedLicenseRepository repository) {
        this.signer = signer;
        this.repository = repository;
    }

    /** @throws NoActiveKeyException if no signing key is active */
    public SigningKeyInfo signingKey() {
        LicenseSigner current = currentSigner();
        String publicKey = Base64.getEncoder().encodeToString(current.publicKeyEncoded());
        return new SigningKeyInfo(current.keyId(), publicKey, SigningKey.trustedKeysLine(current.keyId(), publicKey));
    }

    public boolean hasIssuedLicenses() {
        return !repository.list(1).isEmpty();
    }

    /**
     * Parses the request so the operator can see what they're about to sign
     * for, including whether a license was already issued for this exact
     * request (e.g. the same email processed twice).
     */
    public Preview preview(String requestText) {
        LicenseRequest request = RequestParser.parse(requireText(requestText));
        List<UUID> previous = new ArrayList<>();
        for (IssuedLicenseRepository.IssuedLicenseRecord record : repository.list(Integer.MAX_VALUE)) {
            if (record.requestId().equals(request.requestId())) {
                previous.add(record.licenseId());
            }
        }
        return new Preview(request, previous);
    }

    public LicenseSigner.Issued issue(IssueCommand command, String operator) {
        Objects.requireNonNull(command, "command");
        LicenseSigner current = currentSigner(); // fail before validating input if nothing can sign
        LicenseRequest request = RequestParser.parse(requireText(command.requestText()));

        String customer = command.customer() == null || command.customer().isBlank()
                ? request.organization()
                : command.customer().strip();
        if (customer == null || customer.isBlank()) {
            throw new IssueValidationException("customer name is required (the request has no organization either)");
        }

        Set<String> modules = new LinkedHashSet<>();
        for (String module : command.modules() == null ? List.<String>of() : command.modules()) {
            String id = module == null ? "" : module.strip();
            if (id.isEmpty()) {
                continue;
            }
            if (!MODULE_ID.matcher(id).matches()) {
                throw new IssueValidationException("invalid module id '" + id
                        + "': use lowercase letters, digits, '.', '_' or '-'");
            }
            modules.add(id);
        }
        if (modules.isEmpty()) {
            throw new IssueValidationException("at least one module is required");
        }
        if (command.maxUsers() < 1 || command.maxUsers() > MAX_USERS) {
            throw new IssueValidationException("maxUsers must be between 1 and " + MAX_USERS);
        }
        if (command.validityDays() < 1 || command.validityDays() > MAX_VALIDITY_DAYS) {
            throw new IssueValidationException("validityDays must be between 1 and " + MAX_VALIDITY_DAYS);
        }

        IssueDecision decision = new IssueDecision(customer, modules, command.maxUsers(),
                Duration.ofDays(command.validityDays()));
        LicenseSigner.Issued issued = current.issue(request, decision);
        repository.record(issued.payload(), issued.armoredLicenseText(), operator);
        return issued;
    }

    private LicenseSigner currentSigner() {
        return signer.get().orElseThrow(() -> new NoActiveKeyException(
                "no active signing key - an administrator has to create and activate one first"));
    }

    public List<IssuedLicenseRepository.IssuedLicenseRecord> history(int limit) {
        return repository.list(Math.max(1, Math.min(limit, 1000)));
    }

    public Optional<String> licenseText(UUID licenseId) {
        return repository.licenseText(licenseId);
    }

    private static String requireText(String requestText) {
        if (requestText == null || requestText.isBlank()) {
            throw new IssueValidationException("request text is empty - paste the whole block from the email, "
                    + "including the BEGIN/END LICENSE REQUEST lines");
        }
        return requestText;
    }

    public record IssueCommand(String requestText, String customer, List<String> modules,
                               int maxUsers, int validityDays) {
    }

    public record Preview(LicenseRequest request, List<UUID> previouslyIssuedLicenseIds) {
    }

    public record SigningKeyInfo(String keyId, String publicKeyBase64, String trustedKeysLine) {
    }

    public static final class IssueValidationException extends IllegalArgumentException {
        public IssueValidationException(String message) {
            super(message);
        }
    }

    public static final class NoActiveKeyException extends IllegalStateException {
        public NoActiveKeyException(String message) {
            super(message);
        }
    }
}
