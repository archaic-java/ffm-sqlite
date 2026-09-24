package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import work.archaic.service.sqlite.v01.Database;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestTrail;

final class FaultFixture {
    private FaultFixture() { }
    @FunctionalInterface interface Check { void run(Path directory) throws Exception; }
    static void run(TestTrail trail, Check check) throws Exception {
        var directory = Files.createTempDirectory("ffm-sqlite-fault-");
        trail.note("Fixture directory: " + directory);
        boolean passed = false;
        try {
            check.run(directory);
            passed = true;
        } finally {
            if (passed) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            } else trail.note("Preserved evidence: " + directory);
        }
    }
    static void create(Database db) throws SqliteException {
        db.write(session -> {
            try (var statement = session.prepare("CREATE TABLE parent (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")) {
                assert !statement.step() : "Parent schema should finish";
            }
            try (var statement = session.prepare(
                    "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id), value TEXT NOT NULL)")) {
                assert !statement.step() : "Child schema should finish";
            }
            return null;
        });
        insert(db, 1, "original");
    }
    static void insert(Database db, long id, String value) throws SqliteException {
        db.write(session -> {
            try (var statement = session.prepare("INSERT INTO parent VALUES (?, ?)")) {
                statement.bind(1, id).bind(2, value);
                assert !statement.step() : "Parent insert should finish";
            }
            try (var statement = session.prepare("INSERT INTO child VALUES (?, ?, ?)")) {
                statement.bind(1, id).bind(2, id).bind(3, value);
                assert !statement.step() : "Child insert should finish";
            }
            return null;
        });
    }
    static String rows(Database db) throws SqliteException {
        return db.read(session -> {
            var rows = new StringBuilder();
            try (var statement = session.prepare(
                    "SELECT p.id, p.name, c.value FROM parent p JOIN child c ON c.parent_id = p.id ORDER BY p.id")) {
                while (statement.step()) rows.append(statement.longAt(0)).append(':')
                        .append(statement.stringAt(1)).append(':').append(statement.stringAt(2)).append(';');
            }
            return rows.toString();
        });
    }
}

record IndependentWriterLock(Sqlite provider) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        FaultFixture.run(trail, directory -> {
            var file = directory.resolve("test.db");
            try (var first = provider.open(file, 1, Duration.ofSeconds(2));
                 var second = provider.open(file, 1, Duration.ofSeconds(2))) {
                FaultFixture.create(first);
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                    var lock = tasks.submit(() -> first.write(session -> {
                        try (var statement = session.prepare("INSERT INTO parent VALUES (2, 'held')")) {
                            assert !statement.step() : "Pending parent should be inserted";
                        }
                        held.countDown();
                        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Lock holder not released");
                        return true;
                    }));
                    var replay = new AtomicInteger();
                    try {
                        assert held.await(3, TimeUnit.SECONDS) : "First owner should hold write lock";
                        try {
                            second.write(session -> {
                                replay.incrementAndGet();
                                return null;
                            });
                            throw new AssertionError("Second owner should report SQLITE_BUSY");
                        } catch (SqliteException expected) {
                            assert (expected.code() & 0xff) == 5 : "Lock failure should report SQLITE_BUSY";
                        }
                        assert replay.get() == 0 : "Busy callback must not start or be replayed";
                        assert FaultFixture.rows(second).equals("1:original:original;")
                                : "Busy attempt must leave original committed rows";
                    } finally { release.countDown(); }
                    assert lock.get(3, TimeUnit.SECONDS) : "First owner should finish";
                    assert replay.get() == 0 : "Failed callback must never run after lock release";
                }
                FaultFixture.insert(second, 3, "recovered");
                assert FaultFixture.rows(second).equals("1:original:original;3:recovered:recovered;")
                        : "Next writer should succeed without half rows or replay";
            }
        });
    }
    @Override public String toString() { return "FFM SQLite independent writer lock failure and reuse"; }
}

