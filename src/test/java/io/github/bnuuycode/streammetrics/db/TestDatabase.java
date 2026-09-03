package io.github.bnuuycode.streammetrics.db;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

/**
 * A throwaway database for tests, built the same way the real one is.
 *
 * <p>Backed by a file in a temporary folder rather than SQLite's {@code :memory:}
 * mode. In-memory reads better but behaves differently in the way that matters
 * here: every connection to {@code :memory:} gets its own empty database, so
 * Flyway would migrate one and the repository under test would read another. The
 * data source opens a connection per call, so that trap is real.
 *
 * <p>A temporary file also keeps the settings honest. WAL and foreign keys are
 * applied exactly as in production, which means a migration that breaks a
 * reference fails here rather than on a live vault.
 *
 * <p>Nothing here can reach {@code data/dev.db}: the path comes from JUnit's
 * {@code @TempDir}, and JUnit deletes the folder when the test ends.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    /** A migrated, empty database inside {@code folder}. */
    public static Jdbi freshIn(Path folder) {
        SQLiteConfig pragmas = new SQLiteConfig();
        pragmas.setJournalMode(SQLiteConfig.JournalMode.WAL);
        pragmas.setBusyTimeout(5000);
        pragmas.enforceForeignKeys(true);

        SQLiteDataSource dataSource = new SQLiteDataSource(pragmas);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("test.db"));

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        return Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
    }
}
