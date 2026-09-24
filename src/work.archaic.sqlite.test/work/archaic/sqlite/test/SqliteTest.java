package work.archaic.sqlite.test;

import java.util.Collection;
import java.util.ServiceLoader;
import work.archaic.service.catalog.test.SqliteCases;
import work.archaic.service.sqlite.v01.Sqlite;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestSuite;

/** Runs the reusable SQLite v01 catalog cases against this provider. */
public record SqliteTest() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        Sqlite provider = ServiceLoader.load(Sqlite.class).findFirst().orElseThrow();
        SqliteCases.register(provider, cases);
        cases.add(new RepeatedClose(provider));
        cases.add(new InterruptedLease(provider, false));
        cases.add(new InterruptedLease(provider, true));
        cases.add(new IndependentWriterLock(provider));
        cases.add(new BackupFaultCleanup(provider));
        cases.add(new BackupDeadline(provider));
        cases.add(new NativeHandleCycles(provider));
        cases.add(new HarnessSelfCheck("success", null));
        cases.add(new HarnessSelfCheck("nonzero", "exit"));
        cases.add(new HarnessSelfCheck("malformed", "startup protocol"));
        cases.add(new HarnessSelfCheck("no-ready", "startup timeout"));
        cases.add(new HarnessSelfCheck("missing", "protocol"));
        cases.add(new HarnessSelfCheck("hang", "overall timeout"));
        cases.add(new HarnessSelfCheck("hang-descendant", "overall timeout"));
        ModelScenarios.register(provider, cases);
        CrashScenarios.register(provider, cases);
    }
}
