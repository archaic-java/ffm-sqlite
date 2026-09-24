package work.archaic.sqlite.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.sqlite.v01.Statement;

final class SqliteStatement implements Statement {
    private final SqliteConnection connection;
    private MemorySegment handle;
    private boolean row;
    private boolean done;
    SqliteStatement(SqliteConnection connection, MemorySegment handle) {
        this.connection = connection;
        this.handle = handle;
    }

    private void active() { if (handle == null) throw new IllegalStateException("Statement closed"); }
    private void binding(int code) throws SqliteException { if (code != Native.OK) throw connection.error(code); }
    @Override public Statement bind(int index, long value) throws SqliteException {
        active(); binding(Native.integer(Native.BIND_LONG, handle, index, value)); return this;
    }
    @Override public Statement bind(int index, double value) throws SqliteException {
        active(); binding(Native.integer(Native.BIND_DOUBLE, handle, index, value)); return this;
    }
    @Override public Statement bind(int index, String value) throws SqliteException {
        if (value == null) return bindNull(index);
        active();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(value);
            binding(Native.integer(Native.BIND_TEXT, handle, index, bytes, Math.toIntExact(bytes.byteSize() - 1), Native.TRANSIENT));
        }
        return this;
    }
    @Override public Statement bind(int index, byte[] value) throws SqliteException {
        if (value == null) return bindNull(index);
        active();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(Math.max(1, value.length));
            MemorySegment.copy(value, 0, bytes, ValueLayout.JAVA_BYTE, 0, value.length);
            binding(Native.integer(Native.BIND_BLOB, handle, index, bytes, value.length, Native.TRANSIENT));
        }
        return this;
    }
    @Override public Statement bindNull(int index) throws SqliteException {
        active(); binding(Native.integer(Native.BIND_NULL, handle, index)); return this;
    }
    @Override public boolean step() throws SqliteException {
        active();
        if (done) throw new IllegalStateException("Statement completed");
        int code = Native.integer(Native.STEP, handle);
        row = code == Native.ROW;
        done = code == Native.DONE;
        if (row) return true;
        if (done) return false;
        done = true;
        throw connection.error(code);
    }
    @Override public int columns() { active(); return Native.integer(Native.COLUMN_COUNT, handle); }
    private void column(int index) {
        active();
        if (!row) throw new IllegalStateException("No current row");
        if (index < 0 || index >= columns()) throw new IndexOutOfBoundsException(index);
    }
    @Override public boolean isNull(int index) {
        column(index); return Native.integer(Native.COLUMN_TYPE, handle, index) == Native.NULL_VALUE;
    }
    @Override public long longAt(int index) {
        column(index); return (long) Native.call(Native.COLUMN_LONG, handle, index);
    }
    @Override public double doubleAt(int index) {
        column(index); return (double) Native.call(Native.COLUMN_DOUBLE, handle, index);
    }
    @Override public String stringAt(int index) {
        column(index);
        if (isNull(index)) return null;
        MemorySegment text = Native.pointer(Native.COLUMN_TEXT, handle, index);
        int size = Native.integer(Native.COLUMN_BYTES, handle, index);
        return new String(text.reinterpret(size).toArray(ValueLayout.JAVA_BYTE), java.nio.charset.StandardCharsets.UTF_8);
    }
    @Override public byte[] bytesAt(int index) {
        column(index);
        if (isNull(index)) return null;
        int size = Native.integer(Native.COLUMN_BYTES, handle, index);
        if (size == 0) return new byte[0];
        return Native.pointer(Native.COLUMN_BLOB, handle, index).reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
    }
    @Override public void close() throws SqliteException {
        if (handle == null) return;
        int code = Native.integer(Native.FINALIZE, handle);
        handle = null;
        if (code != Native.OK) throw connection.error(code);
    }
}
