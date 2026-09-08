package com.madras.spark;

import com.madras.MadrasReader;
import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

class MadrasScan implements Scan, Batch {

    // Rows per partition -- a fixed default rather than a byte-size target,
    // since row byte size varies a lot by column encoding. Tune/expose as an
    // option if needed (e.g. via CaseInsensitiveStringMap in the provider).
    private static final long ROWS_PER_PARTITION = 2_000_000L;

    // Static, cross-instance cache: Spark's optimizer (especially with
    // Adaptive Query Execution) can re-plan a query more than once, each
    // time potentially constructing a FRESH MadrasTable/ScanBuilder/Scan
    // rather than reusing the same objects -- so instance-level memoization
    // alone isn't enough to avoid repeating the native trie lookup for
    // what is, from the user's perspective, one query. Keyed by (file path
    // + pushed-condition signature), which uniquely identifies the matched
    // row-id set regardless of which object instance computes it.
    //
    // NOTE: this cache is process-lifetime and unbounded. For a
    // long-running Spark application issuing many DISTINCT filtered
    // queries against many/large files, this will accumulate entries
    // indefinitely. Acceptable for now (each entry is just a long[] of
    // matched row ids, not full row data), but consider an LRU eviction
    // policy or a size/time-based cap if this becomes a real concern in
    // practice.
    private static final ConcurrentHashMap<String, long[]> ROW_ID_CACHE = new ConcurrentHashMap<>();

    private final MadrasTable table;
    private final String path;
    private final StructType schema;
    private final MadrasScanBuilder.PushedCondition pushed; // null if nothing was pushed
    private final MadrasOptions options;
    private final boolean countStarPushed;

    MadrasScan(MadrasTable table, StructType schema, MadrasScanBuilder.PushedCondition pushed, MadrasOptions options) {
        this(table, schema, pushed, options, false);
    }

    MadrasScan(MadrasTable table, StructType schema, MadrasScanBuilder.PushedCondition pushed,
               MadrasOptions options, boolean countStarPushed) {
        this.table = table;
        this.path = table.path();
        this.schema = schema;
        this.pushed = pushed;
        this.options = options;
        this.countStarPushed = countStarPushed;
    }

    @Override
    public StructType readSchema() {
        return schema;
    }

    @Override
    public String description() {
        if (pushed == null) return "MadrasScan(full scan)";
        switch (pushed.type) {
            case EQ: return "MadrasScan(pushed: " + pushed.column + " = " + pushed.value + ")";
            case IN: return "MadrasScan(pushed: " + pushed.column + " IN " + pushed.values + ")";
            case RANGE: return "MadrasScan(pushed: " + pushed.lowerValue + " <"
                    + (pushed.lowerInclusive ? "=" : "") + " " + pushed.column + " <"
                    + (pushed.upperInclusive ? "=" : "") + " " + (pushed.upperValue == null ? "∞" : pushed.upperValue) + ")";
            default: return "MadrasScan(pushed: unknown)";
        }
    }

    @Override
    public Batch toBatch() {
        return this;
    }

    // Memoized so a second planInputPartitions() call on the same Scan
    // instance (Spark's optimizer -- particularly with Adaptive Query
    // Execution enabled -- can legitimately invoke this more than once
    // while planning/re-planning a single query) doesn't repeat the native
    // trie lookup. Safe because a Scan instance is immutable/single-query
    // scoped once constructed.
    private InputPartition[] cachedPartitions;

    @Override
    public InputPartition[] planInputPartitions() {
        if (cachedPartitions != null) {
            return cachedPartitions;
        }
        cachedPartitions = computeInputPartitions();
        return cachedPartitions;
    }

    private long[] lookupPushedRowIds() {
        MadrasReader reader = table.metaReader();
        int colIdx = reader.columnIndex(pushed.column);
        switch (pushed.type) {
            case EQ:
                return reader.lookupRowIds(colIdx, pushed.value);
            case IN:
                return reader.lookupInRowIds(colIdx, pushed.values);
            case RANGE:
                return reader.rangeLookupRowIds(colIdx, pushed.lowerValue, pushed.lowerInclusive,
                        pushed.upperValue, pushed.upperInclusive);
            default:
                throw new IllegalStateException("Unhandled pushed condition type: " + pushed.type);
        }
    }

    private InputPartition[] computeInputPartitions() {
        if (countStarPushed) {
            // COUNT(*), no GROUP BY. If a filter was ALSO pushed (e.g.
            // COUNT(*) WHERE indexed_col = x), reuse its already-computed
            // matched-row-id count; otherwise answer directly from trie
            // metadata (table.metaReader().metadata().rows) -- no scan of
            // any kind. This is the actual point of this pushdown: the
            // Calcite JDBC driver was confirmed (by direct measurement) to
            // fetch every column for every row even for a bare COUNT(*);
            // this path never touches row data at all.
            long count;
            if (pushed != null) {
                String cacheKey = path + "|" + pushed.type + "|" + pushed.column + "|"
                        + pushed.value + "|" + pushed.values + "|"
                        + pushed.lowerValue + "|" + pushed.lowerInclusive + "|"
                        + pushed.upperValue + "|" + pushed.upperInclusive;
                long[] matchedIds = ROW_ID_CACHE.computeIfAbsent(cacheKey, k -> lookupPushedRowIds());
                count = matchedIds.length;
            } else {
                count = table.metaReader().metadata().rows;
            }
            return new InputPartition[]{new MadrasCountInputPartition(count)};
        }

        if (pushed != null) {
            String cacheKey = path + "|" + pushed.type + "|" + pushed.column + "|"
                    + pushed.value + "|" + pushed.values + "|"
                    + pushed.lowerValue + "|" + pushed.lowerInclusive + "|"
                    + pushed.upperValue + "|" + pushed.upperInclusive;

            long[] matchedIds = ROW_ID_CACHE.computeIfAbsent(cacheKey, k -> lookupPushedRowIds());

            List<InputPartition> partitions = new ArrayList<>();
            for (int offset = 0; offset < matchedIds.length; offset += ROWS_PER_PARTITION) {
                int end = (int) Math.min(offset + ROWS_PER_PARTITION, matchedIds.length);
                long[] slice = java.util.Arrays.copyOfRange(matchedIds, offset, end);
                partitions.add(new MadrasRowIdsInputPartition(path, slice, options.mmap));
            }
            return partitions.toArray(new InputPartition[0]);
        }

        long rowCount = table.metaReader().metadata().rows;
        List<InputPartition> partitions = new ArrayList<>();
        for (long offset = 0; offset < rowCount; offset += ROWS_PER_PARTITION) {
            long count = Math.min(ROWS_PER_PARTITION, rowCount - offset);
            partitions.add(new MadrasRangeInputPartition(path, offset, count, options.mmap));
        }
        return partitions.toArray(new InputPartition[0]);
    }

    @Override
    public PartitionReaderFactory createReaderFactory() {
        return new MadrasPartitionReaderFactory(schema);
    }
}


