package com.madras;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a new .mdsi file. Mirrors the Python MadrasBuilder's shape:
 * declare columns up front, append data column-by-column (possibly across
 * multiple calls, e.g. once per Spark micro-batch of buffered rows), then
 * finish() to index PK rows and serialize.
 *
 * One MadrasWriter per output file. Not thread-safe -- do not call from
 * multiple threads concurrently against the same instance.
 */
public final class MadrasWriter implements AutoCloseable {

    /** MST_* type chars -- keep in sync with madras/dv1/common.hpp. */
    public static final char MST_BIN = '*';
    public static final char MST_TEXT = 't';
    public static final char MST_INT = 'i';
    public static final char MST_BIGINT = 'I';
    public static final char MST_DECV = '.';
    public static final char MST_DATE = 'j';
    public static final char MST_TIME = 'k';
    public static final char MST_TIME_TZ = 'l';
    public static final char MST_TIMESTAMP = 'm';
    public static final char MST_TIMESTAMP_TZ = 'n';
    public static final char MST_TIMESTAMP_MS = 'o';
    public static final char MST_TIMESTAMP_NS = 'p';
    public static final char MST_TIMESTAMP_SEC = 'q';

    public static final class ColumnSpec {
        public final String name;
        public final char dt;
        public final char enc;
        public final boolean isPk;

        public ColumnSpec(String name, char dt, char enc, boolean isPk) {
            this.name = name;
            this.dt = dt;
            this.enc = enc;
            this.isPk = isPk;
        }
    }

    private final long handle;
    private final char[] dtCodes;
    private boolean finished = false;
    private boolean closed = false;

    public MadrasWriter(String path, String tableName, List<ColumnSpec> columns) {
        MadrasNative.ensureLoaded();
        String[] names = new String[columns.size()];
        StringBuilder dt = new StringBuilder();
        StringBuilder enc = new StringBuilder();
        int pkCount = 0;
        dtCodes = new char[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnSpec c = columns.get(i);
            names[i] = c.name;
            dt.append(c.dt);
            enc.append(c.enc);
            dtCodes[i] = c.dt;
            if (c.isPk) pkCount++;
        }
        this.handle = MadrasNative.nativeOpenWriter(path, tableName, names,
                dt.toString(), enc.toString(), pkCount);
    }

    private void checkOpen() {
        if (finished || closed) throw new IllegalStateException("MadrasWriter is finished/closed");
    }

    public boolean isNumericColumn(int colIdx) {
        char dt = dtCodes[colIdx];
        return dt != MST_TEXT && dt != MST_BIN;
    }

    /** values.length must equal isNull.length; NaN entries in values are ignored where isNull[i] is true. */
    public void writeNumericColumn(int colIdx, double[] values, boolean[] isNull) {
        checkOpen();
        MadrasNative.nativeWriteNumericColumn(handle, colIdx, values, isNull);
    }

    /** null entries in values represent SQL NULL. */
    public void writeTextColumn(int colIdx, String[] values) {
        checkOpen();
        MadrasNative.nativeWriteTextColumn(handle, colIdx, values);
    }

    /** Indexes PK rows (if any) and writes the final file. Call exactly once. */
    public void finish() {
        checkOpen();
        MadrasNative.nativeFinishWriter(handle);
        finished = true;
    }

    /** Discards the writer without producing a complete/valid file. */
    public void abort() {
        if (finished || closed) return;
        MadrasNative.nativeAbortWriter(handle);
        closed = true;
    }

    @Override
    public void close() {
        if (!finished && !closed) abort();
    }
}
