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
 * Same row-extraction logic as MadrasPartitionReader, but iterates a fixed
 * array of row ids (from a pushed equality filter's lookupRowIds() result)
 * via getColumnsByIds() in sub-batches, instead of a contiguous
 * offset/count range via getColumns().
 */
class MadrasRowIdsPartitionReader implements PartitionReader<InternalRow> {

    private static final int SUB_BATCH_SIZE = 100_000;

    private final MadrasReader reader;
    private final long[] rowIds;
    private final int[] colIndices;
    private final DataType[] colTypes;

    private int cursor = 0;           // position within rowIds
    private Object[] currentBatchCols;
    private int batchStart = -1;      // index into rowIds where the current batch begins
    private int batchLen = 0;
    private int batchPos = 0;

    MadrasRowIdsPartitionReader(String path, long[] rowIds, StructType schema) {
        this.reader = new MadrasReader(path);
        this.rowIds = rowIds;

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
        if (cursor >= rowIds.length) return false;
        if (batchStart == -1 || batchPos >= batchLen) {
            fetchNextBatch();
        }
        return batchLen > 0;
    }

    private void fetchNextBatch() {
        int start = cursor;
        int end = Math.min(start + SUB_BATCH_SIZE, rowIds.length);
        long[] slice = Arrays.copyOfRange(rowIds, start, end);
        currentBatchCols = reader.getColumnsByIds(slice, colIndices);
        batchStart = start;
        batchLen = slice.length;
        batchPos = 0;
    }

    @Override
    public InternalRow get() {
        Object[] values = new Object[colIndices.length];
        for (int c = 0; c < colIndices.length; c++) {
            values[c] = extract(currentBatchCols[c], batchPos, colTypes[c]);
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
            if (sparkType == DataTypes.DateType) return (int) v;
            return v;
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
