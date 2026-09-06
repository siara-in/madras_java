package com.madras.jdbc;

import com.madras.MadrasReader;

/**
 * Supports the MiniSqlParser SELECT subset (see that class for the exact
 * grammar). Batch execution, prepared parameters, generated keys, and other
 * Statement features not applicable to a read-only single-file source throw
 * SQLFeatureNotSupportedException.
 */
public final class MadrasStatement implements java.sql.Statement {

    private final MadrasConnection connection;
    private MadrasResultSet currentResultSet;
    private boolean closed = false;
    private int maxRows = 0;

    MadrasStatement(MadrasConnection connection) {
        this.connection = connection;
    }

    @Override
    public java.sql.ResultSet executeQuery(java.lang.String sql) throws java.sql.SQLException {
        if (closed) throw new java.sql.SQLException("Statement is closed");
        MiniSqlParser.ParsedQuery q = MiniSqlParser.parse(sql);
        MadrasReader reader = connection.reader();
        MadrasReader.Metadata meta = reader.metadata();

        java.util.List<Integer> colIdxList = new java.util.ArrayList<>();
        if (q.columns.isEmpty()) {
            for (int i : reader.dataColumnIndices()) colIdxList.add(i);
        } else {
            for (String name : q.columns) colIdxList.add(reader.columnIndex(name.trim()));
        }
        int[] colIndices = new int[colIdxList.size()];
        for (int i = 0; i < colIndices.length; i++) colIndices[i] = colIdxList.get(i);

        String[] columnNames = new String[colIndices.length];
        char[] mstTypes = new char[colIndices.length];
        for (int i = 0; i < colIndices.length; i++) {
            MadrasReader.ColumnMeta cm = meta.columns.get(colIndices[i]);
            columnNames[i] = cm.name;
            mstTypes[i] = cm.type;
        }

        long[] rowIds;
        boolean needsClientFilter = false;

        if (q.whereColumn == null) {
            rowIds = fullRange(reader, effectiveLimit(q.limit));
        } else {
            int whereColIdx = reader.columnIndex(q.whereColumn);
            boolean indexable = reader.isIndexable(whereColIdx);
            boolean rangeable = reader.isRangeable(whereColIdx);

            switch (q.whereOp) {
                case EQ:
                    rowIds = indexable ? reader.lookupRowIds(whereColIdx, q.whereValues.get(0))
                                        : fullScanThenFilter(reader, q, whereColIdx);
                    break;
                case IN:
                    rowIds = indexable ? reader.lookupInRowIds(whereColIdx, q.whereValues)
                                        : fullScanThenFilter(reader, q, whereColIdx);
                    break;
                case BETWEEN:
                    rowIds = rangeable
                            ? reader.rangeLookupRowIds(whereColIdx, q.whereValues.get(0), true, q.whereValues.get(1), true)
                            : fullScanThenFilter(reader, q, whereColIdx);
                    break;
                case GT:
                    rowIds = rangeable ? reader.rangeLookupRowIds(whereColIdx, q.whereValues.get(0), false, null, false)
                                        : fullScanThenFilter(reader, q, whereColIdx);
                    break;
                case GTE:
                    rowIds = rangeable ? reader.rangeLookupRowIds(whereColIdx, q.whereValues.get(0), true, null, false)
                                        : fullScanThenFilter(reader, q, whereColIdx);
                    break;
                case LT:
                case LTE:
                    // Open-ended LOWER bound (col < x / col <= x, no lower limit) isn't
                    // supported by the native range lookup -- always falls back.
                    rowIds = fullScanThenFilter(reader, q, whereColIdx);
                    break;
                default:
                    throw new java.sql.SQLException("Unhandled WHERE operator: " + q.whereOp);
            }

            Long cap = effectiveLimit(q.limit);
            if (cap != null && rowIds.length > cap) {
                rowIds = java.util.Arrays.copyOf(rowIds, cap.intValue());
            }
        }

        MadrasResultSetMetaData md = new MadrasResultSetMetaData(columnNames, mstTypes);
        this.currentResultSet = new MadrasResultSet(reader, columnNames, colIndices, rowIds, md);
        return this.currentResultSet;
    }

