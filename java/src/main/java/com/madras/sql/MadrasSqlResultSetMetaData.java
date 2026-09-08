package com.madras.sql;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * The native engine's query_result currently returns every value already
 * formatted as text (see madras_sql_native.hpp's get_col_val_text) -- there
 * is no per-column type information surfaced back through the JNI layer
 * yet. Reporting VARCHAR uniformly is therefore accurate to what's actually
 * returned, not a simplification hiding richer type info that exists
 * elsewhere. ResultSet.getInt()/getLong()/getDouble() still work correctly
 * (they parse the text), this only affects what a JDBC client's schema
 * browser displays as the column type.
 */
public final class MadrasSqlResultSetMetaData implements java.sql.ResultSetMetaData {

    private final List<String> columnNames;

    MadrasSqlResultSetMetaData(List<String> columnNames) {
        this.columnNames = columnNames;
    }

    private void check(int col) throws SQLException {
        if (col < 1 || col > columnNames.size()) {
            throw new SQLException("Invalid column index: " + col);
        }
    }

    @Override
    public int getColumnCount() throws SQLException {
        return columnNames.size();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        check(column);
        return columnNames.get(column - 1);
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        check(column);
        return Types.VARCHAR;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        check(column);
        return "TEXT";
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return "java.lang.String";
    }

    @Override
    public int isNullable(int column) throws SQLException {
        check(column);
        return columnNullableUnknown;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException { return 128; }

    @Override
    public String getTableName(int column) throws SQLException { return ""; }

    @Override
    public String getCatalogName(int column) throws SQLException { return ""; }

    @Override
    public String getSchemaName(int column) throws SQLException { return ""; }

    @Override
    public int getPrecision(int column) throws SQLException { return 0; }

    @Override
    public int getScale(int column) throws SQLException { return 0; }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException { return false; }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException { return true; }

    @Override
    public boolean isSearchable(int column) throws SQLException { return true; }

    @Override
    public boolean isCurrency(int column) throws SQLException { return false; }

    @Override
    public boolean isSigned(int column) throws SQLException { return false; }

    @Override
    public boolean isReadOnly(int column) throws SQLException { return true; }

    @Override
    public boolean isWritable(int column) throws SQLException { return false; }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException { return false; }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}
