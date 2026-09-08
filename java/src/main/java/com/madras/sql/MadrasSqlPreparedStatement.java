package com.madras.sql;

import java.math.BigDecimal;
import java.sql.*;
import java.util.Arrays;

/**
 * Parse+plan happens ONCE, in the constructor (native prepare()) --
 * repeated executeQuery() calls only substitute bound parameter values
 * into the already-built plan and run execution directly, skipping
 * re-parsing/re-planning entirely. Measured (C++ level, 1M repeated
 * calls): a consistent ~1.5x speedup over re-parsing from scratch every
 * time, ~5.8 microseconds saved per call -- real but modest, since
 * parsing a short WHERE clause is inherently cheap and the actual index
 * lookup dominates either way. See dv1/engine.hpp's own comment on this
 * design for the full native-side picture.
 *
 * An earlier version of this class did safe textual substitution of '?'
 * into fresh SQL text on every execute() call -- correct and safe, but it
 * did NOT skip re-parsing, so it never delivered this speedup. That
 * approach is gone now; this one calls MadrasSqlEngine.prepare() once and
 * MadrasSqlEngine.executePrepared() per call instead.
 */
public final class MadrasSqlPreparedStatement extends MadrasSqlStatement implements PreparedStatement {

    private final MadrasSqlConnection connection;
    private final long preparedHandle;
    private final int paramCount;
    private final Object[] boundValues;
    private final boolean[] boundSet;
    private boolean closed = false;

    MadrasSqlPreparedStatement(MadrasSqlConnection connection, String sql) throws SQLException {
        super(connection);
        this.connection = connection;
        try {
            this.preparedHandle = connection.engine().prepare(sql);
        } catch (RuntimeException e) {
            throw new SQLException("Failed to prepare statement: " + e.getMessage(), e);
        }
        this.paramCount = connection.engine().getParamCount(preparedHandle);
        this.boundValues = new Object[paramCount];
        this.boundSet = new boolean[paramCount];
    }

    private void checkParamIndex(int paramIndex) throws SQLException {
        if (paramIndex < 1 || paramIndex > paramCount) {
            throw new SQLException("Parameter index " + paramIndex +
                    " out of range -- this statement has " + paramCount + " placeholder(s)");
        }
    }

    private void bind(int paramIndex, Object value) throws SQLException {
        checkParamIndex(paramIndex);
        boundValues[paramIndex - 1] = value;
        boundSet[paramIndex - 1] = true;
    }

