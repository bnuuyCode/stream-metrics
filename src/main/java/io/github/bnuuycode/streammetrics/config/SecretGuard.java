package io.github.bnuuycode.streammetrics.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Refuses to start if a credential has been typed into the template file.
 *
 * <p>Why this exists: {@code config.properties} and
 * {@code config.properties.example} sit next to each other in every file list,
 * their names differ by one suffix, and only one of them is git-ignored.
 * Nothing on screen says which is which. Putting a secret in the wrong one is
 * not carelessness, it is the predictable result of that design — and the
 * consequence is a credential published to GitHub forever.
 *
 * <p>So the design gets fixed rather than the human blamed. A secret in the
 * template now stops the application dead, before it can reach a commit. It
 * costs ten seconds to correct and closes a hole that is permanent once opened.
 *
 * <p>Same principle as the rest of the project: prefer what fails in your face
 * over what disappears silently.
 */
final class SecretGuard {

    private static final String TEMPLATE = "config.properties.example";

    /**
     * Key fragments that mean "this must never carry a real value here".
     *
     * <p>Kept narrow on purpose: {@code server.port=7000} and
     * {@code database.path=...} are supposed to have values in the template,
     * and a guard that cries wolf is a guard people switch off.
     */
    private static final List<String> SECRET_MARKERS =
            List.of("secret", "clientid", "token", "password");

    private SecretGuard() {
    }

    static void verifyTemplateIsClean() {
        Path template = Path.of(TEMPLATE);
        if (!Files.exists(template)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(template)) {
            properties.load(in);
        } catch (IOException e) {
            // Unreadable template is not worth blocking startup over: it holds
            // no settings the application actually uses.
            return;
        }

        List<String> offenders = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank() && looksSecret(key)) {
                offenders.add(key);
            }
        }

        if (!offenders.isEmpty()) {
            throw new IllegalStateException(buildMessage(offenders));
        }
    }

    private static boolean looksSecret(String key) {
        String normalised = key.toLowerCase(Locale.ROOT);
        return SECRET_MARKERS.stream().anyMatch(normalised::contains);
    }

    private static String buildMessage(List<String> offenders) {
        return """

                =========================================================
                  A credential was found in %s

                  Filled in: %s

                  That file is a TEMPLATE. It is versioned and it goes to
                  GitHub — anything written there becomes public.

                  Fix it:
                    1. Clear those values in %s (leave them empty)
                    2. Put the real values in config.properties instead
                       (that one is git-ignored)
                    3. If the file was already committed and pushed,
                       generate a new secret on the platform — removing it
                       from a later commit does not remove it from history

                  Startup blocked until the template is clean.
                =========================================================
                """.formatted(TEMPLATE, String.join(", ", offenders), TEMPLATE);
    }
}
