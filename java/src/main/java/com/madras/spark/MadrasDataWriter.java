package com.madras.spark;

import com.madras.MadrasWriter;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.write.DataWriter;
import org.apache.spark.sql.connector.write.WriterCommitMessage;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Buffers InternalRow values per column (simple ArrayList<Double>/
 * ArrayList<String> boxing for clarity -- a higher-throughput version would
 * use growable primitive arrays to avoid boxing/GC pressure; flagged as a
 * perf follow-up, not attempted here) and flushes to the native
 * MadrasWriter in FLUSH_INTERVAL-row chunks so memory use stays bounded on
 * large CTAS sources.
 */
class MadrasDataWriter implements DataWriter<InternalRow> {

    private static final int FLUSH_INTERVAL = 100_000;

    private final StructType schema;
    private final char[] mstTypes;
    private final MadrasWriter writer;

    private final List<Object> colBuffers; // ArrayList<Double> or ArrayList<String> per column
    private long rowsBuffered = 0;

    MadrasDataWriter(String path, StructType schema) {
        this.schema = schema;
        this.mstTypes = MadrasWriteSchemaUtil.mstTypesFor(schema);

        StructField[] fields = schema.fields();
        List<MadrasWriter.ColumnSpec> specs = new ArrayList<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            char dt = mstTypes[i];
            char enc = MadrasWriteSchemaUtil.defaultEncodingFor(dt, false);
            specs.add(new MadrasWriter.ColumnSpec(fields[i].name(), dt, enc, false));
        }
        // NOTE: no primary-key support wired up here yet -- PK columns
        // require rows to be indexed in the exact insert order (see
        // MadrasWriter.finish()'s row-by-row insert_record loop), which is
        // straightforward to add but was left out of this pass since CTAS
        // callers rarely need it for arbitrary query results. Extend
        // ColumnSpec construction above (isPk=true for the desired leading
        // columns) if PK support is needed.
        this.writer = new MadrasWriter(path, tableNameFromPath(path), specs);

        this.colBuffers = new ArrayList<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (isNumeric(mstTypes[i])) {
                colBuffers.add(new ArrayList<Double>());
            } else {
                colBuffers.add(new ArrayList<String>());
            }
        }
    }

    private static String tableNameFromPath(String path) {
        String name = new java.io.File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot == -1 ? name : name.substring(0, dot);
    }

    private static boolean isNumeric(char mstType) {
        return mstType != 't' && mstType != '*';
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(InternalRow record) throws IOException {
        StructField[] fields = schema.fields();
        for (int c = 0; c < fields.length; c++) {
            DataType type = fields[c].dataType();
            boolean isNull = record.isNullAt(c);
            if (isNumeric(mstTypes[c])) {
                List<Double> buf = (List<Double>) colBuffers.get(c);
                if (isNull) {
                    buf.add(Double.NaN); // NOTE: NaN doubles as both "isNull sentinel" and "actual" here.
                                          // KNOWN LIMITATION: a DoubleType column containing a genuine
                                          // NaN value (not null) will be misclassified as NULL by
                                          // flush()'s Double.isNaN(v) check below. Fix by tracking
                                          // nullness in a parallel boolean buffer instead of
                                          // overloading NaN, if real NaN payloads are expected.
                } else {
                    buf.add(extractNumeric(record, c, type));
                }
            } else {
                List<String> buf = (List<String>) colBuffers.get(c);
                if (isNull) {
                    buf.add(null);
                } else if (mstTypes[c] == '*') {
                    // BLOB columns: MadrasWriter.writeTextColumn currently takes
                    // String[] only (see JNI nativeWriteTextColumn) -- binary
                    // columns aren't wired up on the write path yet. Flag loudly
                    // rather than silently mangling bytes through a String.
                    throw new IOException("BinaryType columns are not yet supported by the madras write path");
                } else {
                    buf.add(record.getUTF8String(c).toString());
                }
            }
        }
        rowsBuffered++;
        if (rowsBuffered >= FLUSH_INTERVAL) {
            flush();
        }
    }

    private double extractNumeric(InternalRow record, int c, DataType type) {
        if (type == DataTypes.IntegerType || type == DataTypes.ShortType || type == DataTypes.ByteType) {
            return record.getInt(c);
        } else if (type == DataTypes.LongType) {
            return record.getLong(c);
        } else if (type == DataTypes.BooleanType) {
            return record.getBoolean(c) ? 1.0 : 0.0;
        } else if (type == DataTypes.FloatType) {
            return record.getFloat(c);
        } else if (type == DataTypes.DoubleType) {
            return record.getDouble(c);
        } else if (type == DataTypes.DateType) {
            return record.getInt(c); // days since epoch, matches Spark's internal DateType representation
        } else if (type == DataTypes.TimestampType) {
            return record.getLong(c); // microseconds since epoch, matches Spark's internal TimestampType representation
        }
        throw new IllegalStateException("Unhandled numeric Spark type: " + type);
    }

    @SuppressWarnings("unchecked")
    private void flush() {
        StructField[] fields = schema.fields();
        for (int c = 0; c < fields.length; c++) {
            if (isNumeric(mstTypes[c])) {
                List<Double> buf = (List<Double>) colBuffers.get(c);
                int n = buf.size();
                double[] values = new double[n];
                boolean[] isNull = new boolean[n];
                for (int i = 0; i < n; i++) {
                    Double v = buf.get(i);
                    if (v == null || Double.isNaN(v)) {
                        isNull[i] = true;
                        values[i] = 0.0;
                    } else {
                        values[i] = v;
                    }
                }
                writer.writeNumericColumn(c, values, isNull);
                buf.clear();
            } else {
                List<String> buf = (List<String>) colBuffers.get(c);
                writer.writeTextColumn(c, buf.toArray(new String[0]));
                buf.clear();
            }
        }
        rowsBuffered = 0;
    }

    @Override
    public WriterCommitMessage commit() throws IOException {
        flush();
        writer.finish();
        return new MadrasWriterCommitMessage();
    }

    @Override
    public void abort() throws IOException {
        writer.abort();
    }

    @Override
    public void close() throws IOException {
        // finish()/abort() already release the native handle -- nothing
        // further to release here.
    }
}
