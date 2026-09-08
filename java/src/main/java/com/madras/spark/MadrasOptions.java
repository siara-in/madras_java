package com.madras.spark;

/**
 * Mirrors the DuckDB extension's read_madras() named parameters:
 * mmap (default true), index (force a specific column index for pushdown),
 * no_index (disable index usage entirely, always full scan + Spark's own
 * post-scan filtering).
 */
final class MadrasOptions {
    final boolean mmap;
    final Integer forcedIndex; // null = auto-detect per filter (default)
    final boolean noIndex;

    MadrasOptions(boolean mmap, Integer forcedIndex, boolean noIndex) {
        this.mmap = mmap;
        this.forcedIndex = forcedIndex;
        this.noIndex = noIndex;
    }

    static MadrasOptions defaults() {
        return new MadrasOptions(true, null, false);
    }

    static MadrasOptions parse(java.util.Map<String, String> props) {
        boolean mmap = !"false".equalsIgnoreCase(props.get("mmap")); // default true
        boolean noIndex = "true".equalsIgnoreCase(props.get("no_index"));
        Integer forcedIndex = null;
        String idxStr = props.get("index");
        if (idxStr != null && !idxStr.isEmpty()) {
            try {
                forcedIndex = Integer.parseInt(idxStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'index' option must be an integer column index: " + idxStr);
            }
        }
        return new MadrasOptions(mmap, forcedIndex, noIndex);
    }
}
