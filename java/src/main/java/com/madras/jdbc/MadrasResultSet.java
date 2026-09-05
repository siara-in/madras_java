package com.madras.jdbc;

import com.madras.MadrasReader;

/**
 * Forward-only, read-only ResultSet over a MadrasReader's fetched columns.
 * Supported: next/close/wasNull, getString/getInt/getLong/getDouble/
 * getBoolean/getObject (by index or label), getMetaData, findColumn, getRow.
 * Everything else (updatable result sets, streaming BLOB/CLOB access,
 * cursors, RowId, scroll-sensitive navigation) throws
 * SQLFeatureNotSupportedException -- this is a minimal, forward-only reader,
 * not a full JDBC ResultSet implementation.
 */
public final class MadrasResultSet implements java.sql.ResultSet {

    private final String[] columnNames;
    private final Object[][] rowMajorData; // [row][col], already materialized (see MadrasStatement)
    private final long[] rowIds;
    private final MadrasResultSetMetaData metaData;

    private int cursor = -1;
    private boolean started = false;
    private boolean closed = false;
    private boolean lastWasNull = false;

    MadrasResultSet(String[] columnNames, Object[][] rowMajorData, long[] rowIds,
                     MadrasResultSetMetaData metaData) {
        this.columnNames = columnNames;
        this.rowMajorData = rowMajorData;
        this.rowIds = rowIds;
        this.metaData = metaData;
    }

    private Object rawValue(int columnIndex) throws java.sql.SQLException {
        if (closed) throw new java.sql.SQLException("ResultSet is closed");
        if (!started || cursor >= rowMajorData.length) {
            throw new java.sql.SQLException("No current row -- call next() first");
        }
        if (columnIndex < 1 || columnIndex > columnNames.length) {
            throw new java.sql.SQLException("Invalid column index: " + columnIndex);
        }
        return rowMajorData[cursor][columnIndex - 1];
    }

