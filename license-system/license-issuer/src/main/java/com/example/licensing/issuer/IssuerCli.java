package com.example.licensing.issuer;

import com.example.licensing.common.model.LicenseRequest;
import com.example.licensing.issuer.keystore.PasswordPrompt;
import com.example.licensing.issuer.keystore.SigningKey;
import com.example.licensing.issuer.keystore.SigningKeyStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal interactive issuance tool, meant to be run by hand by whoever
 * handles incoming license request emails.
 *
 * <p>Usage: {@code java -cp ... com.example.licensing.issuer.IssuerCli <keystore.p12> <audit-dir> [kid]}
 *
 * <p>Asks for the keystore password first (without echo), so a wrong
 * password fails before anything is pasted. Then: paste the request text
 * from the email (including the BEGIN/END markers) followed by an empty
 * line, answer a few prompts, and the signed license text is printed to
 * paste into the reply email. Every issuance is recorded in the audit
 * directory.
 *
 * <p>{@code kid} is only needed if the keystore holds more than one key; by
 * convention it holds exactly one (see {@link SigningKeyStore}).
 */
public final class IssuerCli {

    private IssuerCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: IssuerCli <keystore.p12> <audit-dir> [kid]");
            System.exit(2);
        }
        Path keystoreFile = Path.of(args[0]);
        Path auditDir = Path.of(args[1]);
        Optional<String> kid = args.length == 3 ? Optional.of(args[2]) : Optional.empty();

        SigningKey signingKey;
        char[] password = PasswordPrompt.read("Keystore password: ");
        try {
            signingKey = SigningKeyStore.load(keystoreFile, kid, password);
        } catch (SigningKeyStore.SigningKeyStoreException e) {
            System.err.println("Could not load signing key: " + e.getMessage());
            System.exit(1);
            return;
        } finally {
            PasswordPrompt.wipe(password);
        }

        LicenseSigner signer = new LicenseSigner(signingKey, Clock.systemUTC());
        IssuedLicenseRepository repository = new FileIssuedLicenseRepository(auditDir);
        System.out.println("Signing key loaded: kid=" + signer.keyId());
        System.out.println();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        System.out.println("Paste the license request text, then an empty line:");
        String requestText = readUntilBlankLine(in);

        LicenseRequest request;
        try {
            request = RequestParser.parse(requestText);
        } catch (RequestParser.RequestParseException e) {
            System.err.println("Could not parse request: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println();
        System.out.println("Parsed request:");
        System.out.println("  product:        " + request.productId() + " " + request.productVersion());
        System.out.println("  installationId: " + request.installationId());
        System.out.println("  clusterId:      " + request.clusterId());
        System.out.println("  organization:   " + request.organization());
        System.out.println("  requested at:   " + request.createdAt());
        System.out.println();

        String customer = prompt(in, "Customer name for the license [" + request.organization() + "]: ");
        if (customer.isBlank()) {
            customer = request.organization();
        }
        Set<String> modules = new LinkedHashSet<>();
        for (String m : prompt(in, "Modules (comma-separated, e.g. reports,integration-1c): ").split(",")) {
            String trimmed = m.strip();
            if (!trimmed.isEmpty()) {
                modules.add(trimmed);
            }
        }
        int maxUsers = promptInt(in, "Max users: ");
        int validityDays = promptInt(in, "Validity, in days (e.g. 365): ");

        IssueDecision decision = new IssueDecision(customer, modules, maxUsers, Duration.ofDays(validityDays));

        System.out.println();
        System.out.println("About to issue:");
        System.out.println("  customer:  " + decision.customer());
        System.out.println("  modules:   " + decision.modules());
        System.out.println("  maxUsers:  " + decision.maxUsers());
        System.out.println("  validity:  " + validityDays + " days");
        System.out.println("  signed by: kid=" + signer.keyId());
        String confirm = prompt(in, "Confirm? [y/N]: ");
        if (!confirm.strip().equalsIgnoreCase("y")) {
            System.out.println("Aborted.");
            return;
        }

        LicenseSigner.Issued issued = signer.issue(request, decision);
        repository.record(issued.payload(), issued.armoredLicenseText(), System.getProperty("user.name"));

        System.out.println();
        System.out.println("License issued (licenseId " + issued.payload().licenseId()
                + "). Paste this into the reply email:");
        System.out.println();
        System.out.println(issued.armoredLicenseText());
    }

    private static String readUntilBlankLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null && !line.isBlank()) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String prompt(BufferedReader in, String message) throws IOException {
        System.out.print(message);
        System.out.flush();
        String line = in.readLine();
        return line == null ? "" : line;
    }

    private static int promptInt(BufferedReader in, String message) throws IOException {
        String raw = prompt(in, message).strip();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.err.println("Not a number: '" + raw + "'");
            System.exit(1);
            return 0; // unreachable
        }
    }
}
