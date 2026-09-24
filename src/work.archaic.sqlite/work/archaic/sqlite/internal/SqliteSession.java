package work.archaic.sqlite.internal;

import java.util.ArrayList;
import java.util.List;
import work.archaic.service.sqlite.v01.Session;
import work.archaic.service.sqlite.v01.SqliteException;
import work.archaic.service.sqlite.v01.Statement;

final class SqliteSession implements Session {
    private final SqliteConnection connection;
    private final List<SqliteStatement> statements = new ArrayList<>();
    private boolean closed;
    SqliteSession(SqliteConnection connection) { this.connection = connection; }

    @Override public Statement prepare(String sql) throws SqliteException {
        if (closed) throw new IllegalStateException("Session expired");
        var statement = connection.prepare(sql);
        statements.add(statement);
        return statement;
    }

    @Override public synchronized void cancel() {
        if (closed) throw new IllegalStateException("Session expired");
        connection.interrupt();
    }

    synchronized void close() throws SqliteException {
        if (closed) return;
        closed = true;
        SqliteException failure = null;
        for (var statement : statements) {
            try { statement.close(); }
            catch (SqliteException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
