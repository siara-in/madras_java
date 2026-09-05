package com.madras.spark;

import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.read.SupportsPushDownRequiredColumns;
import org.apache.spark.sql.types.StructType;

/**
 * Supports column pruning (SupportsPushDownRequiredColumns) so a query that
 * only touches a few columns avoids fetching the rest -- this maps directly
 * onto MadrasReader.getColumns()'s explicit column-index list.
 *
 * Filter pushdown (SupportsPushDownFilters) is NOT implemented yet: wiring
 * WHERE-clause predicates down to MadrasReader.lookupRowIds() for indexed
 * columns would mirror the DuckDB extension's pushdown_complex_filter logic,
 * but is a separate, non-trivial piece of work -- left as a clear follow-up
 * rather than attempted half-correctly here.
 */
class MadrasScanBuilder implements ScanBuilder, SupportsPushDownRequiredColumns {

    private final String path;
    private StructType schema;

    MadrasScanBuilder(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public void pruneColumns(StructType requiredSchema) {
        this.schema = requiredSchema;
    }

    @Override
    public Scan build() {
        return new MadrasScan(path, schema);
    }
}
