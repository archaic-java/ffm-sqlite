package work.archaic.sqlite;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import work.archaic.service.sqlite.v01.Session;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.sqlite.v01.Statement;

/** Internal FFM boundary. Native pointers never escape this package. */
final class Native {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup("libsqlite3.so.0", Arena.global());
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN = fn("sqlite3_open_v2", INT, PTR, PTR, INT, PTR);
    private static final MethodHandle CLOSE = fn("sqlite3_close", INT, PTR);
    private static final MethodHandle PREPARE = fn("sqlite3_prepare_v2", INT, PTR, PTR, INT, PTR, PTR);
    private static final MethodHandle STEP = fn("sqlite3_step", INT, PTR);
    private static final MethodHandle FINALIZE = fn("sqlite3_finalize", INT, PTR);
    private static final MethodHandle MESSAGE = fn("sqlite3_errmsg", PTR, PTR);
    private static final MethodHandle BUSY_TIMEOUT = fn("sqlite3_busy_timeout", INT, PTR, INT);
    private static final MethodHandle BIND_LONG = fn("sqlite3_bind_int64", INT, PTR, INT, LONG);
    private static final MethodHandle BIND_DOUBLE = fn("sqlite3_bind_double", INT, PTR, INT, DOUBLE);
    private static final MethodHandle BIND_TEXT = fn("sqlite3_bind_text", INT, PTR, INT, PTR, INT, PTR);
    private static final MethodHandle BIND_BLOB = fn("sqlite3_bind_blob", INT, PTR, INT, PTR, INT, PTR);
    private static final MethodHandle BIND_NULL = fn("sqlite3_bind_null", INT, PTR, INT);
    private static final MethodHandle COLUMN_COUNT = fn("sqlite3_column_count", INT, PTR);
    private static final MethodHandle COLUMN_TYPE = fn("sqlite3_column_type", INT, PTR, INT);
    private static final MethodHandle COLUMN_LONG = fn("sqlite3_column_int64", LONG, PTR, INT);
    private static final MethodHandle COLUMN_DOUBLE = fn("sqlite3_column_double", DOUBLE, PTR, INT);
    private static final MethodHandle COLUMN_TEXT = fn("sqlite3_column_text", PTR, PTR, INT);
    private static final MethodHandle COLUMN_BLOB = fn("sqlite3_column_blob", PTR, PTR, INT);
    private static final MethodHandle COLUMN_BYTES = fn("sqlite3_column_bytes", INT, PTR, INT);
    private static final int OK = 0, ROW = 100, DONE = 101, NULL = 5;
    private static final int READONLY = 0x1, READWRITE = 0x2, CREATE = 0x4, EXRESCODE = 0x02000000;
    private static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1L);

    private Native() { }

    private static MethodHandle fn(String name, java.lang.foreign.MemoryLayout result,
                                   java.lang.foreign.MemoryLayout... args) {
        return LINKER.downcallHandle(LIBRARY.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }

    private static Object call(MethodHandle method, Object... args) {
        try { return method.invokeWithArguments(args); }
        catch (Throwable e) { throw new IllegalStateException("SQLite native call failed", e); }
    }

    private static int integer(MethodHandle method, Object... args) { return (int) call(method, args); }
    private static MemorySegment pointer(MethodHandle method, Object... args) {
        return (MemorySegment) call(method, args);
    }

    static Connection open(Path path, boolean readonly, boolean create) throws SqliteException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(PTR);
            int flags = readonly ? READONLY : READWRITE | (create ? CREATE : 0);
            int code = integer(OPEN, arena.allocateFrom(path.toString()), out, flags | EXRESCODE,
                    MemorySegment.NULL);
            MemorySegment handle = out.get(PTR, 0);
            if (code != OK) {
                String message = handle.address() == 0 ? "Could not allocate SQLite connection"
                        : pointer(MESSAGE, handle).reinterpret(4096).getString(0);
                if (handle.address() != 0) integer(CLOSE, handle);
                throw new SqliteException(code, message);
            }
            var connection = new Connection(handle);
            int timeout = integer(BUSY_TIMEOUT, handle, 50);
            if (timeout != OK) {
                try { connection.close(); } catch (SqliteException suppressed) { /* keep original error */ }
                throw new SqliteException(timeout, "Could not set busy timeout");
            }
            return connection;
        }
    }

    static final class Connection {
        private MemorySegment handle;
        Connection(MemorySegment handle) { this.handle = handle; }

        SqliteException error(int code) {
            return new SqliteException(code, pointer(MESSAGE, handle).reinterpret(4096).getString(0));
        }

        StatementImpl prepare(String sql) throws SqliteException {
            if (handle == null) throw new IllegalStateException("Connection closed");
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(PTR);
                MemorySegment tail = arena.allocate(PTR);
                MemorySegment source = arena.allocateFrom(sql);
                int code = integer(PREPARE, handle, source, -1, out, tail);
                if (code != OK) throw error(code);
                MemorySegment statement = out.get(PTR, 0);
                if (statement.address() == 0) throw new IllegalArgumentException("Empty SQL statement");
                // A second statement in the input is never silently discarded.
                String remaining = tail.get(PTR, 0).reinterpret(source.byteSize()).getString(0).strip();
                if (!remaining.isEmpty()) {
                    integer(FINALIZE, statement);
                    throw new IllegalArgumentException("Expected exactly one SQL statement");
                }
                return new StatementImpl(this, statement);
            }
        }

        void execute(String sql) throws SqliteException {
            try (var statement = prepare(sql)) {
                while (statement.step()) { /* PRAGMAs may return a row. */ }
            }
        }

        void close() throws SqliteException {
            if (handle == null) return;
            int code = integer(CLOSE, handle);
            if (code != OK) throw error(code);
            handle = null;
        }
    }

    static final class Scope implements Session {
        private final Connection connection;
        private final List<StatementImpl> statements = new ArrayList<>();
        private boolean closed;
        Scope(Connection connection) { this.connection = connection; }

        @Override public Statement prepare(String sql) throws SqliteException {
            if (closed) throw new IllegalStateException("Session expired");
            var statement = connection.prepare(sql);
            statements.add(statement);
            return statement;
        }

        void close() throws SqliteException {
            if (closed) return;
            closed = true;
            SqliteException failure = null;
            for (var statement : statements) {
                try { statement.close(); }
                catch (SqliteException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }

    static final class StatementImpl implements Statement {
        private final Connection connection;
        private MemorySegment handle;
        private boolean row;
        private boolean done;
        StatementImpl(Connection connection, MemorySegment handle) {
            this.connection = connection;
            this.handle = handle;
        }

        private void active() { if (handle == null) throw new IllegalStateException("Statement closed"); }
        private void binding(int code) throws SqliteException { if (code != OK) throw connection.error(code); }
        @Override public Statement bind(int index, long value) throws SqliteException {
            active(); binding(integer(BIND_LONG, handle, index, value)); return this;
        }
        @Override public Statement bind(int index, double value) throws SqliteException {
            active(); binding(integer(BIND_DOUBLE, handle, index, value)); return this;
        }
        @Override public Statement bind(int index, String value) throws SqliteException {
            if (value == null) return bindNull(index);
            active();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bytes = arena.allocateFrom(value);
                binding(integer(BIND_TEXT, handle, index, bytes, Math.toIntExact(bytes.byteSize() - 1), TRANSIENT));
            }
            return this;
        }
        @Override public Statement bind(int index, byte[] value) throws SqliteException {
            if (value == null) return bindNull(index);
            active();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bytes = arena.allocate(Math.max(1, value.length));
                MemorySegment.copy(value, 0, bytes, ValueLayout.JAVA_BYTE, 0, value.length);
                binding(integer(BIND_BLOB, handle, index, bytes, value.length, TRANSIENT));
            }
            return this;
        }
        @Override public Statement bindNull(int index) throws SqliteException {
            active(); binding(integer(BIND_NULL, handle, index)); return this;
        }
        @Override public boolean step() throws SqliteException {
            active();
            if (done) throw new IllegalStateException("Statement completed");
            int code = integer(STEP, handle);
            row = code == ROW;
            done = code == DONE;
            if (row) return true;
            if (done) return false;
            throw connection.error(code);
        }
        @Override public int columns() { active(); return integer(COLUMN_COUNT, handle); }
        private void column(int index) {
            active();
            if (!row) throw new IllegalStateException("No current row");
            if (index < 0 || index >= columns()) throw new IndexOutOfBoundsException(index);
        }
        @Override public boolean isNull(int index) {
            column(index); return integer(COLUMN_TYPE, handle, index) == NULL;
        }
        @Override public long longAt(int index) {
            column(index); return (long) call(COLUMN_LONG, handle, index);
        }
        @Override public double doubleAt(int index) {
            column(index); return (double) call(COLUMN_DOUBLE, handle, index);
        }
        @Override public String stringAt(int index) {
            column(index);
            if (isNull(index)) return null;
            MemorySegment text = pointer(COLUMN_TEXT, handle, index);
            int size = integer(COLUMN_BYTES, handle, index);
            return new String(text.reinterpret(size).toArray(ValueLayout.JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
        }
        @Override public byte[] bytesAt(int index) {
            column(index);
            if (isNull(index)) return null;
            int size = integer(COLUMN_BYTES, handle, index);
            if (size == 0) return new byte[0];
            return pointer(COLUMN_BLOB, handle, index).reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        }
        @Override public void close() throws SqliteException {
            if (handle == null) return;
            int code = integer(FINALIZE, handle);
            handle = null;
            if (code != OK) throw connection.error(code);
        }
    }
}
