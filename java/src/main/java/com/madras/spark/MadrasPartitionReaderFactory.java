package com.madras.spark;

import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.StructType;

class MadrasPartitionReaderFactory implements PartitionReaderFactory {
    private final StructType schema;

    MadrasPartitionReaderFactory(StructType schema) {
        this.schema = schema;
    }

    @Override
    public PartitionReader<InternalRow> createReader(InputPartition partition) {
        if (partition instanceof MadrasCountInputPartition) {
            return new MadrasCountPartitionReader(((MadrasCountInputPartition) partition).count);
        }
        if (partition instanceof MadrasRowIdsInputPartition) {
            MadrasRowIdsInputPartition p = (MadrasRowIdsInputPartition) partition;
            return new MadrasRowIdsPartitionReader(p.path, p.rowIds, schema, p.mmap);
        }
        MadrasRangeInputPartition p = (MadrasRangeInputPartition) partition;
        return new MadrasPartitionReader(p.path, p.offset, p.count, schema, p.mmap);
    }
}
