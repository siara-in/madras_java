package com.madras.spark;

import org.apache.spark.sql.connector.read.InputPartition;

/** Carries a single, already-computed COUNT(*) value -- no file access needed at read time. */
class MadrasCountInputPartition implements InputPartition {
    final long count;
    MadrasCountInputPartition(long count) {
        this.count = count;
    }
}
