package com.madras.spark;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.read.PartitionReader;

import java.io.IOException;

class MadrasCountPartitionReader implements PartitionReader<InternalRow> {
    private final long count;
    private boolean consumed = false;

    MadrasCountPartitionReader(long count) {
        this.count = count;
    }

    @Override
    public boolean next() {
        if (consumed) return false;
        consumed = true;
        return true;
    }

    @Override
    public InternalRow get() {
        Object[] values = new Object[]{count};
        return InternalRow.apply(scala.collection.JavaConverters
                .asScalaBuffer(java.util.Arrays.asList(values)).toSeq());
    }

    @Override
    public void close() throws IOException {
        // no resources held
    }
}
