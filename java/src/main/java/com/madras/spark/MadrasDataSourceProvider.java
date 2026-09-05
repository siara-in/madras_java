package com.madras.spark;

import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableProvider;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.sources.DataSourceRegister;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Map;

/**
 * Registers as `spark.read.format("madras").load("path/to/file.mdsi")`.
 *
 * This is a minimal, batch-read-only Data Source V2 provider: one .mdsi
 * file per `.load(path)` call, schema inferred from the file's own column
 * metadata (no user-supplied schema support yet -- add if needed).
 */
public class MadrasDataSourceProvider implements TableProvider, DataSourceRegister {

    @Override
    public String shortName() {
        return "madras";
    }

    @Override
    public StructType inferSchema(CaseInsensitiveStringMap options) {
        String path = requirePath(options);
        return MadrasSchemaUtil.inferSchema(path);
    }

    @Override
    public Table getTable(StructType schema, Transform[] partitioning, Map<String, String> properties) {
        String path = properties.get("path");
        if (path == null) {
            throw new IllegalArgumentException("madras source requires a 'path' option (the .mdsi file)");
        }
        return new MadrasTable(path, schema);
    }

    @Override
    public boolean supportsExternalMetadata() {
        // Must be true for CTAS: Spark derives the schema from the SELECT
        // and passes it straight to getTable() without requiring
        // inferSchema() to succeed first -- inferSchema() opens the source
        // file, which doesn't exist yet for a CTAS target.
        return true;
    }

    private static String requirePath(CaseInsensitiveStringMap options) {
        String path = options.get("path");
        if (path == null) {
            throw new IllegalArgumentException("madras source requires a 'path' option (the .mdsi file)");
        }
        return path;
    }
}
