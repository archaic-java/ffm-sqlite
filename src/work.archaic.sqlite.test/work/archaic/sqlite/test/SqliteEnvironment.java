package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import java.util.ServiceLoader;
import work.archaic.service.sqlite.v01.Sqlite;

/** Prints the actual SQLite runtime and compile options used by this provider. */
public final class SqliteEnvironment {
    private SqliteEnvironment() { }
    public static void main(String[] args) throws Exception {
        var provider = ServiceLoader.load(Sqlite.class).findFirst().orElseThrow();
        var directory = Files.createTempDirectory("ffm-sqlite-info-");
        try {
            try (var db = provider.open(directory.resolve("test.db"), 1, Duration.ofSeconds(2))) {
                System.out.println("java.version=" + System.getProperty("java.version"));
                System.out.println("os.arch=" + System.getProperty("os.arch"));
                System.out.print(ModelScenarios.configuration(db));
                db.read(session -> {
                    try (var options = session.prepare("PRAGMA compile_options")) {
                        while (options.step()) System.out.println("sqlite.compile_option=" + options.stringAt(0));
                    }
                    return null;
                });
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
