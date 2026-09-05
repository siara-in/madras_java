package com.madras.spark;

import com.madras.MadrasReader;
import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.List;

class MadrasScan implements Scan, Batch {

    // Rows per partition -- a fixed default rather than a byte-size target,
    // since row byte size varies a lot by column encoding. Tune/expose as an
    // option if needed (e.g. via CaseInsensitiveStringMap in the provider).
    private static final long ROWS_PER_PARTITION = 2_000_000L;

    private final String path;
    private final StructType schema;

    MadrasScan(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public StructType readSchema() {
        return schema;
    }

    @Override
    public Batch toBatch() {
        return this;
    }

    @Override
    public InputPartition[] planInputPartitions() {
        long rowCount;
        try (MadrasReader reader = new MadrasReader(path)) {
            rowCount = reader.metadata().rows;
        }
        List<InputPartition> partitions = new ArrayList<>();
        for (long offset = 0; offset < rowCount; offset += ROWS_PER_PARTITION) {
            long count = Math.min(ROWS_PER_PARTITION, rowCount - offset);
            partitions.add(new MadrasInputPartition(path, offset, count));
        }
        if (partitions.isEmpty()) {
            // Empty table: still need at least zero partitions, which is fine --
            // Spark handles an empty InputPartition[] correctly.
        }
        return partitions.toArray(new InputPartition[0]);
    }

    @Override
    public PartitionReaderFactory createReaderFactory() {
        return new MadrasPartitionReaderFactory(schema);
    }
}
