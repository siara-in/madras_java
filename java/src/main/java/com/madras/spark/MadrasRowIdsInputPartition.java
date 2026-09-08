package com.madras.spark;

import org.apache.spark.sql.connector.read.InputPartition;

class MadrasRowIdsInputPartition implements InputPartition {
    final String path;
    final long[] rowIds;
    final boolean mmap;

    MadrasRowIdsInputPartition(String path, long[] rowIds, boolean mmap) {
        this.path = path;
        this.rowIds = rowIds;
        this.mmap = mmap;
    }
}
