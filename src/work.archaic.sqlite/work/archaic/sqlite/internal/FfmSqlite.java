package work.archaic.sqlite.internal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import work.archaic.service.sqlite.v01.Database;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.sqlite.v01.SqliteException;

/** Linux provider backed by libsqlite3.so.0. */
public final class FfmSqlite implements Sqlite {
    public FfmSqlite() { }

    @Override public Database open(Path file, int readers, Duration wait) throws SqliteException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(wait);
        if (readers < 1) throw new IllegalArgumentException("At least one reader is required");
        if (wait.isNegative() || wait.isZero() || wait.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Wait must be positive and at most one day");
        Native.validateLibrary();
        return new LocalDatabase(file.toAbsolutePath(), readers, wait);
    }
}
