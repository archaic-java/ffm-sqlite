package work.archaic.sqlite.test;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestTrail;
import work.archaic.service.sqlite.v01.Sqlite;

/** Test-only, bounded supervisor. Protocol: READY, PROGRESS token, COMPLETED, one line each. */
final class ChildJvm {
    private static final String EOF = "\u0000EOF";
    private static final int OUTPUT_LIMIT = 65_536;
    private static final int LINE_LIMIT = 256;
    // Minau runs cases concurrently; a large crash campaign must not start many JVMs at once.
    private static final Semaphore CHILD_SLOT = new Semaphore(1, true);
    private ChildJvm() { }

    static final class Failure extends Exception {
        final String phase;
        final Path evidence;
        Failure(String phase, Path evidence, String detail) {
            super("Child " + phase + ": " + detail + " (evidence: " + evidence + ")");
            this.phase = phase;
            this.evidence = evidence;
        }
    }

    static void run(String mode, TestTrail trail, Duration startup, Duration overall) throws Exception {
        Path directory = Files.createTempDirectory("ffm-sqlite-child-");
        trail.note("Child evidence: " + directory);
        var command = command(mode, directory);
        Files.writeString(directory.resolve("command.txt"), String.join("\n", command) + "\n");
        Process process = null;
        Thread stdout = null;
        Thread stderr = null;
        var lines = new ArrayBlockingQueue<String>(128);
        var pumpError = new AtomicReference<Throwable>();
        boolean passed = false;
        boolean acquired = false;
        Throwable originalFailure = null;
        try {
            CHILD_SLOT.acquire();
            acquired = true;
            long deadline = System.nanoTime() + overall.toNanos();
            process = new ProcessBuilder(command).start();
            Process child = process;
            stdout = Thread.ofVirtual().start(() -> pump(child.getInputStream(),
                    directory.resolve("stdout.log"), lines, pumpError));
            stderr = Thread.ofVirtual().start(() -> pump(child.getErrorStream(),
                    directory.resolve("stderr.log"), null, pumpError));
            String first = lines.poll(Math.min(startup.toNanos(), remaining(deadline)), TimeUnit.NANOSECONDS);
            if (first == null) throw new Failure("startup timeout", directory, "no READY acknowledgement");
            if (!first.equals("READY")) throw new Failure("startup protocol", directory, "expected READY, got " + first);
            boolean completed = false;
            for (;;) {
                long left = remaining(deadline);
                if (left == 0) throw new Failure("overall timeout", directory, "waiting for COMPLETED/exit");
                String line = lines.poll(left, TimeUnit.NANOSECONDS);
                if (line == null) throw new Failure("overall timeout", directory, "waiting for child output");
                if (line.equals(EOF)) break;
                if (line.equals("COMPLETED") && !completed) completed = true;
                else if (line.matches("PROGRESS [A-Za-z0-9._-]+")) trail.note("Child: " + line);
                else throw new Failure("protocol", directory, "unexpected acknowledgement: " + line);
            }
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS))
                throw new Failure("overall timeout", directory, "process did not exit after output closed");
            if (!completed) throw new Failure("protocol", directory, "missing COMPLETED acknowledgement");
            if (process.exitValue() != 0)
                throw new Failure("exit", directory, "status " + process.exitValue());
            if (pumpError.get() != null)
                throw new Failure("output", directory, pumpError.get().toString());
            passed = true;
        } catch (Exception | Error original) {
            originalFailure = original;
            throw original;
        } finally {
            Throwable cleanupFailure = null;
            if (process != null) {
                try { stop(process); }
                catch (Exception cleanup) { cleanupFailure = cleanup; }
            }
            try {
                if (stdout != null && !stdout.join(Duration.ofSeconds(1)))
                    throw new IllegalStateException("Stdout pump survived child cleanup");
                if (stderr != null && !stderr.join(Duration.ofSeconds(1)))
                    throw new IllegalStateException("Stderr pump survived child cleanup");
            } catch (Exception cleanup) {
                if (cleanupFailure == null) cleanupFailure = cleanup;
                else cleanupFailure.addSuppressed(cleanup);
            }
            if (passed) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (Exception cleanup) {
                    if (cleanupFailure == null) cleanupFailure = cleanup;
                    else cleanupFailure.addSuppressed(cleanup);
                }
            } else trail.note("Retained child files: " + directory);
            if (acquired) CHILD_SLOT.release();
            if (cleanupFailure != null) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception error) throw error;
                else throw new AssertionError(cleanupFailure);
            }
        }
    }

    private static List<String> command(String mode, Path directory) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-ea", "--enable-native-access=work.archaic.sqlite",
                "--module-path", Path.of("out").toAbsolutePath().toString(),
                "--add-modules", "work.archaic.sqlite",
                "-m", "work.archaic.sqlite.test/work.archaic.sqlite.test.ChildMain",
                mode, directory.toString());
    }

    /** Stops a child after an acknowledged phase; returns the untouched database/WAL/SHM fixture. */
    static Path crashAt(Sqlite provider, String mode, String phase, long delayMillis,
                        long seed, int iteration, TestTrail trail) throws Exception {
        Path directory = Files.createTempDirectory("ffm-sqlite-crash-");
        trail.note("Crash evidence: " + directory);
        try (var database = provider.open(directory.resolve("test.db"), 1, Duration.ofSeconds(2))) {
            Files.writeString(directory.resolve("initial-config.txt"), ModelScenarios.configuration(database));
            ModelScenarios.initialize(database);
        }
        var command = command(mode, directory);
        Files.writeString(directory.resolve("command.txt"), String.join("\n", command) + "\n");
        Process process = null;
        Thread stdout = null;
        Thread stderr = null;
        var events = new ArrayBlockingQueue<String>(128);
        var pumpError = new AtomicReference<Throwable>();
        var history = new StringBuilder("scenario=" + mode + "\nkill-after=" + phase
                + "\ndelay-ms=" + delayMillis + "\nseed=" + seed + "\niteration=" + iteration
                + "\njava.version=" + System.getProperty("java.version") + "\n");
        Throwable originalFailure = null;
        boolean acquired = false;
        try {
            CHILD_SLOT.acquire();
            acquired = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            process = new ProcessBuilder(command).start();
            Process child = process;
            stdout = Thread.ofVirtual().start(() -> pump(child.getInputStream(),
                    directory.resolve("stdout.log"), events, pumpError));
            stderr = Thread.ofVirtual().start(() -> pump(child.getErrorStream(),
                    directory.resolve("stderr.log"), null, pumpError));
            String first = events.poll(Math.min(TimeUnit.SECONDS.toNanos(2), remaining(deadline)),
                    TimeUnit.NANOSECONDS);
            if (!"READY".equals(first))
                throw new Failure("startup", directory, "expected READY, got " + first);
            history.append("READY\n");
            boolean reached = false;
            while (remaining(deadline) > 0) {
                String event = events.poll(remaining(deadline), TimeUnit.NANOSECONDS);
                if (event == null) break;
                history.append(event).append('\n');
                if (event.equals("PROGRESS " + phase)) { reached = true; break; }
                if (event.equals(EOF) || event.equals("COMPLETED")
                        || !event.matches("PROGRESS [A-Za-z0-9._-]+"))
                    throw new Failure("protocol", directory, "phase not reached: " + event);
            }
            if (!reached) throw new Failure("overall timeout", directory, "missing phase " + phase);
            trail.note("Kill after " + phase + " (delay " + delayMillis + " ms)");
            if (delayMillis > 0) Thread.sleep(delayMillis);
            process.destroyForcibly();
            if (!process.waitFor(1, TimeUnit.SECONDS))
                throw new Failure("kill timeout", directory, "child survived force kill");
            stop(process);
            history.append("exit-status=").append(process.exitValue()).append('\n');
            if (process.exitValue() == 0)
                throw new Failure("kill", directory, "child exited normally before forced termination");
            Files.writeString(directory.resolve("history.txt"), history.toString());
            return directory;
        } catch (Exception | Error original) {
            originalFailure = original;
            throw original;
        } finally {
            Throwable cleanupFailure = null;
            if (process != null) try { stop(process); }
            catch (Exception cleanup) { cleanupFailure = cleanup; }
            try {
                if (stdout != null && !stdout.join(Duration.ofSeconds(1)))
                    throw new IllegalStateException("Crash stdout pump survived");
                if (stderr != null && !stderr.join(Duration.ofSeconds(1)))
                    throw new IllegalStateException("Crash stderr pump survived");
            } catch (Exception cleanup) {
                if (cleanupFailure == null) cleanupFailure = cleanup;
                else cleanupFailure.addSuppressed(cleanup);
            }
            if (originalFailure != null && !Files.exists(directory.resolve("history.txt")))
                try { Files.writeString(directory.resolve("history.txt"), history.toString()); }
                catch (Exception cleanup) {
                    if (cleanupFailure == null) cleanupFailure = cleanup;
                    else cleanupFailure.addSuppressed(cleanup);
                }
            if (acquired) CHILD_SLOT.release();
            if (cleanupFailure != null) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception error) throw error;
                else throw new AssertionError(cleanupFailure);
            }
        }
    }

    private static long remaining(long deadline) { return Math.max(0L, deadline - System.nanoTime()); }

    private static void pump(InputStream input, Path log, ArrayBlockingQueue<String> lines,
                             AtomicReference<Throwable> error) {
        try (input; OutputStream output = Files.newOutputStream(log)) {
            int written = 0;
            boolean truncated = false;
            var line = new java.io.ByteArrayOutputStream();
            boolean oversized = false;
            byte[] chunk = new byte[4096];
            for (int n; (n = input.read(chunk)) != -1;) {
                int keep = Math.min(n, OUTPUT_LIMIT - written);
                if (keep > 0) { output.write(chunk, 0, keep); written += keep; }
                if (keep < n) truncated = true;
                if (lines != null) for (int i = 0; i < n; i++) {
                    int value = chunk[i] & 0xff;
                    if (value == '\n') {
                        if (!lines.offer(oversized ? "OVERSIZED" : line.toString(StandardCharsets.UTF_8)))
                            error.compareAndSet(null, new IllegalStateException("Protocol queue full"));
                        line.reset();
                        oversized = false;
                    } else if (line.size() < LINE_LIMIT) line.write(value);
                    else oversized = true;
                }
            }
            if (lines != null && (line.size() > 0 || oversized))
                lines.offer(oversized ? "OVERSIZED" : line.toString(StandardCharsets.UTF_8));
            if (truncated) output.write("\n[output truncated at 65536 bytes]\n".getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) { error.compareAndSet(null, failure); }
        finally { if (lines != null) lines.offer(EOF); }
    }

    private static void stop(Process process) throws Exception {
        var descendants = new ArrayList<>(process.toHandle().descendants().toList());
        descendants.sort(Comparator.comparingInt(ChildJvm::depth).reversed());
        for (var child : descendants) child.destroy();
        process.destroy();
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        for (var child : descendants) if (child.isAlive()) child.destroyForcibly();
        if (!process.waitFor(1, TimeUnit.SECONDS)) throw new IllegalStateException("Child JVM survived force kill");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        for (var child : descendants) {
            while (child.isAlive() && remaining(deadline) > 0) Thread.onSpinWait();
            if (child.isAlive()) throw new IllegalStateException("Descendant survived force kill: " + child.pid());
        }
    }

    private static int depth(ProcessHandle process) {
        int depth = 0;
        for (var parent = process.parent(); parent.isPresent(); parent = parent.get().parent()) depth++;
        return depth;
    }
}

record HarnessSelfCheck(String mode, String expectedPhase) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        if (expectedPhase == null) {
            ChildJvm.run(mode, trail, Duration.ofSeconds(2), Duration.ofSeconds(5));
            return;
        }
        try {
            ChildJvm.run(mode, trail, Duration.ofSeconds(2), Duration.ofSeconds(2));
            throw new AssertionError("Expected child failure phase: " + expectedPhase);
        } catch (ChildJvm.Failure expected) {
            assert expected.phase.equals(expectedPhase) : "Unexpected child failure: " + expected;
            assert Files.exists(expected.evidence.resolve("stdout.log")) : "Failed run should retain stdout";
            assert Files.exists(expected.evidence.resolve("stderr.log")) : "Failed run should retain stderr";
            if (mode.equals("hang-descendant")) {
                long pid = Long.parseLong(Files.readString(expected.evidence.resolve("descendant.pid")));
                assert ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true)
                        : "Hung child descendant must be reaped";
            }
        }
    }
}
