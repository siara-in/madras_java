package com.madras.spark;

import com.madras.MadrasReader;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.unsafe.types.UTF8String;

import java.io.IOException;
import java.util.Arrays;

/**
 * Streams rows for one partition's [offset, offset+count) range, fetching
 * from the native reader in sub-batches (SUB_BATCH_SIZE rows at a time) to
 * bound memory rather than materializing the whole partition's columns at
 * once.
 */
class MadrasPartitionReader implements PartitionReader<InternalRow> {

    private static final long SUB_BATCH_SIZE = 100_000L;

    private final MadrasReader reader;
    private final int[] colIndices;
    private final DataType[] colTypes;
    private final long partitionOffset;
    private final long partitionCount;

    private long cursor = 0;          // position within the partition, 0-based
    private Object[] currentBatchCols; // column-major: one Object per column (double[]/String[]/byte[][])
    private long batchStart = -1;     // absolute row offset of currentBatchCols[0]
    private long batchLen = 0;
    private long batchPos = 0;        // position within currentBatchCols

    MadrasPartitionReader(String path, long offset, long count, StructType schema) {
        this.reader = new MadrasReader(path);
        this.partitionOffset = offset;
        this.partitionCount = count;

        StructField[] fields = schema.fields();
        this.colIndices = new int[fields.length];
        this.colTypes = new DataType[fields.length];
        for (int i = 0; i < fields.length; i++) {
            colIndices[i] = reader.columnIndex(fields[i].name());
            colTypes[i] = fields[i].dataType();
        }
    }

    @Override
    public boolean next() {
        if (cursor >= partitionCount) return false;
        if (batchStart == -1 || batchPos >= batchLen) {
            fetchNextBatch();
        }
        return batchLen > 0;
    }

    private void fetchNextBatch() {
        long absOffset = partitionOffset + cursor;
        long remaining = partitionCount - cursor;
        long thisBatchLen = Math.min(SUB_BATCH_SIZE, remaining);
        currentBatchCols = reader.getColumns(absOffset, thisBatchLen, colIndices);
        batchStart = absOffset;
        batchLen = thisBatchLen;
        batchPos = 0;
    }

    @Override
    public InternalRow get() {
        Object[] values = new Object[colIndices.length];
        for (int c = 0; c < colIndices.length; c++) {
            values[c] = extract(currentBatchCols[c], (int) batchPos, colTypes[c]);
        }
        cursor++;
        batchPos++;
        return InternalRow.apply(scala.collection.JavaConverters
                .asScalaBuffer(Arrays.asList(values)).toSeq());
    }

    private Object extract(Object columnData, int idx, DataType sparkType) {
        if (columnData instanceof double[]) {
            double v = ((double[]) columnData)[idx];
            if (Double.isNaN(v)) return null;
            if (sparkType == DataTypes.IntegerType) return (int) v;
            if (sparkType == DataTypes.LongType) return (long) v;
            if (sparkType == DataTypes.DateType) return (int) v; // days since epoch, matches Spark's DateType internal rep
            return v; // DoubleType, or TimestampType (see note in README re: micros conversion)
        } else if (columnData instanceof String[]) {
            String s = ((String[]) columnData)[idx];
            return s == null ? null : UTF8String.fromString(s);
        } else if (columnData instanceof byte[][]) {
            return ((byte[][]) columnData)[idx];
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
