# ffm-sqlite

Linux SQLite provider for `work.archaic.service.sqlite.v01.Sqlite`, using JDK 25 FFM and the system `libsqlite3.so.0`. One writer connection and a bounded set of read-only connections serve scoped transactions. The provider uses WAL mode and enables foreign keys on each connection.

Build and verify with a sibling checkout of [service-catalog](https://github.com/archaic-java/service-catalog):

```sh
javac @cmd/compile
java @cmd/test
```

Use `ServiceLoader.load(Sqlite.class)` from a named consumer module that declares `uses work.archaic.service.sqlite.v01.Sqlite`. Add `--enable-native-access=work.archaic.sqlite` at launch. The caller must finish database operations before closing the database.

Current slice supports one prepared SQL statement at a time, scalar and byte-array bindings, forward stepping, transaction rollback, online backup to a new file, passive checkpoint progress, and session cancellation. The catalog contains the executable provider conformance check. Streaming BLOBs, temporal helpers, and automated schema migration remain for later slices.
