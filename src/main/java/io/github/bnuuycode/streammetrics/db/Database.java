package io.github.bnuuycode.streammetrics.db;

import io.github.bnuuycode.streammetrics.config.AppConfig;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens the SQLite file, brings the schema up to date, and hands back a
 * configured JDBI instance.
 */
public final class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final Jdbi jdbi;
    private final Path path;

    private Database(Jdbi jdbi, Path path) {
        this.jdbi = jdbi;
        this.path = path;
    }

    public static Database initialize(AppConfig config) {
        Path path = config.databasePath().toAbsolutePath();
        createParentDirectory(path);

        SQLiteDataSource dataSource = new SQLiteDataSource(pragmas());
        dataSource.setUrl("jdbc:sqlite:" + path);

        migrate(dataSource);

        // installPlugin enables the SqlObject style: DAOs declared as
        // interfaces with the SQL in an annotation.
        Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());

        log.info("Database ready at {}", path);
        return new Database(jdbi, path);
    }

    /**
     * The three settings from DECISIONS.md § 10. All three are treacherous
     * SQLite defaults, and all three fail quietly rather than loudly.
     */
    private static SQLiteConfig pragmas() {
        SQLiteConfig config = new SQLiteConfig();

        // Write-Ahead Logging: readers and writers stop blocking each other.
        // Without it the scheduler writing a snapshot locks out the HTTP
        // threads trying to read.
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);

        // When the file is briefly locked anyway, wait up to five seconds
        // instead of throwing SQLITE_BUSY at the first collision.
        config.setBusyTimeout(5000);

        // Foreign keys are OFF by default in SQLite, for historical
        // compatibility. Without this line every REFERENCES clause in the
        // schema is pure decoration and ON DELETE CASCADE never fires.
        config.enforceForeignKeys(true);

        return config;
    }

    /**
     * Applies any migration in {@code src/main/resources/db/migration} that has
     * not run yet, in filename order, recording each one so it never runs
     * twice. Safe to call on every startup.
     */
    private static void migrate(SQLiteDataSource dataSource) {
        MigrateResult result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        if (result.migrationsExecuted > 0) {
            log.info("Applied {} migration(s), schema now at version {}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        } else {
            log.info("Schema already up to date");
        }
    }

    private static void createParentDirectory(Path path) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create folder for " + path, e);
        }
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    /**
     * A cheap liveness check: counts the tables actually present in the file.
     * Used by the health endpoint to prove the database is not merely
     * configured but genuinely open and populated with a schema.
     */
    public DatabaseStatus status() {
        int tables = jdbi.withHandle(handle -> handle
                .createQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'")
                .mapTo(Integer.class)
                .one());

        return new DatabaseStatus(path.toString(), tables);
    }

    /** What the health endpoint reports about the database. */
    public record DatabaseStatus(String path, int tableCount) {
    }
}