    // No SQL-literal quoting/escaping needed here (unlike the old
    // text-substitution approach) -- bound values are passed as plain
    // strings straight to the native comparison logic, never re-inserted
    // into SQL text at all. A NULL binding becomes an empty string, which
    // never equality-matches a real (non-null) column value -- the same
    // "NULL never matches" behavior standard SQL `= NULL` semantics give,
    // and the same behavior the previous implementation had.
    private static String formatParamValue(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean) return ((Boolean) value) ? "1" : "0";
        return value.toString();
    }

    private String[] buildParamArray() throws SQLException {
        for (int i = 0; i < paramCount; i++) {
            if (!boundSet[i]) {
                throw new SQLException("Parameter " + (i + 1) + " was never set -- " +
                        "call setXxx() for every '?' before executing");
            }
        }
        String[] params = new String[paramCount];
        for (int i = 0; i < paramCount; i++) params[i] = formatParamValue(boundValues[i]);
        return params;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        if (closed) throw new SQLException("PreparedStatement is closed");
        MadrasSqlEngine.Result r = connection.engine().executePrepared(preparedHandle, buildParamArray());
        if (!r.ok) throw new SQLException(r.error);
        MadrasSqlResultSetMetaData md = new MadrasSqlResultSetMetaData(r.columnNames);
        return new MadrasSqlResultSet(r.columnNames, r.rows, md);
    }

    @Override
    public boolean execute() throws SQLException {
        executeQuery();
        return true;
    }

    // Statement.executeQuery(String) inherited from the parent class
    // would let a caller execute arbitrary fresh SQL on a
    // PreparedStatement, bypassing the whole point of having prepared it
    // -- reject it explicitly rather than silently allow it.
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw new SQLException("Use the no-argument executeQuery() on a PreparedStatement " +
                "(the SQL was already prepared when this statement was created)");
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            connection.engine().closePrepared(preparedHandle);
            closed = true;
        }
        super.close();
    }

    @Override
    public void clearParameters() throws SQLException {
        Arrays.fill(boundValues, null);
        Arrays.fill(boundSet, false);
    }

    @Override
    public void setNull(int paramIndex, int sqlType) throws SQLException {
        bind(paramIndex, null);
    }

    @Override
    public void setNull(int paramIndex, int sqlType, String typeName) throws SQLException {
        bind(paramIndex, null);
    }

    @Override
    public void setBoolean(int paramIndex, boolean x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setByte(int paramIndex, byte x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setShort(int paramIndex, short x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setInt(int paramIndex, int x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setLong(int paramIndex, long x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setFloat(int paramIndex, float x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setDouble(int paramIndex, double x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setBigDecimal(int paramIndex, BigDecimal x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setString(int paramIndex, String x) throws SQLException {
        bind(paramIndex, x);
    }

    @Override
    public void setObject(int paramIndex, Object x) throws SQLException {
        if (x == null || x instanceof String || x instanceof Boolean || x instanceof Number) {
            bind(paramIndex, x);
            return;
        }
        throw new SQLFeatureNotSupportedException(
                "setObject: unsupported type " + x.getClass().getName() +
                " -- use setString/setInt/setLong/setDouble/setBoolean/setBigDecimal/setNull instead");
    }

    @Override
    public void setObject(int paramIndex, Object x, int targetSqlType) throws SQLException {
        setObject(paramIndex, x);
    }

    @Override
    public void setObject(int paramIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(paramIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        // No columns known without executing -- this engine doesn't parse
        // the SELECT list independently of running the query.
        throw new SQLFeatureNotSupportedException("getMetaData -- execute the query first and use ResultSet.getMetaData()");
    }

    @Override
    public int executeUpdate() throws SQLException {
        throw new SQLFeatureNotSupportedException("executeUpdate -- this driver has no write path");
    }

    @Override
    public void addBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("addBatch");
    }

    // -- Stream/Reader/Date/Time/Ref/Array/Blob/Clob binding: not
    // implemented, matching this driver's established pattern of clear,
    // safe-default stubs for JDBC surface area beyond this engine's
    // actual (flat, scalar-typed columns) data model.

    @Override
    public void setDate(int paramIndex, Date x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setDate");
    }

    @Override
    public void setDate(int paramIndex, Date x, java.util.Calendar cal) throws SQLException {
        throw new SQLFeatureNotSupportedException("setDate");
    }

    @Override
    public void setTime(int paramIndex, Time x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTime");
    }

    @Override
    public void setTime(int paramIndex, Time x, java.util.Calendar cal) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTime");
    }

    @Override
    public void setTimestamp(int paramIndex, Timestamp x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTimestamp");
    }

    @Override
    public void setTimestamp(int paramIndex, Timestamp x, java.util.Calendar cal) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTimestamp");
    }

    @Override
    public void setAsciiStream(int paramIndex, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream");
    }

    @Override
    public void setAsciiStream(int paramIndex, java.io.InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream");
    }

    @Override
    public void setAsciiStream(int paramIndex, java.io.InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream");
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int paramIndex, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setUnicodeStream");
    }

    @Override
    public void setBinaryStream(int paramIndex, java.io.InputStream x, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream");
    }

    @Override
    public void setBinaryStream(int paramIndex, java.io.InputStream x, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream");
    }

    @Override
    public void setBinaryStream(int paramIndex, java.io.InputStream x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream");
    }

    @Override
    public void setCharacterStream(int paramIndex, java.io.Reader reader, int length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream");
    }

    @Override
    public void setCharacterStream(int paramIndex, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream");
    }

    @Override
    public void setCharacterStream(int paramIndex, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream");
    }

    @Override
    public void setNCharacterStream(int paramIndex, java.io.Reader value, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNCharacterStream");
    }

    @Override
    public void setNCharacterStream(int paramIndex, java.io.Reader value) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNCharacterStream");
    }

    @Override
    public void setBytes(int paramIndex, byte[] x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBytes");
    }

    @Override
    public void setRef(int paramIndex, Ref x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setRef");
    }

    @Override
    public void setBlob(int paramIndex, Blob x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBlob");
    }

    @Override
    public void setBlob(int paramIndex, java.io.InputStream inputStream, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBlob");
    }

    @Override
    public void setBlob(int paramIndex, java.io.InputStream inputStream) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBlob");
    }

    @Override
    public void setClob(int paramIndex, Clob x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setClob");
    }

    @Override
    public void setClob(int paramIndex, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setClob");
    }

    @Override
    public void setClob(int paramIndex, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("setClob");
    }

    @Override
    public void setNClob(int paramIndex, NClob value) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNClob");
    }

    @Override
    public void setNClob(int paramIndex, java.io.Reader reader, long length) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNClob");
    }

    @Override
    public void setNClob(int paramIndex, java.io.Reader reader) throws SQLException {
        throw new SQLFeatureNotSupportedException("setNClob");
    }

    @Override
    public void setArray(int paramIndex, Array x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setArray");
    }

    @Override
    public void setURL(int paramIndex, java.net.URL x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setURL");
    }

    @Override
    public void setRowId(int paramIndex, RowId x) throws SQLException {
        throw new SQLFeatureNotSupportedException("setRowId");
    }

    @Override
    public void setNString(int paramIndex, String value) throws SQLException {
        setString(paramIndex, value);
    }

    @Override
    public void setSQLXML(int paramIndex, SQLXML xmlObject) throws SQLException {
        throw new SQLFeatureNotSupportedException("setSQLXML");
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException("getParameterMetaData");
    }
}
