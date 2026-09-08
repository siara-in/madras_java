package com.madras.sql;

/**
 * Passes SQL text straight through to MadrasSqlEngine -- no local parsing
 * here (unlike com.madras.jdbc's old MiniSqlParser, this driver doesn't
 * need its own SQL parser at all; the native engine already has one).
 */
public class MadrasSqlStatement implements java.sql.Statement {

    private final MadrasSqlConnection connection;
    private MadrasSqlResultSet currentResultSet;
    private boolean closed = false;
    private int maxRows = 0;

    MadrasSqlStatement(MadrasSqlConnection connection) {
        this.connection = connection;
    }

    @Override
    public java.sql.ResultSet executeQuery(java.lang.String sql) throws java.sql.SQLException {
        if (closed) throw new java.sql.SQLException("Statement is closed");
        String effectiveSql = applyMaxRows(sql);
        MadrasSqlEngine.Result r = connection.engine().execute(effectiveSql);
        if (!r.ok) {
            throw new java.sql.SQLException(r.error);
        }
        java.util.List<java.lang.String> effectiveCols = r.columnNames;
        java.util.List<java.util.List<java.lang.String>> effectiveRows = r.rows;
        MadrasSqlResultSetMetaData md = new MadrasSqlResultSetMetaData(effectiveCols);
        this.currentResultSet = new MadrasSqlResultSet(effectiveCols, effectiveRows, md);
        return this.currentResultSet;
    }

    // Injects a LIMIT into the SQL text (only if one isn't already
    // present) when setMaxRows() has been called -- this is what actually
    // makes the cap take effect during the native scan itself, engaging
    // the engine's own already-fast early-exit logic. Applying maxRows
    // AFTER engine.execute() returns (the previous, real bug here) does
    // nothing to avoid the full unbounded scan: for an unfiltered
    // "SELECT * FROM t" over millions of rows, the native call would
    // already have materialized everything before any truncation happened
    // on the Java side. Many JDBC client tools (DBeaver included) call
    // setMaxRows() to cap preview/page size instead of adding LIMIT to the
    // SQL text themselves -- this is the mechanism that needs to actually
    // respect it.
    private String applyMaxRows(String sql) {
        if (maxRows <= 0) return sql;
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("LIMIT")) return sql; // already has its own LIMIT -- don't double up
        String trimmed = sql.replaceAll(";\\s*$", "");
        return trimmed + " LIMIT " + maxRows;
    }

    @Override
    public boolean execute(java.lang.String sql) throws java.sql.SQLException {
        executeQuery(sql);
        return true;
    }

    @Override
    public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
        return currentResultSet;
    }

    @Override
    public void close() throws java.sql.SQLException {
        closed = true;
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        return closed;
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        return connection;
    }

    @Override
    public int getMaxRows() throws java.sql.SQLException {
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws java.sql.SQLException {
        this.maxRows = max;
    }

    @Override
    public int getQueryTimeout() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public void setQueryTimeout(int seconds) throws java.sql.SQLException {
        // No query timeout support -- queries execute synchronously and are
        // usually extremely fast given this engine's index/metadata
        // shortcuts; not worth cancellation plumbing yet.
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> iface) throws java.sql.SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new java.sql.SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> iface) throws java.sql.SQLException {
        return iface.isInstance(this);
    }

    @Override
    public boolean execute(java.lang.String sql, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("execute(String,int)");
    }

    @Override
    public boolean execute(java.lang.String sql, int[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("execute(String,int[])");
    }

    @Override
    public boolean execute(java.lang.String sql, java.lang.String[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("execute(String,String[])");
    }
    @Override
    public int executeUpdate(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeUpdate");
    }

    @Override
    public int getMaxFieldSize() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getMaxFieldSize");
    }

    @Override
    public void setMaxFieldSize(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setMaxFieldSize");
    }

    @Override
    public void setEscapeProcessing(boolean arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setEscapeProcessing");
    }

    @Override
    public void cancel() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("cancel");
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getWarnings");
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("clearWarnings");
    }

    @Override
    public void setCursorName(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setCursorName");
    }

    @Override
    public int getUpdateCount() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getUpdateCount");
    }

    @Override
    public boolean getMoreResults() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getMoreResults");
    }

    @Override
    public void setFetchDirection(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setFetchDirection");
    }

    @Override
    public int getFetchDirection() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getFetchDirection");
    }

    @Override
    public void setFetchSize(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setFetchSize");
    }

    @Override
    public int getFetchSize() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getFetchSize");
    }

    @Override
    public int getResultSetConcurrency() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getResultSetConcurrency");
    }

    @Override
    public int getResultSetType() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getResultSetType");
    }

    @Override
    public void addBatch(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("addBatch");
    }

    @Override
    public void clearBatch() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("clearBatch");
    }

    @Override
    public int[] executeBatch() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeBatch");
    }

    @Override
    public boolean getMoreResults(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getMoreResults");
    }

    @Override
    public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getGeneratedKeys");
    }

    @Override
    public int executeUpdate(java.lang.String arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeUpdate");
    }

    @Override
    public int executeUpdate(java.lang.String arg0, int[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeUpdate");
    }

    @Override
    public int executeUpdate(java.lang.String arg0, java.lang.String[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeUpdate");
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getResultSetHoldability");
    }

    @Override
    public void setPoolable(boolean arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setPoolable");
    }

    @Override
    public boolean isPoolable() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isPoolable");
    }

    @Override
    public void closeOnCompletion() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("closeOnCompletion");
    }

    @Override
    public boolean isCloseOnCompletion() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isCloseOnCompletion");
    }

    @Override
    public long getLargeUpdateCount() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getLargeUpdateCount");
    }

    @Override
    public void setLargeMaxRows(long arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("setLargeMaxRows");
    }

    @Override
    public long getLargeMaxRows() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getLargeMaxRows");
    }

    @Override
    public long[] executeLargeBatch() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeLargeBatch");
    }

    @Override
    public long executeLargeUpdate(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeLargeUpdate");
    }

    @Override
    public long executeLargeUpdate(java.lang.String arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeLargeUpdate");
    }

    @Override
    public long executeLargeUpdate(java.lang.String arg0, int[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeLargeUpdate");
    }

    @Override
    public long executeLargeUpdate(java.lang.String arg0, java.lang.String[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("executeLargeUpdate");
    }

    @Override
    public java.lang.String enquoteLiteral(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("enquoteLiteral");
    }

    @Override
    public java.lang.String enquoteIdentifier(java.lang.String arg0, boolean arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("enquoteIdentifier");
    }

    @Override
    public boolean isSimpleIdentifier(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isSimpleIdentifier");
    }

    @Override
    public java.lang.String enquoteNCharLiteral(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("enquoteNCharLiteral");
    }

}
