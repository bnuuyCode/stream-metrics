package io.github.bnuuycode.streammetrics.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Properties;

/**
 * Application settings, read from {@code config.properties} in the working
 * directory.
 *
 * <p>That file is git-ignored because it holds the Twitch client secret.
 * {@code config.properties.example} is the versioned copy, with the keys
 * present and the values empty.
 *
 * <p>Every setting has a working default, so the app runs on a fresh clone with
 * no configuration file at all. That matters: nothing is more discouraging than
 * a project that refuses to start before you have filled in six fields.
 */
public record AppConfig(int port, Path databasePath, ZoneId zone, Optional<TwitchConfig> twitch) {

    private static final String FILE_NAME = "config.properties";

    private static final int DEFAULT_PORT = 7000;
    private static final String DEFAULT_DB_PATH = "data/stream-metrics.db";

    /**
     * Which timezone decides where one day ends and the next begins.
     *
     * <p>Not the machine's default on purpose: travelling, or a laptop that
     * guesses wrong, would silently shift the boundary and put two readings on
     * the same calendar day (DECISIONS.md § 6.5).
     */
    private static final String DEFAULT_ZONE = "America/Sao_Paulo";

    /**
     * Twitch application credentials.
     *
     * <p>Wrapped in an {@link Optional} on purpose: the application must start
     * and serve its dashboard before any platform is connected. Absent here
     * means "not configured yet", which the UI reports honestly rather than
     * crashing over.
     */
    public record TwitchConfig(String clientId, String clientSecret, String redirectUri) {
    }

    public static AppConfig load() {
        // Before reading anything: make sure no credential was typed into the
        // template file that gets published. See SecretGuard for why.
        SecretGuard.verifyTemplateIsClean();

        Properties properties = new Properties();

        Path file = Path.of(FILE_NAME);
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                // Failing loudly here is deliberate. A config file that exists
                // but cannot be read means the app would silently fall back to
                // defaults and connect to the wrong database — precisely the
                // kind of quiet wrongness this project is built to avoid.
                throw new IllegalStateException("Could not read " + FILE_NAME, e);
            }
        }

        int port = readInt(properties, "server.port", DEFAULT_PORT);

        return new AppConfig(
                port,
                Path.of(properties.getProperty("database.path", DEFAULT_DB_PATH)),
                ZoneId.of(properties.getProperty("timezone", DEFAULT_ZONE).trim()),
                readTwitch(properties, port)
        );
    }

    private static Optional<TwitchConfig> readTwitch(Properties properties, int port) {
        String clientId = trimmed(properties.getProperty("twitch.clientId"));
        String clientSecret = trimmed(properties.getProperty("twitch.clientSecret"));

        if (clientId == null || clientSecret == null) {
            return Optional.empty();
        }

        // The redirect URI must match what was registered in the Twitch
        // developer console byte for byte, so it is derived from the port
        // rather than typed twice and left to drift apart.
        String redirectUri = properties.getProperty(
                "twitch.redirectUri", "http://localhost:" + port + "/auth/twitch/callback");

        return Optional.of(new TwitchConfig(clientId, clientSecret, redirectUri.trim()));
    }

    private static String trimmed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Setting '" + key + "' must be a number, found: " + raw, e);
        }
    }
}
