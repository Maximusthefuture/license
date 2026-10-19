package com.example.licensing.issuer;

import com.example.licensing.issuer.keystore.PasswordPrompt;
import com.example.licensing.issuer.keystore.SigningKey;
import com.example.licensing.issuer.keystore.SigningKeyStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Creates and inspects license-signing keystores.
 *
 * <pre>
 * KeyGenTool generate   &lt;kid&gt; &lt;keystore.p12&gt;     new Ed25519 key in a new password-protected .p12
 * KeyGenTool public-key &lt;keystore.p12&gt; [kid]     print the public key again (needs the password)
 * </pre>
 *
 * <p>Typical first-time setup is to generate two keys - a primary one used
 * for every license, and a reserve one that is never used unless the
 * primary is lost - and to embed both public keys in the client. See the
 * README ("Signing keys") for the full procedure.
 *
 * <p>Only the public key is ever printed. The private key exists only
 * inside the encrypted .p12 file.
 */
public final class KeyGenTool {

    private KeyGenTool() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 3 && args[0].equals("generate")) {
                generate(args[1], Path.of(args[2]));
            } else if ((args.length == 2 || args.length == 3) && args[0].equals("public-key")) {
                printPublicKey(Path.of(args[1]), args.length == 3 ? Optional.of(args[2]) : Optional.empty());
            } else {
                usage();
                System.exit(2);
            }
        } catch (SigningKeyStore.SigningKeyStoreException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void generate(String kid, Path keystoreFile) {
        // fail before asking for a password
        SigningKeyStore.requireValidKeyId(kid);
        if (Files.exists(keystoreFile)) {
            throw new IllegalStateException("refusing to overwrite existing keystore: " + keystoreFile);
        }
        char[] password = PasswordPrompt.readNew(
                "New keystore password (min " + SigningKeyStore.MIN_PASSWORD_LENGTH + " chars): ");
        SigningKey key;
        try {
            key = SigningKeyStore.create(keystoreFile, kid, password);
        } finally {
            PasswordPrompt.wipe(password);
        }

        System.out.println("Created " + keystoreFile.toAbsolutePath() + " with key id '" + key.keyId() + "'.");
        System.out.println();
        printTrustedKeysSnippet(key);
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Add the line above to license-client TrustedKeys.java.");
        System.out.println("  2. Rebuild license-client, run KeyFingerprintTool and put its output into");
        System.out.println("     integrity/StatusMessages.java (PRIMARY_KEY_FINGERPRINT).");
        System.out.println("  3. Back up this .p12 file (at least two offline copies, different places)");
        System.out.println("     and store the password in a password manager - NOT next to the file.");
        System.out.println("     Never commit the .p12 to git or put it in an image, CI or Kubernetes.");
    }

    private static void printPublicKey(Path keystoreFile, Optional<String> kid) {
        char[] password = PasswordPrompt.read("Keystore password: ");
        SigningKey key;
        try {
            key = SigningKeyStore.load(keystoreFile, kid, password);
        } finally {
            PasswordPrompt.wipe(password);
        }
        printTrustedKeysSnippet(key);
    }

    private static void printTrustedKeysSnippet(SigningKey key) {
        String publicKeyBase64 = Base64.getEncoder().encodeToString(key.publicKey().getEncoded());
        System.out.println("Public key for TrustedKeys.java:");
        System.out.println("    \"" + key.keyId() + "\", Base64.getDecoder().decode(\"" + publicKeyBase64 + "\"),");
    }

    private static void usage() {
        System.err.println("usage:");
        System.err.println("  KeyGenTool generate <kid> <keystore.p12>");
        System.err.println("  KeyGenTool public-key <keystore.p12> [kid]");
        System.err.println();
        System.err.println("kid: lowercase letters, digits, '.', '_', '-'  (e.g. 2026-01, 2026-01-reserve)");
        System.err.println("The password is asked interactively; without a console it is read from "
                + PasswordPrompt.ENV_VARIABLE + ".");
    }
}
