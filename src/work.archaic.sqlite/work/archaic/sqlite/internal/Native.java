package work.archaic.sqlite.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import work.archaic.service.sqlite.v01.SqliteException;

/** Internal FFM boundary. Native pointers never escape this package. */
final class Native {
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup("libsqlite3.so.0", Arena.global());
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN = fn("sqlite3_open_v2", INT, PTR, PTR, INT, PTR);
    static final MethodHandle CLOSE = fn("sqlite3_close", INT, PTR);
    static final MethodHandle PREPARE = fn("sqlite3_prepare_v2", INT, PTR, PTR, INT, PTR, PTR);
    static final MethodHandle STEP = fn("sqlite3_step", INT, PTR);
    static final MethodHandle FINALIZE = fn("sqlite3_finalize", INT, PTR);
    static final MethodHandle MESSAGE = fn("sqlite3_errmsg", PTR, PTR);
    private static final MethodHandle BUSY_TIMEOUT = fn("sqlite3_busy_timeout", INT, PTR, INT);
    private static final MethodHandle THREADSAFE = fn("sqlite3_threadsafe", INT);
    static final MethodHandle ERR_CODE = fn("sqlite3_extended_errcode", INT, PTR);
    static final MethodHandle BACKUP_INIT = fn("sqlite3_backup_init", PTR, PTR, PTR, PTR, PTR);
    static final MethodHandle BACKUP_STEP = fn("sqlite3_backup_step", INT, PTR, INT);
    static final MethodHandle BACKUP_FINISH = fn("sqlite3_backup_finish", INT, PTR);
    static final MethodHandle CHECKPOINT = fn("sqlite3_wal_checkpoint_v2", INT, PTR, PTR, INT, PTR, PTR);
    static final MethodHandle INTERRUPT = LINKER.downcallHandle(
            LIBRARY.find("sqlite3_interrupt").orElseThrow(), FunctionDescriptor.ofVoid(PTR));
    static final MethodHandle BIND_LONG = fn("sqlite3_bind_int64", INT, PTR, INT, LONG);
    static final MethodHandle BIND_DOUBLE = fn("sqlite3_bind_double", INT, PTR, INT, DOUBLE);
    static final MethodHandle BIND_TEXT = fn("sqlite3_bind_text", INT, PTR, INT, PTR, INT, PTR);
    static final MethodHandle BIND_BLOB = fn("sqlite3_bind_blob", INT, PTR, INT, PTR, INT, PTR);
    static final MethodHandle BIND_NULL = fn("sqlite3_bind_null", INT, PTR, INT);
    static final MethodHandle COLUMN_COUNT = fn("sqlite3_column_count", INT, PTR);
    static final MethodHandle COLUMN_TYPE = fn("sqlite3_column_type", INT, PTR, INT);
    static final MethodHandle COLUMN_LONG = fn("sqlite3_column_int64", LONG, PTR, INT);
    static final MethodHandle COLUMN_DOUBLE = fn("sqlite3_column_double", DOUBLE, PTR, INT);
    static final MethodHandle COLUMN_TEXT = fn("sqlite3_column_text", PTR, PTR, INT);
    static final MethodHandle COLUMN_BLOB = fn("sqlite3_column_blob", PTR, PTR, INT);
    static final MethodHandle COLUMN_BYTES = fn("sqlite3_column_bytes", INT, PTR, INT);
    static final int OK = 0, BUSY = 5, LOCKED = 6, ROW = 100, DONE = 101, NULL_VALUE = 5;
    private static final int READONLY = 0x1, READWRITE = 0x2, CREATE = 0x4;
    private static final int FULLMUTEX = 0x00010000, PRIVATECACHE = 0x00040000, EXRESCODE = 0x02000000;
    static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1L);

    private Native() { }

    private static MethodHandle fn(String name, java.lang.foreign.MemoryLayout result,
                                   java.lang.foreign.MemoryLayout... args) {
        return LINKER.downcallHandle(LIBRARY.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }

    static Object call(MethodHandle method, Object... args) {
        try { return method.invokeWithArguments(args); }
        catch (Throwable e) { throw new IllegalStateException("SQLite native call failed", e); }
    }

    static int integer(MethodHandle method, Object... args) { return (int) call(method, args); }
    static MemorySegment pointer(MethodHandle method, Object... args) {
        return (MemorySegment) call(method, args);
    }

    static void validateLibrary() throws SqliteException {
        if (integer(THREADSAFE) == 0)
            throw new SqliteException("SQLite library was built without thread safety");
    }

    static SqliteConnection open(Path path, boolean readonly, boolean create) throws SqliteException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(PTR);
            int flags = readonly ? READONLY : READWRITE | (create ? CREATE : 0);
            int code = integer(OPEN, arena.allocateFrom(path.toString()), out,
                    flags | FULLMUTEX | PRIVATECACHE | EXRESCODE,
                    MemorySegment.NULL);
            MemorySegment handle = out.get(PTR, 0);
            if (code != OK) {
                String message = handle.address() == 0 ? "Could not allocate SQLite connection"
                        : pointer(MESSAGE, handle).reinterpret(4096).getString(0);
                if (handle.address() != 0) integer(CLOSE, handle);
                throw new SqliteException(code, message);
            }
            var connection = new SqliteConnection(handle);
            int timeout = integer(BUSY_TIMEOUT, handle, 50);
            if (timeout != OK) {
                try { connection.close(); } catch (SqliteException suppressed) { /* keep original error */ }
                throw new SqliteException(timeout, "Could not set busy timeout");
            }
            return connection;
        }
    }
}
