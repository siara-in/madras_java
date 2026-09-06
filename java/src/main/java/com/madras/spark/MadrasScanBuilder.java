package com.madras.spark;

import com.madras.MadrasReader;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.read.SupportsPushDownFilters;
import org.apache.spark.sql.connector.read.SupportsPushDownRequiredColumns;
import org.apache.spark.sql.sources.And;
import org.apache.spark.sql.sources.EqualTo;
import org.apache.spark.sql.sources.Filter;
import org.apache.spark.sql.sources.GreaterThan;
import org.apache.spark.sql.sources.GreaterThanOrEqual;
import org.apache.spark.sql.sources.In;
import org.apache.spark.sql.sources.LessThan;
import org.apache.spark.sql.sources.LessThanOrEqual;
import org.apache.spark.sql.types.StructType;

import java.util.ArrayList;
import java.util.List;

/**
 * Supports column pruning and pushdown of: a single EqualTo, a single In,
 * and single-column ranges (BETWEEN-equivalent And(GreaterThan|GreaterThan
 * OrEqual, LessThan|LessThanOrEqual), or a one-sided GreaterThan/
 * GreaterThanOrEqual). LessThan/LessThanOrEqual alone (open-ended lower
 * bound) and any multi-column condition are left for Spark's own post-scan
 * filtering -- same scope as the JDBC driver's MiniSqlParser, and for the
 * same underlying reason (no open-lower-bound seek support in the native
 * range lookup).
 *
 * PK columns and non-PK 'T'/'W'-encoded columns are treated identically
 * here, matching MadrasReader.isIndexable()/isRangeable() -- any known
 * discrepancy in the underlying trie library's range-seek behavior on PK
 * columns is out of scope for this layer to work around.
 */
class MadrasScanBuilder implements ScanBuilder, SupportsPushDownRequiredColumns, SupportsPushDownFilters {

    private final String path;
    private StructType schema;
    private PushedCondition pushed; // null if nothing was pushed
    private Filter[] remainingFilters = new Filter[0];

    MadrasScanBuilder(String path, StructType schema) {
        this.path = path;
        this.schema = schema;
    }

    @Override
    public void pruneColumns(StructType requiredSchema) {
        this.schema = requiredSchema;
    }

    @Override
    public Filter[] pushFilters(Filter[] filters) {
        // IMPORTANT: Spark hands this a FLAT, already-split array -- a
        // BETWEEN/two-sided range arrives as two separate top-level entries
        // (e.g. GreaterThanOrEqual + LessThanOrEqual), not one And(...)
        // object. Must look across the whole array for a matching lower+
        // upper bound on the SAME column before claiming either half alone,
        // or a bare GreaterThan(OrEqual) gets wrongly claimed as an
        // open-ended range while its paired upper bound sits unrecognized
        // right next to it -- exactly the class of bug already hit once
        // with the DuckDB extension's pushdown_complex_filter.
        List<Filter> remaining = new ArrayList<>();
        boolean claimed = false;

        // Pass 1: find a genuine lower+upper pair on the same column,
        // wherever they are in the array (also handles the case where Spark
        // DOES still wrap them in a single And(...), via tryExtractBound's
        // recursive unwrap below).
        String rangeColumn = null;
        Filter lowerFilter = null, upperFilter = null;
        String lowerValue = null, upperValue = null;
        boolean lowerInclusive = false, upperInclusive = false;

        for (int i = 0; i < filters.length && lowerFilter == null; i++) {
            Bound b = extractBound(filters[i], true);
            if (b != null) {
                for (int j = 0; j < filters.length; j++) {
                    if (j == i) continue;
                    Bound b2 = extractBound(filters[j], false);
                    if (b2 != null && b2.column.equals(b.column) && isRangeable(b.column)) {
                        rangeColumn = b.column;
                        lowerFilter = filters[i];
                        upperFilter = filters[j];
                        lowerValue = b.value;
                        lowerInclusive = b.inclusive;
                        upperValue = b2.value;
                        upperInclusive = b2.inclusive;
                        break;
                    }
                }
            }
        }

        if (rangeColumn != null) {
            PushedCondition pc = new PushedCondition();
            pc.originalFilter = lowerFilter; // pushedFilters() reports one representative filter
            pc.type = PushedCondition.Type.RANGE;
            pc.column = rangeColumn;
            pc.lowerValue = lowerValue;
            pc.lowerInclusive = lowerInclusive;
            pc.upperValue = upperValue;
            pc.upperInclusive = upperInclusive;
            pushed = pc;
            claimed = true;
        }

        for (Filter f : filters) {
            if (claimed && (f == lowerFilter || f == upperFilter)) continue; // fully consumed by the range above
            if (!claimed) {
                PushedCondition candidate = tryClaimSingle(f);
                if (candidate != null) {
                    pushed = candidate;
                    claimed = true;
                    continue;
                }
            }
            remaining.add(f);
        }
        remainingFilters = remaining.toArray(new Filter[0]);
        return remainingFilters;
    }

