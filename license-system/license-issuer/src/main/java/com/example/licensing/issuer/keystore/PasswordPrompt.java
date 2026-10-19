package com.example.licensing.issuer.keystore;

import java.io.Console;
import java.util.Arrays;

/**
 * Reads the keystore password without echoing it to the terminal.
 *
 * <p>When there is no interactive console (stdin/stdout redirected, e.g. in
 * a script or test), falls back to the {@value #ENV_VARIABLE} environment
 * variable and prints a warning: environment variables can leak through
 * process listings, crash dumps and shell history of whoever set them, so
 * that path is meant for automation you have thought about, not everyday use.
 * The password is never accepted as a command-line argument, which would
 * leak the same way and more easily.
 */
public final class PasswordPrompt {

    public static final String ENV_VARIABLE = "LICENSE_KEYSTORE_PASSWORD";

    private PasswordPrompt() {
    }

    /** Reads an existing password. Callers should {@link #wipe} it once the keystore is loaded. */
    public static char[] read(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("%s", prompt);
            if (password == null) {
                throw new IllegalStateException("no password entered");
            }
            return password;
        }
        return fromEnvironment();
    }

    /** Reads a new password: asks twice and enforces the minimum length. */
    public static char[] readNew(String prompt) {
        Console console = System.console();
        if (console == null) {
            char[] password = fromEnvironment();
            requireMinimumLength(password);
            return password;
        }
        while (true) {
            char[] first = console.readPassword("%s", prompt);
            if (first == null) {
                throw new IllegalStateException("no password entered");
            }
            if (first.length < SigningKeyStore.MIN_PASSWORD_LENGTH) {
                console.printf("Password must be at least %d characters.%n", SigningKeyStore.MIN_PASSWORD_LENGTH);
                wipe(first);
                continue;
            }
            char[] second = console.readPassword("Repeat password: ");
            boolean same = second != null && Arrays.equals(first, second);
            wipe(second);
            if (same) {
                return first;
            }
            wipe(first);
            console.printf("Passwords do not match, try again.%n");
        }
    }

    public static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] fromEnvironment() {
        String value = System.getenv(ENV_VARIABLE);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("no interactive console to ask for the keystore password; run "
                    + "this from a terminal, or set " + ENV_VARIABLE + " for non-interactive use");
        }
        System.err.println("WARNING: no interactive console, reading keystore password from " + ENV_VARIABLE);
        return value.toCharArray();
    }

    private static void requireMinimumLength(char[] password) {
        if (password.length < SigningKeyStore.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "keystore password must be at least " + SigningKeyStore.MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