    /**
     * Combines the SQL LIMIT clause (if any) with Statement.setMaxRows()
     * (if set via the standard JDBC API, which many clients -- including
     * DBeaver's Data tab -- use to cap preview/page size instead of adding
     * LIMIT to the SQL text). Whichever is smaller wins. Returns null if
     * neither is set (no cap).
     */
    private Long effectiveLimit(Long sqlLimit) {
        Long cap = sqlLimit;
        if (maxRows > 0) {
            cap = (cap == null) ? (long) maxRows : Math.min(cap, maxRows);
        }
        return cap;
    }

    private static long[] fullRange(MadrasReader reader, Long limit) {
        long total = reader.metadata().rows;
        long n = limit != null ? Math.min(limit, total) : total;
        long[] ids = new long[(int) n];
        for (int i = 0; i < n; i++) ids[i] = i;
        return ids;
    }

    /**
     * Client-side fallback: full scan of the WHERE column, comparing each
     * row's value in Java. Used whenever the condition can't be pushed to
     * the native index (non-indexable column, or an open-ended lower-bound
     * range) -- correctness is preserved, just without acceleration.
     */
    private static long[] fullScanThenFilter(MadrasReader reader, MiniSqlParser.ParsedQuery q, int whereColIdx)
            throws java.sql.SQLException {
        long total = reader.metadata().rows;
        int[] singleCol = new int[]{whereColIdx};
        Object[] colData = reader.getColumns(0, -1, singleCol);
        Object col = colData[0];

        java.util.List<Long> matched = new java.util.ArrayList<>();
        for (long i = 0; i < total; i++) {
            Object v = extractValue(col, (int) i);
            if (v != null && matchesCondition(v, q)) {
                matched.add(i);
            }
        }
        long[] ids = new long[matched.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = matched.get(i);
        return ids;
    }

    private static boolean matchesCondition(Object value, MiniSqlParser.ParsedQuery q) {
        switch (q.whereOp) {
            case EQ:
                return String.valueOf(value).equals(q.whereValues.get(0));
            case IN:
                for (String v : q.whereValues) if (String.valueOf(value).equals(v)) return true;
                return false;
            case BETWEEN: {
                double d = toDouble(value);
                return d >= Double.parseDouble(q.whereValues.get(0)) && d <= Double.parseDouble(q.whereValues.get(1));
            }
            case GT: return toDouble(value) > Double.parseDouble(q.whereValues.get(0));
            case GTE: return toDouble(value) >= Double.parseDouble(q.whereValues.get(0));
            case LT: return toDouble(value) < Double.parseDouble(q.whereValues.get(0));
            case LTE: return toDouble(value) <= Double.parseDouble(q.whereValues.get(0));
            default: return false;
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static long columnLength(Object col) {
        if (col instanceof double[]) return ((double[]) col).length;
        if (col instanceof String[]) return ((String[]) col).length;
        if (col instanceof byte[][]) return ((byte[][]) col).length;
        return 0;
    }

    private static Object extractValue(Object col, int idx) {
        if (col instanceof double[]) {
            double v = ((double[]) col)[idx];
            return Double.isNaN(v) ? null : (Double) v;
        }
        if (col instanceof String[]) return ((String[]) col)[idx];
        if (col instanceof byte[][]) return ((byte[][]) col)[idx];
        return null;
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
        // No query timeout support -- reads are local/synchronous, not worth
        // implementing cancellation plumbing for a single-file scan.
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


    @Override
    public boolean execute(java.lang.String sql, int autoGeneratedKeys) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("execute(String,int)");
    }

    @Override
    public boolean execute(java.lang.String sql, int[] columnIndexes) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("execute(String,int[])");
    }

    @Override
    public boolean execute(java.lang.String sql, java.lang.String[] columnNames) throws java.sql.SQLException {
        throw new java.sql.SQLFeatureNotSupportedException("execute(String,String[])");
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
