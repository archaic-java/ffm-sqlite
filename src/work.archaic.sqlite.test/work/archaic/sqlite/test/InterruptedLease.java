package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestTrail;

/** Current Linux provider behavior for an interrupted wait; v01 does not specify interruption. */
record InterruptedLease(Sqlite provider, boolean writer) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var directory = Files.createTempDirectory("ffm-sqlite-interrupted-lease-");
        trail.note("Database directory: " + directory);
        boolean passed = false;
        try {
            try (var db = provider.open(directory.resolve("test.db"), 1, Duration.ofSeconds(2));
                 var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                var acquired = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var attempting = new CountDownLatch(1);
                var waitingThread = new AtomicReference<Thread>();
                var callbackRan = new AtomicBoolean();
                var holder = tasks.submit(() -> {
                    if (writer) db.write(session -> {
                        acquired.countDown();
                        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Writer holder was not released");
                        return null;
                    });
                    else db.read(session -> {
                        acquired.countDown();
                        if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Reader holder was not released");
                        return null;
                    });
                    return true;
                });
                try {
                    assert acquired.await(3, TimeUnit.SECONDS) : "Sole lease should be held";
                    var waiter = tasks.submit(() -> {
                        waitingThread.set(Thread.currentThread());
                        attempting.countDown();
                        try {
                            if (writer) db.write(session -> { callbackRan.set(true); return null; });
                            else db.read(session -> { callbackRan.set(true); return null; });
                            throw new AssertionError("Interrupted waiter must not acquire lease");
                        } catch (SqliteException expected) {
                            assert expected.getCause() instanceof InterruptedException
                                    : "Provider should preserve interrupted wait as cause";
                            assert Thread.currentThread().isInterrupted()
                                    : "Provider should restore waiter interrupt status";
                        }
                        return true;
                    });
                    assert attempting.await(3, TimeUnit.SECONDS) : "Waiter should attempt lease";
                    waitingThread.get().interrupt();
                    assert waiter.get(3, TimeUnit.SECONDS) : "Interrupted waiter should finish";
                    assert !callbackRan.get() : "Interrupted waiter callback must never run";
                } finally { release.countDown(); }
                assert holder.get(3, TimeUnit.SECONDS) : "Holder should release its lease";
                if (writer) db.write(session -> {
                    try (var statement = session.prepare("CREATE TABLE recovered (n INTEGER)")) {
                        assert !statement.step() : "Next writer should remain usable";
                    }
                    return null;
                });
                else assert db.read(session -> {
                    try (var statement = session.prepare("SELECT 81")) {
                        assert statement.step() : "Next reader should remain usable";
                        return statement.longAt(0);
                    }
                }) == 81 : "Interrupted waiter must not consume sole reader";
            }
            passed = true;
        } finally {
            if (passed) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            } else trail.note("Preserved evidence: " + directory);
        }
    }
    @Override public String toString() {
        return writer ? "FFM SQLite interrupted writer wait" : "FFM SQLite interrupted reader wait";
    }
}
