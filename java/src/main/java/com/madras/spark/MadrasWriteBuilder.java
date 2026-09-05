package com.madras.spark;

import org.apache.spark.sql.connector.write.BatchWrite;
import org.apache.spark.sql.connector.write.Write;
import org.apache.spark.sql.connector.write.WriteBuilder;
import org.apache.spark.sql.types.StructType;

class MadrasWriteBuilder implements WriteBuilder {

    private final String path;
    private final StructType schema;

    MadrasWriteBuilder(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public Write build() {
        return new Write() {
            @Override
            public BatchWrite toBatch() {
                return new MadrasBatchWrite(path, schema);
            }
        };
    }
}
