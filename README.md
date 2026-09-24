# ffm-sqlite

Linux SQLite provider for `work.archaic.service.sqlite.v01.Sqlite`, using JDK 25 FFM and the system `libsqlite3.so.0`. One writer connection and a bounded set of read-only connections serve scoped transactions. The provider uses WAL mode and enables foreign keys on each connection.

The JPMS service provider and its implementation live in the unexported `work.archaic.sqlite.internal` package. Only `FfmSqlite` is public for service loading; the database coordinator, session, statement, connection and native bindings are separate package-private classes.

Build and verify with sibling checkouts of [service-catalog](https://github.com/archaic-java/service-catalog)
at `474d272cf603e8edc1713f07f10e5a29b2022373` (the SQLite case split) and
[Minau](https://github.com/archaic-java/minau) at `7abc609a7092dc6491d9af299499cfe434ad7f23`.
The checked-in `lib/src` links point to their named modules:

```sh
javac @cmd/compile
java @cmd/test
```

Use `ServiceLoader.load(Sqlite.class)` from a named consumer module that declares `uses work.archaic.service.sqlite.v01.Sqlite`. Add `--enable-native-access=work.archaic.sqlite` at launch. The caller must finish database operations before closing the database.

Current slice supports one prepared SQL statement at a time, scalar and byte-array bindings, forward stepping, transaction rollback, online backup to a new file, passive checkpoint progress, and session cancellation. The provider test suite registers catalog conformance cases with Minau v02. Streaming BLOBs, temporal helpers, and automated schema migration remain for later slices.

Provider fault cases use two independent database owners for a real SQLite writer lock, a missing backup parent, an existing target, a multi-page payload with a one-nanosecond backup limit, and Linux `/proc/self/fd` links into a dedicated fixture directory for handle ownership evidence. Precise interruption during `sqlite3_backup_step` cannot be established through the public API: a caller may be interrupted before backup begins or after it finishes. That race needs a supervised native-step fixture; elapsed-time guesses are not used as proof.

The test-only child-JVM harness retains failed runs in unique `ffm-sqlite-child-*` directories and records the location in the Minau failure trail. `command.txt` lists the exact executable and arguments, one per line; `stdout.log` and `stderr.log` retain at most 64 KiB each, with a truncation marker. From this repository root, replay a child scenario with the same JDK used by the parent:

```sh
java -ea --enable-native-access=work.archaic.sqlite --module-path out \
  --add-modules work.archaic.sqlite \
  -m work.archaic.sqlite.test/work.archaic.sqlite.test.ChildMain \
  success /path/to/evidence-directory
```

Replace `success` and the directory with the values recorded in `command.txt`. The protocol is one stdout line each for `READY`, optional `PROGRESS token`, and `COMPLETED`; successful exit also requires status zero. The parent enforces startup and overall deadlines, drains both output streams, and kills/reaps the process tree after failure.
