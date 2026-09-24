package work.archaic.sqlite.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import work.archaic.service.sqlite.v01.Checkpoint;
import work.archaic.service.sqlite.v01.Database;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.sqlite.v01.Work;

final class LocalDatabase implements Database {
    private final SqliteConnection writer;
    private final ArrayBlockingQueue<SqliteConnection> available;
    private final List<SqliteConnection> all;
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
            if (!"wal".equalsIgnoreCase(writer.oneString("PRAGMA journal_mode=WAL")))
                throw new SqliteException("Database did not enter WAL mode");
            writer.execute("PRAGMA foreign_keys=ON");
            if (writer.oneLong("PRAGMA foreign_keys") != 1)
                throw new SqliteException("Foreign keys are unavailable on writer");
            for (int i = 0; i < readers; i++) {
                var reader = Native.open(file, true, false);
                all.add(reader);
                reader.execute("PRAGMA foreign_keys=ON");
                if (reader.oneLong("PRAGMA foreign_keys") != 1)
                    throw new SqliteException("Foreign keys are unavailable on reader");
                available.add(reader);
            }
        } catch (Throwable failure) {
            for (var connection : all) {
                try { connection.close(); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            if (failure instanceof SqliteException e) throw e;
            throw failure;
        }
    }

    @Override public <T, X extends Throwable> T read(Work<T, X> work) throws SqliteException, X {
        Objects.requireNonNull(work);
        SqliteConnection connection;
        enter();
        try {
            connection = available.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
            if (connection == null) throw new SqliteException("Timed out waiting for a reader");
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

    @Override public <T, X extends Throwable> T write(Work<T, X> work) throws SqliteException, X {
        Objects.requireNonNull(work);
        enter();
        try {
            if (!writerLease.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS))
                throw new SqliteException("Timed out waiting for the writer");
        } catch (InterruptedException e) {
            leave();
            Thread.currentThread().interrupt();
            throw new SqliteException("Interrupted waiting for the writer", e);
        }
        catch (SqliteException e) { leave(); throw e; }
        try { return run(work, writer, true); }
        finally { writerLease.unlock(); leave(); }
    }

    @Override public void backup(Path destination, Duration limit) throws SqliteException, IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(limit);
        if (limit.isNegative() || limit.isZero() || limit.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Backup limit must be positive and at most one day");
        var target = destination.toAbsolutePath();
        if (Files.exists(target)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
        enter();
        SqliteConnection source;
        try {
            source = available.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
            if (source == null) throw new SqliteException("Timed out waiting for a backup reader");
        } catch (InterruptedException e) {
            leave();
            Thread.currentThread().interrupt();
            throw new SqliteException("Interrupted waiting for backup reader", e);
        } catch (SqliteException e) { leave(); throw e; }

        Path temporary = null;
        Throwable failure = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".sqlite-backup-", ".db");
            source.backupTo(temporary, limit);
            // A hard link publishes a complete image atomically and fails if target exists.
            Files.createLink(target, temporary);
        } catch (SqliteException | IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                if (temporary != null) Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            }
            finally { available.add(source); leave(); }
        }
    }

    @Override public Checkpoint checkpoint() throws SqliteException {
        enter();
        try {
            if (!writerLease.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS))
                throw new SqliteException("Timed out waiting for the writer to checkpoint");
        } catch (InterruptedException e) {
            leave();
            Thread.currentThread().interrupt();
            throw new SqliteException("Interrupted waiting to checkpoint", e);
        } catch (SqliteException e) { leave(); throw e; }
        try { return writer.checkpoint(); }
        finally { writerLease.unlock(); leave(); }
    }

    private synchronized void enter() {
        if (closed) throw new IllegalStateException("Database closed");
        active++;
    }

    private synchronized void leave() { active--; }

    private <T, X extends Throwable> T run(Work<T, X> work,
                                           SqliteConnection connection, boolean write)
            throws SqliteException, X {
        connection.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
        var session = new SqliteSession(connection);
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
        SqliteException failure = null;
        for (var connection : all) {
            try { connection.close(); }
            catch (SqliteException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            closed = false;
            throw failure;
        }
    }
}
