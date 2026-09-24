package work.archaic.sqlite.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import work.archaic.service.sqlite.v01.Database;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestTrail;

final class ModelScenarios {
    private ModelScenarios() { }
    static void register(Sqlite provider, java.util.Collection<TestCase> cases) {
        int iterations = bounded("sqlite.model.iterations", 64, 1, 10_000);
        String saved = System.getProperty("sqlite.model.sequence");
        if (saved != null) {
            if (saved.isBlank()) throw new IllegalArgumentException("sqlite.model.sequence must be a file path");
            cases.add(new ModelScenario(provider, 0, iterations, Path.of(saved)));
        } else {
            String chosen = System.getProperty("sqlite.model.seed");
            if (chosen == null) for (long seed : new long[] {1L, 42L, 20260924L})
                cases.add(new ModelScenario(provider, seed, iterations, null));
            else {
                long seed;
                try { seed = Long.parseLong(chosen); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid sqlite.model.seed", error); }
                cases.add(new ModelScenario(provider, seed, iterations, null));
            }
        }
        cases.add(new ModelOracleCheck());
    }
    private static int bounded(String key, int fallback, int min, int max) {
        String value = System.getProperty(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) { /* report below */ }
        throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
    }

    record Transfer(long id, int from, int to, long amount) { }
    record Snapshot(long first, long second, Map<Long, Transfer> committed) { }
    record Operation(String kind, long id, int from, int to, long amount) {
        @Override public String toString() { return kind + " " + id + " " + from + " " + to + " " + amount; }
        static Operation parse(String line) {
            String[] fields = line.split(" ");
            if (fields.length != 5) throw new IllegalArgumentException("Invalid operation: " + line);
            String kind = fields[0];
            if (!List.of("TRANSFER", "FAIL_DEBIT", "FAIL_CREDIT", "READ", "REOPEN").contains(kind))
                throw new IllegalArgumentException("Unknown operation: " + kind);
            return new Operation(kind, Long.parseLong(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), Long.parseLong(fields[4]));
        }
    }

    static final class Oracle {
        long first = 100;
        long second = 100;
        final Map<Long, Transfer> committed = new LinkedHashMap<>();
        Snapshot expected() { return new Snapshot(first, second, Map.copyOf(committed)); }
        void apply(Operation op) {
            if (!op.kind.equals("TRANSFER")) return;
            if (committed.containsKey(op.id)) throw new AssertionError("Duplicate model ID " + op.id);
            if (op.from == 1) { first -= op.amount; second += op.amount; }
            else { second -= op.amount; first += op.amount; }
            committed.put(op.id, new Transfer(op.id, op.from, op.to, op.amount));
        }
    }

    static List<Operation> generate(long seed, int iterations) {
        var random = new SplittableRandom(seed);
        var operations = new ArrayList<Operation>();
        var model = new Oracle();
        long id = 1;
        for (int i = 0; i < iterations; i++) {
            int choice = random.nextInt(10);
            if (choice == 0) operations.add(new Operation("READ", 0, 0, 0, 0));
            else if (choice == 1) operations.add(new Operation("REOPEN", 0, 0, 0, 0));
            else {
                int from = random.nextBoolean() ? 1 : 2;
                if ((from == 1 ? model.first : model.second) == 0) from = 3 - from;
                long balance = from == 1 ? model.first : model.second;
                long amount = Math.min(1 + random.nextInt(5), balance);
                String kind = choice < 4 ? "FAIL_DEBIT" : choice < 6 ? "FAIL_CREDIT" : "TRANSFER";
                var op = new Operation(kind, id++, from, 3 - from, amount);
                operations.add(op);
                model.apply(op);
            }
        }
        return List.copyOf(operations);
    }

    static void initialize(Database db) throws SqliteException {
        db.write(session -> {
            try (var accounts = session.prepare("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER NOT NULL CHECK(balance >= 0))")) {
                assert !accounts.step() : "Accounts schema should finish";
            }
            try (var transfers = session.prepare("CREATE TABLE transfers (id INTEGER PRIMARY KEY, from_id INTEGER REFERENCES accounts(id), to_id INTEGER REFERENCES accounts(id), amount INTEGER NOT NULL)")) {
                assert !transfers.step() : "Transfer schema should finish";
            }
            try (var initial = session.prepare("INSERT INTO accounts VALUES (1, 100), (2, 100)")) {
                assert !initial.step() : "Initial balances should be inserted";
            }
            return null;
        });
    }
    static void execute(Database db, Operation op) throws Exception {
        if (op.kind.equals("READ") || op.kind.equals("REOPEN")) return;
        var deliberate = new IOException("Deliberate rollback for operation " + op.id);
        try {
            db.write(session -> {
                try (var debit = session.prepare("UPDATE accounts SET balance = balance - ? WHERE id = ?")) {
                    debit.bind(1, op.amount).bind(2, op.from);
                    assert !debit.step() : "Debit should finish";
                }
                if (op.kind.equals("FAIL_DEBIT")) throw deliberate;
                try (var credit = session.prepare("UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
                    credit.bind(1, op.amount).bind(2, op.to);
                    assert !credit.step() : "Credit should finish";
                }
                if (op.kind.equals("FAIL_CREDIT")) throw deliberate;
                try (var transfer = session.prepare("INSERT INTO transfers VALUES (?, ?, ?, ?)")) {
                    transfer.bind(1, op.id).bind(2, op.from).bind(3, op.to).bind(4, op.amount);
                    assert !transfer.step() : "Unique transfer should finish";
                }
                return null;
            });
            assert op.kind.equals("TRANSFER") : "Deliberate failure should escape";
        } catch (IOException actual) {
            assert actual == deliberate : "Original callback failure should escape unchanged";
        }
    }
    static Snapshot actual(Database db) throws SqliteException {
        return db.read(session -> {
            long first, second;
            try (var accounts = session.prepare("SELECT id, balance FROM accounts ORDER BY id")) {
                assert accounts.step() && accounts.longAt(0) == 1 : "First account must exist";
                first = accounts.longAt(1);
                assert accounts.step() && accounts.longAt(0) == 2 : "Second account must exist";
                second = accounts.longAt(1);
                assert !accounts.step() : "Only two accounts should exist";
            }
            var transfers = new LinkedHashMap<Long, Transfer>();
            try (var query = session.prepare("SELECT id, from_id, to_id, amount FROM transfers ORDER BY id")) {
                while (query.step()) {
                    long id = query.longAt(0);
                    var previous = transfers.put(id, new Transfer(id, (int) query.longAt(1),
                            (int) query.longAt(2), query.longAt(3)));
                    assert previous == null : "Duplicate committed ID";
                }
            }
            return new Snapshot(first, second, Map.copyOf(transfers));
        });
    }
    static String configuration(Database db) throws SqliteException {
        return db.read(session -> {
            var result = new StringBuilder();
            for (String sql : List.of("SELECT sqlite_version()", "PRAGMA journal_mode", "PRAGMA synchronous")) {
                try (var query = session.prepare(sql)) {
                    assert query.step() : "SQLite setting should be readable: " + sql;
                    result.append(sql).append('=').append(query.stringAt(0)).append('\n');
                }
            }
            return result.toString();
        });
    }
}

record ModelScenario(Sqlite provider, long seed, int iterations, Path savedSequence) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        Path directory = Files.createTempDirectory("ffm-sqlite-model-");
        trail.note("Replay evidence directory: " + directory);
        boolean passed = false;
        Database db = null;
        Throwable failure = null;
        try {
            List<ModelScenarios.Operation> operations;
            if (savedSequence == null) operations = ModelScenarios.generate(seed, iterations);
            else {
                var lines = Files.readAllLines(savedSequence);
                if (lines.size() < 2 || !lines.getFirst().equals("sqlite-model-v1"))
                    throw new IllegalArgumentException("Unsupported saved scenario version");
                if (lines.size() > 10_002) throw new IllegalArgumentException("Saved scenario too large");
                operations = lines.stream().skip(2).map(ModelScenarios.Operation::parse).toList();
                if (operations.isEmpty()) throw new IllegalArgumentException("Saved scenario has no operations");
            }
            long replaySeed = savedSequence == null ? seed
                    : Long.parseLong(Files.readAllLines(savedSequence).get(1).substring("seed=".length()));
            var script = directory.resolve("sequence.txt");
            var lines = new ArrayList<String>();
            lines.add("sqlite-model-v1");
            lines.add("seed=" + replaySeed);
            for (var op : operations) lines.add(op.toString());
            Files.write(script, lines);
            db = provider.open(directory.resolve("test.db"), 1, Duration.ofSeconds(2));
            Files.writeString(directory.resolve("environment.txt"),
                    "scenario=sqlite-model-v1\nseed=" + replaySeed + "\niterations=" + operations.size()
                    + "\njava.version=" + System.getProperty("java.version") + "\n"
                    + "java.vendor=" + System.getProperty("java.vendor") + "\n" + ModelScenarios.configuration(db));
            ModelScenarios.initialize(db);
            var oracle = new ModelScenarios.Oracle();
            for (int index = 0; index < operations.size(); index++) {
                var op = operations.get(index);
                trail.note("Operation " + index + ": " + op);
                if (op.kind().equals("REOPEN")) {
                    db.close();
                    db = null;
                    db = provider.open(directory.resolve("test.db"), 1, Duration.ofSeconds(2));
                } else ModelScenarios.execute(db, op);
                oracle.apply(op);
                var actual = ModelScenarios.actual(db);
                var expected = oracle.expected();
                assert actual.equals(expected) : "Model mismatch at operation " + index + ": expected "
                        + expected + ", actual " + actual;
                assert actual.first() + actual.second() == 200 : "Total balance must be conserved";
            }
            db.close();
            db = null;
            passed = true;
        } catch (Exception | Error original) {
            failure = original;
            throw original;
        } finally {
            if (db != null) try { db.close(); }
            catch (Exception cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
            if (passed) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            } else trail.note("Retained model files: " + directory);
        }
    }
    @Override public String toString() {
        return savedSequence == null ? "SQLite model seed=" + seed + " iterations=" + iterations
                : "SQLite model saved sequence=" + savedSequence;
    }
}

record ModelOracleCheck() implements TestCase {
    @Override public void run(TestTrail trail) {
        var model = new ModelScenarios.Oracle();
        var transfer = new ModelScenarios.Operation("TRANSFER", 17, 1, 2, 5);
        model.apply(transfer);
        var deliberatelyWrong = new ModelScenarios.Snapshot(100, 100, Map.of());
        assert !model.expected().equals(deliberatelyWrong) : "Oracle must detect a missing committed transfer";
        var failed = new ModelScenarios.Operation("FAIL_CREDIT", 18, 2, 1, 4);
        model.apply(failed);
        assert model.expected().equals(new ModelScenarios.Snapshot(95, 105, Map.of(17L,
                new ModelScenarios.Transfer(17, 1, 2, 5))))
                : "Oracle must leave rollback out of committed state";
    }
}
