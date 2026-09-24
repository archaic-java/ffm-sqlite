package work.archaic.sqlite.test;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
