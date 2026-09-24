package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestTrail;

/** Current provider behavior; SQLite v01 does not promise idempotent close across providers. */
record RepeatedClose(Sqlite provider) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var directory = Files.createTempDirectory("ffm-sqlite-repeat-close-");
        trail.note("Database directory: " + directory);
        boolean passed = false;
        try {
            var db = provider.open(directory.resolve("test.db"), 1, Duration.ofSeconds(2));
            try {
                db.read(session -> {
                    var statement = session.prepare("SELECT 7");
                    assert statement.step() : "Statement should yield a row";
                    assert statement.longAt(0) == 7 : "First query should return 7";
                    statement.close();
                    statement.close();
                    return null;
                });
                assert db.read(session -> {
                    try (var next = session.prepare("SELECT 8")) {
                        assert next.step() : "Reader must remain usable after repeated statement close";
                        return next.longAt(0);
                    }
                }) == 8 : "Subsequent read should return 8";
            } finally { db.close(); }
            db.close();
            passed = true;
        } finally {
            if (passed) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            } else trail.note("Preserved evidence: " + directory);
        }
    }
    @Override public String toString() { return "FFM SQLite repeated close (provider behavior)"; }
}
