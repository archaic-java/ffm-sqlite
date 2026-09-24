package work.archaic.sqlite.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import work.archaic.service.sqlite.v01.Checkpoint;
import work.archaic.service.sqlite.v01.SqliteException;

final class SqliteConnection implements AutoCloseable {
    private MemorySegment handle;
    SqliteConnection(MemorySegment handle) { this.handle = handle; }

    SqliteException error(int code) {
        return new SqliteException(code, Native.pointer(Native.MESSAGE, handle).reinterpret(4096).getString(0));
    }

    SqliteException currentError() { return error(Native.integer(Native.ERR_CODE, handle)); }

    SqliteStatement prepare(String sql) throws SqliteException {
        if (handle == null) throw new IllegalStateException("Connection closed");
        if (sql.indexOf('\0') >= 0) throw new IllegalArgumentException("SQL contains a NUL byte");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(Native.PTR);
            MemorySegment tail = arena.allocate(Native.PTR);
            MemorySegment source = arena.allocateFrom(sql);
            int code = Native.integer(Native.PREPARE, handle, source, -1, out, tail);
            if (code != Native.OK) throw error(code);
            MemorySegment statement = out.get(Native.PTR, 0);
            if (statement.address() == 0) throw new IllegalArgumentException("Empty SQL statement");
            // A second statement in the input is never silently discarded.
            String remaining = tail.get(Native.PTR, 0).reinterpret(source.byteSize()).getString(0).strip();
            if (!remaining.isEmpty()) {
                Native.integer(Native.FINALIZE, statement);
                throw new IllegalArgumentException("Expected exactly one SQL statement");
            }
            return new SqliteStatement(this, statement);
        }
    }

    void execute(String sql) throws SqliteException {
        try (var statement = prepare(sql)) {
            while (statement.step()) { /* PRAGMAs may return a row. */ }
        }
    }

    String oneString(String sql) throws SqliteException {
        try (var statement = prepare(sql)) {
            if (!statement.step()) throw new SqliteException("Expected one row: " + sql);
            return statement.stringAt(0);
        }
    }

    long oneLong(String sql) throws SqliteException {
        try (var statement = prepare(sql)) {
            if (!statement.step()) throw new SqliteException("Expected one row: " + sql);
            return statement.longAt(0);
        }
    }

    Checkpoint checkpoint() throws SqliteException {
        try (Arena arena = Arena.ofConfined()) {
            var log = arena.allocate(Native.INT);
            var completed = arena.allocate(Native.INT);
            int code = Native.integer(Native.CHECKPOINT, handle, arena.allocateFrom("main"), 0, log, completed);
            if (code != Native.OK) throw error(code);
            return new Checkpoint(log.get(Native.INT, 0), completed.get(Native.INT, 0));
        }
    }

    void interrupt() { Native.call(Native.INTERRUPT, handle); }

    void backupTo(Path destination, Duration limit) throws SqliteException {
        try (var target = Native.open(destination, false, true); Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom("main");
            MemorySegment backup = Native.pointer(Native.BACKUP_INIT, target.handle, name, handle, name);
            if (backup.address() == 0) throw target.currentError();
            Throwable failure = null;
            long start = System.nanoTime();
            try {
                for (;;) {
                    if (System.nanoTime() - start >= limit.toNanos())
                        throw new SqliteException("Online backup exceeded its time limit");
                    int code = Native.integer(Native.BACKUP_STEP, backup, 64);
                    if (code == Native.DONE) break;
                    if (code != Native.OK && code != Native.BUSY && code != Native.LOCKED)
                        throw target.error(code);
                    // Release the source read lock between chunks and park the virtual thread.
                    try { Thread.sleep(1); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SqliteException("Online backup interrupted", e);
                    }
                }
            } catch (SqliteException | RuntimeException | Error e) {
                failure = e;
                throw e;
            } finally {
                int finish = Native.integer(Native.BACKUP_FINISH, backup);
                if (finish != Native.OK) {
                    var error = target.error(finish);
                    if (failure == null) throw error;
                    failure.addSuppressed(error);
                }
            }
        }
    }

    @Override public void close() throws SqliteException {
        if (handle == null) return;
        int code = Native.integer(Native.CLOSE, handle);
        if (code != Native.OK) throw error(code);
        handle = null;
    }
}