    /** A single-sided bound extracted from one filter, for pairing across the flat array. */
    private static final class Bound {
        String column;
        String value;
        boolean inclusive;
    }

    private Bound extractBound(Filter f, boolean wantLower) {
        Bound b = new Bound();
        if (wantLower) {
            if (f instanceof GreaterThan) {
                b.column = ((GreaterThan) f).attribute();
                b.value = String.valueOf(((GreaterThan) f).value());
                b.inclusive = false;
                return b;
            }
            if (f instanceof GreaterThanOrEqual) {
                b.column = ((GreaterThanOrEqual) f).attribute();
                b.value = String.valueOf(((GreaterThanOrEqual) f).value());
                b.inclusive = true;
                return b;
            }
        } else {
            if (f instanceof LessThan) {
                b.column = ((LessThan) f).attribute();
                b.value = String.valueOf(((LessThan) f).value());
                b.inclusive = false;
                return b;
            }
            if (f instanceof LessThanOrEqual) {
                b.column = ((LessThanOrEqual) f).attribute();
                b.value = String.valueOf(((LessThanOrEqual) f).value());
                b.inclusive = true;
                return b;
            }
        }
        return null;
    }

    // Handles EqualTo / In / a standalone one-sided GreaterThan(OrEqual) with
    // no matching upper bound anywhere in the array (genuinely open-ended),
    // and a nested And(...) in case Spark ever does hand one directly.
    private PushedCondition tryClaimSingle(Filter f) {
        if (f instanceof EqualTo) {
            EqualTo eq = (EqualTo) f;
            if (!isIndexable(eq.attribute())) return null;
            PushedCondition pc = new PushedCondition();
            pc.originalFilter = f;
            pc.type = PushedCondition.Type.EQ;
            pc.column = eq.attribute();
            pc.value = String.valueOf(eq.value());
            return pc;
        }
        if (f instanceof In) {
            In in = (In) f;
            if (!isIndexable(in.attribute())) return null;
            PushedCondition pc = new PushedCondition();
            pc.originalFilter = f;
            pc.type = PushedCondition.Type.IN;
            pc.column = in.attribute();
            pc.values = new ArrayList<>();
            for (Object v : in.values()) pc.values.add(String.valueOf(v));
            return pc;
        }
        if (f instanceof GreaterThan || f instanceof GreaterThanOrEqual) {
            String col = (f instanceof GreaterThan) ? ((GreaterThan) f).attribute() : ((GreaterThanOrEqual) f).attribute();
            if (!isRangeable(col)) return null;
            PushedCondition pc = new PushedCondition();
            pc.originalFilter = f;
            pc.type = PushedCondition.Type.RANGE;
            pc.column = col;
            pc.lowerValue = String.valueOf((f instanceof GreaterThan) ? ((GreaterThan) f).value() : ((GreaterThanOrEqual) f).value());
            pc.lowerInclusive = (f instanceof GreaterThanOrEqual);
            pc.upperValue = null;
            return pc;
        }
        if (f instanceof And) {
            And and = (And) f;
            Bound lower = extractBound(and.left(), true);
            Bound upper = extractBound(and.right(), false);
            if (lower == null || upper == null) {
                lower = extractBound(and.right(), true);
                upper = extractBound(and.left(), false);
            }
            if (lower != null && upper != null && lower.column.equals(upper.column) && isRangeable(lower.column)) {
                PushedCondition pc = new PushedCondition();
                pc.originalFilter = f;
                pc.type = PushedCondition.Type.RANGE;
                pc.column = lower.column;
                pc.lowerValue = lower.value;
                pc.lowerInclusive = lower.inclusive;
                pc.upperValue = upper.value;
                pc.upperInclusive = upper.inclusive;
                return pc;
            }
            return null;
        }
        return null;
    }

    @Override
    public Filter[] pushedFilters() {
        return pushed == null ? new Filter[0] : new Filter[]{pushed.originalFilter};
    }

    private boolean isIndexable(String columnName) {
        try (MadrasReader reader = new MadrasReader(path)) {
            return reader.isIndexable(reader.columnIndex(columnName));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isRangeable(String columnName) {
        try (MadrasReader reader = new MadrasReader(path)) {
            return reader.isRangeable(reader.columnIndex(columnName));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Memoized for the same reason as MadrasScan.planInputPartitions() --
    // Spark's optimizer (especially with AQE) can call build() more than
    // once while planning a single query. Returning the same instance means
    // MadrasScan's own planInputPartitions() cache (see there) is reused
    // too, rather than constructing a fresh Scan that would need to redo
    // the native lookup from scratch.
    private Scan cachedScan;

    @Override
    public Scan build() {
        if (cachedScan == null) {
            cachedScan = new MadrasScan(path, schema, pushed);
        }
        return cachedScan;
    }

    /** Internal representation of whatever single condition got pushed. */
    static final class PushedCondition {
        enum Type { EQ, IN, RANGE }
        Filter originalFilter;
        Type type;
        String column;
        String value;             // EQ
        List<String> values;       // IN
        String lowerValue, upperValue; // RANGE
        boolean lowerInclusive, upperInclusive;
    }
}


