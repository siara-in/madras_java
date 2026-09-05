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
        return MadrasNative.nativeLookupRowIds(handle, columnIndex(column), value);
    }

    public long[] lookupRowIds(int colIdx, String value) {
        checkOpen();
        return MadrasNative.nativeLookupRowIds(handle, colIdx, value);
    }

    /** Fetches specific row ids -- same Object[] shape as getColumns(). */
    public Object[] getColumnsByIds(long[] rowIds, int[] colIndices) {
        checkOpen();
        return MadrasNative.nativeGetColumnsByIds(handle, rowIds, colIndices);
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
