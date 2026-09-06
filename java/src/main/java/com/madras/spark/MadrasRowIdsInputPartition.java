package com.madras.spark;

import org.apache.spark.sql.connector.read.InputPartition;

class MadrasRowIdsInputPartition implements InputPartition {
    final String path;
    final long[] rowIds;

    MadrasRowIdsInputPartition(String path, long[] rowIds) {
        this.path = path;
        this.rowIds = rowIds;
    }
}
