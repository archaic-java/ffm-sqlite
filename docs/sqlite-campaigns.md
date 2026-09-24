# SQLite verification campaigns

## Commands and dependency pins

Use JDK 25 on Linux, system `libsqlite3.so.0`, and sibling checkouts of:

- `archaic-java/service-catalog` at `85b5d00e341d52abdc7b2fca32d8a8f888309767` (through the lease/cancellation cases).
- `archaic-java/minau` at `890647065e1250ef3b628bf292b3d78d22f4024a` (case selection).

These commits are deliberately pinned in both workflows; changing either requires a local compilation and test run, then a reviewed pin change. Check out the provider as `ffm-sqlite` beside `service-catalog` and `minau`. From `ffm-sqlite`:

```sh
javac @cmd/compile
java @cmd/sqlite-info
java @cmd/test
java -Dsqlite.model.seed=123456 -Dsqlite.model.iterations=1000 @cmd/model
java -Dsqlite.crash.seed=20260924 -Dsqlite.crash.samples=20 @cmd/crash
```

`cmd/test` is the bounded PR gate. `cmd/model` excludes crash cases; `cmd/crash` excludes model cases. Both include the fast contract/provider checks. The weekly and manual campaign workflow validates a signed seed, model iterations (1–10000), and timing samples (0–100) before passing each value as one quoted JVM property argument. It does not evaluate input as shell code. Every job has an outer timeout; every hazardous child also has a deadline enforced by its parent. The fast matrix uses Ubuntu 22.04 and 24.04 on x86-64 and arm64; the campaign matrix uses the same four runner images. [GitHub's runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) lists these labels for public repositories.

`cmd/sqlite-info` logs the actual SQLite version, compile options, journal mode and synchronous setting on each runner. The local Ubuntu 24.04 x86-64 run used Java 25.0.4, SQLite 3.45.1, WAL and `synchronous=2`; all 46 fast cases, a 300-operation model sample, and ten crash timing samples passed.

The [fast workflow run](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978377476) passed all four jobs with Temurin 25.0.4.1, WAL and `synchronous=2`. Each job log records its complete `PRAGMA compile_options` output:

| Runner job | Architecture | System SQLite |
| --- | --- | --- |
| [Ubuntu 22.04](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978377476/job/107564135486) | x86-64 | 3.37.2 |
| [Ubuntu 24.04](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978377476/job/107564135777) | x86-64 | 3.45.1 |
| [Ubuntu 22.04 arm](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978377476/job/107564135836) | aarch64 | 3.37.2 |
| [Ubuntu 24.04 arm](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978377476/job/107564135779) | aarch64 | 3.45.1 |

The lowest tested system SQLite version is 3.37.2 with the logged Ubuntu 22.04 compile options and WAL/`synchronous=2`; this is evidence for that configuration, not a claim about every 3.37.2 build. The larger campaign workflow is scheduled and manually dispatchable once this workflow reaches the default branch.

On failure, CI uploads the Minau log, environment log, and retained synthetic `ffm-sqlite-*` / `sqlite-conformance-*` directories for seven days. These may contain DB/WAL/SHM, sequence, protocol history and child output, but no application data or credentials. The failing Minau trail supplies the evidence directory. Replay a model failure with `-Dsqlite.model.sequence=/path/to/sequence.txt @cmd/model`. Replay a single crash case using `-Dsqlite.crash.mode=<before|debit|credit|commit|random>`, the `seed` and `iteration` from `history.txt`, and `@cmd/crash`. The child command and exact arguments are in `command.txt`.

An isolated [deliberate failure run](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978770696) changed one provider test assertion from expecting `SELECT 7` to expecting 999. All four fast jobs went red and uploaded artifacts. The inspected [Ubuntu 24.04 artifact](https://github.com/archaic-java/ffm-sqlite/actions/runs/35978770696/artifacts/10799670135) contains `ci-fast.log` with the exact failed `RepeatedClose.java:22` assertion, the retained `ffm-sqlite-repeat-close-*/test.db`, `ci-environment.txt`, and child command/output logs. With that temporary mutation applied, replay the case using `java @cmd/test --suite work.archaic.sqlite.test.SqliteTest --case 22`; Minau reports the case and evidence directory. The [temporary PR](https://github.com/archaic-java/ffm-sqlite/pull/17) was closed unmerged and its branch head restored to the clean CI commit. The artifact expires after seven days; the linked run and recorded assertion remain as evidence.

## Manual mutation audit

The following mutations were applied locally one at a time, compiled, tested with model/crash campaigns disabled, and then removed. Each returned Minau exit status 1. The final source was recompiled afterward.

| Deliberate mutation | Detecting case | Observation |
| --- | --- | --- |
| Omit the provider's `ROLLBACK` in `LocalDatabase.run` | `Transactions`, `TransferRollback(1|2|3)` | Six failures, including the next writer on the same connection. |
| Skip statement finalization in `SqliteSession.close` | `ExpiredHandles` | Forgotten statement remained usable after the scope. |
| Return the sole reader to the pool before its callback ends | Provider `InterruptedLease(reader)` | The pending caller entered/reused the leased connection; case failed. This mutation was not independently detected by the other cases in that run, so broader concurrent-ownership coverage remains a gap. |
| Permit `SqliteSession.cancel` after close | `ExpiredCancellation` | Expired session failed to reject cancellation during the next borrower's scope. |

This audit shows that these specific injected faults are observable; it does not establish complete fault coverage. No mutation is present in the submitted source.
