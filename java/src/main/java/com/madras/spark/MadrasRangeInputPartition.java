package com.madras.spark;

import org.apache.spark.sql.connector.read.InputPartition;

class MadrasRangeInputPartition implements InputPartition {
    final String path;
    final long offset;
    final long count;
    final boolean mmap;

    MadrasRangeInputPartition(String path, long offset, long count, boolean mmap) {
        this.path = path;
        this.offset = offset;
        this.count = count;
        this.mmap = mmap;
    }
}
