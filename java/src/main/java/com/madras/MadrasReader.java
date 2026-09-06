package com.madras;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level reader over a .mdsi file. Mirrors the Python MadrasReader's
 * shape: metadata, columnar batch fetch, and index/word lookup.
 */
public final class MadrasReader implements Closeable {

    public static final class ColumnMeta {
        public final int index;
        public final String name;
        public final char type;     // MST_* type char
        public final char encoding; // MSE_*/shorthand encoding char

        ColumnMeta(int index, String name, char type, char encoding) {
            this.index = index;
            this.name = name;
            this.type = type;
            this.encoding = encoding;
        }
    }

    public static final class Metadata {
        public final long rows;
        public final int pkColumns;
        public final List<ColumnMeta> columns;

        Metadata(long rows, int pkColumns, List<ColumnMeta> columns) {
            this.rows = rows;
            this.pkColumns = pkColumns;
            this.columns = columns;
        }
    }

    private final long handle;
    private final Metadata metadata;
    private volatile boolean closed = false;

    public MadrasReader(String path) {
        this(path, true);
    }

    public MadrasReader(String path, boolean mmap) {
        MadrasNative.ensureLoaded();
        this.handle = MadrasNative.nativeOpen(path, mmap);
        this.metadata = parseMetadata(MadrasNative.nativeMetadata(handle));
    }

    private static Metadata parseMetadata(String[] flat) {
        long rows = Long.parseLong(flat[0]);
        int pkCols = Integer.parseInt(flat[1]);
        int colCount = Integer.parseInt(flat[2]);
        List<ColumnMeta> cols = new ArrayList<>(colCount);
        int p = 3;
        for (int i = 0; i < colCount; i++) {
            String name = flat[p++];
            char type = flat[p++].charAt(0);
            char enc = flat[p++].charAt(0);
            cols.add(new ColumnMeta(i, name, type, enc));
        }
        return new Metadata(rows, pkCols, cols);
    }

    public Metadata metadata() {
        return metadata;
    }

    public int columnIndex(String name) {
        for (ColumnMeta c : metadata.columns) {
            if (c.name.equals(name)) return c.index;
        }
        throw new IllegalArgumentException("No such column: " + name);
    }

    /**
     * Returns the "data" column indices only -- excludes secondary-index
     * ('S'-typed) columns, matching the read_madras table function's
     * default projection.
     */
    /**
     * True if this column can be looked up via lookupRowIds/rangeLookupRowIds
     * (PK columns, 'T'-encoded trie columns, or 'W'-encoded word columns).
     * Range lookups on 'W' columns are not supported (word/phrase search is
     * exact-value only) -- see isRangeable().
     */
    public boolean isIndexable(int colIdx) {
        ColumnMeta c = metadata.columns.get(colIdx);
        return c.encoding == 'T' || c.encoding == 'W' || colIdx < metadata.pkColumns;
    }

    public boolean isRangeable(int colIdx) {
        ColumnMeta c = metadata.columns.get(colIdx);
        return c.encoding != 'W' && isIndexable(colIdx);
    }

    public int[] dataColumnIndices() {
        List<Integer> out = new ArrayList<>();
        for (ColumnMeta c : metadata.columns) {
            if (c.type == 'S') break;
            out.add(c.index);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Fetches columns [offset, offset+count) as raw Object[] (one entry per
     * requested column index): double[] for numeric (NaN = null), String[]
     * for text, byte[][] for blob. count = -1 means "to end of table".
     */
    public Object[] getColumns(long offset, long count, int[] colIndices) {
        checkOpen();
        return MadrasNative.nativeGetColumns(handle, offset, count, colIndices);
    }

    /**
     * Exact-match or word/phrase lookup (for 'W'-encoded columns) -> matching
     * row ids. Combine with getColumnsByIds() to fetch the actual matched rows
     * without a full range scan.
     */
    public long[] lookupRowIds(String column, String value) {
        checkOpen();
        long[] result = MadrasNative.nativeLookupRowIds(handle, columnIndex(column), value);
        System.err.println("Using index: " + column + " = " + value + " -- index pass: " + result.length);
        return result;
    }

    public long[] lookupRowIds(int colIdx, String value) {
        checkOpen();
        long[] result = MadrasNative.nativeLookupRowIds(handle, colIdx, value);
        System.err.println("Using index: col#" + colIdx + " = " + value + " -- index pass: " + result.length);
        return result;
    }

    /** Fetches specific row ids -- same Object[] shape as getColumns(). */
    public Object[] getColumnsByIds(long[] rowIds, int[] colIndices) {
        checkOpen();
        return MadrasNative.nativeGetColumnsByIds(handle, rowIds, colIndices);
    }

    /**
     * IN (v1, v2, ...) lookup: unions row ids matching any of the given
     * values, deduplicated. Each value is looked up via the same exact-match
     * path as a single equality lookup.
     */
    public long[] lookupInRowIds(String column, java.util.List<String> values) {
        return lookupInRowIds(columnIndex(column), values);
    }

    public long[] lookupInRowIds(int colIdx, java.util.List<String> values) {
        checkOpen();
        java.util.LinkedHashSet<Long> all = new java.util.LinkedHashSet<>();
        for (String v : values) {
            for (long id : MadrasNative.nativeLookupRowIds(handle, colIdx, v)) {
                all.add(id);
            }
        }
        long[] result = new long[all.size()];
        int i = 0;
        for (long id : all) result[i++] = id;
        System.err.println("Using index: col#" + colIdx + " IN " + values + " -- index pass: " + result.length);
        return result;
    }

    /**
     * Range lookup, e.g. col BETWEEN a AND b, or col > x (upperValue = null
     * for an open-ended upper bound). lowerValue is required -- an
     * open-ended LOWER bound (col < x with no lower limit) isn't supported;
     * fall back to a full scan + client-side filter for that case.
     */
    public long[] rangeLookupRowIds(String column, String lowerValue, boolean lowerInclusive,
                                     String upperValue, boolean upperInclusive) {
        return rangeLookupRowIds(columnIndex(column), lowerValue, lowerInclusive, upperValue, upperInclusive);
    }

    public long[] rangeLookupRowIds(int colIdx, String lowerValue, boolean lowerInclusive,
                                     String upperValue, boolean upperInclusive) {
        checkOpen();
        long[] result = MadrasNative.nativeRangeLookupRowIds(handle, colIdx, lowerValue, lowerInclusive,
                                                     upperValue, upperInclusive);
        System.err.println("Using index: col#" + colIdx + " range [" + lowerValue + (lowerInclusive ? "<=" : "<")
                + " x " + (upperInclusive ? "<=" : "<") + (upperValue == null ? "\u221e" : upperValue)
                + "] -- index pass: " + result.length);
        return result;
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("MadrasReader is closed");
    }

    @Override
    public void close() {
        if (!closed) {
            MadrasNative.nativeClose(handle);
            closed = true;
        }
    }
}
