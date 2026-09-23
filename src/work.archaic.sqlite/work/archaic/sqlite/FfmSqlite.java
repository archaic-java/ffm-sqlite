package work.archaic.sqlite;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import work.archaic.service.sqlite.v01.Database;
import work.archaic.service.sqlite.v01.Session;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.sqlite.v01.Work;

/** Linux provider backed by libsqlite3.so.0. */
public final class FfmSqlite implements Sqlite {
    public FfmSqlite() { }

    @Override public Database open(Path file, int readers, Duration wait) throws SqliteException {
        Objects.requireNonNull(file);
        Objects.requireNonNull(wait);
        if (readers < 1) throw new IllegalArgumentException("At least one reader is required");
        if (wait.isNegative() || wait.isZero()) throw new IllegalArgumentException("Wait must be positive");
        return new LocalDatabase(file.toAbsolutePath(), readers, wait);
    }

    private static final class LocalDatabase implements Database {
        private final Native.Connection writer;
        private final ArrayBlockingQueue<Native.Connection> available;
        private final List<Native.Connection> all;
        private final ReentrantLock writerLease = new ReentrantLock(true);
        private final Duration wait;
        private int active;
        private boolean closed;

        LocalDatabase(Path file, int readers, Duration wait) throws SqliteException {
            this.wait = wait;
            all = new ArrayList<>();
            available = new ArrayBlockingQueue<>(readers, true);
            try {
                writer = Native.open(file, false, true);
                all.add(writer);
                writer.execute("PRAGMA journal_mode=WAL");
                writer.execute("PRAGMA foreign_keys=ON");
                for (int i = 0; i < readers; i++) {
                    var reader = Native.open(file, true, false);
                    all.add(reader);
                    reader.execute("PRAGMA foreign_keys=ON");
                    available.add(reader);
                }
            } catch (Throwable failure) {
                for (var connection : all) connection.close();
                if (failure instanceof SqliteException e) throw e;
                throw failure;
            }
        }

        @Override public <T> T read(Work<T> work) throws SqliteException {
            Objects.requireNonNull(work);
            Native.Connection connection;
            enter();
            try {
                connection = available.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
                if (connection == null) throw new SqliteException(5, "Timed out waiting for a reader");
            }
            catch (InterruptedException e) {
                leave();
                Thread.currentThread().interrupt();
                throw new SqliteException("Interrupted waiting for a reader", e);
            }
            catch (SqliteException e) { leave(); throw e; }
            try { return run(work, connection, false); }
            finally { available.add(connection); leave(); }
        }

        @Override public <T> T write(Work<T> work) throws SqliteException {
            Objects.requireNonNull(work);
            enter();
            try {
                if (!writerLease.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS))
                    throw new SqliteException(5, "Timed out waiting for the writer");
            } catch (InterruptedException e) {
                leave();
                Thread.currentThread().interrupt();
                throw new SqliteException("Interrupted waiting for the writer", e);
            }
            catch (SqliteException e) { leave(); throw e; }
            try { return run(work, writer, true); }
            finally { writerLease.unlock(); leave(); }
        }

        private synchronized void enter() {
            if (closed) throw new IllegalStateException("Database closed");
            active++;
        }

        private synchronized void leave() { active--; }

        private <T> T run(Work<T> work, Native.Connection connection, boolean write)
                throws SqliteException {
            connection.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            var session = new Native.Scope(connection);
            try {
                T result = work.run(session);
                session.close();
                connection.execute("COMMIT");
                return result;
            } catch (Throwable failure) {
                try { session.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
                try { connection.execute("ROLLBACK"); }
                catch (Throwable rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                if (failure instanceof SqliteException e) throw e;
                throw failure;
            }
        }

        @Override public synchronized void close() throws SqliteException {
            if (closed) return;
            closed = true;
            // Closing with active leases is a programming error; no native pointer may be freed.
            if (active != 0) {
                closed = false;
                throw new IllegalStateException("Database has active operations");
            }
            for (var connection : all) connection.close();
        }
    }
}
