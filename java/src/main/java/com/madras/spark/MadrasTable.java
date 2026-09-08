package com.madras.spark;

import com.madras.MadrasReader;
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
    private final MadrasOptions options;

    // Shared, lazily-opened reader for DRIVER-side planning work only
    // (filter analysis in MadrasScanBuilder, row-count/index lookups in
    // MadrasScan.computeInputPartitions) -- same rationale as
    // MadrasCalciteTable.metaReader(): MadrasReader construction is not
    // cheap (a full file re-parse), and a MadrasTable instance persists
    // across a query's planning cycle (including repeated re-planning under
    // Adaptive Query Execution), so reusing one reader here avoids
    // redundant reopens the same way it did for the Calcite driver.
    //
    // IMPORTANT: this does NOT extend to executor-side code
    // (MadrasPartitionReader/MadrasRowIdsPartitionReader). Those run in
    // separate JVMs/processes (real executors in a cluster, or separate
    // threads with their own task context even in local mode) that never
    // see this driver-side object -- each task necessarily opens its own
    // reader, and that's correct/unavoidable, not the same inefficiency.
    private volatile MadrasReader metaReader;

    MadrasTable(String path, StructType schema) {
        this(path, schema, MadrasOptions.defaults());
    }

    MadrasTable(String path, StructType schema, MadrasOptions options) {
        this.path = path;
        this.schema = schema;
        this.options = options;
    }

    MadrasReader metaReader() {
        MadrasReader r = metaReader;
        if (r == null) {
            synchronized (this) {
                r = metaReader;
                if (r == null) {
                    r = new MadrasReader(path, options.mmap);
                    metaReader = r;
                }
            }
        }
        return r;
    }

    @Override
    public String name() {
        return "madras(" + path + ")";
    }

    String path() { return path; }
    MadrasOptions options() { return options; }

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
        return new MadrasScanBuilder(this);
    }

    @Override
    public WriteBuilder newWriteBuilder(LogicalWriteInfo info) {
        return new MadrasWriteBuilder(path, info.schema());
    }
}
