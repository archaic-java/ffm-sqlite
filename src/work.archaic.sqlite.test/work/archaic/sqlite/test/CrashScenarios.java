package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestTrail;

final class CrashScenarios {
    private CrashScenarios() { }
    static void register(Sqlite provider, java.util.Collection<TestCase> cases) {
        long seed = Long.parseLong(System.getProperty("sqlite.crash.seed", "20260924"));
        int iteration = bounded("sqlite.crash.iteration", 0, 0, 10_000);
        String mode = System.getProperty("sqlite.crash.mode");
        var permitted = List.of("before", "debit", "credit", "commit", "random");
        if (mode != null) {
            if (!permitted.contains(mode)) throw new IllegalArgumentException("Invalid sqlite.crash.mode");
            cases.add(new CrashRecovery(provider, mode, seed, iteration));
        } else {
            for (String fixed : permitted.subList(0, 4))
                cases.add(new CrashRecovery(provider, fixed, seed, 0));
            int samples = bounded("sqlite.crash.samples", 2, 0, 100);
            for (int index = 0; index < samples; index++)
                cases.add(new CrashRecovery(provider, "random", seed, index));
        }
        cases.add(new CrashOracleCheck());
    }
    private static int bounded(String name, int fallback, int min, int max) {
        String value = System.getProperty(name);
        if (value == null) return fallback;
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) { /* report below */ }
        throw new IllegalArgumentException(name + " must be " + min + ".." + max);
    }
}

record CrashRecovery(Sqlite provider, String mode, long seed, int iteration) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        String phase = switch (mode) {
            case "before" -> "before-transaction";
            case "debit" -> "after-debit";
            case "credit" -> "after-credit";
            case "commit" -> "after-commit";
            case "random" -> "before-commit";
            default -> throw new IllegalArgumentException(mode);
        };
        long delay = mode.equals("random") ? new SplittableRandom(seed ^ iteration).nextInt(6) : 0;
        Path directory = ChildJvm.crashAt(provider, "crash-" + mode, phase, delay, seed, iteration, trail);
        boolean passed = false;
        try {
            ChildJvm.run("verify@" + directory, trail, Duration.ofSeconds(2), Duration.ofSeconds(5));
            String state = Files.readString(directory.resolve("recovered-state.txt")).strip();
            trail.note("Recovered state: " + state);
            if (mode.equals("commit")) assert state.equals("committed")
                    : "Confirmed COMMIT must survive application process kill";
            else if (!mode.equals("random")) assert state.equals("absent")
                    : "Uncommitted transfer must roll back after process kill";
            else assert state.equals("absent") || state.equals("committed")
                    : "Kill sampled around COMMIT must recover wholly or not at all";
            passed = true;
        } finally {
            if (passed) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            } else trail.note("Retained crashed database and WAL: " + directory);
        }
    }
    @Override public String toString() { return "SQLite process crash " + mode + " seed=" + seed + " iteration=" + iteration; }
}

record CrashOracleCheck() implements TestCase {
    @Override public void run(TestTrail trail) {
        var absent = new ModelScenarios.Snapshot(100, 100, java.util.Map.of());
        var committed = new ModelScenarios.Snapshot(90, 110,
                java.util.Map.of(1L, new ModelScenarios.Transfer(1, 1, 2, 10)));
        var partial = new ModelScenarios.Snapshot(90, 100, java.util.Map.of());
        assert !partial.equals(absent) && !partial.equals(committed)
                : "Crash oracle must reject a half transfer even when balance count is plausible";
    }
}
