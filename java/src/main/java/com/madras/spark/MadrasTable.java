package com.madras.spark;

import org.apache.spark.sql.connector.catalog.SupportsRead;
import org.apache.spark.sql.connector.catalog.SupportsWrite;
import org.apache.spark.sql.connector.catalog.TableCapability;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.write.LogicalWriteInfo;
import org.apache.spark.sql.connector.write.WriteBuilder;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MadrasTable implements SupportsRead, SupportsWrite {

    private final String path;
    private final StructType schema;

    MadrasTable(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public String name() {
        return "madras(" + path + ")";
    }

    @Override
    public StructType schema() {
        return schema;
    }

    @Override
    public Set<TableCapability> capabilities() {
        return new HashSet<>(Arrays.asList(
                TableCapability.BATCH_READ,
                TableCapability.BATCH_WRITE,
                TableCapability.TRUNCATE // CTAS always writes a fresh file; there's no append-to-existing support
        ));
    }

    @Override
    public ScanBuilder newScanBuilder(CaseInsensitiveStringMap options) {
        return new MadrasScanBuilder(path, schema);
    }

    @Override
    public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
        return new MadrasWriteBuilder(path, info.schema());
    }
}
