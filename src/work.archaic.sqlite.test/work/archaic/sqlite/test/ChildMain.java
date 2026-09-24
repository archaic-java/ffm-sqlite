package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ServiceLoader;
import work.archaic.service.sqlite.v01.Sqlite;

/** Dedicated named-module entry point for supervised SQLite test workloads. */
public final class ChildMain {
    private ChildMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected mode and evidence directory");
        String mode = args[0];
        Path directory = Path.of(args[1]);
        if (mode.equals("malformed")) {
            System.out.println("BROKEN");
            return;
        }
        if (mode.equals("no-ready")) {
            Thread.sleep(3_600_000L);
            return;
        }
        System.out.println("READY");
        System.out.flush();
        if (mode.startsWith("verify@")) {
            verify(Path.of(mode.substring("verify@".length())));
            System.out.println("PROGRESS verified");
            System.out.println("COMPLETED");
            return;
        }
        if (mode.startsWith("crash-")) {
            crashTransfer(mode, directory);
            return;
        }
        switch (mode) {
            case "success" -> {
                System.out.println("PROGRESS success");
                Files.writeString(directory.resolve("completed.txt"), "ok\n");
                System.out.println("COMPLETED");
            }
            case "nonzero" -> {
                System.err.println("Deliberate child failure");
                System.out.println("COMPLETED");
                System.exit(7);
            }
            case "missing" -> System.out.println("PROGRESS missing-completion");
            case "hang" -> {
                System.out.println("PROGRESS waiting");
                System.out.flush();
                Thread.sleep(3_600_000L);
            }
            case "hang-descendant" -> {
                var descendant = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-ea", "--module-path", System.getProperty("jdk.module.path", "out"),
                        "-m", "work.archaic.sqlite.test/work.archaic.sqlite.test.ChildMain",
                        "hang", directory.toString()).start();
                Files.writeString(directory.resolve("descendant.pid"), Long.toString(descendant.pid()));
                System.out.println("PROGRESS descendant-started");
                System.out.flush();
                Thread.sleep(3_600_000L);
            }
            default -> throw new IllegalArgumentException("Unknown child mode: " + mode);
        }
        System.out.flush();
    }

    private static void progress(String phase) {
        System.out.println("PROGRESS " + phase);
        System.out.flush();
    }

    private static Sqlite provider() {
        return ServiceLoader.load(Sqlite.class).findFirst().orElseThrow();
    }

    private static void crashTransfer(String mode, Path directory) throws Exception {
        if (mode.equals("crash-before")) {
            progress("before-transaction");
            Thread.sleep(3_600_000L);
            return;
        }
        try (var db = provider().open(directory.resolve("test.db"), 1, Duration.ofSeconds(2))) {
            db.write(session -> {
                try (var debit = session.prepare("UPDATE accounts SET balance = balance - 10 WHERE id = 1")) {
                    assert !debit.step() : "Debit should complete";
                }
                if (mode.equals("crash-debit")) {
                    progress("after-debit");
                    Thread.sleep(3_600_000L);
                }
                try (var credit = session.prepare("UPDATE accounts SET balance = balance + 10 WHERE id = 2")) {
                    assert !credit.step() : "Credit should complete";
                }
                if (mode.equals("crash-credit")) {
                    progress("after-credit");
                    Thread.sleep(3_600_000L);
                }
                try (var transfer = session.prepare("INSERT INTO transfers VALUES (1, 1, 2, 10)")) {
                    assert !transfer.step() : "Unique transfer should complete";
                }
                if (mode.equals("crash-random")) progress("before-commit");
                return null;
            });
            progress("after-commit");
            Thread.sleep(3_600_000L);
        }
    }

    private static void verify(Path directory) throws Exception {
        try (var db = provider().open(directory.resolve("test.db"), 1, Duration.ofSeconds(2))) {
            var state = ModelScenarios.actual(db);
            assert state.first() + state.second() == 200 : "Recovered total balance must be conserved";
            var absent = state.equals(new ModelScenarios.Snapshot(100, 100, java.util.Map.of()));
            var committed = state.equals(new ModelScenarios.Snapshot(90, 110,
                    java.util.Map.of(1L, new ModelScenarios.Transfer(1, 1, 2, 10))));
            assert absent || committed : "Recovered transfer must be wholly absent or committed: " + state;
            assert db.read(session -> {
                try (var check = session.prepare("PRAGMA integrity_check")) {
                    assert check.step() : "Integrity result should be available";
                    return "ok".equals(check.stringAt(0)) && !check.step();
                }
            }) : "Recovered SQLite image must pass integrity_check";
            assert db.read(session -> {
                try (var check = session.prepare("PRAGMA foreign_key_check")) {
                    return !check.step();
                }
            }) : "Recovered foreign keys must be valid";
            Files.writeString(directory.resolve("recovered-state.txt"), committed ? "committed\n" : "absent\n");
            Files.writeString(directory.resolve("recovery-config.txt"),
                    "java.version=" + System.getProperty("java.version") + "\n"
                    + ModelScenarios.configuration(db));
        }
    }
}
