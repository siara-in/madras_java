package com.madras.jdbc;

import com.madras.MadrasReader;
import java.sql.SQLException;
import java.sql.Types;

public final class MadrasResultSetMetaData implements java.sql.ResultSetMetaData {

    private final String[] columnNames;
    private final char[] mstTypes; // MST_* char per column, for SQL type mapping

    MadrasResultSetMetaData(String[] columnNames, char[] mstTypes) {
        this.columnNames = columnNames;
        this.mstTypes = mstTypes;
    }

    private void check(int col) throws SQLException {
        if (col < 1 || col > columnNames.length) {
            throw new SQLException("Invalid column index: " + col);
        }
    }

    @Override
    public int getColumnCount() throws SQLException {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        check(column);
        return columnNames[column - 1];
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return getColumnName(column);
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        check(column);
        switch (mstTypes[column - 1]) {
            case 't': return Types.VARCHAR;      // MST_TEXT
            case '*': return Types.VARBINARY;     // MST_BIN
            case 'i': return Types.INTEGER;       // MST_INT
            case 'I': return Types.BIGINT;        // MST_BIGINT
            case '.': return Types.DOUBLE;        // MST_DECV
            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                return Types.DECIMAL;             // MST_DEC0..9
            case 'j': return Types.DATE;          // MST_DATE
            case 'k': case 'l': return Types.TIME; // MST_TIME / MST_TIME_TZ
            case 'm': case 'n': case 'o': case 'p': case 'q':
                return Types.TIMESTAMP;           // MST_TIMESTAMP variants
            default: return Types.VARCHAR;
        }
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        check(column);
        int t = getColumnType(column);
        switch (t) {
            case Types.VARCHAR: return "TEXT";
            case Types.VARBINARY: return "BLOB";
            case Types.INTEGER: return "INTEGER";
            case Types.BIGINT: return "BIGINT";
            case Types.DOUBLE: return "DOUBLE";
            case Types.DECIMAL: return "DECIMAL";
            case Types.DATE: return "DATE";
            case Types.TIME: return "TIME";
            case Types.TIMESTAMP: return "TIMESTAMP";
            default: return "TEXT";
        }
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        int t = getColumnType(column);
        switch (t) {
            case Types.VARCHAR: return "java.lang.String";
            case Types.VARBINARY: return "[B";
            case Types.INTEGER: return "java.lang.Integer";
            case Types.BIGINT: return "java.lang.Long";
            case Types.DOUBLE: case Types.DECIMAL: return "java.lang.Double";
            case Types.DATE: return "java.sql.Date";
            case Types.TIME: return "java.sql.Time";
            case Types.TIMESTAMP: return "java.sql.Timestamp";
            default: return "java.lang.Object";
        }
    }

    @Override
    public int isNullable(int column) throws SQLException {
        check(column);
        return columnNullableUnknown; // reader doesn't currently expose per-column not-null constraints
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return 128; // no fixed-width guarantee from the underlying format; a reasonable default
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return ""; // single-file source, no multi-table concept
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        return "";
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        check(column);
        char t = mstTypes[column - 1];
        if (t >= '0' && t <= '9') return 18; // fixed-point decimal columns
        return 0;
    }

    @Override
    public int getScale(int column) throws SQLException {
        check(column);
        char t = mstTypes[column - 1];
        if (t >= '0' && t <= '9') return t - '0'; // MST_DEC0..9 encodes scale directly in the type char
        return 0;
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException { return false; }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException { return true; }

    @Override
    public boolean isSearchable(int column) throws SQLException { return true; }

    @Override
    public boolean isCurrency(int column) throws SQLException { return false; }

    @Override
    public boolean isSigned(int column) throws SQLException {
        check(column);
        char t = mstTypes[column - 1];
        return t == 'i' || t == 'I' || t == '.' || (t >= '0' && t <= '9');
    }

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
