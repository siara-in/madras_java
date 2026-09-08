package com.madras.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Direct wrapper over the new native madras_sql engine (src/madras_sql/src)
 * -- an alternative to the Calcite-based JDBC driver for the query shapes
 * it supports (SELECT cols|* FROM t [WHERE col OP literal] [LIMIT n],
 * COUNT(*)). Bypasses Calcite's SQL parsing/planning entirely.
 */
public final class MadrasSqlEngine implements AutoCloseable {

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final List<String> columnNames;
        public final List<List<String>> rows;

        Result(boolean ok, String error, List<String> columnNames, List<List<String>> rows) {
            this.ok = ok;
            this.error = error;
            this.columnNames = columnNames;
            this.rows = rows;
        }
    }

    private final long handle;
    private volatile boolean closed = false;

    public MadrasSqlEngine(String path) {
        this.handle = MadrasSqlNative.nativeSqlOpen(path);
    }

    public Result execute(String sql) {
        if (closed) throw new IllegalStateException("MadrasSqlEngine is closed");
        String[] flat = MadrasSqlNative.nativeSqlExecute(handle, sql);
        return parseFlatResult(flat);
    }

    // -----------------------------------------------------------------
    // Prepared statements: parse+plan ONCE (prepare), then bind-and-execute
    // repeatedly (executePrepared) -- skips re-parsing/re-planning on each
    // call, unlike calling execute() with substituted literal values every
    // time. See dv1/engine.hpp's own comment on this for the underlying
    // native design.
    // -----------------------------------------------------------------

    /** Returns an opaque native handle -- caller must call closePrepared() exactly once when done. */
    public long prepare(String sql) {
        if (closed) throw new IllegalStateException("MadrasSqlEngine is closed");
        return MadrasSqlNative.nativeSqlPrepare(handle, sql);
    }

    public int getParamCount(long preparedHandle) {
        return MadrasSqlNative.nativeSqlGetParamCount(preparedHandle);
    }

    public Result executePrepared(long preparedHandle, String[] params) {
        if (closed) throw new IllegalStateException("MadrasSqlEngine is closed");
        String[] flat = MadrasSqlNative.nativeSqlExecutePrepared(preparedHandle, params);
        return parseFlatResult(flat);
    }

    public void closePrepared(long preparedHandle) {
        MadrasSqlNative.nativeSqlPreparedClose(preparedHandle);
    }

    // Process-wide, not per-instance -- controls the native "Using index:
    // ..." diagnostic logging. Defaults to on. Repeated-call tools (like
    // SqlCli's .compare, which fires this on every one of potentially
    // thousands of iterations) need a way to quiet it.
    public static void setIndexLogging(boolean enabled) {
        MadrasSqlNative.nativeSqlSetIndexLogging(enabled);
    }

    public static boolean getIndexLogging() {
        return MadrasSqlNative.nativeSqlGetIndexLogging();
    }

    private static Result parseFlatResult(String[] flat) {
        int p = 0;
        boolean ok = "1".equals(flat[p++]);
        String error = flat[p++];
        int colCount = Integer.parseInt(flat[p++]);
        List<String> columnNames = new ArrayList<>(colCount);
        for (int i = 0; i < colCount; i++) columnNames.add(flat[p++]);
        int rowCount = Integer.parseInt(flat[p++]);
        List<List<String>> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<String> row = new ArrayList<>(colCount);
            for (int c = 0; c < colCount; c++) row.add(flat[p++]);
            rows.add(row);
        }
        return new Result(ok, error, columnNames, rows);
    }

    @Override
    public void close() {
        if (!closed) {
            MadrasSqlNative.nativeSqlClose(handle);
            closed = true;
        }
    }
}