record BackupFaultCleanup(Sqlite provider) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        FaultFixture.run(trail, directory -> {
            var file = directory.resolve("test.db");
            try (var db = provider.open(file, 1, Duration.ofSeconds(2))) {
                FaultFixture.create(db);
                var destination = directory.resolve("backup.db");
                var previous = new byte[] {10, 20, 30, 40};
                Files.write(destination, previous);
                try {
                    db.backup(destination, Duration.ofSeconds(2));
                    throw new AssertionError("Backup must refuse existing target");
                } catch (java.nio.file.FileAlreadyExistsException expected) { /* preserved */ }
                assert Arrays.equals(Files.readAllBytes(destination), previous)
                        : "Rejected backup must leave destination byte-for-byte unchanged";
                var missing = directory.resolve("missing-parent").resolve("backup.db");
                try {
                    db.backup(missing, Duration.ofSeconds(2));
                    throw new AssertionError("Missing parent must fail backup");
                } catch (java.io.IOException expected) { /* cannot create temporary file */ }
                assert !Files.exists(missing) : "Failed backup must not publish target";
                try (var listing = Files.list(directory)) {
                    assert listing.noneMatch(p -> p.getFileName().toString().startsWith(".sqlite-backup-"))
                            : "Failed backup must remove temporary images";
                }
                assert FaultFixture.rows(db).equals("1:original:original;")
                        : "Sole reader must be returned after backup failure";
                Files.delete(destination);
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                    var writer = tasks.submit(() -> db.write(session -> {
                        try (var parent = session.prepare("INSERT INTO parent VALUES (2, 'pending')")) {
                            assert !parent.step() : "Pending parent should be inserted";
                        }
                        try (var child = session.prepare("INSERT INTO child VALUES (2, 2, 'pending')")) {
                            assert !child.step() : "Pending child should be inserted";
                        }
                        entered.countDown();
                        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Writer not released");
                        return true;
                    }));
                    try {
                        assert entered.await(3, TimeUnit.SECONDS) : "Writer should enter transaction";
                        db.backup(destination, Duration.ofSeconds(2));
                        try (var reopened = provider.open(destination, 1, Duration.ofSeconds(2))) {
                            assert FaultFixture.rows(reopened).equals("1:original:original;")
                                    : "Backup must contain only coherent committed parent/child rows";
                            assert reopened.read(session -> {
                                try (var query = session.prepare("PRAGMA foreign_key_check")) {
                                    return !query.step();
                                }
                            }) : "Backup should retain valid foreign keys";
                        }
                    } finally { release.countDown(); }
                    assert writer.get(3, TimeUnit.SECONDS) : "Writer should commit after backup";
                }
                assert FaultFixture.rows(db).equals("1:original:original;2:pending:pending;")
                        : "Next read should see complete committed transaction";
            }
        });
    }
    @Override public String toString() { return "FFM SQLite backup publication and failure cleanup"; }
}

record NativeHandleCycles(Sqlite provider) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        FaultFixture.run(trail, directory -> {
            var file = directory.resolve("test.db");
            for (int cycle = 0; cycle < 12; cycle++) {
                try {
                    provider.open(directory.resolve("nonexistent").resolve("invalid.db"), 1,
                            Duration.ofSeconds(2));
                    throw new AssertionError("Invalid parent must fail initialization");
                } catch (SqliteException expected) { /* SQLite CANTOPEN */ }
                try (var db = provider.open(file, 1, Duration.ofSeconds(2))) {
                    for (int i = 0; i < 20; i++) db.read(session -> {
                        try (var statement = session.prepare("SELECT 1")) {
                            assert statement.step() : "Repeated prepare should yield a row";
                            assert statement.longAt(0) == 1 : "Repeated query should return 1";
                        }
                        return null;
                    });
                }
            }
            try (var fds = Files.list(Path.of("/proc/self/fd"))) {
                var handles = fds.filter(fd -> {
                    try { return Files.readSymbolicLink(fd).toString().startsWith(directory.toString()); }
                    catch (java.io.IOException ignored) { return false; } // fd may close during enumeration
                }).toList();
                trail.note("Open FDs into fixture after close: " + handles);
                assert handles.isEmpty() : "No native database file descriptors may remain after cycles";
            }
            try (var db = provider.open(file, 1, Duration.ofSeconds(2))) {
                assert db.read(session -> {
                    try (var statement = session.prepare("SELECT 91")) {
                        assert statement.step() : "Next open should remain usable";
                        return statement.longAt(0);
                    }
                }) == 91 : "Subsequent open/read should work";
            }
        });
    }
    @Override public String toString() { return "FFM SQLite failed opens and native handle cycles"; }
}

record BackupDeadline(Sqlite provider) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        FaultFixture.run(trail, directory -> {
            var file = directory.resolve("test.db");
            var target = directory.resolve("backup.db");
            try (var db = provider.open(file, 1, Duration.ofSeconds(2))) {
                FaultFixture.create(db);
                db.write(session -> {
                    try (var create = session.prepare("CREATE TABLE payload (data BLOB)")) {
                        assert !create.step() : "Payload schema should finish";
                    }
                    try (var insert = session.prepare("INSERT INTO payload VALUES (?)")) {
                        insert.bind(1, new byte[1 << 20]);
                        assert !insert.step() : "Multi-page payload should be inserted";
                    }
                    return null;
                });
                try {
                    db.backup(target, Duration.ofNanos(1));
                    throw new AssertionError("One-nanosecond backup budget should expire before multi-page copy");
                } catch (SqliteException expected) {
                    assert expected.getMessage().contains("time limit") : "Backup deadline should report limit";
                }
                assert !Files.exists(target) : "Failed deadline must not publish partial backup";
                try (var listing = Files.list(directory)) {
                    assert listing.noneMatch(p -> p.getFileName().toString().startsWith(".sqlite-backup-"))
                            : "Timed-out backup must remove temporary image";
                }
                assert FaultFixture.rows(db).equals("1:original:original;")
                        : "Sole reader should remain usable after backup deadline";
                db.backup(target, Duration.ofSeconds(2));
                try (var reopened = provider.open(target, 1, Duration.ofSeconds(2))) {
                    assert FaultFixture.rows(reopened).equals("1:original:original;")
                            : "Next backup should publish complete original rows";
                    assert reopened.read(session -> {
                        try (var statement = session.prepare("SELECT length(data) FROM payload")) {
                            assert statement.step() : "Payload should be present";
                            return statement.longAt(0);
                        }
                    }) == 1 << 20 : "Next backup should contain full multi-page payload";
                }
            }
        });
    }
    @Override public String toString() { return "FFM SQLite backup deadline cleanup and reuse"; }
}