    @Override
    public boolean next() throws java.sql.SQLException {
        if (closed) throw new java.sql.SQLException("ResultSet is closed");
        if (cursor >= rowIds.length - 1 && started) return false;
        if (!started) {
            started = true;
            cursor = 0;
            return rowIds.length > 0;
        }
        cursor++;
        return cursor < rowIds.length;
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
    public boolean wasNull() throws java.sql.SQLException {
        return lastWasNull;
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws java.sql.SQLException {
        return metaData;
    }

    @Override
    public int findColumn(java.lang.String columnLabel) throws java.sql.SQLException {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnLabel)) return i + 1;
        }
        throw new java.sql.SQLException("No such column: " + columnLabel);
    }

    @Override
    public int getRow() throws java.sql.SQLException {
        return started ? cursor + 1 : 0;
    }

    @Override
    public java.lang.String getString(int columnIndex) throws java.sql.SQLException {
        Object v = rawValue(columnIndex);
        lastWasNull = (v == null);
        return v == null ? null : String.valueOf(v);
    }

    @Override
    public java.lang.String getString(java.lang.String columnLabel) throws java.sql.SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws java.sql.SQLException {
        Object v = rawValue(columnIndex);
        lastWasNull = (v == null);
        if (v == null) return 0;
        return ((Number) v).intValue();
    }

    @Override
    public int getInt(java.lang.String columnLabel) throws java.sql.SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws java.sql.SQLException {
        Object v = rawValue(columnIndex);
        lastWasNull = (v == null);
        if (v == null) return 0L;
        return ((Number) v).longValue();
    }

    @Override
    public long getLong(java.lang.String columnLabel) throws java.sql.SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public double getDouble(int columnIndex) throws java.sql.SQLException {
        Object v = rawValue(columnIndex);
        lastWasNull = (v == null);
        if (v == null) return 0.0;
        return ((Number) v).doubleValue();
    }

    @Override
    public double getDouble(java.lang.String columnLabel) throws java.sql.SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws java.sql.SQLException {
        Object v = rawValue(columnIndex);
        lastWasNull = (v == null);
        if (v == null) return false;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @Override
    public boolean getBoolean(java.lang.String columnLabel) throws java.sql.SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public java.lang.Object getObject(int columnIndex) throws java.sql.SQLException {
        Object v = rawValue(columnIndex);
        lastWasNull = (v == null);
        return v;
    }

    @Override
    public java.lang.Object getObject(java.lang.String columnLabel) throws java.sql.SQLException {
        return getObject(findColumn(columnLabel));
    }
    @Override
    public byte getByte(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getByte");
    }

    @Override
    public short getShort(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getShort");
    }

    @Override
    public float getFloat(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getFloat");
    }

    @Override
    public java.math.BigDecimal getBigDecimal(int arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBigDecimal");
    }

    @Override
    public byte[] getBytes(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBytes");
    }

    @Override
    public java.sql.Date getDate(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getDate");
    }

    @Override
    public java.sql.Time getTime(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTime");
    }

    @Override
    public java.sql.Timestamp getTimestamp(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTimestamp");
    }

    @Override
    public java.io.InputStream getAsciiStream(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getAsciiStream");
    }

    @Override
    public java.io.InputStream getUnicodeStream(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getUnicodeStream");
    }

    @Override
    public java.io.InputStream getBinaryStream(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBinaryStream");
    }

    @Override
    public byte getByte(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getByte");
    }

    @Override
    public short getShort(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getShort");
    }

    @Override
    public float getFloat(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getFloat");
    }

    @Override
    public java.math.BigDecimal getBigDecimal(java.lang.String arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBigDecimal");
    }

    @Override
    public byte[] getBytes(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBytes");
    }

    @Override
    public java.sql.Date getDate(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getDate");
    }

    @Override
    public java.sql.Time getTime(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTime");
    }

    @Override
    public java.sql.Timestamp getTimestamp(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTimestamp");
    }

    @Override
    public java.io.InputStream getAsciiStream(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getAsciiStream");
    }

    @Override
    public java.io.InputStream getUnicodeStream(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getUnicodeStream");
    }

    @Override
    public java.io.InputStream getBinaryStream(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBinaryStream");
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
    public java.lang.String getCursorName() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getCursorName");
    }

    @Override
    public java.io.Reader getCharacterStream(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getCharacterStream");
    }

    @Override
    public java.io.Reader getCharacterStream(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getCharacterStream");
    }

    @Override
    public java.math.BigDecimal getBigDecimal(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBigDecimal");
    }

    @Override
    public java.math.BigDecimal getBigDecimal(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBigDecimal");
    }

    @Override
    public boolean isBeforeFirst() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isBeforeFirst");
    }

    @Override
    public boolean isAfterLast() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isAfterLast");
    }

    @Override
    public boolean isFirst() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isFirst");
    }

    @Override
    public boolean isLast() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("isLast");
    }

    @Override
    public void beforeFirst() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("beforeFirst");
    }

    @Override
    public void afterLast() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("afterLast");
    }

    @Override
    public boolean first() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("first");
    }

    @Override
    public boolean last() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("last");
    }

    @Override
    public boolean absolute(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("absolute");
    }

    @Override
    public boolean relative(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("relative");
    }

    @Override
    public boolean previous() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("previous");
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
    public int getType() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getType");
    }

    @Override
    public int getConcurrency() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getConcurrency");
    }

    @Override
    public boolean rowUpdated() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("rowUpdated");
    }

    @Override
    public boolean rowInserted() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("rowInserted");
    }

    @Override
    public boolean rowDeleted() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("rowDeleted");
    }

    @Override
    public void updateNull(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNull");
    }

    @Override
    public void updateBoolean(int arg0, boolean arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBoolean");
    }

    @Override
    public void updateByte(int arg0, byte arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateByte");
    }

    @Override
    public void updateShort(int arg0, short arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateShort");
    }

    @Override
    public void updateInt(int arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateInt");
    }

    @Override
    public void updateLong(int arg0, long arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateLong");
    }

    @Override
    public void updateFloat(int arg0, float arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateFloat");
    }

    @Override
    public void updateDouble(int arg0, double arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateDouble");
    }

    @Override
    public void updateBigDecimal(int arg0, java.math.BigDecimal arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBigDecimal");
    }

    @Override
    public void updateString(int arg0, java.lang.String arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateString");
    }

    @Override
    public void updateBytes(int arg0, byte[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBytes");
    }

    @Override
    public void updateDate(int arg0, java.sql.Date arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateDate");
    }

    @Override
    public void updateTime(int arg0, java.sql.Time arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateTime");
    }

    @Override
    public void updateTimestamp(int arg0, java.sql.Timestamp arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateTimestamp");
    }

    @Override
    public void updateAsciiStream(int arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(int arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(int arg0, java.io.Reader arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateCharacterStream");
    }

    @Override
    public void updateObject(int arg0, java.lang.Object arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void updateObject(int arg0, java.lang.Object arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void updateNull(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNull");
    }

    @Override
    public void updateBoolean(java.lang.String arg0, boolean arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBoolean");
    }

    @Override
    public void updateByte(java.lang.String arg0, byte arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateByte");
    }

    @Override
    public void updateShort(java.lang.String arg0, short arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateShort");
    }

    @Override
    public void updateInt(java.lang.String arg0, int arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateInt");
    }

    @Override
    public void updateLong(java.lang.String arg0, long arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateLong");
    }

    @Override
    public void updateFloat(java.lang.String arg0, float arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateFloat");
    }

    @Override
    public void updateDouble(java.lang.String arg0, double arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateDouble");
    }

    @Override
    public void updateBigDecimal(java.lang.String arg0, java.math.BigDecimal arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBigDecimal");
    }

    @Override
    public void updateString(java.lang.String arg0, java.lang.String arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateString");
    }

    @Override
    public void updateBytes(java.lang.String arg0, byte[] arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBytes");
    }

    @Override
    public void updateDate(java.lang.String arg0, java.sql.Date arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateDate");
    }

    @Override
    public void updateTime(java.lang.String arg0, java.sql.Time arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateTime");
    }

    @Override
    public void updateTimestamp(java.lang.String arg0, java.sql.Timestamp arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateTimestamp");
    }

    @Override
    public void updateAsciiStream(java.lang.String arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(java.lang.String arg0, java.io.InputStream arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(java.lang.String arg0, java.io.Reader arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateCharacterStream");
    }

    @Override
    public void updateObject(java.lang.String arg0, java.lang.Object arg1, int arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void updateObject(java.lang.String arg0, java.lang.Object arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void insertRow() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("insertRow");
    }

    @Override
    public void updateRow() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateRow");
    }

    @Override
    public void deleteRow() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("deleteRow");
    }

    @Override
    public void refreshRow() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("refreshRow");
    }

    @Override
    public void cancelRowUpdates() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("cancelRowUpdates");
    }

    @Override
    public void moveToInsertRow() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("moveToInsertRow");
    }

    @Override
    public void moveToCurrentRow() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("moveToCurrentRow");
    }

    @Override
    public java.sql.Statement getStatement() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getStatement");
    }

    @Override
    public java.sql.Ref getRef(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getRef");
    }

    @Override
    public java.sql.Blob getBlob(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBlob");
    }

    @Override
    public java.sql.Clob getClob(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getClob");
    }

    @Override
    public java.sql.Array getArray(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getArray");
    }

    @Override
    public java.sql.Ref getRef(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getRef");
    }

    @Override
    public java.sql.Blob getBlob(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getBlob");
    }

    @Override
    public java.sql.Clob getClob(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getClob");
    }

    @Override
    public java.sql.Array getArray(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getArray");
    }

    @Override
    public java.sql.Date getDate(int arg0, java.util.Calendar arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getDate");
    }

    @Override
    public java.sql.Date getDate(java.lang.String arg0, java.util.Calendar arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getDate");
    }

    @Override
    public java.sql.Time getTime(int arg0, java.util.Calendar arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTime");
    }

    @Override
    public java.sql.Time getTime(java.lang.String arg0, java.util.Calendar arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTime");
    }

    @Override
    public java.sql.Timestamp getTimestamp(int arg0, java.util.Calendar arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTimestamp");
    }

    @Override
    public java.sql.Timestamp getTimestamp(java.lang.String arg0, java.util.Calendar arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getTimestamp");
    }

    @Override
    public java.net.URL getURL(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getURL");
    }

    @Override
    public java.net.URL getURL(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getURL");
    }

    @Override
    public void updateRef(int arg0, java.sql.Ref arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateRef");
    }

    @Override
    public void updateRef(java.lang.String arg0, java.sql.Ref arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateRef");
    }

    @Override
    public void updateBlob(int arg0, java.sql.Blob arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBlob");
    }

    @Override
    public void updateBlob(java.lang.String arg0, java.sql.Blob arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBlob");
    }

    @Override
    public void updateClob(int arg0, java.sql.Clob arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateClob");
    }

    @Override
    public void updateClob(java.lang.String arg0, java.sql.Clob arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateClob");
    }

    @Override
    public void updateArray(int arg0, java.sql.Array arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateArray");
    }

    @Override
    public void updateArray(java.lang.String arg0, java.sql.Array arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateArray");
    }

    @Override
    public java.sql.RowId getRowId(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getRowId");
    }

    @Override
    public java.sql.RowId getRowId(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getRowId");
    }

    @Override
    public void updateRowId(int arg0, java.sql.RowId arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateRowId");
    }

    @Override
    public void updateRowId(java.lang.String arg0, java.sql.RowId arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateRowId");
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getHoldability");
    }

    @Override
    public void updateNString(int arg0, java.lang.String arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNString");
    }

    @Override
    public void updateNString(java.lang.String arg0, java.lang.String arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNString");
    }

    @Override
    public void updateNClob(int arg0, java.sql.NClob arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNClob");
    }

    @Override
    public void updateNClob(java.lang.String arg0, java.sql.NClob arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNClob");
    }

    @Override
    public java.sql.NClob getNClob(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNClob");
    }

    @Override
    public java.sql.NClob getNClob(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNClob");
    }

    @Override
    public java.sql.SQLXML getSQLXML(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getSQLXML");
    }

    @Override
    public java.sql.SQLXML getSQLXML(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getSQLXML");
    }

    @Override
    public void updateSQLXML(int arg0, java.sql.SQLXML arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateSQLXML");
    }

    @Override
    public void updateSQLXML(java.lang.String arg0, java.sql.SQLXML arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateSQLXML");
    }

    @Override
    public java.lang.String getNString(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNString");
    }

    @Override
    public java.lang.String getNString(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNString");
    }

    @Override
    public java.io.Reader getNCharacterStream(int arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNCharacterStream");
    }

    @Override
    public java.io.Reader getNCharacterStream(java.lang.String arg0) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(java.lang.String arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNCharacterStream");
    }

    @Override
    public void updateAsciiStream(int arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(int arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateCharacterStream");
    }

    @Override
    public void updateAsciiStream(java.lang.String arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(java.lang.String arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(java.lang.String arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateCharacterStream");
    }

    @Override
    public void updateBlob(int arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBlob");
    }

    @Override
    public void updateBlob(java.lang.String arg0, java.io.InputStream arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBlob");
    }

    @Override
    public void updateClob(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateClob");
    }

    @Override
    public void updateClob(java.lang.String arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateClob");
    }

    @Override
    public void updateNClob(int arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNClob");
    }

    @Override
    public void updateNClob(java.lang.String arg0, java.io.Reader arg1, long arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNClob");
    }

    @Override
    public void updateNCharacterStream(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(java.lang.String arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNCharacterStream");
    }

    @Override
    public void updateAsciiStream(int arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(int arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateCharacterStream");
    }

    @Override
    public void updateAsciiStream(java.lang.String arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(java.lang.String arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(java.lang.String arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateCharacterStream");
    }

    @Override
    public void updateBlob(int arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBlob");
    }

    @Override
    public void updateBlob(java.lang.String arg0, java.io.InputStream arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateBlob");
    }

    @Override
    public void updateClob(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateClob");
    }

    @Override
    public void updateClob(java.lang.String arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateClob");
    }

    @Override
    public void updateNClob(int arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNClob");
    }

    @Override
    public void updateNClob(java.lang.String arg0, java.io.Reader arg1) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateNClob");
    }

    @Override
    public void updateObject(int arg0, java.lang.Object arg1, java.sql.SQLType arg2, int arg3) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void updateObject(java.lang.String arg0, java.lang.Object arg1, java.sql.SQLType arg2, int arg3) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void updateObject(int arg0, java.lang.Object arg1, java.sql.SQLType arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }

    @Override
    public void updateObject(java.lang.String arg0, java.lang.Object arg1, java.sql.SQLType arg2) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("updateObject");
    }


    @Override
    public java.lang.Object getObject(int columnIndex, java.util.Map<java.lang.String, java.lang.Class<?>> map) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getObject(int,Map)");
    }

    @Override
    public java.lang.Object getObject(java.lang.String columnLabel, java.util.Map<java.lang.String, java.lang.Class<?>> map) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("getObject(String,Map)");
    }

    @Override
    public <T> T getObject(int columnIndex, java.lang.Class<T> type) throws java.sql.SQLException {
        Object v = getObject(columnIndex);
        return v == null ? null : type.cast(v);
    }

    @Override
    public <T> T getObject(java.lang.String columnLabel, java.lang.Class<T> type) throws java.sql.SQLException {
        return getObject(findColumn(columnLabel), type);
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
}
