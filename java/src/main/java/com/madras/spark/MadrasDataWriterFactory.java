package com.madras.spark;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.write.DataWriter;
import org.apache.spark.sql.connector.write.DataWriterFactory;
import org.apache.spark.sql.types.StructType;

class MadrasDataWriterFactory implements DataWriterFactory {
    private final String path;
    private final StructType schema;

    MadrasDataWriterFactory(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public DataWriter<InternalRow> createWriter(int partitionId, long taskId) {
        return new MadrasDataWriter(path, schema);
    }
}
